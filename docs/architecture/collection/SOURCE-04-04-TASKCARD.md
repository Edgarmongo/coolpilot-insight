# SOURCE-04-04 · Desired Capability 数据结构与状态语义定稿

> 状态：**设计冻结（候选）**，等待评审。**本阶段不修改代码、不修改数据库、不修改 Flyway。**
>
> 性质：这是 SOURCE-04 最关键的设计工作——它冻结 Java `capability_json`（Desired）的结构、
> 字段映射和状态语义，为 04-02（消费 Python Observed）与 04-03（三态核对）提供唯一的字段契约。
> 先定 Desired，再写消费与核对，避免字段映射返工。
>
> 关联：`SOURCE-02-CONTRACT.md`（冻结契约）、`SOURCE-02-capability-schema.json`（v1.1）、
> `ADR-006`（三态模型）、`SOURCE-03-SUPPLEMENT-REVIEW.md`（证据可信化）。

---

## 0. 交付物清单（本次应交付的全部内容）

1. **当前 `capability_json` 的真实样本** —— §1
2. **SOURCE-02 v1.1 Schema 与 Java 字段映射** —— §2
3. **Desired / Observed / Effective 完整状态转换表** —— §3
4. **`PENDING` 与 `effectiveStatus` 枚举的兼容裁决** —— §4
5. **版本匹配与配置变更失效规则** —— §5
6. **覆盖八种场景的测试设计** —— §6

---

## 1. 当前 `capability_json` 的真实样本

### 1.1 数据库里的真实值（唯一一行）

```sql
-- ds_source.id = 1, code = 'DOUYIN'
capability_json = '{"comments": true, "commentStatus": "AVAILABLE",
                    "contentDetail": true, "keywordSearch": false, "keywordStatus": "PENDING"}'
```

来源：`docker exec cpi-mysql mysql ... -e "SELECT capability_json FROM ds_source"`（2026-11-16 实查）。

### 1.2 种子数据（V9 迁移）

```sql
INSERT INTO ds_source (..., capability_json, ...)
VALUES ('DOUYIN', '抖音', 'SHORT_VIDEO', 'douyin', 'ENABLED',
        '{"contentDetail":true,"comments":true,"keywordSearch":false,
          "commentStatus":"AVAILABLE","keywordStatus":"PENDING"}',
        '评论采集已实测；关键词搜索未验证');
```

### 1.3 当前结构的字段清单（这是要迁移的对象）

| 字段 | 类型 | 现值 | 语义 |
|---|---|---|---|
| `comments` | boolean | `true` | 「评论采集」是否声明 |
| `contentDetail` | boolean | `true` | 「内容详情」是否声明 |
| `keywordSearch` | boolean | `false` | 「关键词搜索」是否声明 |
| `commentStatus` | string | `AVAILABLE` | 评论能力的实现状态 |
| `keywordStatus` | string | `PENDING` | 关键词搜索的实现状态 |

### 1.4 当前结构的四个问题（必须解决）

| # | 问题 | 后果 |
|---|---|---|
| P1 | **能力码不完整**：只有 comments / contentDetail / keywordSearch 三个，**没有** `COMMENT_REPLY` / `AUTHOR_PROFILE` / `AUTHOR_POSTS` / `MEDIA_DOWNLOAD` | Python 已加载的 `COMMENT_REPLY` 在 Desired 里根本没有表达位置 |
| P2 | **结构是「扁平布尔 + 并列状态」，没有能力对象** | 无法表达每条能力的 `accessModes` / `contractVersion` / 版本 |
| P3 | **`PENDING` 不在 `effectiveStatus` 枚举里** | 与 SOURCE-02 契约各说各话（详见 §4） |
| P4 | **无 `schemaVersion`**，无法识别旧结构 | 迁移时只能靠"猜字段形状" |

### 1.5 结论

当前 `capability_json` 是**手工维护、与 Python 零联动、结构不完整**的历史遗留。
它在 ADR-006 里的定位是「Desired（业务声明）」，这个定位**保留**，
但结构必须升级到能承载「每条能力 × 访问模式」的契约形状。

---

## 2. SOURCE-02 v1.1 Schema 与 Java 字段映射

### 2.1 设计原则：Desired 复用契约形状

**决定**：Desired 的每条能力，用 SOURCE-02 `capabilities[].code` + `accessModes` 表达
「业务声明提供哪些能力」，**不复制** `implementationStatus` / `adapterFunction` 这类
「只属于 Observed」的字段——那些是 Python 的运行时事实，不是 Java 的业务声明。

> **为什么不把 Python 的 Observed 状态写回 `capability_json`**（用户强调的红线）：
> Desired 是**业务声明**，Observed 是**运行时事实**，Effective 是**计算结论**。
> 一旦把 Observed 写进 `capability_json`，三态就塌成两态，而且某次探测失败会
> 静默"清空"掉 Java 已声明的能力——这正是 ADR-006 明确禁止的。

### 2.2 Desired 能力对象（冻结）

```json
{
  "schemaVersion": "2.0",
  "capabilities": [
    {
      "code": "COMMENT",
      "category": "COMMENT",
      "accessModes": ["PUBLIC", "AUTHENTICATED"],
      "desired": "ENABLED"
    },
    {
      "code": "KEYWORD_SEARCH",
      "category": "SEARCH",
      "accessModes": ["PUBLIC"],
      "desired": "DISABLED"
    }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `schemaVersion` | string | 是 | Desired 结构版本，冻结为 `2.0`（区别于 Observed 的 `1.1`） |
| `capabilities[]` | array | 是 | 业务声明的能力列表 |
| `capabilities[].code` | string | 是 | SOURCE-02 能力码（枚举见下） |
| `capabilities[].category` | string | 是 | SOURCE-02 能力分类（SEARCH/CONTENT/COMMENT/USER/MEDIA） |
| `capabilities[].accessModes` | string[] | 是 | 该能力允许的访问模式（PUBLIC/AUTHENTICATED） |
| `capabilities[].desired` | string | 是 | **Desired 自身的开关**，取值 `ENABLED` / `DISABLED` |

**`desired` 字段的语义**：它表达「Java 侧是否**声明**提供这条能力」。
注意它**不是** `implementationStatus`（那是 Python 的实现状态），
也**不是** `effectiveStatus`（那是核对结果）。它是三态里 Desired 那一态自己的布尔开关。

### 2.3 能力码枚举（与 SOURCE-02 逐字一致，一字不扩）

| category | code |
|---|---|
| SEARCH | `KEYWORD_SEARCH` |
| CONTENT | `CONTENT_DETAIL` |
| COMMENT | `COMMENT` |
| COMMENT | `COMMENT_REPLY` |
| USER | `AUTHOR_PROFILE` |
| USER | `AUTHOR_POSTS` |
| MEDIA | `MEDIA_DOWNLOAD` |

### 2.4 Java 字段映射（SOURCE-02 v1.1 → Desired → Observed → Effective）

| 维度 | Desired（`capability_json` v2） | Observed（`/v1/capabilities` 1.1） | Effective（计算） |
|---|---|---|---|
| 能力标识 | `code` | `code` | `code` |
| 分类 | `category` | `category` | `category` |
| 访问模式 | `accessModes`（业务声明） | `accessModes`（运行时声明） | 取交集后逐 mode 判定 |
| 业务开关 | `desired`（ENABLED/DISABLED） | —（无此概念） | 参与 AVAILABLE 判定 |
| 实现状态 | —（**不存**） | `implementationStatus` | 参与 AVAILABLE 判定 |
| 契约版本 | —（不存，见 §5） | `contractVersion` | 参与版本匹配 |
| 函数引用 | —（**不存**，只属 Observed） | `adapterFunction` | 函数缺失 → 不 AVAILABLE |
| 验证证据 | —（**不存**） | `evidence` | 证据无效 → 不 AVAILABLE |
| 语义 | —（恒 READ，契约约定） | `semantics`（READ） | 恒 READ |

**关键结论**：`capability_json` v2 **只存 Desired 自己那一列**（code + category +
accessModes + desired），实现状态、函数、证据、版本全部留在 Observed。
这样三态之间没有字段重叠，杜绝"Java 配置、Python 运行态、缓存"三套数据互相冲突。

### 2.5 Java 侧的承载

| 现有 | 处置 |
|---|---|
| `DataSource.capabilityJson`（`String`） | **保留列**，内容升级为 v2 结构；不新增列 |
| `SourceQueryService.parseCapability`（解析为 `Map`） | 升级为解析 v2 结构；**兼容旧结构**（§4.2） |
| `DataSourceView.capability`（`Map`） | 替换为类型化 `List<DesiredCapability>`（04-06 实施） |
| 新增 `DesiredCapability` DTO | 04-06 实施时落地 |

> 本阶段（04-04）**只定结构**，不写 Java 类。DTO 落地属 04-06。

---

## 3. Desired / Observed / Effective 完整状态转换表

### 3.1 三态定义（重申，与 ADR-006 一致）

| 态 | 全称 | 权威来源 | 持久化 | 含义 |
|---|---|---|---|---|
| **Desired** | 期望能力 | Java `capability_json` | 数据库（`ds_source.capability_json`） | 业务上"声明/允许"提供 |
| **Observed** | 实际能力 | Python Registry | **不落库**，可短期缓存 | 运行时"真实加载了" |
| **Effective** | 有效能力 | 两侧核对**计算** | **不作为独立事实来源**，随查询计算 | 当前"真正允许执行" |

**铁律**（用户强调）：Effective **不是第三份持久化数据**，是**派生结论**。
缓存 Observed 是允许的（避免每次请求都打 Python），但缓存**必须带 TTL 与 stale 标记**，
且**绝不**回写 `capability_json`。

### 3.2 完整状态转换表（八场景 + 补充分支）

Effective 是对「(source, connector, capability, accessMode)」四元组的判定。
判定顺序自上而下**短路**（命中即停，不往下走）。

| # | 前置条件 | Java Desired | Python Observed | `effectiveStatus` | 可否执行 |
|---|---|---|---|---|---|
| 0 | Source.status = DISABLED | 任意 | 任意 | `SOURCE_DISABLED` | ❌ 不允许新执行 |
| 1 | Registry 查询失败 / 主版本不支持 | 任意 | **未知** | `UNKNOWN` | ❌（不伪造 UNAVAILABLE） |
| 2 | Desired 未声明该能力（`desired=DISABLED` 或不在列表） | 未声明 | 任意（含已加载） | `UNREGISTERED` | ❌ 不允许执行 |
| 3 | Desired 已声明，但 Observed 无此 Connector | 已声明 | 无 Connector | `ADAPTER_MISSING` | ❌ |
| 4 | Desired 已声明，Connector 版本与 Desired 期望不符 | 已声明 | 版本不匹配 | `VERSION_MISMATCH` | ❌ |
| 5 | Desired 已声明，Connector 下无此能力 | 已声明 | 无此能力 | `UNREGISTERED` | ❌ |
| 6 | Desired 已声明，能力存在但 `implementationStatus=DEGRADED` | 已声明 | DEGRADED | `DEGRADED` | ⚠️ 降级执行 |
| 7 | Desired 已声明，能力只支持 AUTHENTICATED 且无 `isUsable` 账号 | 已声明 | AUTHENTICATED | `ACCOUNT_REQUIRED` | ❌ |
| 8 | Desired 已声明，Observed 状态 ∈ {VERIFIED, IMPLEMENTED} 且账号/模式满足 | 已声明 | 已实现 | `AVAILABLE` | ✅ |
| 9 | Desired 已声明，Observed 状态 ∈ {UNVERIFIED, DISABLED, UNSUPPORTED} | 已声明 | 未验证/禁用/不支持 | `UNREGISTERED` | ❌ |

> 枚举纪律：上表用的都是 `effectiveStatus` 的**正式八值**
> （`AVAILABLE` / `ADAPTER_MISSING` / `VERSION_MISMATCH` / `DEGRADED` /
> `ACCOUNT_REQUIRED` / `SOURCE_DISABLED` / `UNREGISTERED` / `UNKNOWN`）。
> **没有 `UNAVAILABLE` 这个值**——它不是枚举成员，是"非 AVAILABLE"的通俗概括。
> 用户示意表里的 `UNAVAILABLE` 在正式枚举里对应 `ADAPTER_MISSING` / `UNREGISTERED`。

### 3.3 关于 `UNKNOWN` 与 `UNAVAILABLE` 的区别（用户强调，必须写清楚）

| 概念 | 语义 | 何时 |
|---|---|---|
| `UNAVAILABLE`（通俗） | **已确认**不满足执行条件 | 版本不匹配、函数缺失、未声明、未加载、需账号……（对应上表 #2~#7、#9） |
| `UNKNOWN`（正式枚举） | **无法获得可靠判断结果** | Python 服务不可达、响应非法、Schema 主版本不认识 |

**铁律**：**Collection Service 暂时不可达，不代表所有平台能力都失效。**
不可达时只能判 `UNKNOWN`，不能判成"全部不可用"。这是 04-02 的缓存策略
（保留上次成功结果 + stale 标记）与 04-03 的推导规则共同保证的。

---

## 4. `PENDING` 与 `effectiveStatus` 枚举的兼容裁决

### 4.1 问题

- 旧 `capability_json` 里有 `"keywordStatus": "PENDING"`；
- 旧 `Capability.status`（SOURCE-03 之前的 legacy 词表）也用过 `PENDING`；
- 但 `effectiveStatus` 的正式八值里**没有** `PENDING`。

`PENDING` 是 Java 侧**自己发明的历史状态词**，与冻结契约各说各话。

### 4.2 裁决（三选一，本阶段推荐 A，最终待你拍板）

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **A（推荐）** | `capability_json` 升级为 v2 结构，旧字段**在兼容期由服务端派生**，`PENDING` 映射为「未声明能力的语义」 | Desired 与 Observed 同构，04-03 无需字段映射 | 一次数据迁移 + 兼容期双读 |
| B | 保留旧结构，只把状态词换成契约枚举（`PENDING` → `UNREGISTERED`） | 改动最小 | Desired/Observed 仍是两套结构，04-03 要写映射 |
| C | 把 `PENDING` 加入 `effectiveStatus` 枚举 | 最省事 | **破坏契约冻结**，违反"枚举一字不扩"的红线，否决 |

**裁决结论**：
- **否决 C** —— `PENDING` 不是 Effective 枚举，加进去就是为迁就历史而污染契约。
- **推荐 A** —— 因为 04-03 的核对逻辑要求 Desired 与 Observed 同构，
  方案 B 的"两套结构"会在核对层埋下长期映射债。
- `PENDING` 的**语义归宿**：旧 `keywordStatus=PENDING` 表达的是"能力未验证"，
  在 v2 结构里它**不再是一个状态词**，而是由「`desired` 声明 + Observed 的
  `implementationStatus=UNVERIFIED`」共同推导为 Effective `UNREGISTERED`。
  即：`PENDING` 被**拆解**掉了，没有直接的等价枚举值。

### 4.3 兼容期策略（不破坏老前端，不阻塞迁移）

- 读：`parseCapability` 先探测 `schemaVersion`；无该字段 → 按旧结构解析，
  在返回视图里**同时**给旧字段和新结构，前端无缝过渡；
- 写：04-06 落地时，管理接口写 v2；旧字段由服务端从 v2 派生（`comments`、
  `keywordSearch` 等布尔 + `commentStatus` 等状态词），**只读不再写**；
- 迁移：一次性 UPDATE 现有行 → v2 结构（可重入，`WHERE schemaVersion IS NULL`）。

---

## 5. 版本匹配与配置变更失效规则

### 5.1 三种版本，三种语义（用户点名的易混点）

| 版本 | 载体 | 语义 | 变化触发 |
|---|---|---|---|
| **Schema 版本** | `capabilities[].schemaVersion`（顶层） | 契约结构版本 | 结构破坏性变更 → 升主版本 |
| **Contract 版本** | `capabilities[].contractVersion` | 单条能力的入参/出参契约版本 | 该能力的输入/输出形状变化 |
| **Adapter 版本** | `connectorVersion` | 某个 Connector 的实现版本 | 该接入的行为变化 |

**匹配规则**（自上而下，任一不满足 → 不 AVAILABLE）：

1. **Schema 主版本**：Java 认识的最高主版本 = 1（`SUPPORTED_SCHEMA_MAJOR=1`）。
   读到 `2.x` → 拒绝解析，整体 `UNKNOWN`。
2. **Contract 版本**：Desired 侧**不存储** contractVersion（它属于 Observed，见 §2.2）。
   匹配逻辑是：Desired 声明了能力码，Observed 返回该能力时带 `contractVersion`，
   Java 校验它与 `DesiredCapability` 里**约定支持的** contract 主版本是否一致。
   首期策略：**主版本一致即兼容**（`1.x` ↔ `1.y` 兼容，`2.x` 不兼容）。
3. **Adapter 版本**：Desired 通过 `adapter_code` 关联到 connectorCode（见 §5.3），
   若业务配置里锁定了期望的 `connectorVersion`，则逐段比较；
   首期**不锁版本**（`adapter_code` 只关联 connector，不锁 semver），
   版本不匹配仅在「Desired 显式声明了期望版本」时才触发 `VERSION_MISMATCH`。

### 5.2 配置变更失效规则（用户点名的第五个问题）

**Desired 变更后，旧的核对结论如何失效？**

| 变更类型 | 失效动作 |
|---|---|
| 修改 `capability_json`（增删改能力/改 desired） | 服务端**立即**失效该 Source 的 Effective 缓存（如果 04-03 有缓存）；下次查询重新核对 |
| 修改 `ds_source.status`（ENABLED↔DISABLED） | 同上；且 `SOURCE_DISABLED` 立即生效，**不允许新执行** |
| Observed 侧变化（Python 重启/Adapter 版本变化） | 由 Observed 缓存的 TTL 自然失效（§3.1），或 Python 侧推更新 |

**关键**：Effective 不落库（§3.1），所以"失效"的本质是**缓存失效 + 下次重算**，
不是"删除一份持久化数据"。这从机制上避免了"旧核对结论残留"。

### 5.3 connectorCode 与 `adapter_code` 的映射（补充裁决）

- `ds_source.adapter_code` 目前是 `'douyin'`（小写），Python 侧 connectorCode 是
  `DOUYIN_PUBLIC`（大写）。
- **裁决**：Desired 里新增一个显式关联字段，把 `adapter_code` 与 connectorCode 对齐。
  首期做法：`capability_json` v2 顶层加 `"connectorCode": "DOUYIN_PUBLIC"`，
  它**是** Desired 指向哪个接入的声明；`adapter_code` 列暂留作兼容，
  不作为核对键（避免大小写/命名两套口径）。

```json
{
  "schemaVersion": "2.0",
  "connectorCode": "DOUYIN_PUBLIC",
  "capabilities": [ ... ]
}
```

---

## 6. 覆盖八种场景的测试设计

> 本阶段只**设计**测试，不实现。落地属 04-03（核对逻辑）与 04-06（管理 API）。

### 6.1 核对层单元测试（`CapabilityReconcilerTest`，04-03 落地）

| # | 场景 | Java Desired | Python Observed | 断言 `effectiveStatus` | 断言可否执行 |
|---|---|---|---|---|---|
| 1 | 双方一致 | COMMENT 声明 | COMMENT VERIFIED | `AVAILABLE` | ✅ |
| 2 | 已声明未验证 | KEYWORD_SEARCH 声明 | KEYWORD_SEARCH UNVERIFIED | `UNREGISTERED` | ❌ |
| 3 | 已声明未加载 | COMMENT 声明 | （无 COMMENT） | `UNREGISTERED` | ❌ |
| 4 | 已声明函数缺失 | COMMENT 声明 | （诊断 INVALID_IMPLEMENTATION） | `UNREGISTERED` | ❌ |
| 5 | 未声明已加载 | （无 COMMENT_REPLY） | COMMENT_REPLY VERIFIED | `UNREGISTERED` | ❌ 不允许执行 |
| 6 | 已声明版本不匹配 | COMMENT 声明 + 期望版本 | COMMENT 但 connectorVersion 不符 | `VERSION_MISMATCH` | ❌ |
| 7 | 已声明服务不可达 | COMMENT 声明 | （HTTP 超时/500） | `UNKNOWN` | ❌（不伪造 UNAVAILABLE） |
| 8 | Source 停用 | COMMENT 声明 | COMMENT VERIFIED | `SOURCE_DISABLED` | ❌ 不允许新执行 |

补充边界（非示意表，但必须测）：

| # | 场景 | 断言 |
|---|---|---|
| 9 | 能力只支持 AUTHENTICATED 且无账号 | `ACCOUNT_REQUIRED` |
| 10 | 能力 DEGRADED | `DEGRADED`（降级执行，非禁用） |
| 11 | Registry 返回 Schema 2.x | `UNKNOWN` + `schemaSupported=false` |
| 12 | Observed 缓存 TTL 过期 | 触发重新查询，不返回陈旧 Effective |

### 6.2 缓存层测试（04-02 落地）

- 查询成功 → 缓存；查询失败 → **保留上次成功结果 + 标记 stale**；
- 失败后**不得**返回空集合冒充"没有能力"（要返回 `UNKNOWN`）；
- TTL 到期 → 主动刷新；刷新失败 → 继续 stale + 告警。

### 6.3 兼容层测试（04-06 落地）

- 旧结构 `capability_json`（无 `schemaVersion`）→ 仍能读，返回视图同时含旧字段与新结构；
- 新结构 v2 → 正确解析 `connectorCode` + `capabilities[]`；
- 迁移脚本可重入：`WHERE schemaVersion IS NULL` 只迁移一次。

### 6.4 端到端测试（04-08 落地）

- 真实 `ds_source` 行 + 真实 `/v1/capabilities` → 三态明细；
- 采集服务停掉 → Effective 全部 `UNKNOWN`，且 `capability_json` 内容不变。

---

## 7. 冻结决议汇总（评审时逐条确认）

| # | 决议 | 结论 |
|---|---|---|
| R1 | Desired 结构 | `capability_json` 升级为 v2：`{schemaVersion, connectorCode, capabilities[{code,category,accessModes,desired}]}` |
| R2 | 能力唯一标识 | `source + connector + capability + accessMode` 四元组 |
| R3 | Desired 不存实现态 | `implementationStatus` / `adapterFunction` / `evidence` / `contractVersion` **一律不写回** Desired |
| R4 | PENDING 裁决 | 不加入 Effective 枚举（否决 C）；语义被"声明 + UNVERIFIED"拆解；推荐迁移方案 A |
| R5 | 版本匹配 | Schema 主版本 + Contract 主版本 + 可选 Adapter 锁版本；首期不锁 connectorVersion |
| R6 | 变更失效 | Desired/status 变更立即失效缓存；Effective 不落库，靠重算 |
| R7 | UNKNOWN vs UNAVAILABLE | 服务不可达 → `UNKNOWN`，**不判全部失效** |
| R8 | 唯一标识需含 AccessMode | 同一能力 PUBLIC 与 AUTHENTICATED 是两个可执行面，需分开判定 |

---

## 8. 本阶段明确不做的事

- ❌ 不改代码（不写 `DesiredCapability` DTO、不改 `SourceQueryService`）
- ❌ 不改数据库（不 `ALTER ds_source`）
- ❌ 不新增 Flyway 迁移（当前停在 V30）
- ❌ 不实现 04-02（Java 消费 Python）、04-03（核对逻辑）
- ❌ 不开发前端（04-07 继续暂缓）
- ❌ 不扩充 `effectiveStatus` / `implementationStatus` / `capabilityCode` 枚举

---

## 9. 待评审确认项（阻塞 04-02/04-03 实施）

1. **§4.2 迁移方案 A / B 的选择**（推荐 A：v2 结构 + 旧字段派生）。
2. **§5.1 版本匹配深度**（首期"主版本一致即兼容"是否够用）。
3. **§5.3 `connectorCode` 进 Desired 顶层** vs 复用 `adapter_code` 列。
4. **§2.2 `desired` 字段命名**（ENABLED/DISABLED）是否与直觉一致。
