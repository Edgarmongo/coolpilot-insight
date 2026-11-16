# SOURCE-02 · 领域模型与接口契约冻结

版本：v1.0（冻结候选）　日期：2026-11-16
依据：SOURCE-00 外部审计 + SOURCE-01 现状审计 + 本仓库真实代码核对
状态：**冻结候选，待 SOURCE-03/04 实施验证后转正式**

> 本文件只定义契约，不改任何代码、Schema、Migration。
> 冻结的是"Java 与 Python 对能力的共同理解"，不是新增功能。

---

## 02-01 领域模型

### 实体与关系

```
Source（平台，业务唯一标识）
  │
  └──< Connector（接入实现，一对多）
         │
         └──< Capability（该接入能执行的操作，一对多）
                │
                └── 在某种 AccessMode 下是否可用

Account（真实平台账号）── 绑定到 Source，不绑定到 Connector
Execution（一次采集执行）── 引用 Source + Connector + Capability + AccessMode + Account?
```

### 实体定义

| 实体 | 职责 | 唯一标识 | 存储 |
|---|---|---|---|
| **Source** | 平台本身（抖音/小红书/Web） | `sourceCode`（`DOUYIN`） | `ds_source`（已有） |
| **Connector** | 平台的一种接入实现（公开网页/官方API/授权会话） | `connectorCode`（`DOUYIN_PUBLIC`） | **首期不落库**，Python Registry + JSON 契约表达 |
| **Capability** | 某接入能执行的操作（评论采集/内容详情/搜索） | `capabilityCode`（`COMMENT`） | 能力清单 JSON |
| **Account** | 真实平台账号及授权状态 | `externalAccountId` | `ds_account`（已有） |
| **Execution** | 一次真实采集调用 | `idem_key` | `col_execution`（已有） |

### 关键裁决（对应你提的六个问题）

**Q1 平台与 Adapter 关系？**
**一对多，且不叫 Adapter 而叫 Connector。** 抖音可以同时存在 `DOUYIN_PUBLIC`（公开网页滚动）、`DOUYIN_OFFICIAL`（官方API）、`DOUYIN_SESSION`（授权会话）三种接入。每个 Connector 独立的能力集合、独立的健康状态。**首期不建 `ds_connector` 表**——只有出现"后台动态配置多接入并存/在线切换"的真实需求时才持久化。

**Q2 能力清单谁维护？**
**Desired（Java）+ Observed（Python）双份，不互替。** 详见 02-03。

**Q3 账号与能力的关系？**
**账号绑定 Source，不绑定 Capability。** 能力是否需要账号由 `accessMode` 决定：`PUBLIC` 能力不需要账号，`AUTHENTICATED` 能力必须有一个 `isUsable=true` 的账号。同一 Source 下哪个账号被选中由 Execution 决定，不由能力定义决定。

**Q4 健康状态怎么算？**
**分层计算，不是单一枚举。** 见 02-05。

**Q5 平台接口变化怎么办？**
**Connector + Capability 级失效，不拖累整个 Source。** 每个能力独立记录 `contractVersion` 和 `implementationStatus`，某个能力失效只把该能力标记为 `DEGRADED`，其它能力不受影响。

**Q6 如何吸收 AiToEarn/DTK 而不被其设计限制？**
**只吸收能力模型与工程模式，不吸收其实体结构。** 我们已有 Source/Account/Task/Run/Execution 实体，AiToEarn 的 PlatformIntegration 和 DTK 的 PlatformAdapter 只作为"能力描述契约"的参考，不替换我们的领域模型。

---

## 02-02 三态能力模型（核心）

这是整个契约最重要的部分。**「Java 显示支持」和「Python 能执行」必须是两份独立事实。**

| 状态 | 全称 | 权威来源 | 含义 |
|---|---|---|---|
| **Desired** | 期望能力 | Java `capability_json` | 业务上"允许/声明"提供哪些能力 |
| **Observed** | 实际能力 | Python Registry 运行时探测 | 当前真实加载了哪些 Adapter、函数、版本 |
| **Effective** | 有效能力 | **两侧核对计算得出** | 当前真正允许执行的能力 |

### Effective 推导规则

```
Effective(capability, accessMode) =
    Desired 声明该能力
    AND Observed 存在对应实现（函数已注册）
    AND accessMode 在能力的 accessModes 列表内
    AND implementationStatus ∈ {IMPLEMENTED, VERIFIED}
    AND （若为 AUTHENTICATED）存在 isUsable=true 的账号
    AND Source.status = ENABLED
```

### 核对结果映射（SOURCE-08 的核心）

| Desired | Observed | Effective 展示 |
|---|---|---|
| 已声明 | 已实现且版本匹配 | `AVAILABLE` |
| 已声明 | 未加载 / 无函数 | `ADAPTER_MISSING` |
| 已声明 | 已实现但版本不匹配 | `VERSION_MISMATCH` |
| 已声明 | 已实现但 status=DEGRADED | `DEGRADED` |
| 已声明 | 已实现但需登录且无可用账号 | `ACCOUNT_REQUIRED` |
| 已声明 | 已实现但 Source 已停用 | `SOURCE_DISABLED` |
| 未声明 | 已实现 | `UNREGISTERED`（提示：Python 有、Java 没登记） |
| 任意 | Python 不可达 | `UNKNOWN`（**不伪造为 UNAVAILABLE**） |

---

## 02-03 Capability Schema（最重要交付物）

### 现状问题（先记录）

两侧当前字段完全不一致：

```
Python base.py Capability:  comments / content_detail / author_profile / keyword_search / pagination / status / note
Java ds_source.capability_json: comments / commentStatus / contentDetail / keywordSearch / keywordStatus
```

同一份信息、两套字段名、粒度不同、无版本号。这是本次契约要消灭的。

### 冻结后的统一 Schema（v1）

```json
{
  "schemaVersion": "1.0",
  "sourceCode": "DOUYIN",
  "connectorCode": "DOUYIN_PUBLIC",
  "connectorVersion": "1.0.0",
  "capabilities": [
    {
      "code": "COMMENT",
      "name": "视频评论采集",
      "category": "COMMENT",
      "accessModes": ["PUBLIC", "AUTHENTICATED"],
      "implementationStatus": "VERIFIED",
      "contractVersion": "1.0",
      "verifiedAt": "2026-11-16T00:00:00Z",
      "adapterFunction": "comment.collect",
      "inputSchema": {
        "type": "object",
        "properties": {
          "videoId": { "type": "string" },
          "maxComments": { "type": "integer", "minimum": 0 }
        },
        "required": ["videoId"]
      },
      "outputSchema": { "$ref": "#/definitions/RawComment" },
      "limits": {
        "maxComments": 300,
        "maxPageSize": 20,
        "supportsPagination": true
      },
      "retryPolicy": {
        "maxAttempts": 3,
        "retryableErrors": ["NETWORK", "RATE_LIMIT"]
      }
    }
  ]
}
```

### 字段约束

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `schemaVersion` | string | 是 | 顶层 Schema 版本，当前固定 `"1.0"` |
| `sourceCode` | enum | 是 | `DOUYIN`/`XIAOHONGSHU`/`KUAISHOU`/`WEB`（首期） |
| `connectorCode` | string | 是 | 接入实现标识，如 `DOUYIN_PUBLIC` |
| `connectorVersion` | semver | 是 | 该接入实现的版本，接口变更时递增 |
| `capabilities[].code` | enum | 是 | 见下表能力枚举 |
| `capabilities[].accessModes` | enum[] | 是 | `PUBLIC`/`AUTHENTICATED`，决定是否需要账号 |
| `capabilities[].implementationStatus` | enum | 是 | 见下表状态枚举 |
| `capabilities[].adapterFunction` | string | 是 | **关键**：Python 侧真实可调用函数标识，能力必须由它证明 |
| `capabilities[].contractVersion` | string | 是 | 该能力的入参/出参契约版本，独立于 connectorVersion |
| `capabilities[].verifiedAt` | ISO8601 | 否 | 最近一次验证时间；为空表示未验证 |
| `capabilities[].limits` | object | 否 | 分页/数量上限 |
| `capabilities[].retryPolicy.retryableErrors` | enum[] | 是 | 可重试的错误类别 |

### 能力码枚举（首期）

| category | code | 说明 |
|---|---|---|
| SEARCH | `KEYWORD_SEARCH` | 关键词搜索发现内容 |
| CONTENT | `CONTENT_DETAIL` | 视频/笔记/文章详情 |
| COMMENT | `COMMENT` | 评论列表 |
| COMMENT | `COMMENT_REPLY` | 评论回复（楼中楼） |
| USER | `AUTHOR_PROFILE` | 公开用户资料 |
| USER | `AUTHOR_POSTS` | 公开作品列表 |
| MEDIA | `MEDIA_DOWNLOAD` | 允许访问的媒体信息 |

**注意**：监测（品牌/竞品/需求监测）**不是 Capability**，是 analysis 层对能力的组合调用。Capability 只表达"能从平台取到什么"。

### 实现状态枚举

| 值 | 含义 | 是否可执行 |
|---|---|---|
| `UNVERIFIED` | 代码存在但从未验证 | ❌ |
| `IMPLEMENTED` | 已实现且本地测试通过 | ⚠️ 允许但需标注 |
| `VERIFIED` | 已通过真实平台验证 | ✅ |
| `DEGRADED` | 可用但受限（如分页上限、限流） | ⚠️ 降级执行 |
| `DISABLED` | 被显式禁用 | ❌ |
| `UNSUPPORTED` | 平台不支持该能力 | ❌ |

**硬规则**：`adapterFunction` 指向的函数不存在时，无论 `implementationStatus` 写什么，Effective 一律不是 AVAILABLE。这是"能力由实现推导"的落地。

---

## 02-04 Registry 契约（Python）

### Adapter Registry

```python
# 每个 Connector 一个 Adapter 实例
class AdapterRegistry:
    def register(self, adapter: PlatformAdapter) -> None: ...
    def get(self, connector_code: str) -> PlatformAdapter: ...
    def list(self) -> list[AdapterMetadata]: ...
    def has(self, connector_code: str) -> bool: ...
```

规则：
- 重复注册同一 `connectorCode` → 拒绝（不覆盖，防静默替换）
- 单个 Adapter 加载失败 → 标记该 Connector 为 `FAILED`，不影响其它
- `list()` 只返回真实已实例化的 Adapter

### Capability Registry

```python
class CapabilityRegistry:
    def register_function(self, fn_name: str, fn: Callable) -> None: ...
    def resolve(self, fn_name: str) -> Callable | None: ...
    def list_capabilities(self) -> list[Capability]: ...
```

规则：
- **能力 = 声明 + 函数绑定**。`list_capabilities()` 只返回 `adapterFunction` 能 `resolve()` 到真实函数的能力。
- 声明了但 `resolve()` 返回 None → 该能力不出现在 Observed 清单（即使 JSON 里写了）。
- 这与 SOURCE-00 审计的两个项目结论一致：AiToEarn 的 `createMetadataCache`、DTK 的 Protocol 方法，都是"由实现推导"。

---

## 02-05 API 契约（Java ↔ Python 内部）

### 既有接口（保留）

| 方法 | 路径 | 现状 |
|---|---|---|
| GET | `/health` | 存活 + adapters 列表（**当前硬编码，SOURCE-03 要改为真实 Registry**） |
| GET | `/v1/capabilities` | 能力清单（**当前只报 stub，SOURCE-03 要改为真实 Observed**） |
| POST | `/v1/jobs` | 提交采集任务 |
| GET | `/v1/jobs/{id}` | 状态 |
| GET | `/v1/jobs/{id}/result` | 结果 |

### 新增（SOURCE-03 实施）

| 方法 | 路径 | 返回 |
|---|---|---|
| GET | `/v1/adapters` | Observed Adapter 清单（含版本、加载状态） |
| GET | `/v1/adapters/{connectorCode}` | 单个 Adapter 详情 + 其能力 |
| GET | `/v1/adapters/{connectorCode}/capabilities` | 该接入的 Observed 能力清单（按 02-03 Schema） |

### 统一响应信封

```json
{ "code": 0, "data": {...}, "error": null, "meta": { "traceId": "..." } }
```

### 健康检查分层（SOURCE-05）

| 级别 | 内容 | 需真实平台请求 | 返回状态 |
|---|---|---|---|
| L1 | Collection Service 存活 | 否 | UP/DOWN |
| L2 | Adapter 已加载 | 否 | LOADED/FAILED/NOT_FOUND |
| L3 | 能力函数已注册 | 否 | 能力清单 vs 声明的差异 |
| L4 | 平台网络连通 | 是（受控探测） | REACHABLE/UNREACHABLE/UNKNOWN |
| L5 | 授权访问+真实采集 | 是（指定账号+最小操作） | VALID/EXPIRED/INVALID/RATE_LIMIT |

**硬规则**：L1~L3 自动执行；L4/L5 需显式触发且受速率限制；**绝不为了健康检查伪造身份或高频调用平台**。

---

## 02-06 错误与状态契约

### 两层错误模型

**第一层：技术错误码**（新增，细粒度、可定位）

复用已实现的码并扩展：

| 技术错误码 | 含义 |
|---|---|
| `CREDENTIAL_MISSING` | 未配置凭证引用 |
| `CREDENTIAL_NOT_FOUND` | 凭证引用指向的文件不可读 |
| `SECRET_STORAGE_UNAVAILABLE` | 凭证存储目录不可用（部署问题） |
| `SERVICE_UNAVAILABLE` | 采集服务不可达（环境问题） |
| `CONNECT_TIMEOUT` | 平台连接超时 |
| `PLATFORM_UNAVAILABLE` | 平台接口不可用/变更 |
| `RATE_LIMITED` | 触发平台限流 |
| `AUTH_REQUIRED` | 需要登录但未提供有效账号 |
| `VIDEO_MISSING` | 目标视频不存在（**校验失败，不建 Execution**） |
| `ADAPTER_ERROR` | Adapter 执行异常 |

**第二层：业务失败类型**（复用现有七类，不新建）

| failure_type | 对应技术码示例 | retryable |
|---|---|---|
| `AUTHENTICATION` | CREDENTIAL_MISSING/NOT_FOUND、AUTH_REQUIRED | ❌ |
| `AUTHORIZATION` | 授权范围不足、FORBIDDEN | ❌ |
| `RATE_LIMIT` | RATE_LIMITED | ✅（退避后） |
| `NETWORK` | CONNECT_TIMEOUT、UPSTREAM_UNREACHABLE | ✅ |
| `PLATFORM` | PLATFORM_UNAVAILABLE、VIDEO_MISSING、内容已删 | ❌ |
| `DATA` | 解析失败、清洗失败、格式异常 | ✅（有限次） |
| `SYSTEM` | ADAPTER_ERROR、SERVICE_UNAVAILABLE、内部异常 | ✅（有限次） |

**硬规则**：`retryable` 由 failure_type 决定，不由 Java 读 errorMessage 猜。
用户取消 → `CANCELLED`，**不设 failure_type**，不计入 failed_count。

### 版本兼容规则

- `schemaVersion` 顶层：`1.0` → `1.x` 向后兼容（只加字段），`2.0` 破坏性变更需双读过渡。
- `connectorVersion`：接口行为变化时递增（如分页参数变化）。
- `contractVersion`（能力级）：入参/出参结构变化时递增。
- Java 遇到不认识的 `schemaVersion` 主版本 → 拒绝解析并标记 `UNKNOWN`，不静默忽略。

---

## 02-07 安全边界（冻结原则，首期不实现 OAuth）

| 原则 | 约束 |
|---|---|
| OAuth state | 随机、一次性、短有效期（如未来实现） |
| 回调主体校验 | 必须验证发起授权的登录主体，**绝不信任客户端传的 userId** |
| 账号与凭证分离 | `ds_account` 存账号业务信息，`credential_ref` 只存引用 |
| 凭证读取 | 仅 Execution 执行期、仅当前账号、经权限校验+审计 |
| 凭证不出现在 | API 响应、Task 快照、Execution 日志、普通业务日志 |
| 凭证撤销后 | 后续 Execution 不得读取旧凭证 |
| 存储不可用 | 返回 `SECRET_STORAGE_UNAVAILABLE`，**不改账号 auth_status** |
| 验证语义 | `CONFIG_ONLY` 仅证明配置层面，不得解释为平台真实登录有效 |
| 平台访问 | 不伪造签名、不模拟设备指纹、不做身份轮换绕过限制 |

---

## 交付物清单

| 编号 | 交付物 | 位置 |
|---|---|---|
| 02-01 | 领域模型 | 本文件 §02-01 |
| 02-02 | Capability Schema | 本文件 §02-03 + `SOURCE-02-capability-schema.json` |
| 02-03 | Registry 契约 | 本文件 §02-04 |
| 02-04 | API 契约 | 本文件 §02-05 |
| 02-05 | 状态与错误契约 | 本文件 §02-06 |
| 02-06 | 安全边界 | 本文件 §02-07 |
| ADR | 架构决策记录 | `ADR-006-source-capability-model.md` |
| 审计范围标注 | SOURCE-00 范围 | `SOURCE-00-AUDIT-SCOPE.md` |

## 未决问题清单

| # | 问题 | 影响 |
|---|---|---|
| 1 | Connector 是否首期就要持久化 | 建议不建表，等多接入并存需求出现再评 |
| 2 | `ds_source.status` 用 `ENABLED`，`ds_account.status` 用 `ACTIVE` | 建议保留各自语义（不同实体），不强行统一 |
| 3 | Python `/v1/capabilities` 现有返回格式与 v1 Schema 差异大 | SOURCE-03 需做响应结构迁移，保留旧端点做兼容期 |
| 4 | `search.py` 关键词搜索从未验证 | 能力矩阵中保持 `UNVERIFIED`，不得标 AVAILABLE |
| 5 | L4/L5 健康检查的频率与账号选择 | SOURCE-05 定，避免变成对平台的探测攻击 |
EOF