# SOURCE-04-04 · `effectiveReason` 完整定义表与重复语义审查

> 状态：**设计冻结，待最终确认**。纯文档，不涉及代码 / 数据库 / Flyway。
>
> 依据：评审裁决「20 个 Reason 暂不精简，但必须先提供完整定义表、触发条件、
> 状态映射，并完成重复语义审查」。
>
> 关联：`ADR-008`（两层表达）、`SOURCE-04-04-TASKCARD.md` §3（Effective 决策表）。

---

## 一、治理原则（评审要求的五项检查）

| 检查项 | 要求 | 本文档如何满足 |
|---|---|---|
| **语义唯一** | 一个 Reason 只表达一种明确原因 | §2 逐值给出唯一触发条件 |
| **无重复** | 不允许两个值仅措辞不同、含义相同 | §3 逐对交叉审查 |
| **可追溯** | 每个 Reason 都能对应到明确的核对条件 | §2 的"判定条件"列 + §4 判定顺序 |
| **可测试** | 每个 Reason 至少一个正向测试用例 | §5 逐值给测试构造方式 |
| **可扩展** | 新增原因不得改变既有 Reason 的含义 | §6 扩展规则 |

---

## 二、21 值完整定义表

> **2026-11-16 增补**：原为 20 值。`SOURCE-04-03-FIX-02` 新增第 21 值
> `DESIRED_UNPARSEABLE`，用于把"Java 自己的 Desired 配置读不懂"与
> "Python 快照结构不兼容"区分开（原实现借用 `SCHEMA_UNSUPPORTED`，
> 会把排障方向指向错误的服务）。该值的完整论证与唯一性检查见 §九。

> **数据来源缩写**：
> `D` = Desired（`ds_source.capability_json`）
> `S` = Source 状态（`ds_source.status`）
> `C` = `/v1/capabilities`（Observed 清单）
> `A` = `/v1/adapters`（Connector 元信息 + diagnostics）
> `H` = HTTP 调用结果
> `K` = 缓存状态
> `AC` = 账号（`ds_account`）

| # | Reason | 含义（唯一） | 判定条件（可追溯） | 数据来源 | `effectiveStatus` |
|---|---|---|---|---|---|
| 1 | `NONE` | 无任何阻断原因 | 全部条件通过 | 推导 | `AVAILABLE` |
| 2 | `SOURCE_DISABLED` | Java 侧已停用该 Source | `S.status = DISABLED` | `S` | `SOURCE_DISABLED` |
| 3 | `REGISTRY_UNREACHABLE` | 采集服务无法访问，且**无任何历史快照** | HTTP 异常/超时/5xx **且** 缓存无上次成功结果 | `H` + `K` | `UNKNOWN` |
| 4 | `SCHEMA_UNSUPPORTED` | 响应结构不可用：非法 JSON / 缺 `schemaVersion` / 主版本 > 1 | 解析失败或 `majorOf(schemaVersion) > 1` | `C` | `UNKNOWN` |
| 5 | `OBSERVED_STALE` | 只有历史快照，本次刷新失败且已超 TTL | 缓存有上次成功结果，本次刷新失败，`now > fetchedAt + TTL` | `H` + `K` | `UNKNOWN` |
| 6 | `NOT_DECLARED` | Desired 未声明该能力 | `D` 中无该 `(source, connector, code)`，或 `desiredStatus=DISABLED` | `D` | `UNREGISTERED` |
| 7 | `ADAPTER_NOT_LOADED` | 整个 Connector 加载失败 | `A.loadStatus = FAILED` | `A` | `ADAPTER_MISSING` |
| 8 | `CONNECTOR_VERSION_MISMATCH` | Connector 版本与 Desired 锁定值不符（仅在锁定时判定） | `D` 声明了 `adapterVersion` **且** `C/A.connectorVersion ≠ 锁定值` | `D` + `A` | `VERSION_MISMATCH` |
| 9 | `CONTRACT_VERSION_MISMATCH` | 能力契约主版本不兼容 | `majorOf(C.contractVersion) ≠ majorOf(Desired 支持的主版本)` | `C` + `D` | `VERSION_MISMATCH` |
| 10 | `CAPABILITY_NOT_REGISTERED` | **Registry 中不存在该能力**（Connector 已加载，无相关诊断） | `C` 中无该 `code` **且** `A.diagnostics` 无该能力的记录 | `C` + `A` | `UNREGISTERED` |
| 11 | `FUNCTION_UNRESOLVED` | **能力已声明，但实现函数无法解析或不成立** | `A.diagnostics` 有该能力且 `reason = INVALID_IMPLEMENTATION` | `A` | `UNREGISTERED` |
| 12 | `FUNCTION_NOT_CALLABLE` | 引用指向的对象存在但**不可调用** | ⚠️ **当前不可达**，见 §3.1 | `A`（缺子码） | `UNREGISTERED` |
| 13 | `CAPABILITY_DISABLED` | 能力被 Python 侧**显式禁用** | `C.implementationStatus = DISABLED` | `C` | `UNREGISTERED` |
| 14 | `PLATFORM_UNSUPPORTED` | 平台**客观不支持**该能力 | `C.implementationStatus = UNSUPPORTED` | `C` | `UNREGISTERED` |
| 15 | `ACCESS_MODE_UNSUPPORTED` | Observed 的 `accessModes` **不包含** Desired 要求的模式 | `Desired.accessModes ⊄ C.accessModes` | `D` + `C` | `UNREGISTERED` |
| 16 | `VERIFICATION_REQUIRED` | **能力已注册，但从未获得有效验证证据**（含数据自相矛盾的情形） | `C.implementationStatus ∈ {UNVERIFIED, IMPLEMENTED}`，或该字段不在契约六值内（解析层已拒绝，此处为末道兜底）；**即便证据 `accepted=true` 也归此**——`IMPLEMENTED` 是本地测试、`UNVERIFIED` + 已接受证据是字段冲突，都不构成真实平台验证 | `C` | `UNKNOWN` |
| 17 | `EVIDENCE_EXPIRED` | 验证证据**超过有效期** | **任意未被 13/14 拦下的状态** 且 `C.evidence.rejectionReason = EVIDENCE_EXPIRED` | `C` | `UNKNOWN` |
| 18 | `EVIDENCE_INVALID` | 证据**完整性或有效性校验失败**（篡改 / 实现变更 / 版本或范围不符 / 结构非法） | **任意未被 13/14 拦下的状态** 且 `rejectionReason ∈ {EVIDENCE_TAMPERED, EVIDENCE_STALE, EVIDENCE_VERSION_MISMATCH, EVIDENCE_SCOPE_MISMATCH, EVIDENCE_MALFORMED}` | `C` | `UNKNOWN` |
| 19 | `ACCOUNT_REQUIRED` | 需要 `AUTHENTICATED` 但**无可用账号** | `Desired.accessModes ⊇ {AUTHENTICATED}` **且** 该 Source 下无 `status=ACTIVE && auth_status=VALID` 的账号 | `D` + `AC` | `ACCOUNT_REQUIRED` |
| 20 | `DEGRADED` | 能力可用但受限 | `C.implementationStatus = DEGRADED` **且** 证据已接受（`accepted=true`） | `C` | `DEGRADED` |
| 21 | `DESIRED_UNPARSEABLE` | **Java 侧 Desired 配置无法解析**（非法 JSON / 未知主版本 / 结构或枚举非法 / 重复能力） | `DesiredCapabilityParser` 返回 `supported=false` **且** 快照为 `FRESH` | `D` | `UNKNOWN` |

### 2.1 评审点名的五个易混值，定义边界

| Reason | 精确定义 | **不是**什么 |
|---|---|---|
| `REGISTRY_UNREACHABLE` | **服务无法访问**（网络层/HTTP 层失败），且无历史可用数据 | 不是"服务可达但没有该能力" |
| `FUNCTION_UNRESOLVED` | **服务可访问**，但该能力的实现函数无法解析或不成立 | 不是"能力没注册"（#10）、不是"服务挂了"（#3） |
| `VERIFICATION_REQUIRED` | 能力**已注册且函数可用**，但从未获得有效验证证据 | 不是"没注册"、不是"证据过期"（#17） |
| `EVIDENCE_EXPIRED` | 验证证据**曾经有效**，但现在超过 TTL | 不是"从未验证"（#16）、不是"证据被篡改"（#18） |
| `EVIDENCE_INVALID` | 证据**存在但不可信**（完整性/范围/版本/结构校验失败） | 不是"过期"（#17，单独一值） |

---

## 三、重复语义审查（逐对交叉检查）

对 20 个值做两两语义比对，重点检查"措辞不同但含义相同"以及"含义相近导致无法区分"。

### 3.1 审查发现：一处**数据源不可区分**（不是语义重复）

| 对 | 语义是否相同 | 数据源能否区分 | 结论 |
|---|---|---|---|
| `FUNCTION_UNRESOLVED` vs `FUNCTION_NOT_CALLABLE` | **不同**：前者"找不到"，后者"找到了但不能调" | ❌ **不能** | 见下方处置 |

**证据**（`collection-service/app/registry/capability_registry.py:147-151`）：

```python
fn = self.resolve(spec.adapter_function)
if fn is None:
    raw = resolve_reference(spec.adapter_function)
    detail = "属性不存在或模块无法导入" if raw is None else "引用指向的对象不可调用"
    result = (False, DiagnosticReason.FUNCTION_UNRESOLVED, detail)
```

两种情况**共用同一个诊断码** `INVALID_IMPLEMENTATION`，只有 `detail` 自由文本不同。
而 SOURCE-03 已定下规则：「**禁止使用自由文本作为机器判断依据**」。
因此 Java 无法结构化地区分这两者。

**处置（三项，同时执行）**：

1. **保留两个值**（评审裁决：暂不精简；且两者语义确实不同）；
2. **标注 `FUNCTION_NOT_CALLABLE` 当前不可达**——它违反"每个 Reason 至少一个正向测试用例"
   的要求，属于**已知缺陷**而非完整设计；
3. **短期实现映射规则**：`INVALID_IMPLEMENTATION` 一律映射为 `FUNCTION_UNRESOLVED`，
   `FUNCTION_NOT_CALLABLE` 保留待用，**不得**通过解析 `detail` 文本猜测。

**使其可达的前置条件**（记入 §7 Q9）：Python 侧把 `DiagnosticReason` 拆成两个子码
（如 `FUNCTION_NOT_FOUND` / `FUNCTION_NOT_CALLABLE`），属 SOURCE-03 契约演进，
需单独评审。**本轮不动**。

### 3.2 其余 20 值交叉审查结论（无重复；新增值的唯一性另见 §九）

| 对 | 语义差异 | 数据源可区分 | 结论 |
|---|---|---|---|
| `SOURCE_DISABLED` vs `CAPABILITY_DISABLED` | Source 级停用 vs 单能力被 Python 禁用 | `S.status` vs `C.implementationStatus` | ✅ 不同 |
| `CAPABILITY_DISABLED` vs `PLATFORM_UNSUPPORTED` | 主动禁用 vs 客观不支持 | `DISABLED` vs `UNSUPPORTED` | ✅ 不同 |
| `NOT_DECLARED` vs `CAPABILITY_NOT_REGISTERED` | Java 没声明 vs Python 没注册 | `D` vs `C` | ✅ 不同 |
| `ADAPTER_NOT_LOADED` vs `CAPABILITY_NOT_REGISTERED` | 整个 Connector 挂了 vs Connector 在但没这能力 | `loadStatus` vs `C` 缺项 | ✅ 不同 |
| `CONNECTOR_VERSION_MISMATCH` vs `CONTRACT_VERSION_MISMATCH` | 接入实现版本 vs 单能力契约版本 | 两个不同字段 | ✅ 不同 |
| `ACCESS_MODE_UNSUPPORTED` vs `ACCOUNT_REQUIRED` | 模式根本不支持 vs 支持但没账号 | `accessModes` vs 账号表 | ✅ 不同 |
| `REGISTRY_UNREACHABLE` vs `OBSERVED_STALE` | 无历史数据 vs 有历史但过期 | `K` 有无上次成功结果 | ✅ 不同 |
| `VERIFICATION_REQUIRED` vs `EVIDENCE_EXPIRED` | 从未验证 vs 验证过但过期 | `evidence == null` vs `rejectionReason` | ✅ 不同 |
| `EVIDENCE_EXPIRED` vs `EVIDENCE_INVALID` | 过期 vs 不可信 | 同一 `rejectionReason` 字段的不同取值 | ✅ 不同 |
| `SCHEMA_UNSUPPORTED` vs `REGISTRY_UNREACHABLE` | 响应拿到了但不可用 vs 根本没拿到 | 解析阶段 vs 传输阶段 | ✅ 不同 |
| `NONE` vs `DEGRADED` | 无阻断 vs 可用但受限 | `NONE` 只配 `AVAILABLE` | ✅ 不同 |

### 3.3 `EVIDENCE_INVALID` 是**合并**而非重复

它聚合了 SOURCE-03 的 5 个证据拒绝原因：

| Python `rejectionReason` | 归入 | 理由 |
|---|---|---|
| `EVIDENCE_TAMPERED` | `EVIDENCE_INVALID` | 证据不可信，处置相同（需重新验证） |
| `EVIDENCE_STALE` | `EVIDENCE_INVALID` | 同上 |
| `EVIDENCE_VERSION_MISMATCH` | `EVIDENCE_INVALID` | 同上 |
| `EVIDENCE_SCOPE_MISMATCH` | `EVIDENCE_INVALID` | 同上 |
| `EVIDENCE_MALFORMED` | `EVIDENCE_INVALID` | 同上 |
| `EVIDENCE_EXPIRED` | `EVIDENCE_EXPIRED` | **单列**：过期是"时间到了"，与"不可信"性质不同，且处置不同（可重跑验证 vs 需先查原因） |

> 精细原因未丢失：`CapabilityItem.Evidence.rejectionReason()` 仍保留原始码，
> 供诊断展示。Effective 层只做聚合，不做丢弃。

---

## 四、判定顺序与优先级

**判定自上而下短路，命中即停。** 顺序本身就是优先级声明。

| 序 | Reason | 为什么排这里 |
|---|---|---|
| 1 | `SOURCE_DISABLED` | 停用是最高优先级——无论 Python 状态如何都不允许新执行 |
| 2 | `REGISTRY_UNREACHABLE` | 没有 Observed 数据，后续一切判定都无依据 |
| 3 | `SCHEMA_UNSUPPORTED` | 拿到了但读不懂，等同无依据 |
| 4 | `OBSERVED_STALE` | 有依据但过期，仍不足以判定 |
| 5 | `DESIRED_UNPARSEABLE` | Java 自己的配置读不懂；排在快照之后，因为"服务不可达"是更前置的未知 |
| 6 | `NOT_DECLARED` | 业务没声明 → 不需要关心 Python 状态 |
| 7 | `ADAPTER_NOT_LOADED` | Connector 级失败，优先于能力级判定 |
| 8 | `CONNECTOR_VERSION_MISMATCH` | 接入版本不符，能力清单不可信 |
| 9 | `CONTRACT_VERSION_MISMATCH` | 单能力契约不符 |
| 10 | `CAPABILITY_NOT_REGISTERED` | 到这一步才确认"能力真的不在" |
| 11 | `FUNCTION_UNRESOLVED` | 有声明但实现不成立 |
| 12 | `CAPABILITY_DISABLED` | Python 主动禁用 |
| 13 | `PLATFORM_UNSUPPORTED` | 平台客观不支持 |
| 14 | `ACCESS_MODE_UNSUPPORTED` | 能力在，但访问模式不覆盖 |
| 15 | `VERIFICATION_REQUIRED` | 未验证（含 `IMPLEMENTED`：本地测试通过 ≠ 真实平台验证） |
| 16 | `EVIDENCE_EXPIRED` | 证据过期 |
| 17 | `EVIDENCE_INVALID` | 证据无效 |
| 18 | `ACCOUNT_REQUIRED` | 前面都过了，只是没账号 |
| 19 | `DEGRADED` | 可用但受限（须持有已接受证据） |
| 20 | `NONE` | 全部通过 |

> **`VERIFICATION_REQUIRED` / `EVIDENCE_*` 排在 `ACCOUNT_REQUIRED` 之前**：
> 没验证的能力，即使有账号也不能执行。先判更基础的缺失。
>
> **`DESIRED_UNPARSEABLE` 为什么排在第 5 位而不是第 1 位**（`SOURCE-04-03-FIX-02`）：
> 它是 Java 自身配置的问题，看起来可以最先判。但它排在 `REGISTRY_UNREACHABLE` 之后，
> 原因是"服务不可达"意味着 **连判定依据都没有**，此时把结论归因为"你的配置读不懂"
> 会误导排查方向。先报更基础的未知，再报配置错误。

---

## 五、测试用例清单（每值至少一个正向用例）

> 构造方式：核对层单测用**手工构造的 Observed 快照**（不依赖真实 Registry），
> 避免为了测某个 reason 去改 Python 或造真实故障。

| # | Reason | 构造方式（Arrange） | 断言（Assert） |
|---|---|---|---|
| 1 | `NONE` | Desired ENABLED + Observed VERIFIED + 证据有效 + 账号满足 | `AVAILABLE` / `NONE` / 可执行 |
| 2 | `SOURCE_DISABLED` | `ds_source.status = DISABLED` | `SOURCE_DISABLED` / 不可执行 |
| 3 | `REGISTRY_UNREACHABLE` | HTTP 抛 `ConnectException` + 缓存空 | `UNKNOWN` / 不可执行 |
| 4 | `SCHEMA_UNSUPPORTED` | 响应 `{"schemaVersion":"2.0"}` | `UNKNOWN` / `schemaSupported=false` |
| 5 | `OBSERVED_STALE` | 缓存有旧结果，`fetchedAt` 回拨超 TTL，本次刷新失败 | `UNKNOWN` + 原结果仍在诊断区 |
| 6 | `NOT_DECLARED` | Desired 无该能力 | `UNREGISTERED` / 不可执行 |
| 7 | `ADAPTER_NOT_LOADED` | `/v1/adapters` 返回 `loadStatus=FAILED` | `ADAPTER_MISSING` |
| 8 | `CONNECTOR_VERSION_MISMATCH` | Desired 锁 `"1.0.0"`，Observed `"1.1.0"` | `VERSION_MISMATCH` |
| 9 | `CONTRACT_VERSION_MISMATCH` | Observed `contractVersion="2.0"` | `VERSION_MISMATCH` |
| 10 | `CAPABILITY_NOT_REGISTERED` | Observed 清单无该能力 + diagnostics 为空 | `UNREGISTERED` |
| 11 | `FUNCTION_UNRESOLVED` | diagnostics 含 `reason=INVALID_IMPLEMENTATION` | `UNREGISTERED` |
| 12 | `FUNCTION_NOT_CALLABLE` | ⚠️ **当前无法从真实数据构造**；只能用手工注入的 `CapabilityItem` 造单测 | `UNREGISTERED`（标注为预留） |
| 13 | `CAPABILITY_DISABLED` | `implementationStatus=DISABLED` | `UNREGISTERED` |
| 14 | `PLATFORM_UNSUPPORTED` | `implementationStatus=UNSUPPORTED` | `UNREGISTERED` |
| 15 | `ACCESS_MODE_UNSUPPORTED` | Desired 要 `AUTHENTICATED`，Observed 只有 `PUBLIC` | `UNREGISTERED` |
| 16 | `VERIFICATION_REQUIRED` | `implementationStatus=UNVERIFIED` + `evidence=null`；另加 `UNVERIFIED` + 已接受证据、`IMPLEMENTED` + 任意证据 | `UNKNOWN` / 不可执行 |
| 17 | `EVIDENCE_EXPIRED` | `UNVERIFIED` + `rejectionReason=EVIDENCE_EXPIRED` | `UNKNOWN` |
| 18 | `EVIDENCE_INVALID` | `UNVERIFIED` + `rejectionReason=EVIDENCE_TAMPERED` | `UNKNOWN` |
| 19 | `ACCOUNT_REQUIRED` | 能力只 `AUTHENTICATED` + 无 ACTIVE&VALID 账号 | `ACCOUNT_REQUIRED` |
| 20 | `DEGRADED` | `implementationStatus=DEGRADED` + 证据已接受 | `DEGRADED` / 降级执行 |
| 21 | `DESIRED_UNPARSEABLE` | `capability_json` 为非法 JSON / `schemaVersion=3.0` / v2 重复能力，且快照 FRESH | `UNKNOWN` / 不可执行；detail 指明是 Desired 问题 |

**附加断言（每个用例都要带）**：

```java
// Effective 只有 AVAILABLE（且 NONE）才允许执行
assertThat(cap.executable()).isEqualTo(cap.status().equals("AVAILABLE"));
// UNKNOWN 绝不允许执行
if (cap.status().equals("UNKNOWN")) {
    assertThat(cap.executable()).isFalse();
}
```

---

## 六、扩展规则（防语义漂移）

| 规则 | 说明 |
|---|---|
| E1 | 新增 Reason 必须同时补：定义、判定条件、数据来源、`effectiveStatus` 映射、正向测试 |
| E2 | **不得**改变既有 Reason 的含义；确需改变时新增一个值并废弃旧值 |
| E3 | 新 Reason 不得与既有值在"同一数据源 + 同一条件"下同时成立（否则必有一个是冗余的） |
| E4 | Reason 与 `effectiveStatus` 是**多对一**关系；不得反过来让一个 Reason 对应多个 status |
| E5 | Reason 不得承载"处置动作"信息（如"建议重试"），处置由 `retryable` 另行表达 |

---

## 七、遗留问题

| # | 问题 | 归属 |
|---|---|---|
| **Q9** | `FUNCTION_NOT_CALLABLE` 当前不可达——Python 只给 `INVALID_IMPLEMENTATION` 单一码，靠 `detail` 自由文本区分，违反"不得用自由文本作机器判据"。需 Python 拆分诊断子码 | SOURCE-03 契约演进，单独评审 |
| Q10 | `EVIDENCE_INVALID` 聚合了 5 个原始码，若将来某类需要不同处置（如 `EVIDENCE_STALE` 可自动重验），需再拆分 | 按需 |
| Q11 | `OBSERVED_STALE` 的 TTL 与 Observed 缓存 TTL 是否为同一值（§5.3 建议 60s）需在 04-02 确认 | 04-02 |

---

## 八、与 04-03 的衔接

- 本表是 04-03 `CapabilityReconciler` 的**唯一实现依据**；
- 04-03 落地时必须为本表 20 行各写一条单测（`FUNCTION_NOT_CALLABLE` 除外，
  按 §3.1 标注为预留并用手工注入构造）；
- 04-03 的判定顺序必须与 §4 逐行一致，**不得**为方便实现调整顺序；
- 任何顺序调整都必须回改本文档并说明理由。


---

## 九、2026-11-16 增补：第 21 值 `DESIRED_UNPARSEABLE`

> 来源：`SOURCE-04-03-CODE-REVIEW.md` M1。原实现把"Desired 读不懂"映射为
> `SCHEMA_UNSUPPORTED`——那是 Observed 快照的故障码，会让排障方向指向 Python 服务，
> 而真正该查的是 `ds_source.capability_json`。

### 9.1 为什么必须新增，而不是复用

| 候选 | 为什么不行 |
|---|---|
| `SCHEMA_UNSUPPORTED` | 语义是"Python 返回的快照结构不可用"。Desired 是 Java 库内数据，故障域完全不同 |
| `NOT_DECLARED` | 事实错误：配置存在，只是读不懂。"未声明"会让运维以为没人配过 |
| `OBSERVED_STALE` | 与快照新鲜度无关 |
| `UNKNOWN` 状态 + 空 reason | 违反 ADR-008：`UNKNOWN` 必须带精确原因，否则"服务挂了"和"配置坏了"无法区分 |

### 9.2 五项治理检查（沿用 §一 的标准）

| 检查项 | 结论 |
|---|---|
| **语义唯一** | 只表达"Java 侧 Desired 配置无法解析"，不含任何 Observed 含义 |
| **无重复** | 与 20 个既有值逐一比对：`SCHEMA_UNSUPPORTED` 指快照、`NOT_DECLARED` 指缺失、`OBSERVED_STALE` 指新鲜度，均不同 |
| **可追溯** | 触发点是唯一入口 `DesiredCapabilityParser.parse()` 返回 `supported=false` |
| **可测试** | 四种输入各有用例：非法 JSON、未知主版本、v2 结构非法、重复能力 |
| **可扩展** | 新增不改变任何既有值的含义 |

### 9.3 与状态枚举的关系

`effectiveStatus` 仍取 `UNKNOWN`——**八值枚举零改动**。
理由：配置坏了意味着"无法获得可靠判断"，正是 `UNKNOWN` 的定义；
它不落在 `UNREGISTERED`（不是"没登记"），也不落在 `VERSION_MISMATCH`（不是版本问题）。

### 9.4 优先级（第 5 位）的论证

见 §四 的注：排在 `OBSERVED_STALE` 之后、`NOT_DECLARED` 之前。
两个边界都要说清：

- **不能排第 1**：服务不可达时连判定依据都没有，此时报"你的配置读不懂"会把
  排查方向从"服务挂了"引到"配置错了"。
- **必须早于 `NOT_DECLARED`**：配置整体读不懂时，逐条判"未声明"是伪结论——
  我们根本不知道它声明了什么。

### 9.5 已知边界

`DESIRED_UNPARSEABLE` 是**全局**结论：一个 Source 的 Desired 解析失败，
该 Source 下所有候选能力都返回同一 reason。这是有意的——
配置是一份文档，读不懂就整份不可信，不应部分采信。
