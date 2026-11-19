# ADR-008 · Effective 状态与诊断原因分离（修复 UNVERIFIED / UNREGISTERED 混淆）

> 日期：2026-11-16　状态：**已接受**
> 关联：`SOURCE-02-CONTRACT.md` §02-02、`ADR-006`（三态模型）、`SOURCE-04-04-TASKCARD.md`
>
> 触发：SOURCE-04-04 评审时指出 `3507c8f` 的一处错误映射——
> 「Desired 已声明 + Observed 为 UNVERIFIED」被折叠为 `UNREGISTERED`。

---

## 一、问题

`3507c8f` 的状态转换表里写了两条：

| 规则 | 判定 | 映射 |
|---|---|---|
| #5 | Desired 已声明，Connector 下无此能力 | `UNREGISTERED` |
| #9 | Desired 已声明，Observed ∈ {UNVERIFIED, DISABLED, UNSUPPORTED} | `UNREGISTERED` |

**#9 是错的。** 它把三种不同的事实折叠成同一个结论：

| 事实 | 真实含义 | 用 `UNREGISTERED` 表达是否准确 |
|---|---|---|
| 能力**不存在**于 Registry | 未注册 | ✅ 准确 |
| 能力存在，函数解析不到 | 实现无效 | ⚠️ 勉强（它确实没能成为可用注册项） |
| 能力存在，函数可用，**从未验证** | 未验证 | ❌ **事实错误**——它明明注册了 |
| 能力存在，证据已过期 | 证据失效 | ❌ 事实错误 |

用户的原话是准确的：

> `UNREGISTERED` 应该表示 Registry 中不存在对应能力注册，
> 而 `UNVERIFIED` 应该表示能力已经注册，但尚未获得有效验证证据。
> 两者是不同事实。

## 二、核对 SOURCE-02 正式枚举

`effectiveStatus` 的冻结八值（`SOURCE-02-capability-schema.json` `$defs.effectiveStatus`）：

```
AVAILABLE / ADAPTER_MISSING / VERSION_MISMATCH / DEGRADED
ACCOUNT_REQUIRED / SOURCE_DISABLED / UNREGISTERED / UNKNOWN
```

Schema 中**没有任何 `reasonCode` / `reason` 定义**（已核对，只有 `failureType` 与 `effectiveStatus` 两个枚举）。

### 2.1 逐值检验：谁能表达「已注册但未验证」？

| 值 | 定义 | 能否表达「已注册未验证」 | 理由 |
|---|---|---|---|
| `AVAILABLE` | 可执行 | ❌ | 未验证不可执行，用了就是撒谎 |
| `ADAPTER_MISSING` | Adapter 未加载 | ❌ | 此时 Adapter **是**加载的 |
| `VERSION_MISMATCH` | 版本不匹配 | ❌ | 版本一致 |
| `DEGRADED` | 可用但受限 | ❌ | 未验证不是"受限"，是"未证明可用" |
| `ACCOUNT_REQUIRED` | 需要账号 | ❌ | 匿名态能力同样可能未验证 |
| `SOURCE_DISABLED` | Source 停用 | ❌ | Source 是启用的 |
| `UNREGISTERED` | 未注册 | ❌ | **它是注册的** |
| `UNKNOWN` | 无法获得可靠判断 | ⚠️ 不精确，但不撒谎 | 见 §3.2 |

**结论：`effectiveStatus` 八值中，没有任何一个能精确表达「已注册但未验证」。**

这是 SOURCE-02 契约的一个**真实缺口**——`implementationStatus` 有六值
（含 `UNVERIFIED`、`DISABLED`、`UNSUPPORTED`），但 `effectiveStatus` 只有八值，
两侧粒度不对齐。

### 2.2 缺口是既存事实，不是本次引入

SOURCE-02 的「核对结果映射」表里本来就**缺**「已声明 + 已实现但 UNVERIFIED」这一行：

| Desired | Observed | Effective（原文） |
|---|---|---|
| 已声明 | 已实现且版本匹配 | `AVAILABLE` |
| 已声明 | 未加载 / 无函数 | `ADAPTER_MISSING` |
| 已声明 | 已实现但版本不匹配 | `VERSION_MISMATCH` |
| 已声明 | 已实现但 status=DEGRADED | `DEGRADED` |
| 已声明 | 已实现但需登录且无可用账号 | `ACCOUNT_REQUIRED` |
| 已声明 | 已实现但 Source 已停用 | `SOURCE_DISABLED` |
| 未声明 | 已实现 | `UNREGISTERED` |
| 任意 | Python 不可达 | `UNKNOWN` |

同时它的推导规则又明确要求 `implementationStatus ∈ {IMPLEMENTED, VERIFIED}` 才 `AVAILABLE`，
——即 **UNVERIFIED 不可 AVAILABLE，但没说什么状态**。契约在这里留了空白。

## 三、决策

### 3.1 「执行准入分类」与「精确原因」正交

把 Effective 的表达拆成两层，各自有明确的枚举治理规则：

| 层 | 字段 | 值域 | 变更治理 |
|---|---|---|---|
| **准入分类** | `effectiveStatus` | SOURCE-02 冻结八值 | **不得增删改**（本次不动） |
| **精确原因** | `effectiveReason` | Java 侧枚举（本 ADR 定义） | Java 内部模型，随实现演进 |

**为什么 `effectiveReason` 不违反"不得修改 SOURCE-02 枚举"**：

- `effectiveStatus` 与 `effectiveReason` 都**只在 Java 侧计算与展示**；
- Python 的 `/v1/capabilities` 只输出 **Observed**（`implementationStatus` 等），
  **从不输出** `effectiveStatus` —— 后者是 Java 的派生结论；
- 因此新增 `effectiveReason` **不触碰** `SOURCE-02-capability-schema.json` 的任何一个枚举；
- 本次修订**零改动** Schema 文件。

> Schema 校验结果：`SOURCE-02-capability-schema.json` 在本 ADR 中**未被修改**。

### 3.2 语义妥协的诚实声明

对「已注册但未验证」，采用：

```
effectiveStatus  = UNKNOWN
effectiveReason  = VERIFICATION_REQUIRED
```

**这不是理想解，是约束下的最优解。** 必须说清楚：

| 视角 | 判断 |
|---|---|
| 为什么不是 `UNREGISTERED` | 事实错误：它注册了 |
| 为什么不是 `AVAILABLE` / `DEGRADED` | 会让下游误以为可执行 |
| 为什么选 `UNKNOWN` | 八值中唯一不撒谎的：**"当前不具备执行条件，且原因不属于已知的六类确定性失效"** |
| `UNKNOWN` 的代价 | 与"服务不可达"共用同一 effectiveStatus，必须靠 `effectiveReason` 区分 |

这正是用户提出"是否需要通过 Effective 状态与诊断原因分离表达"的落点。
`reasonCode` 不是锦上添花，**是这八值枚举可用性的必要条件**。

### 3.3 `effectiveReason` 枚举（Java 内部模型，冻结）

| reasonCode | 含义 | 常配的 effectiveStatus |
|---|---|---|
| `NONE` | 无异常（AVAILABLE 时） | `AVAILABLE` |
| `SOURCE_DISABLED` | Java 侧已停用该 Source | `SOURCE_DISABLED` |
| `NOT_DECLARED` | Desired 未声明该能力 | `UNREGISTERED` |
| `REGISTRY_UNREACHABLE` | Collection Service 不可达 | `UNKNOWN` |
| `SCHEMA_UNSUPPORTED` | Observed 的 Schema 主版本不认识 / 响应非法 | `UNKNOWN` |
| `OBSERVED_STALE` | 只有过期的 Observed 缓存，无新结果 | `UNKNOWN` |
| `ADAPTER_NOT_LOADED` | Adapter 加载失败 | `ADAPTER_MISSING` |
| `CONNECTOR_VERSION_MISMATCH` | Adapter 版本与锁定要求不符 | `VERSION_MISMATCH` |
| `CONTRACT_VERSION_MISMATCH` | 能力契约主版本不兼容 | `VERSION_MISMATCH` |
| `CAPABILITY_NOT_REGISTERED` | Registry 中无此能力 | `UNREGISTERED` |
| `FUNCTION_UNRESOLVED` | 声明了但函数引用解析不到 | `UNREGISTERED` |
| `FUNCTION_NOT_CALLABLE` | 引用指向的对象不可调用 | `UNREGISTERED` |
| `CAPABILITY_DISABLED` | Python 显式禁用该能力 | `UNREGISTERED` |
| `PLATFORM_UNSUPPORTED` | Python 声明平台不支持 | `UNREGISTERED` |
| `VERIFICATION_REQUIRED` | 已注册，但从未验证 | `UNKNOWN` |
| `EVIDENCE_EXPIRED` | 证据超过有效期 | `UNKNOWN` |
| `EVIDENCE_INVALID` | 证据被判定无效（篡改 / 实现变更 / 版本不符） | `UNKNOWN` |
| `ACCESS_MODE_UNSUPPORTED` | Desired 要求的访问模式不在 Observed 的 accessModes 内 | `UNREGISTERED` |
| `ACCOUNT_REQUIRED` | 需要 AUTHENTICATED 但无可用账号 | `ACCOUNT_REQUIRED` |
| `DEGRADED` | 能力可用但受限 | `DEGRADED` |

> 这些是**诊断原因**，不是状态。它们**不进入** `implementationStatus` /
> `effectiveStatus` 枚举，与 SOURCE-03 的 `DiagnosticReason` 保持同一治理原则。

> **完整定义表见 `SOURCE-04-04-EFFECTIVE-REASON.md`**：21 个值逐一给出含义、
> 判定条件、数据来源、`effectiveStatus` 映射、正向测试构造方式，
> 并完成重复语义交叉审查。其中 `FUNCTION_NOT_CALLABLE` 因 Python 侧
> 只有单一诊断码 `INVALID_IMPLEMENTATION`（靠自由文本区分）而**当前不可达**，
> 短期映射规则为统一归入 `FUNCTION_UNRESOLVED`，已记为该文档 Q9。

## 四、九种情况的完整映射（用户点名的检查清单）

判定顺序自上而下**短路**（命中即停）。

| # | 情况 | Python Registry 的事实 | `effectiveStatus` | `effectiveReason` | 可执行 |
|---|---|---|---|---|---|
| 0 | Source 已停用 | 任意（Python 可能仍加载） | `SOURCE_DISABLED` | `SOURCE_DISABLED` | ❌ 不允许新执行 |
| 1 | Registry 不可达 | HTTP 超时 / 5xx / 连接失败 | `UNKNOWN` | `REGISTRY_UNREACHABLE` | ❌ |
| 2 | Registry 响应非法 | JSON 坏 / 缺 schemaVersion / 主版本 > 1 | `UNKNOWN` | `SCHEMA_UNSUPPORTED` | ❌ |
| 3 | 仅有过期缓存 | 上次成功结果已超 TTL 且本次刷新失败 | `UNKNOWN` | `OBSERVED_STALE` | ❌ |
| 4 | Desired 未声明 | 可能已加载 | `UNREGISTERED` | `NOT_DECLARED` | ❌ |
| 5 | **Adapter 未加载** | `loadStatus=FAILED` | `ADAPTER_MISSING` | `ADAPTER_NOT_LOADED` | ❌ |
| 6 | Adapter 版本不匹配（仅在锁定时） | `connectorVersion` ≠ 锁定值 | `VERSION_MISMATCH` | `CONNECTOR_VERSION_MISMATCH` | ❌ |
| 7 | Contract 主版本不兼容 | `contractVersion` 主版本 ≠ Desired 支持的主版本 | `VERSION_MISMATCH` | `CONTRACT_VERSION_MISMATCH` | ❌ |
| 8 | **Capability 未注册** | Observed 列表里**没有**这条能力，且无相关诊断 | `UNREGISTERED` | `CAPABILITY_NOT_REGISTERED` | ❌ |
| 9 | **函数不存在** | 有声明，diagnostics 含 `INVALID_IMPLEMENTATION`（属性不存在/模块无法导入） | `UNREGISTERED` | `FUNCTION_UNRESOLVED` | ❌ |
| 10 | **函数不可调用** | 引用指向非 callable 对象 | `UNREGISTERED` | `FUNCTION_NOT_CALLABLE` | ❌ |
| 11 | 能力被 Python 显式禁用 | `implementationStatus=DISABLED` | `UNREGISTERED` | `CAPABILITY_DISABLED` | ❌ |
| 12 | 平台不支持 | `implementationStatus=UNSUPPORTED` | `UNREGISTERED` | `PLATFORM_UNSUPPORTED` | ❌ |
| 13 | 访问模式不覆盖 | Observed `accessModes` 不含 Desired 要求的模式 | `UNREGISTERED` | `ACCESS_MODE_UNSUPPORTED` | ❌ |
| 14 | **已注册但从未验证** | `implementationStatus=UNVERIFIED` | `UNKNOWN` | `VERIFICATION_REQUIRED` | ❌ |
| 15 | **证据已过期** | `UNVERIFIED` + `evidence.rejectionReason=EVIDENCE_EXPIRED` | `UNKNOWN` | `EVIDENCE_EXPIRED` | ❌ |
| 16 | **证据被判无效** | `UNVERIFIED` + `rejectionReason ∈ {EVIDENCE_TAMPERED, EVIDENCE_STALE, EVIDENCE_VERSION_MISMATCH, EVIDENCE_SCOPE_MISMATCH, EVIDENCE_MALFORMED}` | `UNKNOWN` | `EVIDENCE_INVALID` | ❌ |
| 17 | 需要账号但无可用账号 | 只支持 AUTHENTICATED，无 `isUsable` 账号 | `ACCOUNT_REQUIRED` | `ACCOUNT_REQUIRED` | ❌ |
| 18 | 可用但受限 | `implementationStatus=DEGRADED` | `DEGRADED` | `DEGRADED` | ⚠️ 降级执行 |
| 19 | 正常可用 | `implementationStatus ∈ {VERIFIED, IMPLEMENTED}` 且上述全通过 | `AVAILABLE` | `NONE` | ✅ |

### 4.1 关键区分（本次修订的核心）

| 情况 | effectiveStatus | effectiveReason | 事实 |
|---|---|---|---|
| 能力根本不存在 | `UNREGISTERED` | `CAPABILITY_NOT_REGISTERED` | Registry 里查不到 |
| 函数解析不到 | `UNREGISTERED` | `FUNCTION_UNRESOLVED` | 有声明，实现不成立 |
| **已注册但未验证** | `UNKNOWN` | `VERIFICATION_REQUIRED` | **注册了，缺证据** |
| 服务不可达 | `UNKNOWN` | `REGISTRY_UNREACHABLE` | **不知道** |

前两行是"确认不存在/不成立"，后两行是"存在但不可执行 / 不知道"。
`effectiveStatus` 相同不代表事实相同，**必须看 reason**。

### 4.2 `UNKNOWN` 与"不可用"的区别（用户强调）

| 概念 | 语义 | 对应 |
|---|---|---|
| 确认不满足执行条件 | 有确定性依据 | `ADAPTER_MISSING` / `VERSION_MISMATCH` / `UNREGISTERED` / `ACCOUNT_REQUIRED` / `SOURCE_DISABLED` |
| 无法获得可靠判断 | 缺信息 | `UNKNOWN` + `REGISTRY_UNREACHABLE` / `SCHEMA_UNSUPPORTED` / `OBSERVED_STALE` |
| 已判定但不可执行 | 有确定性依据，且不属于上面六类 | `UNKNOWN` + `VERIFICATION_REQUIRED` / `EVIDENCE_*` |

**铁律**：Collection Service 暂时不可达，不代表所有平台能力都失效。
不可达只能判 `UNKNOWN` + `REGISTRY_UNREACHABLE`，且**不得**把 Desired 或 Observed 缓存清空。

### 4.3 执行准入的唯一判据（评审强调的边界）

> **`UNKNOWN` 绝不能被下游理解为「允许执行」。**

首期采取**白名单**策略，而不是黑名单：

| `effectiveStatus` | 是否允许进入执行流程 |
|---|---|
| `AVAILABLE` | ✅ 允许 |
| `DEGRADED` | ⚠️ 允许但需降级标注 |
| `ADAPTER_MISSING` | ❌ 拒绝 |
| `VERSION_MISMATCH` | ❌ 拒绝 |
| `ACCOUNT_REQUIRED` | ❌ 拒绝 |
| `SOURCE_DISABLED` | ❌ 拒绝 |
| `UNREGISTERED` | ❌ 拒绝 |
| **`UNKNOWN`** | ❌ **拒绝** |

实现要求：

```java
// 唯一允许执行的判据：白名单，且**两个分支都要求 reason 匹配**
boolean executable = ("AVAILABLE".equals(effectiveStatus)
                      && "NONE".equals(effectiveReason))
                  || ("DEGRADED".equals(effectiveStatus)
                      && "DEGRADED".equals(effectiveReason));
```

> **2026-11-16 修订（F1-3）**：原示例写作 `|| "DEGRADED".equals(effectiveStatus)`，
> **只看 status 不看 reason**，等于让 `DEGRADED + EVIDENCE_INVALID` /
> `DEGRADED + EVIDENCE_EXPIRED` / `DEGRADED + VERIFICATION_REQUIRED` 都能执行
> ——"降级"变成了"证据无效也能跑"的通行证，与 §4.3.1 的证据闸门自相矛盾。
> 实现按 ADR 原文照做，所以松的是**本 ADR 的示例**，不是实现。
> 现已收紧为 reason 必须为 `DEGRADED`，并由 `EffectiveCapability` 的构造期不变量强制执行。

**不得**写成语义反转的形式（如 `!"UNREGISTERED".equals(status)`），
那会把 `UNKNOWN` 及未来新增的状态默认放行——这是最危险的一类缺陷。

> **补一层：`status × reason` 组合合法性（2026-11-16，FIX-04 M1/L1）**
> 白名单只回答"能不能执行"，不回答"这两个字段放在一起说不说得通"。
> 缺了后者，`AVAILABLE + EVIDENCE_INVALID + executable=false` 这种自相矛盾的记录
> 也能构造出来。因此 `EffectiveCapability` 构造期另有一张组合白名单
> （见 `SOURCE-04-04-TASKCARD.md` §3.2.2：21 组合法 / 147 组非法）。
> 两个校验分工明确、互不替代：**组合合法 ≠ 可执行，可执行必然组合合法**。

### 4.3.1 证据闸门不因实现状态而豁免（2026-11-16 增补）

白名单解决的是"**结论**能否执行"，还有一个更前置的问题：**结论本身怎么得出**。

`implementationStatus` 是 Schema 允许的**六值**枚举。首版实现只在
`UNVERIFIED` / `VERIFIED` 时校验验证证据，其余值直接跳过，于是：

| 状态 | 证据 | 首版结果 | 问题 |
|---|---|---|---|
| `IMPLEMENTED` | 无 | `AVAILABLE` + 可执行 | 本地测试通过被当成真实平台验证 |
| `IMPLEMENTED` | 被拒 | `AVAILABLE` + 可执行 | 证据校验整段被跳过 |
| `DEGRADED` | 被拒 | `DEGRADED` + 可执行 | 同上 |

这不是白名单能拦住的——白名单看到的已经是一个错误的 `AVAILABLE`。

**裁定**：

1. **证据闸门对所有未被 `DISABLED` / `UNSUPPORTED` 拦下的状态一律生效**，
   不存在"某个状态可以跳过证据校验"。
2. **`IMPLEMENTED` 永不产出 `AVAILABLE`**，归入
   `UNKNOWN` + `VERIFICATION_REQUIRED`（该 reason 的冻结定义即"已注册且函数可用，
   但从未获得有效验证证据"，精确匹配）。
3. **`evidence.accepted=false` 一律不可执行**，与状态无关。
4. `DEGRADED` 必须有已接受证据才能降级执行——"降级"是性能/限流语义，
   不是"证据无效也能跑"的许可证。

完整矩阵见 `SOURCE-04-04-TASKCARD.md` §3.2.1。

> **一般性教训**：对"枚举允许的取值"做准入推导时，**必须穷举全部取值**。
> 只处理常见两值、其余"默认落到最后一步"，等价于给未列举的值开了一条
> 通往 `AVAILABLE` 的暗门。这与 §4.3 反对黑名单是同一个道理的两个面：
> 黑名单漏掉新状态，穷举不全漏掉旧状态。

### 4.4 展示层不得只显示状态（评审强调）

`effectiveStatus` 相同**不代表事实相同**（§3.4）。因此：

| 层 | 强制要求 |
|---|---|
| API 响应 | `effectiveStatus` 与 `effectiveReason` **必须同时返回**，不得只给其一 |
| 前端展示（04-07） | 必须同时显示状态与原因的中文说明，**不得**只显示一个 `UNKNOWN` 或一个红点 |
| 日志 / 诊断 | 两者都要记录，且原因要能映射到 §3.3 的枚举值而非自由文本 |

反例（禁止）：

```
能力状态：UNKNOWN          ← 运营无法判断是"服务挂了"还是"没验证"
```

正例：

```
能力状态：不可执行
原因：已注册但从未验证（VERIFICATION_REQUIRED）
```

## 五、对 04-02 的接口要求（本 ADR 的直接后果）

要区分 §4 的 #8 / #9 / #10，Java **必须同时读两个端点**：

| 端点 | 提供 | 用途 |
|---|---|---|
| `GET /v1/capabilities` | Observed 能力清单（v1.1 Schema） | 判断"能力是否注册"、`implementationStatus`、`evidence` |
| `GET /v1/adapters` 或 `/v1/adapters/{code}` | `diagnostics[]`（含 `reason=INVALID_IMPLEMENTATION`、`adapterFunction`） | 区分"函数不存在/不可调用"与"简单没注册"；判断 `loadStatus` |

**只读 `/v1/capabilities` 会在 #8 与 #9 之间失明** —— 两者在 Observed 清单里都表现为
"没有这条能力"。这是 04-02 必须处理的问题，已写入任务卡。

## 六、后果

### 正面

- 消除了「未验证 = 未注册」的事实错误；
- `effectiveStatus` 枚举**零改动**，契约冻结不受影响；
- 诊断原因可独立演进，不必每次为细分场景动枚举；
- Java 侧能精确回答"为什么不可用"，而不是只给一个粗分类。

### 负面 / 待办

| 事项 | 归属 |
|---|---|
| `UNKNOWN` 承载了两种语义（不知道 vs 知道了但不可执行），必须靠 reason 区分，存在被误读风险 | 展示层必须在 UI 上同时显示 reason，不能只显示 effectiveStatus（04-07） |
| SOURCE-02 的 `effectiveStatus` 粒度与 `implementationStatus` 不对齐，属契约缺口 | **不本次修**。建议在 SOURCE-02 正式修订时评估是否新增 `UNVERIFIED` 值；本次只记录缺口 |
| Java 需同时读两个端点才能区分函数问题 | 04-02 |

## 七、未做的事

- ❌ 未修改 `SOURCE-02-capability-schema.json`（枚举零改动）
- ❌ 未修改 `implementationStatus` / `effectiveStatus` 枚举
- ❌ 未实现 Java 代码（`EffectiveCapability` DTO 与 `CapabilityReconciler` 属 04-03）
- ❌ 未启动 04-02 / 04-03
