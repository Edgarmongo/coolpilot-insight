# SOURCE-04-03-FIX-04-M1/L1 定向修复

> 触发：`SOURCE-04-03-FIX04-REVIEW.md`（`6263d68`）的 M1（MEDIUM）、L1（LOW），
> 以及评审 §三 对 OBS1 的升级条件。
>
> 基线：`6263d68`
> 范围：**只做 M1 / L1（含 OBS1 升级与 OBS2 断言）**。未启动 04-06 / 04-07 / 04-08。

---

## 一、M1：镜像校验扩展比较范围

### 1.1 修订前后

| 项 | 修订前 | 修订后 |
|---|---|---|
| `code` | 双向比较 | 不变 |
| `implementationStatus` | 比较 | 不变 |
| **`accessModes`** | **不比较** | **比较（集合语义，排序后比，顺序无关）** |
| **`evidence`** | **不比较** | **比较 `accepted` / `rejectionReason` / `contentHash` / `verifiedAt`** |
| `adapterFunction` | 不比较 | 仍不比较，**但补了边界说明**（见 1.3） |

`evidence` 指纹取四项的理由：

| 字段 | 为什么必须比 |
|---|---|
| `accepted` | 直接决定第 ⑮~⑰ 步：进入证据闸门还是继续 |
| `rejectionReason` | 区分 `EVIDENCE_EXPIRED` 与 `EVIDENCE_INVALID`，是不同 reason |
| `contentHash` | 标识"是不是同一份证据记录"——内容不同而 accepted 相同，仍是两份不同的证据 |
| `verifiedAt` | 同上，标识证据身份 |

`accessModes` 按**集合**比较（排序后比），因为它是集合语义：元素相同、顺序不同不算分叉。
用例 `acceptsDifferentAccessModesOrder` 固化这一点——否则镜像校验会把合法快照误杀。

### 1.2 实测：扩展前后的判定变化

| 变异输入 | 扩展前 | 扩展后 |
|---|---|---|
| `evidence.accepted` 不一致（状态相同） | **ACCEPT** | **REJECT**，报文 `evidence 两份副本不一致: adapters=<none>，capabilities=accepted=true,…` |
| `accessModes` 不一致 | **ACCEPT** | **REJECT**，报文 `accessModes 两份副本不一致: adapters=[PUBLIC]，capabilities=[AUTHENTICATED, PUBLIC]` |
| `accessModes` 顺序不同、元素相同 | ACCEPT | **ACCEPT**（集合语义，未误杀） |
| `implementationStatus` 不一致 | REJECT | REJECT（未回归） |
| 真实生产快照 | ACCEPT | **ACCEPT**（未误杀） |

### 1.3 `adapterFunction` 的边界（按裁决要求写进代码与契约）

代码注释（`RegistrySnapshotParser` 的 `describeMirrorDiff`）与
`SOURCE-04-04-TASKCARD.md` §3.2.3 都写明：

> `adapterFunction` 解析期只用于 `hasBoundFunction()` 的存在性校验，
> **不进入 Effective 推导**，两份不同不改变任何判定结果。
> 若将来它参与判定，必须同步加入比较。

这样"为什么少比一个字段"是**显式决定**，而不是遗漏。

### 1.4 OBS1 升级：重复 `connectorCode` 必须拒绝

评审要求"确认 OBS1 是否影响判定；若影响则升级为必须修复"。实际核查发现**会影响**：

| 位置 | 行为 |
|---|---|
| `RegistrySnapshot.findConnector()` | 取**第一条**匹配文档（核对器读状态用） |
| `checkCapabilityMirror` 的 `byConnector` | `put` 覆盖，等于取**最后一条** |

两者在重复 `connectorCode` 时看的不是同一份文档——镜像校验可能为 A 背书，
而核对器实际读的是 B。这正是镜像校验要防的"两处取不同数据"。

**处置**：解析期检测 `capabilities[]` 中重复 `connectorCode`，命中即
`BROKEN_CAPABILITY`（用例 `rejectsDuplicateConnectorDocument`）。
选"拒绝"而不是"取第一条"，与 Desired v2 重复能力同一口径：不让顺序决定行为。

---

## 二、L1：`status × reason` 合法性表

### 2.1 完整表（21 合法 / 147 非法）

| `effectiveStatus` | 允许的 `effectiveReason` | 数量 |
|---|---|---|
| `AVAILABLE` | `NONE` | 1 |
| `SOURCE_DISABLED` | `SOURCE_DISABLED` | 1 |
| `ADAPTER_MISSING` | `ADAPTER_NOT_LOADED` | 1 |
| `VERSION_MISMATCH` | `CONNECTOR_VERSION_MISMATCH` / `CONTRACT_VERSION_MISMATCH` | 2 |
| `ACCOUNT_REQUIRED` | `ACCOUNT_REQUIRED` | 1 |
| `DEGRADED` | `DEGRADED` | 1 |
| `UNREGISTERED` | `NOT_DECLARED` / `CAPABILITY_NOT_REGISTERED` / `FUNCTION_UNRESOLVED` / `FUNCTION_NOT_CALLABLE` / `CAPABILITY_DISABLED` / `PLATFORM_UNSUPPORTED` / `ACCESS_MODE_UNSUPPORTED` | 7 |
| `UNKNOWN` | `REGISTRY_UNREACHABLE` / `SCHEMA_UNSUPPORTED` / `OBSERVED_STALE` / `DESIRED_UNPARSEABLE` / `VERIFICATION_REQUIRED` / `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` | 7 |
| **合计** | 8 状态 × 21 reason = 168 种组合，合法 21 种 | **21** |

**表是派生自契约的，不是自创**：每一条都能在 `SOURCE-04-04-TASKCARD.md` §3.2 的
20 步判定表里找到对应行；核对器能产出的每一组 (status, reason) 都在表内。
用例 `everyLegalCombinationIsConstructible` 还会断言 `legalCount == 21`
以及 `8 状态 × 21 reason`，枚举扩容而漏更新表时该用例会失败。

### 2.2 校验顺序：先"事实成立"，再"标志一致"

```java
if (!EffectiveStatus.ALL.contains(status))                  throw 非法 status
if (!EffectiveReason.ALL.contains(reason))                    throw 非法 reason
if (!EffectiveStatus.isLegalCombination(status, reason))      throw 非法组合   // 新增
if (executable != EffectiveStatus.allowsExecution(...))       throw 标志不一致
```

顺序有意为之：若先判 executable，`DEGRADED + EVIDENCE_INVALID + true` 会被报成
"executable 不一致"，掩盖真正的问题（组合本身不成立）。这条在实现时踩到过：
第一次写成 executable 优先，测试报错信息指向错误方向。

### 2.3 **需要你确认：这条收回了 F1-3 的一处放宽**

F1-3 当时的裁决是「允许 `DEGRADED` + 其他 Reason + `executable=false`
作为诊断结果保留」。按 L1 的完整组合表，这个组合**不合法**（`DEGRADED` 只允许配 `DEGRADED`），
因此现在**连 `executable=false` 也拒绝构造**。

我的判断依据：

- `DEGRADED` 的语义是"可用但受限"，它的状态描述必须是"受限"，不能同时说"证据无效"；
- "降级了但证据失效"这类事实的正确表达是 `UNKNOWN` + `EVIDENCE_*`
  ——证据闸门（第 ⑮~⑰ 步）在降级（第 ⑲ 步）**之前**，核对器根本产不出 `DEGRADED + EVIDENCE_INVALID`；
- 所以拒绝这个组合**不损失任何可达的诊断信息**，反而让 `DEGRADED` 的语义变干净。

同时保留评审要求的合法诊断态：`UNKNOWN + EVIDENCE_INVALID + false` 可构造
（用例已断言）。如果你认为 `DEGRADED + 坏 reason + false` 仍须保留，
那需要把这组加入组合表——但那样表就不再与 §3.2 的推导一一对应了。

---

## 三、OBS2：C 用例补直接断言

`bothFaultDomainsRemainVisible`（场景 C）原来只断言 `desiredError` 与 `snapshotState`，
"Effective 为空"由 D 用例用同一夹具间接覆盖。现已补：

```java
assertThat(view.getEffective())
        .as("两边都没有能力码来源时，Effective 必须为空")
        .isEmpty();
```

这样将来若有人给 C、D 用不同夹具，C 的"列表为空"仍有独立保护。

---

## 四、验收测试对照（本任务卡 §二 的 12 项）

| # | 要求 | 用例 | 结果 |
|---|---|---|---|
| 1 | 镜像完全一致：接受 | `01_baseline`（探针）/ 既有用例 | ✅ |
| 2 | `implementationStatus` 不一致：拒绝 | `rejectsMirrorStatusMismatch` | ✅ |
| 3 | `evidence.accepted` 不一致：拒绝 | `rejectsMirrorEvidenceMismatch` | ✅ |
| 4 | `accessModes` 不一致：拒绝 | `rejectsMirrorAccessModesMismatch` | ✅ |
| 5 | 不同 Connector：独立校验 | `rejectsAdapterOnlyCapability`（报 `X/EXTRA`，不串号）+ 探针 `06/07` | ✅ |
| 6 | STUB：不误判 | `stubCapabilitiesExcludedFromMirror` | ✅ |
| 7 | 真实生产快照：通过 | 运行时 API + 探针 | ✅ |
| 8 | `AVAILABLE + EVIDENCE_INVALID`：构造拒绝 | `illegalCombinationRejectedEvenWhenNotExecutable` | ✅ |
| 9 | `UNKNOWN + EVIDENCE_INVALID`：允许 | 同上（断言可构造且 `executable=false`） | ✅ |
| 10 | 合法组合：构造成功 | `everyLegalCombinationIsConstructible`（21 组逐一） | ✅ |
| 11 | 非法组合：构造拒绝 | 同上（每组再用相反 executable 试一次）+ `degraded...` | ✅ |
| 12 | 执行标志与准入不一致：拒绝 | `effectiveInvariantEnforced` + 上条 | ✅ |

补充用例（超出 12 项）：
`acceptsDifferentAccessModesOrder`（防误杀）、`rejectsDuplicateConnectorDocument`（OBS1）。

---

## 五、测试与运行时

| 套件 | 修复前 | 修复后 |
|---|---|---|
| `RegistrySnapshotParserTest` | 30 | **34**（+4：evidence / accessModes / 顺序 / 重复 connector） |
| `CapabilityReconcilerTest` | 47 | **48**（L1 两个用例替换原 F1-3 放宽用例，净 +1） |
| `SourceQueryServiceTest` | 4 | **4**（+1 断言，无新增用例） |
| **Java 合计** | 269 | **274** |
| Python 合计 | 76 | **76**（本轮无 Python 改动） |
| 容器 | 5/5 healthy | 5/5 healthy |

运行时（真实容器，重建后）：

```
snapshotState: FRESH | desiredError: null
COMMENT        -> AVAILABLE   / NONE         exec=true
CONTENT_DETAIL -> AVAILABLE   / NONE         exec=true
KEYWORD_SEARCH -> UNREGISTERED/ NOT_DECLARED exec=false
COMMENT_REPLY  -> UNREGISTERED/ NOT_DECLARED exec=false
```

**真实快照通过扩展后的镜像校验**（`evidence` / `accessModes` 都参与比较）——
这是本轮最关键的回归证据：真实载荷两份副本四项字段本就一致，扩展比较不会误杀。

---

## 六、未做 / 边界

| 项目 | 状态 |
|---|---|
| OBS3（`04-04:619` 历史裁决仍写"20 值"） | ❌ 按裁决"后续文档整理时修正"，本轮未动 |
| L1~L5（首轮评审的 LOW） | ❌ 仍未关闭，不因本轮通过销项 |
| MySQL 表结构与数据 | ❌ 未改（`capability_json` 仍 v1 原值） |
| Flyway | ❌ 未新增（仍 V30） |
| `SOURCE-02-capability-schema.json` | ❌ 未改（八值 `effectiveStatus`、六值 `implementationStatus` 均零改动） |
| Python Registry | ❌ 未改 |
| Task/Run 状态机、前端 | ❌ 未改 |
| SOURCE-04-06 / 04-07 / 04-08 | ❌ 未启动 |

**变更文件**（9 个：3 实现 + 3 测试 + 2 契约 + 1 报告）：

```
backend/.../integration/capability/RegistrySnapshotParser.java   镜像扩展到 evidence/accessModes + 重复 connector 拒绝
backend/.../source/domain/EffectiveStatus.java                   组合白名单表 + isLegalCombination
backend/.../source/domain/EffectiveCapability.java               构造期组合校验 + 校验顺序
backend/src/test/.../RegistrySnapshotParserTest.java             +4 用例
backend/src/test/.../CapabilityReconcilerTest.java               L1 组合矩阵（替换 F1-3 放宽用例）
backend/src/test/.../SourceQueryServiceTest.java                 OBS2 直接断言
docs/architecture/collection/SOURCE-04-04-TASKCARD.md            §3.2.2 组合表 + §3.2.3 镜像范围
docs/architecture/collection/ADR-008-...md                       组合合法性与准入的分工
docs/architecture/collection/SOURCE-04-03-FIX04-M1L1-REPORT.md   本文件
```

---

## 七、待独立复核的点

1. **`DEGRADED + 坏 reason + false` 是否应当被拒**（§2.3）——这条收回了 F1-3 的一处放宽，
   依据是"该组合不在契约推导可达范围内"。若保留，组合表就不再与 §3.2 一一对应。
2. **`evidence` 取四项是否合适**：`contentHash` / `verifiedAt` 不影响判定，只标识证据身份。
   如果认为只该比 `accepted` + `rejectionReason`，可以收紧。
3. **重复 `connectorCode` 选择"拒绝"而非"取第一条"**：与 Desired 重复能力同口径，
   但如果上游确实可能产生重复（当前不会），这里会让整份快照不可用。
