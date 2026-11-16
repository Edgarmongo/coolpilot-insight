# SOURCE-04-03 实施报告：Desired / Observed / Effective 核对

> 状态：**已实施，待代码评审**
> 前置：`SOURCE-04-02-FIX-01` 已通过（N1/N2 关闭，N7 六项进验收标准）
> 关联：`ADR-006`（三态模型）、`ADR-008`（状态与原因分离）、
> `SOURCE-04-04-TASKCARD.md`（Desired v2 结构）、
> `SOURCE-04-04-EFFECTIVE-REASON.md`（19+1 reason 定义与判定顺序）

---

## 一、目标与边界

**目标**：实现三态模型，按冻结的 `effectiveStatus` × `effectiveReason` 推导 Effective，
并接入数据源查询接口。

**边界**（严格遵循）：

| 允许 | 做了 |
|---|---|
| 三态核对与 Effective 推导 | ✅ |
| Desired 读取（v1/v2 双读） | ✅ |
| 数据源查询接口返回三态 | ✅ |
| 单元测试 + 端到端测试 | ✅ |

| 禁止 | 状态 |
|---|---|
| 修改 MySQL 表结构与数据 | ❌ 未做（`capability_json` 仍为 v1 原值） |
| 新增 Flyway Migration | ❌ 未做（仍停 V30） |
| 修改 SOURCE-02 Schema / 枚举 | ❌ 未做 |
| 修改 Desired Schema（v2 结构） | ❌ 未改（按已冻结的 04-04 实现） |
| 修改账号认证 / Task-Run 状态机 | ❌ 未做 |
| 修改前端 | ❌ 未做 |
| Effective 回写数据库 | ❌ 未做（派生结论不落库） |
| 执行 Desired 迁移 | ❌ 未做（属 04-06） |

---

## 二、实现概览

### 2.1 新增类

| 类 | 职责 |
|---|---|
| `source/domain/EffectiveStatus` | 冻结八值 + **白名单**准入判据 |
| `source/domain/EffectiveReason` | 20 值；`REACHABLE` 标识 19 个可达 |
| `source/domain/EffectiveCapability` | Effective 结果；构造时强制不变量 |
| `source/domain/DesiredCapability` | Desired 声明（不含任何实现状态） |
| `source/domain/DesiredCapabilitySet` | Desired 集合 + 查找/判定 |
| `source/application/DesiredCapabilityParser` | v1/v2 双读 + 确定性映射 |
| `source/application/CapabilityReconciler` | **核心**：19 步短路核对 |
| `source/application/AccountAvailability` | 账号可用性窄接口（便于测试与替换） |
| `source/application/DaoAccountAvailability` | 基于 `ds_account` 的实现 |

### 2.2 修改

| 文件 | 改动 | 理由 |
|---|---|---|
| `dto/DataSourceView` | 新增 `desired` / `effective` / `snapshotState` / `snapshotDetail`；保留旧 `capability` | 三态并列，不合并 |
| `application/SourceQueryService` | 注入三个依赖；**一次请求只取一份快照** | 避免同屏不同行来自不同 Registry 版本 |
| `infrastructure/persistence/SourceAccountMapper` | 新增 `selectBySourceId` | 账号可用性查询（原只有 `selectAll` 全表扫） |
| `integration/capability/SnapshotCache` | `lastError(String)` → `failure(SnapshotFailure)` | **见 §2.3** |

### 2.3 为什么改了 `SnapshotCache`

04-03 需要区分 `REGISTRY_UNREACHABLE`（服务不可达）与 `SCHEMA_UNSUPPORTED`（响应不合法）——
两者都是 `UNKNOWN`，但原因不同，诊断价值完全不同。

原 `SnapshotCache` 只保留 `lastError` 字符串，要区分只能去 `contains("主版本")`——
这正是第一轮评审 H1 否决过的做法。所以新增 `SnapshotFailure(kind, detail)`，
把 `FetchResult.Kind` 结构化地保留下来。

改动很小（Entry 字段 + read 传递），但它是 04-03 能产出正确 reason 的前提。

---

## 三、19 步判定顺序（与 EFFECTIVE-REASON §4 逐行一致）

| 序 | 条件 | status | reason |
|---|---|---|---|
| ① | Source 已停用 | `SOURCE_DISABLED` | `SOURCE_DISABLED` |
| — | Desired 不可解析 | `UNKNOWN` | `SCHEMA_UNSUPPORTED` |
| ② | 快照 UNAVAILABLE | `UNKNOWN` | `REGISTRY_UNREACHABLE` |
| ③④ | 快照 STALE | `UNKNOWN` | 按失败分类：`REGISTRY_UNREACHABLE` / `SCHEMA_UNSUPPORTED` / `OBSERVED_STALE` |
| ⑤ | Desired 未声明或 DISABLED | `UNREGISTERED` | `NOT_DECLARED` |
| ⑥ | 接入未加载 / 非真实平台接入 | `ADAPTER_MISSING` | `ADAPTER_NOT_LOADED` |
| ⑦ | Connector 版本与锁定不符 | `VERSION_MISMATCH` | `CONNECTOR_VERSION_MISMATCH` |
| ⑧ | 能力契约主版本不兼容 | `VERSION_MISMATCH` | `CONTRACT_VERSION_MISMATCH` |
| ⑨⑩ | Registry 无此能力 | `UNREGISTERED` | 有函数诊断 → `FUNCTION_UNRESOLVED`；否则 `CAPABILITY_NOT_REGISTERED` |
| ⑪ | Python 显式禁用 | `UNREGISTERED` | `CAPABILITY_DISABLED` |
| ⑫ | 平台不支持 | `UNREGISTERED` | `PLATFORM_UNSUPPORTED` |
| ⑬ | 访问模式不覆盖 | `UNREGISTERED` | `ACCESS_MODE_UNSUPPORTED` |
| ⑭ | 从未验证（evidence 为 null） | `UNKNOWN` | `VERIFICATION_REQUIRED` |
| ⑮ | 证据过期 | `UNKNOWN` | `EVIDENCE_EXPIRED` |
| ⑯ | 证据无效 | `UNKNOWN` | `EVIDENCE_INVALID` |
| ⑰ | 仅 AUTHENTICATED 且无账号 | `ACCOUNT_REQUIRED` | `ACCOUNT_REQUIRED` |
| ⑱ | 可用但受限 | `DEGRADED` | `DEGRADED` |
| ⑲ | 全部通过 | `AVAILABLE` | `NONE` |

**候选集合取 Desired ∪ Observed**：只看 Desired 会漏掉 `NOT_DECLARED`
（Python 有、Java 没登记）；只看 Observed 会漏掉"声明了但没加载"。

---

## 四、N7 六项落地

| 编号 | 实现 | 测试 |
|---|---|---|
| **N7-1** 只遍历真实平台接入 | `platformScopedAdapters()` 构建 connector 映射；`collectCandidates` 也只用它 | `n71StubProducesNoEffective`、`n71DeclaringStubIsAdapterMissing` |
| **N7-2(a)** 未验证不得放行 | ⑭ 步判 `UNKNOWN/VERIFICATION_REQUIRED` | `n72aVerifiedPlatformButUnverifiedCapability` |
| **N7-2(b)** 已验证不得绕过 | ⑬⑰ 步仍独立判定；`VERIFIED` 不短路后续检查 | `n72bVerifiedDoesNotBypassAccessMode`、`n72bVerifiedDoesNotBypassDesired` |
| **N7-3** VERIFIED + STALE 不可执行 | STALE 在全局短路阶段就判 `UNKNOWN` | `n73VerifiedButStaleIsNotExecutable` |
| **N7-4** 只有 FRESH 可准入 | `globalBlock` 检查 `state != FRESH` | `n74NonFreshNeverYieldsAvailable`；运行时实测见 §六 |
| **N7-5** 白名单准入 | `EffectiveStatus.allowsExecution` 正向列举 | `n75WhitelistOnly` |
| **N7-6** 19 正向 + 1 不可达 | 见 §五 | `functionNotCallableIsUnreachable` |

**N7-2(b) 的具体含义**：`platformScoped=true` + `VERIFIED` + `FRESH` 三者齐备，
仍不足以推出 `AVAILABLE`。测试用两条路径证明：
访问模式不覆盖 → `ACCESS_MODE_UNSUPPORTED`；Desired 未声明 → `NOT_DECLARED`。
两者都在"已验证"之后才判定，证明 `VERIFIED` 不是免检通道。

---

## 五、19 个 reason 的测试对照

| # | reason | 测试方法 | 构造 |
|---|---|---|---|
| 1 | `NONE` | `reasonNone` | 全条件通过 |
| 2 | `SOURCE_DISABLED` | `reasonSourceDisabled` | `status=DISABLED` |
| 3 | `REGISTRY_UNREACHABLE` | `reasonRegistryUnreachable` | UNAVAILABLE |
| 4 | `SCHEMA_UNSUPPORTED` | `reasonSchemaUnsupported` / `...ByDesired` | STALE+协议失败 / Desired 不可解析 |
| 5 | `OBSERVED_STALE` | `reasonObservedStale` | STALE 无失败信息 |
| 6 | `NOT_DECLARED` | `reasonNotDeclared` / `...BecauseDisabled` | Desired 空 / DISABLED |
| 7 | `ADAPTER_NOT_LOADED` | `reasonAdapterNotLoaded` / `...NotPlatformScoped` | loadStatus=FAILED / platformScoped=false |
| 8 | `CONNECTOR_VERSION_MISMATCH` | `reasonConnectorVersionMismatch` | 锁 1.0.0 vs 实际 1.1.0 |
| 9 | `CONTRACT_VERSION_MISMATCH` | `reasonContractVersionMismatch` | contractVersion=2.0 |
| 10 | `CAPABILITY_NOT_REGISTERED` | `reasonCapabilityNotRegistered` | Observed 无 + 无诊断 |
| 11 | `FUNCTION_UNRESOLVED` | `reasonFunctionUnresolved` | Observed 无 + 函数诊断 |
| 12 | `FUNCTION_NOT_CALLABLE` | **不可达性测试** | 见下 |
| 13 | `CAPABILITY_DISABLED` | `reasonCapabilityDisabled` | implStatus=DISABLED |
| 14 | `PLATFORM_UNSUPPORTED` | `reasonPlatformUnsupported` | implStatus=UNSUPPORTED |
| 15 | `ACCESS_MODE_UNSUPPORTED` | `reasonAccessModeUnsupported` | Desired=AUTH，Observed=PUBLIC |
| 16 | `VERIFICATION_REQUIRED` | `reasonVerificationRequired` | UNVERIFIED + evidence=null |
| 17 | `EVIDENCE_EXPIRED` | `reasonEvidenceExpired` | rejected EVIDENCE_EXPIRED |
| 18 | `EVIDENCE_INVALID` | `reasonEvidenceInvalid` | rejected EVIDENCE_TAMPERED |
| 19 | `ACCOUNT_REQUIRED` | `reasonAccountRequired` | 仅 AUTHENTICATED + 无账号 |
| 20 | `DEGRADED` | `reasonDegraded` | implStatus=DEGRADED |

**`FUNCTION_NOT_CALLABLE` 的不可达性测试**（`functionNotCallableIsUnreachable`）：

1. 枚举层：`ALL` 有 20 个、`REACHABLE` 有 19 个、`REACHABLE` 不含它；
2. 行为层：用三种不同 `detail` 文本（含"引用指向的对象不可调用"）构造
   `INVALID_IMPLEMENTATION` 诊断，**一律**产出 `FUNCTION_UNRESOLVED`。

第二层是关键：如果有人试图从 `detail` 文本推断"不可调用"，这个测试会立刻失败。

---

## 六、运行时验证（真实容器）

### 6.1 正常路径

```
GET /api/v1/source/sources
=== DOUYIN | status: ENABLED | snapshot: FRESH
    desired: [COMMENT=ENABLED, CONTENT_DETAIL=ENABLED, KEYWORD_SEARCH=DISABLED]
    effective:
      COMMENT       -> AVAILABLE   / NONE           | exec: True
      CONTENT_DETAIL-> AVAILABLE   / NONE           | exec: True
      KEYWORD_SEARCH-> UNREGISTERED/ NOT_DECLARED   | exec: False
      COMMENT_REPLY -> UNREGISTERED/ NOT_DECLARED   | exec: False
```

### 6.2 服务不可达（N7-4 运行时验证）

停掉 `cpi-collection` + 重启 backend（清空缓存）：

```
snapshot: UNAVAILABLE | 无可用快照
   COMMENT        -> UNKNOWN / REGISTRY_UNREACHABLE | exec: False
   CONTENT_DETAIL -> UNKNOWN / REGISTRY_UNREACHABLE | exec: False
   KEYWORD_SEARCH -> UNKNOWN / REGISTRY_UNREACHABLE | exec: False
```

**全部 `UNKNOWN`、全部不可执行**，没有一条 `AVAILABLE`。这正是 N7-4 要的行为。

注意此时只有 3 条而非 4 条——因为快照不可用时候选集合只剩 Desired，
`COMMENT_REPLY`（仅存在于 Observed）自然不出现。

### 6.3 服务恢复

重启 `cpi-collection` 后，下一次请求自动回到 `FRESH` + `AVAILABLE`，
无需手工干预（缓存 Single-flight + TTL 刷新）。

---

## 七、真实发现（不是缺陷，是事实）

**`COMMENT_REPLY` 在 Python 侧已 `VERIFIED`，但 Effective 判 `NOT_DECLARED`。**

原因：库中 `capability_json` 是 v1，只有 `comments` / `contentDetail` / `keywordSearch`
三个字段，**根本没有 `COMMENT_REPLY` 这一项**。所以 Java 侧无法声明它。

这不是核对逻辑的问题，恰恰是核对**工作正常**的证据——
它让"Python 实际具备但 Java 没登记"这件事显式暴露出来，而不是被 `capability.json`
的旧布尔值掩盖。

**修复路径**：迁移到 v2 结构并补声明（属 04-06）。
在迁移前，这个能力不可执行是**正确**的行为。

---

## 八、测试结果

| 套件 | 数量 | 结果 |
|---|---|---|
| `CapabilityReconcilerTest` | 34 | ✅ 全通过 |
| `DesiredCapabilityParserTest` | 10 | ✅ 全通过 |
| **Java 合计** | **241** | ✅ 全通过（原 197 + 44） |
| Python 合计 | 76 | ✅ 未受影响 |

容器：5 个全部 healthy。

---

## 九、未实施声明

| 项目 | 状态 |
|---|---|
| MySQL 表结构与数据 | ❌ 未改（`capability_json` 仍为 v1 原值） |
| Flyway Migration | ❌ 未新增（仍停 V30） |
| SOURCE-02 Schema / 枚举 | ❌ 未改 |
| Desired v2 迁移 | ❌ 未执行（属 04-06） |
| 账号认证模型 | ❌ 未改（只新增了只读查询） |
| Task / Run / Execution / Item 状态机 | ❌ 未改 |
| 前端 | ❌ 未改（`DataSourceView` 新增字段，旧字段保留，老前端不受影响） |
| Effective 持久化 | ❌ 未做（派生结论，不落库） |

**改动文件**：新增 9 个类（6 主 + 3 测试）、修改 4 个（`DataSourceView`、
`SourceQueryService`、`SourceAccountMapper`、`SnapshotCache`）。

---

## 十、遗留

| # | 事项 | 说明 |
|---|---|---|
| L1 | 04-06 迁移后需补 `COMMENT_REPLY` 声明 | 见 §七 |
| L2 | `ACCOUNT_REQUIRED` 只按"仅 AUTHENTICATED 且无账号"判定 | 若能力同时支持 PUBLIC，则不要求账号（可用 PUBLIC 执行）。这是有意的：不该因为没账号就否掉匿名可做的能力 |
| L3 | 无缓存 Effective | 每次查询实时计算，代价极低，避免缓存一致性问题（04-04 §5.3） |
| L4 | `DataSourceView` 同时返回旧 `capability` 与新三态 | 兼容期设计，04-07 前端切换后可移除旧字段 |

---

## 附：评审后的修订

| 任务 | 内容 | 文档 |
|---|---|---|
| `SOURCE-04-03-FIX-01` | H1：`IMPLEMENTED` / `DEGRADED` 证据准入修复（契约 + 代码 + 矩阵测试） | `SOURCE-04-03-FIX01-REPORT.md` |
| `SOURCE-04-03-FIX-02` | M1：Desired 故障分类与优先级；M2：重复能力拒绝 | 待执行 |

> 本文档描述的是 `4e534a8` 的实施状态。**证据闸门的行为已由 FIX-01 修订**：
> 原始实现只在 `UNVERIFIED` / `VERIFIED` 时校验证据，`IMPLEMENTED` / `DEGRADED`
> 会被放行到 `AVAILABLE`（见评审 H1）。请以 FIX-01 报告与
> `SOURCE-04-04-TASKCARD.md` §3.2.1 为准。
