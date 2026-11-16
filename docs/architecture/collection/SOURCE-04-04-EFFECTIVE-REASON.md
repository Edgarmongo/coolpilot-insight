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

## 二、20 值完整定义表

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
| 16 | `VERIFICATION_REQUIRED` | **能力已注册，但从未获得有效验证证据** | `C.implementationStatus = UNVERIFIED` **且** `C.evidence = null` | `C` | `UNKNOWN` |
| 17 | `EVIDENCE_EXPIRED` | 验证证据**超过有效期** | `UNVERIFIED` **且** `C.evidence.rejectionReason = EVIDENCE_EXPIRED` | `C` | `UNKNOWN` |
| 18 | `EVIDENCE_INVALID` | 证据**完整性或有效性校验失败**（篡改 / 实现变更 / 版本或范围不符 / 结构非法） | `UNVERIFIED` **且** `rejectionReason ∈ {EVIDENCE_TAMPERED, EVIDENCE_STALE, EVIDENCE_VERSION_MISMATCH, EVIDENCE_SCOPE_MISMATCH, EVIDENCE_MALFORMED}` | `C` | `UNKNOWN` |
| 19 | `ACCOUNT_REQUIRED` | 需要 `AUTHENTICATED` 但**无可用账号** | `Desired.accessModes ⊇ {AUTHENTICATED}` **且** 该 Source 下无 `status=ACTIVE && auth_status=VALID` 的账号 | `D` + `AC` | `ACCOUNT_REQUIRED` |
| 20 | `DEGRADED` | 能力可用但受限 | `C.implementationStatus = DEGRADED` | `C` | `DEGRADED` |

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

### 3.2 其余 19 值交叉审查结论（无重复）

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
| 5 | `NOT_DECLARED` | 业务没声明 → 不需要关心 Python 状态 |
| 6 | `ADAPTER_NOT_LOADED` | Connector 级失败，优先于能力级判定 |
| 7 | `CONNECTOR_VERSION_MISMATCH` | 接入版本不符，能力清单不可信 |
| 8 | `CONTRACT_VERSION_MISMATCH` | 单能力契约不符 |
| 9 | `CAPABILITY_NOT_REGISTERED` | 到这一步才确认"能力真的不在" |
| 10 | `FUNCTION_UNRESOLVED` | 有声明但实现不成立 |
| 11 | `CAPABILITY_DISABLED` | Python 主动禁用 |
| 12 | `PLATFORM_UNSUPPORTED` | 平台客观不支持 |
| 13 | `ACCESS_MODE_UNSUPPORTED` | 能力在，但访问模式不覆盖 |
| 14 | `VERIFICATION_REQUIRED` | 未验证 |
| 15 | `EVIDENCE_EXPIRED` | 证据过期 |
| 16 | `EVIDENCE_INVALID` | 证据无效 |
| 17 | `ACCOUNT_REQUIRED` | 前面都过了，只是没账号 |
| 18 | `DEGRADED` | 可用但受限 |
| 19 | `NONE` | 全部通过 |

> **`VERIFICATION_REQUIRED` / `EVIDENCE_*` 排在 `ACCOUNT_REQUIRED` 之前**：
> 没验证的能力，即使有账号也不能执行。先判更基础的缺失。

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
| 16 | `VERIFICATION_REQUIRED` | `implementationStatus=UNVERIFIED` + `evidence=null` | `UNKNOWN` |
| 17 | `EVIDENCE_EXPIRED` | `UNVERIFIED` + `rejectionReason=EVIDENCE_EXPIRED` | `UNKNOWN` |
| 18 | `EVIDENCE_INVALID` | `UNVERIFIED` + `rejectionReason=EVIDENCE_TAMPERED` | `UNKNOWN` |
| 19 | `ACCOUNT_REQUIRED` | 能力只 `AUTHENTICATED` + 无 ACTIVE&VALID 账号 | `ACCOUNT_REQUIRED` |
| 20 | `DEGRADED` | `implementationStatus=DEGRADED` | `DEGRADED` / 降级执行 |

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
