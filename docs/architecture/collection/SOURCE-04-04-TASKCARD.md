# SOURCE-04-04 · Desired Capability 数据结构与状态语义定稿

> 状态：**设计冻结（v2 修订版，待最终评审）**
> 基线提交：`3507c8f`　本修订：评审修订指令（四项裁决 + UNVERIFIED 语义修正）
>
> **本阶段不修改 Java/Python 业务代码、不修改前端、不修改 MySQL 数据、
> 不修改 Flyway Migration、不改变 `ds_account` 状态模型、
> 不改变 Task/Run/Execution/Item 状态机、不启动 SOURCE-04-02 / 04-03。**
>
> 关联：`SOURCE-02-CONTRACT.md`、`SOURCE-02-capability-schema.json`（v1.1，本次**未改动**）、
> `ADR-006`（三态模型）、`ADR-008`（Effective 状态与诊断原因分离）、
> `SOURCE-04-04-EFFECTIVE-REASON.md`（20 个 `effectiveReason` 的完整定义表与重复语义审查）、
> `SOURCE-03-SUPPLEMENT-REVIEW.md`（证据可信化）。

---

## 0. 评审裁决记录（四项全部采纳）

| # | 评审项 | 原设计（`3507c8f`） | 裁决 | 本修订 |
|---|---|---|---|---|
| D1 | 迁移方案 | 推荐 A，待确认 | **A 通过** | §2 冻结 v1→v2 迁移映射；**仍不执行迁移** |
| D2 | 版本匹配深度 | 主版本兼容，未锁 Adapter | **有条件通过** | §4 明确：锁定 Adapter 时必须满足锁定要求；保留完整版本串 |
| D3 | `connectorCode` 位置 | 放 Desired **顶层** | **需调整** | §1 改为**每个能力对象内**独立声明 |
| D4 | 字段命名 | `desired` | **需调整** | §1 改为 `desiredStatus`，枚举 `ENABLED` / `DISABLED` |
| **D5** | **UNVERIFIED 的 Effective 投影** | 折叠为 `UNREGISTERED` | **必须修订** | §3 + `ADR-008`：拆为 `UNKNOWN` + `VERIFICATION_REQUIRED` |

### 0.1 D5 的本质

原设计把三种不同事实折叠成一个结论：

| 事实 | 原映射 | 问题 |
|---|---|---|
| 能力不存在于 Registry | `UNREGISTERED` | 正确 |
| 能力存在，函数解析不到 | `UNREGISTERED` | 勉强（确实没成为可用注册项） |
| **能力存在，函数可用，从未验证** | `UNREGISTERED` | **事实错误——它明明注册了** |

**核对结论：`effectiveStatus` 八值中没有任何一个能精确表达「已注册但未验证」。**
这是 SOURCE-02 的既存契约缺口（其"核对结果映射"表本身就缺这一行）。
完整论证见 `ADR-008` §二。

**处置**：不改枚举（本次 Schema 文件零改动），改为
`effectiveStatus`（准入分类，冻结八值）× `effectiveReason`（精确原因，Java 内部模型）正交表达。

---

## 1. 最终 Desired v2 结构

### 1.1 冻结示例（真实数据映射结果）

```json
{
  "schemaVersion": "2.0",
  "capabilities": [
    {
      "sourceCode": "DOUYIN",
      "connectorCode": "DOUYIN_PUBLIC",
      "code": "COMMENT",
      "category": "COMMENT",
      "accessModes": ["PUBLIC", "AUTHENTICATED"],
      "desiredStatus": "ENABLED"
    },
    {
      "sourceCode": "DOUYIN",
      "connectorCode": "DOUYIN_PUBLIC",
      "code": "COMMENT_REPLY",
      "category": "COMMENT",
      "accessModes": ["PUBLIC", "AUTHENTICATED"],
      "desiredStatus": "ENABLED"
    },
    {
      "sourceCode": "DOUYIN",
      "connectorCode": "DOUYIN_PUBLIC",
      "code": "CONTENT_DETAIL",
      "category": "CONTENT",
      "accessModes": ["PUBLIC"],
      "desiredStatus": "ENABLED"
    },
    {
      "sourceCode": "DOUYIN",
      "connectorCode": "DOUYIN_PUBLIC",
      "code": "KEYWORD_SEARCH",
      "category": "SEARCH",
      "accessModes": ["PUBLIC"],
      "desiredStatus": "DISABLED"
    }
  ]
}
```

### 1.2 字段定义

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `schemaVersion` | string | 是 | Desired 结构版本，冻结为 `"2.0"`（**区别于** Observed 的 `1.1`） |
| `capabilities` | array | 是 | 业务声明的能力列表；**可为空数组**（表示该 Source 未声明任何能力） |
| `capabilities[].sourceCode` | string | 是 | 平台码，枚举见 §1.3 |
| `capabilities[].connectorCode` | string | 是 | 接入实现码（**每能力独立声明**，支撑多 Connector） |
| `capabilities[].code` | string | 是 | 能力码，枚举见 §1.3 |
| `capabilities[].category` | string | 是 | 能力分类：`SEARCH`/`CONTENT`/`COMMENT`/`USER`/`MEDIA` |
| `capabilities[].accessModes` | string[] | 是 | 业务允许的访问模式：`PUBLIC` / `AUTHENTICATED`，至少一项 |
| `capabilities[].desiredStatus` | string | 是 | **Desired 自身的开关**：`ENABLED` / `DISABLED` |

### 1.3 枚举（与 SOURCE-02 逐字一致，一字不扩）

**sourceCode**：`DOUYIN` / `XIAOHONGSHU` / `KUAISHOU` / `WEB`

**capabilityCode**：`KEYWORD_SEARCH` / `CONTENT_DETAIL` / `COMMENT` / `COMMENT_REPLY` /
`AUTHOR_PROFILE` / `AUTHOR_POSTS` / `MEDIA_DOWNLOAD`

**category**：`SEARCH` / `CONTENT` / `COMMENT` / `USER` / `MEDIA`

**accessModes**：`PUBLIC` / `AUTHENTICATED`

**desiredStatus**（本设计新增，属 Desired 私有语义）：

| 值 | 含义 |
|---|---|
| `ENABLED` | 业务期望该能力可被提供 |
| `DISABLED` | 业务主动禁止该能力 |

> `desiredStatus` **不是** `implementationStatus`（Python 实现状态），
> **不是** `effectiveStatus`（核对结论），**不是**验证状态。
> 它只回答一个业务问题：Java 侧是否允许提供这条能力。

### 1.4 D3 裁决：为什么 `connectorCode` 必须在能力对象内

一个 Source 可以有多套接入实现：

```
Source: DOUYIN
  ├── Connector DOUYIN_PUBLIC    (公开网页)
  │     ├── COMMENT
  │     └── CONTENT_DETAIL
  └── Connector DOUYIN_SESSION   (授权会话，未来)
        ├── COMMENT
        └── AUTHOR_PROFILE
```

若 `connectorCode` 只放在顶层，一个 Source 的 Desired 就**只能表达一个 Connector**，
与多接入架构直接冲突。放进能力对象后，同一 Source 的不同能力可以指向不同 Connector。

**对 `adapter_code` 列的处置**：保留为历史兼容字段，
但**不认定它是多 Connector 模型的权威来源**。权威来源是
`capabilities[].connectorCode`。二者不一致时的处理见 §2.3。

### 1.5 三态数据来源隔离（铁律，不放宽）

| 态 | 权威来源 | 持久化 | 是否含实现状态 |
|---|---|---|---|
| **Desired** | Java / MySQL `ds_source.capability_json` | ✅ 落库 | ❌ **不含** |
| **Observed** | Python Registry `/v1/capabilities` | ❌ 不落库，可短期缓存 | ✅ 只有它有 |
| **Effective** | Java 计算（Desired × Observed × Source.status） | ❌ **不作为独立事实来源**，可缓存但必须可失效 | ❌ 派生结论 |

**Desired 不存**：`implementationStatus` / `adapterFunction` / `evidence` /
`contractVersion` / `verifiedAt`。这些全部属于 Observed。

**禁止**：把 Observed 写回 `capability_json`。一旦写回，三态塌成两态，
且某次探测失败会静默清空 Java 已声明的能力（ADR-006 明令禁止）。

---

## 2. v1 → v2 字段迁移映射（D1 裁决：方案 A）

> **选择 A 不代表现在执行迁移。** 本节只冻结映射规则；
> 真正的 SQL 变更、Flyway Migration 属独立任务，需单独实施与验收。

### 2.1 现状样本（库中真实值，非推测）

```sql
-- ds_source.id=1, code='DOUYIN'
capability_json = '{"comments": true, "commentStatus": "AVAILABLE",
                    "contentDetail": true, "keywordSearch": false,
                    "keywordStatus": "PENDING"}'
```

### 2.2 确定性映射规则

| v1 字段 | 条件 | v2 结果 |
|---|---|---|
| `comments` | `true` | 生成 `code=COMMENT`, `category=COMMENT`, `desiredStatus=ENABLED` |
| `comments` | `false` | 生成 `code=COMMENT`, `desiredStatus=DISABLED` |
| `contentDetail` | `true` | 生成 `code=CONTENT_DETAIL`, `category=CONTENT`, `desiredStatus=ENABLED` |
| `contentDetail` | `false` | 生成 `code=CONTENT_DETAIL`, `desiredStatus=DISABLED` |
| `keywordSearch` | `true` | 生成 `code=KEYWORD_SEARCH`, `category=SEARCH`, `desiredStatus=ENABLED` |
| `keywordSearch` | `false` | 生成 `code=KEYWORD_SEARCH`, `desiredStatus=DISABLED` |
| `commentStatus` / `keywordStatus` | 任意值 | **丢弃**，见 §2.4 |
| —（v1 无此字段） | — | `sourceCode` 取 `ds_source.code` |
| —（v1 无此字段） | — | `connectorCode` 取映射表（§2.3） |
| —（v1 无此字段） | — | `accessModes` 取保守默认（§2.5） |

**迁移后应得到**（与 §1.1 一致）：

```json
{
  "schemaVersion": "2.0",
  "capabilities": [
    {"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","code":"COMMENT",
     "category":"COMMENT","accessModes":["PUBLIC","AUTHENTICATED"],"desiredStatus":"ENABLED"},
    {"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","code":"CONTENT_DETAIL",
     "category":"CONTENT","accessModes":["PUBLIC"],"desiredStatus":"ENABLED"},
    {"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","code":"KEYWORD_SEARCH",
     "category":"SEARCH","accessModes":["PUBLIC"],"desiredStatus":"DISABLED"}
  ]
}
```

### 2.3 connectorCode 映射（`adapter_code` 不可直接当权威）

当前 `ds_source.adapter_code = 'douyin'`（小写、平台级），
Python 的 connectorCode 是 `DOUYIN_PUBLIC`（大写、接入级）。**两者不是同一个概念。**

| 迁移策略 | 说明 |
|---|---|
| 显式映射表 | `('DOUYIN','douyin') → 'DOUYIN_PUBLIC'`；新增平台时补表 |
| 查不到映射 | **不猜测**，该能力迁移失败，进入诊断（§2.6） |

> 为什么不能用 `adapter_code` 直接兜底：一旦拼错（`douyin` → `DOUYIN`），
> 核对时会永远判 `ADAPTER_MISSING`，且看起来像"Python 没加载"，
> 排查成本极高。宁可迁移失败并显式报错。

### 2.4 旧状态词的归宿（与 ADR-008 一致）

`commentStatus` / `keywordStatus` 这两个 v1 状态词**不迁移**，原因：

- `PENDING` **不在** `effectiveStatus` 八值内；
- 它们本是"实现状态"的蹩脚表达，本就属于 Observed，不属于 Desired；
- v2 里这个信息由「`desiredStatus` + Observed 的 `implementationStatus`」共同派生 Effective。

**迁移后的语义对照**（证明信息没丢）：

| v1 | v2 迁移结果 | 结合实时 Observed | Effective |
|---|---|---|---|
| `keywordSearch:false, keywordStatus:"PENDING"` | `KEYWORD_SEARCH / desiredStatus=DISABLED` | `UNVERIFIED` | `UNREGISTERED` + `NOT_DECLARED`（Desired 未声明优先） |
| `comments:true, commentStatus:"AVAILABLE"` | `COMMENT / desiredStatus=ENABLED` | `VERIFIED` + 证据有效 | `AVAILABLE` + `NONE` |

> 注意第一行：`PENDING` 的信息**没有丢失**，但它不再是 Desired 的状态词，
> 而是由 Desired 的 DISABLED + Observed 的 UNVERIFIED 共同体现。

### 2.5 accessModes 的保守默认

v1 没有 `accessModes` 概念。迁移时**取保守值**：

| 能力 | 默认 accessModes | 依据 |
|---|---|---|
| `COMMENT` | `["PUBLIC","AUTHENTICATED"]` | 匿名可采，登录可提高上限（Python 侧声明如此） |
| `COMMENT_REPLY` | `["PUBLIC","AUTHENTICATED"]` | 同上 |
| `CONTENT_DETAIL` | `["PUBLIC"]` | 只读公开页 |
| `KEYWORD_SEARCH` | `["PUBLIC"]` | Public only |
| 其它 | `["PUBLIC"]` | 保守取最窄 |

> **取窄不取宽**：`accessModes` 越窄，核对时越容易触发 `ACCESS_MODE_UNSUPPORTED`
> 而不是误判可用。误判"可用"的代价远高于误判"不可用"。

### 2.6 迁移失败的处理（不静默丢弃）

| 情况 | 处理 |
|---|---|
| `connectorCode` 映射不到 | 该能力**不写入** v2，但记录诊断 `DESIRED_MIGRATION_FAILED`（能力码 + 原因） |
| `comments` 等字段类型非法（非 boolean） | 同上 |
| `capability_json` 整体非法 JSON | 整行保持 v1 不动，记录诊断，**不覆盖** |
| v2 结构已存在（`schemaVersion` 存在） | 跳过（可重入） |

**铁律**：迁移失败时**宁可保留 v1 并报错，也不猜测字段含义**。
静默丢弃能力会让"Java 声明过"变成"Java 从没声明"，属于事实篡改。

### 2.7 兼容读取（新旧双读，兼容期）

| 输入 | 行为 |
|---|---|
| 无 `schemaVersion` | 按 v1 解析 → 用 §2.2 规则映射为内部 Desired 模型 → 返回视图时**同时**含旧字段与 v2 结构 |
| `schemaVersion = "2.0"` | 直接解析 |
| `schemaVersion = "2.x"`（x>0） | 主版本一致，按 2.0 解析（前向兼容） |
| `schemaVersion` 主版本 ≥ 3 | 拒绝解析，该 Source 的 Desired 判为 `UNKNOWN` + `SCHEMA_UNSUPPORTED` |

**旧结构不允许继续产生新写入**：04-06 落地后，管理接口只写 v2；
旧字段（`comments` 等）由服务端从 v2 **派生输出**，只读不写。

---

## 3. 完整 Effective 状态决策表（D5 修订核心）

> 完整论证见 `ADR-008`。此处给可直接实现的决策表。

### 3.1 两层表达

| 层 | 字段 | 值域 | 治理 |
|---|---|---|---|
| 准入分类 | `effectiveStatus` | SOURCE-02 冻结八值 | **不得增删改** |
| 精确原因 | `effectiveReason` | Java 内部枚举（20 值，见 §3.3） | 随实现演进 |

**为什么加 `effectiveReason` 不违反枚举冻结**：两者都只在 Java 侧计算与展示；
Python 的 `/v1/capabilities` 只输出 Observed，**从不输出** `effectiveStatus`。
本次修订对 `SOURCE-02-capability-schema.json` **零改动**。

### 3.2 判定顺序（自上而下短路）

| # | 情况 | Python Registry 事实 | `effectiveStatus` | `effectiveReason` | 可执行 |
|---|---|---|---|---|---|
| 0 | **Source 已停用** | 任意（Python 可能仍加载） | `SOURCE_DISABLED` | `SOURCE_DISABLED` | ❌ 不允许新执行 |
| 1 | **Registry 不可达** | 超时 / 5xx / 连接失败 | `UNKNOWN` | `REGISTRY_UNREACHABLE` | ❌ |
| 2 | Registry 响应非法 | JSON 坏 / 缺 schemaVersion / 主版本 > 1 | `UNKNOWN` | `SCHEMA_UNSUPPORTED` | ❌ |
| 3 | 仅有过期缓存 | 本次刷新失败，只有 stale | `UNKNOWN` | `OBSERVED_STALE` | ❌ |
| 4 | **Desired 未声明** | 可能已加载 | `UNREGISTERED` | `NOT_DECLARED` | ❌ |
| 5 | **Adapter 未加载** | `loadStatus=FAILED` | `ADAPTER_MISSING` | `ADAPTER_NOT_LOADED` | ❌ |
| 6 | Adapter 版本不匹配（仅在锁定时） | `connectorVersion` ≠ 锁定值 | `VERSION_MISMATCH` | `CONNECTOR_VERSION_MISMATCH` | ❌ |
| 7 | Contract 主版本不兼容 | `contractVersion` 主版本不符 | `VERSION_MISMATCH` | `CONTRACT_VERSION_MISMATCH` | ❌ |
| 8 | **Capability 未注册** | Observed 无此能力，且无相关诊断 | `UNREGISTERED` | `CAPABILITY_NOT_REGISTERED` | ❌ |
| 9 | **函数不存在** | 有声明，诊断 `INVALID_IMPLEMENTATION`（属性不存在/模块无法导入） | `UNREGISTERED` | `FUNCTION_UNRESOLVED` | ❌ |
| 10 | **函数不可调用** | 引用指向非 callable | `UNREGISTERED` | `FUNCTION_NOT_CALLABLE` | ❌ |
| 11 | 能力被 Python 显式禁用 | `implementationStatus=DISABLED` | `UNREGISTERED` | `CAPABILITY_DISABLED` | ❌ |
| 12 | 平台不支持 | `implementationStatus=UNSUPPORTED` | `UNREGISTERED` | `PLATFORM_UNSUPPORTED` | ❌ |
| 13 | 访问模式不覆盖 | Observed `accessModes` 不含 Desired 要求的模式 | `UNREGISTERED` | `ACCESS_MODE_UNSUPPORTED` | ❌ |
| 14 | **已注册但未获真实平台验证** | `implementationStatus ∈ {UNVERIFIED, IMPLEMENTED}`（见 §3.2.1） | `UNKNOWN` | `VERIFICATION_REQUIRED` | ❌ |
| 15 | **证据已过期** | 任意状态 + `evidence.rejectionReason=EVIDENCE_EXPIRED` | `UNKNOWN` | `EVIDENCE_EXPIRED` | ❌ |
| 16 | **证据被判无效** | 任意状态 + `rejectionReason ∈ {EVIDENCE_TAMPERED, EVIDENCE_STALE, EVIDENCE_VERSION_MISMATCH, EVIDENCE_SCOPE_MISMATCH, EVIDENCE_MALFORMED}` | `UNKNOWN` | `EVIDENCE_INVALID` | ❌ |
| 17 | 需要账号但无可用账号 | 只支持 AUTHENTICATED，无 `isUsable` 账号 | `ACCOUNT_REQUIRED` | `ACCOUNT_REQUIRED` | ❌ |
| 18 | 可用但受限 | `implementationStatus=DEGRADED` **且持有已接受证据** | `DEGRADED` | `DEGRADED` | ⚠️ 降级执行 |
| 19 | 正常可用 | `implementationStatus=VERIFIED` **且** 证据已接受 且上述全通过 | `AVAILABLE` | `NONE` | ✅ |

> **2026-11-16 修订（SOURCE-04-03-FIX-01，H1）**：原第 19 行写作
> `∈ {VERIFIED, IMPLEMENTED}`，把 `IMPLEMENTED`（本地测试通过）与 `VERIFIED`
> （真实平台运行验证）等同，导致**未上过真实平台的实现被直接判为可执行**。
> 同一缺陷还有第二个入口：原第 14~16 步把证据校验写成"仅当 `UNVERIFIED` / `VERIFIED`
> 才执行"，于是 `IMPLEMENTED` 与 `DEGRADED` 完全跳过证据校验，
> `evidence.accepted=false` 也会被放行——**失败开放**。
> 本次修订同时关闭这两个入口，裁决依据见 §3.2.1。

### 3.2.1 实现状态 × 证据矩阵（穷举，不得只覆盖常见两值）

`implementationStatus` 是 Schema 允许的**六值**枚举。准入推导必须对**全部六值**有明确结论，
不能只处理 `UNVERIFIED` / `VERIFIED` 就把其余留给"默认通过"。

| `implementationStatus` | 证据 `accepted=true` | 证据缺失（`null`） | 证据被拒（`accepted=false`） |
|---|---|---|---|
| `UNVERIFIED` | ⚠️ 不可能（证据接受即升 `VERIFIED`）；若出现按**不可执行**处理 | `UNKNOWN` + `VERIFICATION_REQUIRED` | `UNKNOWN` + `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` |
| `IMPLEMENTED` | **`UNKNOWN` + `VERIFICATION_REQUIRED`**（本地测试通过 ≠ 真实平台验证） | `UNKNOWN` + `VERIFICATION_REQUIRED` | `UNKNOWN` + `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` |
| `VERIFIED` | 继续第 17~19 步（可判 `AVAILABLE`） | `UNKNOWN` + `VERIFICATION_REQUIRED`（协议异常：`VERIFIED` 必须带证据） | `UNKNOWN` + `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` |
| `DEGRADED` | `DEGRADED` + `DEGRADED`（降级执行） | `UNKNOWN` + `VERIFICATION_REQUIRED` | `UNKNOWN` + `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` |
| `DISABLED` | 第 11 步已返回，不进本矩阵 | 同左 | 同左 |
| `UNSUPPORTED` | 第 12 步已返回，不进本矩阵 | 同左 | 同左 |

**三条铁律**：

1. **证据闸门对所有未被第 11/12 步拦下的状态一律生效**。不存在"某个状态可以跳过证据校验"。
   `DEGRADED` 尤其不能例外——"降级"是性能/限流语义，不是"证据无效也可以跑"的许可证。
2. **`IMPLEMENTED` 永不产出 `AVAILABLE`**。它表示"代码写好、本地测过"，与
   "在真实平台跑通并留下证据"是两件事。Schema 的 `VERIFIED` 才对应后者。
3. **`evidence.accepted=false` 一律不可执行**，与状态无关。这是 04-02 阶段
   `platformScoped` 失败的教训：准入判断里任何"默认通过"的分支都是缺陷。

> **为什么归入 `VERIFICATION_REQUIRED` 而不是新增状态**：该 reason 的冻结定义就是
> "能力已注册且函数可用，但从未获得有效验证证据"，`IMPLEMENTED` 精确落在这个定义里。
> 无需扩充 `effectiveStatus`（八值冻结），也无需把它误归为 `UNREGISTERED`
> ——它确实注册了。

### 3.3 `effectiveReason` 枚举（20 值，Java 内部模型）

`NONE` / `SOURCE_DISABLED` / `REGISTRY_UNREACHABLE` / `SCHEMA_UNSUPPORTED` /
`OBSERVED_STALE` / `NOT_DECLARED` / `ADAPTER_NOT_LOADED` /
`CONNECTOR_VERSION_MISMATCH` / `CONTRACT_VERSION_MISMATCH` /
`CAPABILITY_NOT_REGISTERED` / `FUNCTION_UNRESOLVED` / `FUNCTION_NOT_CALLABLE` /
`CAPABILITY_DISABLED` / `PLATFORM_UNSUPPORTED` / `ACCESS_MODE_UNSUPPORTED` /
`VERIFICATION_REQUIRED` / `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` /
`ACCOUNT_REQUIRED` / `DEGRADED`

> 这些是**诊断原因**，不进入 `implementationStatus` / `effectiveStatus` 枚举。

### 3.4 三种「不执行」的语义分野（本次修订最关键的一张表）

| 事实 | `effectiveStatus` | `effectiveReason` | 同状态如何区分 |
|---|---|---|---|
| 能力根本不存在 | `UNREGISTERED` | `CAPABILITY_NOT_REGISTERED` | 看 reason |
| 函数解析不到 | `UNREGISTERED` | `FUNCTION_UNRESOLVED` | 看 reason |
| **已注册但未验证** | `UNKNOWN` | `VERIFICATION_REQUIRED` | 看 reason |
| **服务不可达** | `UNKNOWN` | `REGISTRY_UNREACHABLE` | 看 reason |

**铁律**：`effectiveStatus` 相同**不代表事实相同**。
展示层必须同时显示 reason，只显示 `effectiveStatus` 会造成误导（04-07 落地时强制）。

### 3.5 `UNKNOWN` vs "不可用"（用户强调）

| 概念 | 语义 | 落在哪些 reason |
|---|---|---|
| 确认不满足执行条件 | 有确定性依据 | `ADAPTER_NOT_LOADED` / `CONNECTOR_VERSION_MISMATCH` / `CONTRACT_VERSION_MISMATCH` / `CAPABILITY_NOT_REGISTERED` / `FUNCTION_*` / `CAPABILITY_DISABLED` / `PLATFORM_UNSUPPORTED` / `ACCESS_MODE_UNSUPPORTED` / `ACCOUNT_REQUIRED` / `SOURCE_DISABLED` |
| 无法获得可靠判断 | 缺信息 | `REGISTRY_UNREACHABLE` / `SCHEMA_UNSUPPORTED` / `OBSERVED_STALE` |
| 已判定但不可执行 | 有依据，不属上面六类 | `VERIFICATION_REQUIRED` / `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` |

**铁律**：Collection Service 暂时不可达 ≠ 所有平台能力失效。
不可达只判 `UNKNOWN` + `REGISTRY_UNREACHABLE`，且不得清空 Desired 或 Observed 缓存。

### 3.6 04-02 的接口要求（本设计的直接后果）

要区分 §3.2 的 #8 / #9 / #10，Java **必须同时读两个端点**：

| 端点 | 提供 | 用途 |
|---|---|---|
| `GET /v1/capabilities` | Observed 能力清单（v1.1） | 判断能力是否注册、`implementationStatus`、`evidence` |
| `GET /v1/adapters` 或 `/{code}` | `diagnostics[]`（含 `INVALID_IMPLEMENTATION`）、`loadStatus` | 区分"函数不存在/不可调用"与"确实没注册" |

**只读 `/v1/capabilities` 会在 #8 与 #9 之间失明**——两者在 Observed 清单里都表现为"没有这条能力"。

---

## 4. Schema / Contract / Adapter 版本兼容规则（D2 裁决）

### 4.1 三种版本，三种语义

| 版本 | 载体 | 语义 | 保留完整串 |
|---|---|---|---|
| **Schema Version** | Observed 顶层 `schemaVersion` | 契约**结构**版本 | ✅ `1.1` |
| **Contract Version** | `capabilities[].contractVersion` | **单条能力**的入参/出参契约版本 | ✅ `1.0` |
| **Adapter Version** | `connectorVersion` | 某个 **Connector 的实现**版本 | ✅ `1.0.0` |

**首期只使用主版本做兼容判断，但完整版本串必须保留**——不能因为只比主版本就丢掉次版本与补丁号。

### 4.2 匹配规则

| 版本 | 规则 | 不满足时 |
|---|---|---|
| Schema | 主版本必须 = 1（`SUPPORTED_SCHEMA_MAJOR`） | `UNKNOWN` + `SCHEMA_UNSUPPORTED` |
| Contract | 主版本一致即兼容（`1.x` ↔ `1.y`） | `VERSION_MISMATCH` + `CONTRACT_VERSION_MISMATCH` |
| Adapter | **未锁定时**采用 Registry 实际版本，不判定；**锁定后**必须精确匹配 | `VERSION_MISMATCH` + `CONNECTOR_VERSION_MISMATCH` |

### 4.3 Adapter 版本锁定的硬约束（D2 的"有条件通过"）

> **不能因为 Schema 与 Contract 主版本一致，就忽略 Adapter 版本冲突。**

| Desired 侧 | Python 实际 | 结果 |
|---|---|---|
| 未声明 `adapterVersion` | `1.3.2` | 不判定版本，采用 `1.3.2`（记录在 Observed） |
| 声明 `adapterVersion: "1.3.2"` | `1.3.2` | 通过 |
| 声明 `adapterVersion: "1.3.2"` | `1.4.0` | **`VERSION_MISMATCH`**（即使 Schema/Contract 主版本都一致） |
| 声明 `adapterVersion: "1.3.2"` | `1.3.1` | **`VERSION_MISMATCH`**（精确匹配，不接受补丁号漂移） |

> 首期**默认不锁定**（`capability_json` v2 不强制 `adapterVersion` 字段）。
> 一旦业务声明锁定，则**精确匹配**（不用 semver range，避免隐式升级）。
> 是否需要 semver range 属后续议题，见 §7。

### 4.4 版本相关字段的归属

| 字段 | 存 Desired？ | 说明 |
|---|---|---|
| `schemaVersion`（Desired 自己的 2.0） | ✅ | Desired 结构版本 |
| `adapterVersion`（锁定要求） | ⚠️ 可选 | 声明即锁定，不声明则采用实际值 |
| `contractVersion` | ❌ | 属 Observed，Desired 只隐含"支持主版本 1" |
| Observed 的 `schemaVersion` | ❌ | 属 Observed |

---

## 5. 缓存失效规则（D5 配套）

Effective **不落库**，"失效"的本质是**缓存失效 + 下次重算**。
Observed 缓存允许存在，但必须带 TTL 与 stale 标记。

### 5.1 三类触发源

| 触发源 | 具体事件 | 缓存动作 | 依据 |
|---|---|---|---|
| **配置变化** | `capability_json` 被修改（增删改能力 / 改 `desiredStatus` / 改 `accessModes` / 改 `connectorCode`） | **立即**失效该 Source 的 Effective 缓存 | Desired 是计算输入，变了必须重算 |
| **配置变化** | `ds_source.status` ENABLED↔DISABLED | **立即**失效 + `SOURCE_DISABLED` 立刻生效 | 停用必须立即阻断新执行 |
| **证据变化** | Python 侧证据到期 / 被拒绝 / 重新验证通过 | 由 Observed 缓存的 TTL 自然失效；不主动推送 | 首期无推送机制，见 §7 |
| **服务不可达** | Registry 查询失败 | **保留上次成功结果 + 标记 stale**；TTL 内继续用，超过 TTL 判 `OBSERVED_STALE` | 不可达 ≠ 能力失效 |

### 5.2 缓存读写的硬约束

| # | 约束 | 理由 |
|---|---|---|
| C1 | 查询成功 → 覆盖缓存并清 stale 标记 | 正常路径 |
| C2 | 查询失败 → **保留**上次成功结果 + 置 stale | 否则一次抖动就把所有能力判成不可用 |
| C3 | 失败时**不得**返回空集合冒充"没有能力" | 空集合会被解读为 `CAPABILITY_NOT_REGISTERED`，是事实篡改 |
| C4 | 无任何历史成功结果 + 本次失败 → `UNKNOWN` + `REGISTRY_UNREACHABLE` | 唯一诚实的结论 |
| C5 | stale 超过 TTL → `UNKNOWN` + `OBSERVED_STALE`，**继续保留**原结果供诊断 | 既不假装新鲜，也不丢掉历史事实 |
| C6 | Effective **永不回写** `capability_json` | 三态隔离铁律 |

### 5.3 TTL 建议

| 缓存 | 建议 TTL | 可配 |
|---|---|---|
| Observed 快照 | 60s | 是（`app.capability.observed-ttl-seconds`） |
| Effective（每次查询实时算，不单独缓存） | — | — |

> Effective 无需独立缓存：它由 Desired（内存中已解析）+ Observed（缓存）+ Source.status 计算，
> 代价极低。单独缓存只会引入"缓存一致性"问题。

---

## 6. 测试设计

> 只设计，不实现。落地属 04-02 / 04-03 / 04-06 / 04-08。

### 6.1 八个主场景（用户指定，逐条对应 §3.2）

| # | Java Desired | Python Observed | 预期 `effectiveStatus` | 预期 `effectiveReason` | 可执行 |
|---|---|---|---|---|---|
| T1 | COMMENT 已声明 ENABLED | 已加载、VERIFIED、证据有效 | `AVAILABLE` | `NONE` | ✅ |
| T2 | KEYWORD_SEARCH 已声明 ENABLED | 已加载、UNVERIFIED | `UNKNOWN` | `VERIFICATION_REQUIRED` | ❌ |
| T3 | COMMENT 已声明 ENABLED | **未加载**（无此 Connector） | `ADAPTER_MISSING` | `ADAPTER_NOT_LOADED` | ❌ |
| T4 | COMMENT 已声明 ENABLED | **函数缺失**（诊断 `INVALID_IMPLEMENTATION`） | `UNREGISTERED` | `FUNCTION_UNRESOLVED` | ❌ |
| T5 | **未声明** COMMENT_REPLY | 已加载、VERIFIED | `UNREGISTERED` | `NOT_DECLARED` | ❌ |
| T6 | COMMENT 已声明 + 锁定 `adapterVersion="1.0.0"` | `connectorVersion=1.1.0` | `VERSION_MISMATCH` | `CONNECTOR_VERSION_MISMATCH` | ❌ |
| T7 | COMMENT 已声明 ENABLED | **服务不可达** | `UNKNOWN` | `REGISTRY_UNREACHABLE` | ❌ |
| T8 | COMMENT 已声明 ENABLED | 已加载、VERIFIED | `SOURCE_DISABLED` | `SOURCE_DISABLED` | ❌ |

**T2 是本次修订的重点**：修复前它会被断言成 `UNREGISTERED`（错误），
修复后必须是 `UNKNOWN` + `VERIFICATION_REQUIRED`。

### 6.2 补充分支（D5 要求逐一覆盖）

| # | 场景 | `effectiveStatus` | `effectiveReason` |
|---|---|---|---|
| T9 | Capability 未注册（且无诊断） | `UNREGISTERED` | `CAPABILITY_NOT_REGISTERED` |
| T10 | 函数不可调用（引用非 callable） | `UNREGISTERED` | `FUNCTION_NOT_CALLABLE` |
| T11 | 能力被显式禁用 `DISABLED` | `UNREGISTERED` | `CAPABILITY_DISABLED` |
| T12 | 平台不支持 `UNSUPPORTED` | `UNREGISTERED` | `PLATFORM_UNSUPPORTED` |
| T13 | 证据已过期 | `UNKNOWN` | `EVIDENCE_EXPIRED` |
| T14 | 证据被篡改 / 实现变更 | `UNKNOWN` | `EVIDENCE_INVALID` |
| T15 | Contract 主版本不兼容（2.x） | `VERSION_MISMATCH` | `CONTRACT_VERSION_MISMATCH` |
| T16 | Schema 主版本不支持（2.x） | `UNKNOWN` | `SCHEMA_UNSUPPORTED` |
| T17 | 响应非法 JSON | `UNKNOWN` | `SCHEMA_UNSUPPORTED` |
| T18 | 访问模式不覆盖 | `UNREGISTERED` | `ACCESS_MODE_UNSUPPORTED` |
| T19 | 需账号但无可用账号 | `ACCOUNT_REQUIRED` | `ACCOUNT_REQUIRED` |
| T20 | DEGRADED | `DEGRADED` | `DEGRADED` |
| T21 | 仅 stale 缓存超 TTL | `UNKNOWN` | `OBSERVED_STALE` |

### 6.3 缓存层测试（04-02）

| # | 场景 | 断言 |
|---|---|---|
| C-1 | 查询成功 | 缓存写入，stale=false |
| C-2 | 查询失败（有历史） | 返回历史结果 + stale=true，**不返回空** |
| C-3 | 查询失败（无历史） | `UNKNOWN` + `REGISTRY_UNREACHABLE` |
| C-4 | stale 超 TTL | `UNKNOWN` + `OBSERVED_STALE`，原结果仍在诊断区可见 |
| C-5 | 服务恢复 | 下次查询成功 → stale 清除 → 恢复 `AVAILABLE` |

### 6.4 兼容与迁移测试（04-06）

| # | 场景 | 断言 |
|---|---|---|
| M-1 | 读 v1 结构（无 schemaVersion） | 正确映射为内部模型；视图同时含旧字段与新结构 |
| M-2 | 读 v2 结构 | 直接解析 |
| M-3 | 读 `schemaVersion="2.5"` | 按 2.0 解析（主版本一致） |
| M-4 | 读 `schemaVersion="3.0"` | 拒绝 + `SCHEMA_UNSUPPORTED` |
| M-5 | 迁移脚本重入 | `WHERE schemaVersion IS NULL` 只迁一次 |
| M-6 | connectorCode 映射不到 | 该能力不写入，产生 `DESIRED_MIGRATION_FAILED` 诊断 |
| M-7 | `capability_json` 非法 JSON | 保留原值不覆盖，产生诊断 |

### 6.5 端到端（04-08）

- 真实 `ds_source` 行 + 真实 `/v1/capabilities` → 三态明细；
- 停掉 `cpi-collection` → Effective 全部 `UNKNOWN` + `REGISTRY_UNREACHABLE`，
  且 `capability_json` 内容**逐字节不变**；
- 恢复 `cpi-collection` → 自动恢复 `AVAILABLE`（无需手工干预）。

---

## 7. 尚未解决的问题清单

| # | 问题 | 影响 | 建议归属 |
|---|---|---|---|
| Q1 | `effectiveStatus` 粒度不足以表达 `UNVERIFIED`，当前用 `UNKNOWN` 承载，存在被误读风险 | 展示层若只显示 effectiveStatus 会误导 | 建议 SOURCE-02 正式修订时评估是否新增 `UNVERIFIED` 值；**本次不动** |
| Q2 | 证据过期/更新无推送机制，只能靠 TTL 自然失效 | 最长滞后一个 TTL | SOURCE-09 或后续 Registry 版本管理 |
| Q3 | Adapter 版本是否需要 semver range（而非精确匹配） | 影响升级策略 | 后续议题 |
| Q4 | `implementationHash` 只覆盖目标函数源码，未覆盖 Adapter 基类、关键解析器、请求执行实现等影响行为的依赖 | 底层实现变化时旧证据可能被错误复用 | SOURCE-09（用户已在 SOURCE-03 审查中提出） |
| Q5 | Java 需同时读 `/v1/capabilities` 与 `/v1/adapters` 才能区分函数问题，两个端点的**一致性**（同一次请求内的快照是否一致）未定义 | 极端情况下两端口径不一致 | ✅ **已纳入 04-02 硬约束**（优先统一快照接口，方案 A；退路方案 B + `registrySnapshotId`） |
| Q6 | 一个 Source 多 Connector 时，Desired 里同一 `code` 出现在不同 Connector 下，唯一标识需含 connectorCode | 当前设计已支持（四元组），但**未在 DB 层加约束** | 04-06 落库时校验 |
| Q7 | `MEDIA_DOWNLOAD` / `AUTHOR_PROFILE` / `AUTHOR_POSTS` 在 Desired 里尚无实际数据 | 迁移后这些能力为空，需人工补声明 | 04-06 管理界面或后续数据补录 |
| Q8 | 迁移时 `accessModes` 的保守默认值可能比业务真实意图窄，导致误判 `ACCESS_MODE_UNSUPPORTED` | 可能压低可用能力数 | 迁移后由运营复核（04-07 展示差异） |

---

## 8. 本阶段明确未做的事（声明）

| 项目 | 状态 |
|---|---|
| Java 业务代码 | ❌ 未修改 |
| Python 业务代码 | ❌ 未修改 |
| 前端 | ❌ 未修改 |
| MySQL 数据 | ❌ 未修改（`ds_source.capability_json` 仍是 v1 原值） |
| Flyway Migration | ❌ 未新增（仍停在 V30） |
| `SOURCE-02-capability-schema.json` | ❌ **未修改**（枚举零改动，本次修订未触碰） |
| `ds_account` 状态模型 | ❌ 未变更 |
| Task/Run/Execution/Item 状态机 | ❌ 未变更 |
| SOURCE-04-02 / 04-03 | ❌ **未启动** |
| SOURCE-04-07 前端 | ❌ 未启动 |

**实际改动仅为文档**：
- 新增 `ADR-008-effective-status-reason-separation.md`
- 修订本文档（`SOURCE-04-04-TASKCARD.md`）
- 同步 `SOURCE-04-TASKCARD.md` 的 04-03 推导规则表

---

## 9. 评审裁决闭环（2026-11-16 二次评审）

| # | 事项 | 裁决 | 落点 |
|---|---|---|---|
| 1 | 迁移方案 | **A 通过**；迁移实施独立立项 | §2 |
| 2 | 版本匹配深度 | **有条件通过**；Adapter 锁定时精确匹配 | §4.3 |
| 3 | `connectorCode` 位置 | **采纳**：每能力对象内 | §1.4 |
| 4 | 字段命名 | **采纳**：`desiredStatus` | §1.3 |
| 5 | UNVERIFIED 投影 | **通过**：`UNKNOWN` + `VERIFICATION_REQUIRED` | §3.2 + `ADR-008` |
| 6 | `effectiveReason` 20 值是否精简 | **暂不精简**；但须补齐定义表与重复审查 | `SOURCE-04-04-EFFECTIVE-REASON.md` |
| 7 | Q5 两端点快照一致性 | **必须纳入 04-02**，优先方案 A，必要时 B；不得用 `generatedAt` 判一致性 | `SOURCE-04-TASKCARD.md` 04-02 §B |
| 8 | `UNKNOWN` 是否允许执行 | **绝不允许**；准入用白名单；API 与前端必须同时展示状态与原因 | `ADR-008` §4.3 / §4.4 |
| 9 | SOURCE-02 是否新增 `UNVERIFIED` | **本轮不动**，保留 Q1 单独评估 | §7 Q1 |

### 9.1 评审后新增的两条硬约束（本文档已同步）

| 约束 | 出处 |
|---|---|
| `effectiveStatus` × `effectiveReason` 必须**成对返回**，不得只给其一 | `ADR-008` §4.4 |
| 一次 Effective 计算只能使用**单一 Observed 快照**，禁止跨端点混用 | `SOURCE-04-TASKCARD.md` 04-02 §B / 04-03 |

### 9.2 进入 04-03 之前必须先完成

| # | 前置 | 状态 |
|---|---|---|
| 1 | 20 个 `effectiveReason` 的完整定义表、触发条件、状态映射 | ✅ `SOURCE-04-04-EFFECTIVE-REASON.md` |
| 2 | 重复语义审查（含"数据源能否区分"） | ✅ 同上 §3（发现 1 处不可达） |
| 3 | 每个 Reason 至少一个正向测试设计 | ✅ 同上 §5 |
| 4 | 04-02 的快照一致性方案定稿（A 或 B） | ⏳ **待 04-02 实施前确认** |
