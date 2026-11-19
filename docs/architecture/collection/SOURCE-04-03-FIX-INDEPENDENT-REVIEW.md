# SOURCE-04-03-FIX 独立代码复核（FIX-01 / FIX-02）

> 评审性质：**只读独立复核**。未修改任何 Java / Python 源码、测试、契约文档、
> Flyway、数据库、Schema、前端；未重建或重启任何容器；未启动 04-06 / 04-07 / 04-08。
> 唯一新增文件为本报告。
>
> 验证用的探针类编译在 `%TEMP%\probe2`（项目目录之外），未进版本控制。

---

## 一、基线与提交范围

### 1.1 实际提交链

```
01584b9  docs(memory): 记录 SOURCE-04-03 FIX-01/FIX-02 修复要点与穷举教训
283096d  docs(memory): 记录 SOURCE-04-03 FIX-01/FIX-02 修复要点与穷举教训   ← 任务卡引用为 FIX-02
d7a4077  fix(source): SOURCE-04-03-FIX-02 修复 Desired 故障分类（M1）与重复能力静默接受（M2）
d6610f9  fix(source): SOURCE-04-03-FIX-01 修复 IMPLEMENTED / DEGRADED 的证据准入漏洞
c7c2c17  docs(memory): 记录 SOURCE-04-03 评审结论（FAIL，H1 潜伏 fail-open）   ← FIX 基线
```

### 1.2 **提交号引用偏差（需先说明）**

任务卡把 FIX-02 记为 `283096d`，但该提交**只新增了一个记忆文件**：

```
283096d  .workbuddy/memory/2026-11-16-supplement-review.md | 33 +++++
```

FIX-02 的**实际代码提交是 `d7a4077`**（12 个文件，+555/−88）。
本报告以 `d7a4077` 为 FIX-02 的评审对象；`283096d` 已在 §五 作为 OBSERVATION 记录。

另发现 `283096d` 与 `01584b9` 是**同一段记忆内容被提交两次**（重复追加）：

```
$ grep -c "^## SOURCE-04-03 修复：FIX-01" .workbuddy/memory/2026-11-16-supplement-review.md
2        # 第 3 行、第 36 行各一份
```

不影响代码与契约，但解释了为什么任务卡引用的提交号对不上。

### 1.3 工作区与授权范围

```
$ git status --short
（空 —— 评审开始时工作区干净）

$ git diff c7c2c17 d7a4077 --stat
 5 Java 主代码 / 2 测试 / 5 文档 / 1 报告
```

| 提交 | 文件数 | 是否越界 |
|---|---|---|
| `d6610f9`（FIX-01） | 7（1 主代码 + 1 测试 + 5 文档） | ✅ 无 |
| `d7a4077`（FIX-02） | 12（5 主代码 + 2 测试 + 5 文档） | ✅ 无 |

未出现 `frontend/`、`db/migration/`、`SOURCE-02-capability-schema.json` 的改动。
Flyway 仍为 V30，`ds_source.capability_json` 仍为 v1 原值（实测见 §四）。

---

## 二、FIX-01 复核（H1：证据闸门）

### 2.1 十二项逐条结论

| # | 检查项 | 结论 | 依据 |
|---|---|---|---|
| 1 | §3.2.1 六值 × 三态矩阵是否完整 | ✅ 18 格全部有值，无留白 | `04-04-TASKCARD:340-347` |
| 2 | 是否覆盖 SOURCE-02 全部合法 `implementationStatus` | ✅ 六值全列；`DISABLED`/`UNSUPPORTED` 标注"第 12/13 步已返回" | `CapabilityStatus.ALL` 六值 vs 矩阵六行一致 |
| 3 | 与 §3.2 / §3.3 / ADR-008 一致 | ✅ 三处口径一致（第 15 行 `∈{UNVERIFIED,IMPLEMENTED}`、第 19 行 `VERIFIED`、第 20 行 `DEGRADED` 需证据） | 逐行比对 |
| 4 | 核对器是否对全部实现状态执行证据校验 | ✅ **是**（`DISABLED`/`UNSUPPORTED` 由第 12/13 步提前返回，属"未走到"而非"被豁免"） | `Reconciler:240-262`；实测 §2.2 |
| 5 | `IMPLEMENTED` 无证据是否被阻止 | ✅ `UNKNOWN` / `VERIFICATION_REQUIRED` / `exec=false` | 实测第 6 行 |
| 6 | `IMPLEMENTED` 证据被拒是否被阻止 | ✅ `UNKNOWN` / `EVIDENCE_INVALID`（或 `EVIDENCE_EXPIRED`）/ `exec=false` | 实测第 7-8 行 |
| 7 | `DEGRADED` 无证据 / 证据被拒 | ✅ 无证据→`VERIFICATION_REQUIRED`；被拒→`EVIDENCE_*`；均 `exec=false` | 实测第 14-16 行 |
| 8 | `VERIFIED` 有效证据是否仍正常准入 | ✅ `AVAILABLE` / `NONE` / `exec=true` | 实测第 9 行 + 运行时 API |
| 9 | `EVIDENCE_TAMPERED` 是否映射 `EVIDENCE_INVALID` 且 detail 保留原码 | ✅ status/reason 为 `EVIDENCE_INVALID`，detail 为「验证证据不被接受: EVIDENCE_TAMPERED 内容被改」 | 实测第 3/7/11/15 行 |
| 10 | `missingEvidenceDetail` 是否区分缺失语境 | ⚠️ 区分了 `VERIFIED`（协议异常）与 `DEGRADED`（不得降级），但 `IMPLEMENTED` 与 `UNVERIFIED` 共用同一句 | 实测第 6/10 行 detail（见 O2） |
| 11 | 是否存在其他状态绕过证据校验的路径 | ❌ **存在两条**：未知 `implementationStatus`、`UNVERIFIED + 证据接受` | 见 F1-1 / F1-2 |
| 12 | `executable` 是否严格白名单 | ⚠️ 白名单成立，但 `DEGRADED` 分支忽略 `reason` | 见 F1-3 |

### 2.2 六值 × 四态实测输出（探针：真实 `RegistrySnapshotParser` + `CapabilityReconciler`）

> 构造方式：取真实快照样本，保留 `DOUYIN_PUBLIC` 平台接入，替换 `COMMENT` 的
> `implementationStatus` 与 `evidence` 后重新解析。Desired 用库中真实 v1
> （`{"comments":true}`），`hasUsableAccount=true`，快照 `FRESH`。

| `implementationStatus` | 证据 | `effectiveStatus` | `effectiveReason` | `executable` | detail |
|---|---|---|---|---|---|
| `UNVERIFIED` | 接受 | **`AVAILABLE`** | `NONE` | **true** | 全部准入条件通过 |
| `UNVERIFIED` | 缺失 | `UNKNOWN` | `VERIFICATION_REQUIRED` | false | 能力已注册但从未获得有效验证证据 |
| `UNVERIFIED` | 篡改 | `UNKNOWN` | `EVIDENCE_INVALID` | false | 验证证据不被接受: EVIDENCE_TAMPERED 内容被改 |
| `UNVERIFIED` | 过期 | `UNKNOWN` | `EVIDENCE_EXPIRED` | false | 验证证据不被接受: EVIDENCE_EXPIRED 超期 |
| `IMPLEMENTED` | 接受 | `UNKNOWN` | `VERIFICATION_REQUIRED` | false | 实现状态为 IMPLEMENTED（本地测试通过），尚未取得真实平台验证 |
| `IMPLEMENTED` | 缺失 | `UNKNOWN` | `VERIFICATION_REQUIRED` | false | 能力已注册但从未获得有效验证证据 |
| `IMPLEMENTED` | 篡改 | `UNKNOWN` | `EVIDENCE_INVALID` | false | 验证证据不被接受: EVIDENCE_TAMPERED 内容被改 |
| `IMPLEMENTED` | 过期 | `UNKNOWN` | `EVIDENCE_EXPIRED` | false | 验证证据不被接受: EVIDENCE_EXPIRED 超期 |
| `VERIFIED` | 接受 | `AVAILABLE` | `NONE` | true | 全部准入条件通过 |
| `VERIFIED` | 缺失 | `UNKNOWN` | `VERIFICATION_REQUIRED` | false | 实现状态为 VERIFIED 但缺少验证证据（协议异常…） |
| `VERIFIED` | 篡改 | `UNKNOWN` | `EVIDENCE_INVALID` | false | 验证证据不被接受: EVIDENCE_TAMPERED 内容被改 |
| `VERIFIED` | 过期 | `UNKNOWN` | `EVIDENCE_EXPIRED` | false | 验证证据不被接受: EVIDENCE_EXPIRED 超期 |
| `DEGRADED` | 接受 | `DEGRADED` | `DEGRADED` | true | 可用但受限（分页/限流等） |
| `DEGRADED` | 缺失 | `UNKNOWN` | `VERIFICATION_REQUIRED` | false | 降级能力缺少验证证据，未经验证不得降级执行 |
| `DEGRADED` | 篡改 | `UNKNOWN` | `EVIDENCE_INVALID` | false | 验证证据不被接受: EVIDENCE_TAMPERED 内容被改 |
| `DEGRADED` | 过期 | `UNKNOWN` | `EVIDENCE_EXPIRED` | false | 验证证据不被接受: EVIDENCE_EXPIRED 超期 |
| `DISABLED` | 任意 4 种 | `UNREGISTERED` | `CAPABILITY_DISABLED` | false | 该能力在采集服务侧被显式禁用 |
| `UNSUPPORTED` | 任意 4 种 | `UNREGISTERED` | `PLATFORM_UNSUPPORTED` | false | 平台不支持该能力 |
| **`PARTIAL`（非法）** | 接受 | **`AVAILABLE`** | `NONE` | **true** | 全部准入条件通过 |
| **字段缺失（非法）** | 接受 | **`AVAILABLE`** | `NONE` | **true** | 全部准入条件通过 |

**H1 的原缺陷已确认关闭**：`IMPLEMENTED` / `DEGRADED` 在四种证据状态下全部不再产出
`AVAILABLE`（除 `DEGRADED + 接受` 按契约降级执行）。与实施报告的关键差异：
**`IMPLEMENTED + 证据接受` 现在明确判 `VERIFICATION_REQUIRED`**，而非旧版的 `AVAILABLE`。

**但表格最后两行暴露了新问题**（F1-1 / F1-2），详见 §五。

### 2.3 与冻结契约的一致性核对

| 契约位置 | 内容 | 实现 | 结论 |
|---|---|---|---|
| §3.2 第 14 行 | `∈{UNVERIFIED,IMPLEMENTED}` → `VERIFICATION_REQUIRED` | 同（`:241-245`、`:256-261`） | ✅ |
| §3.2 第 15/16 行 | 任意状态 + 证据过期/无效 | 同（`:246-255`） | ✅ |
| §3.2 第 18 行 | `DEGRADED` **且**证据已接受 | 同（证据闸门在前，`:271`） | ✅ |
| §3.2 第 19 行 | `VERIFIED` **且**证据已接受 | 同 | ✅ |
| §3.2.1 `UNVERIFIED`/接受 = "按**不可执行**处理" | —— | **`AVAILABLE` + 可执行** | ❌ **F1-1** |
| ADR-008 §4.3.1 铁律 1/2/3 | 闸门不豁免、`IMPLEMENTED` 不产出 `AVAILABLE`、`accepted=false` 不可执行 | 全部成立 | ✅ |

---

## 三、FIX-02 复核（M1 + M2）

### 3.1 M1：Desired 解析错误（八项）

| # | 检查项 | 结论 | 依据 |
|---|---|---|---|
| 1 | `DESIRED_UNPARSEABLE` 定义是否明确、唯一 | ✅ 单一常量、单一使用点；五项治理检查见 `EFFECTIVE-REASON §9.2` | `EffectiveReason:46`、`Reconciler:128` |
| 2 | 是否错误复用 `SCHEMA_UNSUPPORTED` | ✅ 代码中已无该复用（仅注释提及历史） | `grep` 全文 |
| 3 | Reason 顺序是否与冻结顺序一致 | ✅ `REGISTRY_UNREACHABLE`(2) → `SCHEMA/OBSERVED_STALE`(3/4) → `DESIRED_UNPARSEABLE`(5) → `NOT_DECLARED`(6) | `Reconciler:112-129` |
| 4 | Registry 不可达是否优先于 Desired 解析失败 | ⚠️ **代码结构上成立，但该组合无法观测**（候选为空，无行可标注）；STALE 组合有测试 | 见 O1 |
| 5 | 两边同时故障且候选为空时，调用方能否得到准确诊断 | ⚠️ 靠 `desiredError` + `snapshotState` 两个字段补足，但**该填充路径无测试** | 见 F2-4 |
| 6 | `desiredError` 是否污染 `EffectiveCapability` | ✅ 未污染：字段在 `DataSourceView`，`EffectiveCapability` 无此字段 | `grep` 结果为空 |
| 7 | `desiredError` 是否符合 API 兼容性 | ✅ 纯新增字段，旧字段与旧 `capability` 结构不变；实测见 §四 | 运行时 API |
| 8 | Desired 正常时是否 `desiredError=null` | ✅ 实测 `null` | §四 |

**`DESIRED_UNPARSEABLE` 的唯一性复核**：与 20 个既有值逐一比对，确认无重复
（`SCHEMA_UNSUPPORTED` 指快照结构、`NOT_DECLARED` 指缺失、`OBSERVED_STALE` 指新鲜度）。
`effectiveStatus` 仍取 `UNKNOWN`，八值枚举零改动（`EffectiveStatus.ALL` 未变）。

### 3.2 M2：重复能力声明（七项）

| # | 检查项 | 结论 | 依据 |
|---|---|---|---|
| 1 | 重复键是否严格 `connectorCode + code` | ✅ `seen.add(connector + "/" + code)` | `Parser:167` |
| 2 | 同键、相同 `desiredStatus` 是否拒绝 | ✅ `rejectsDuplicateEnabled` | 测试通过 |
| 3 | 同键、不同 `desiredStatus` 是否拒绝 | ✅ `rejectsDuplicateContradictory` | 测试通过 |
| 4 | 不同 Connector、相同 code 是否允许 | ✅ `allowsSameCodeInDifferentConnector`（断言两条都在） | 测试通过 |
| 5 | 是否拒绝整份文档 | ✅ 返回 `unsupported(...)`，`capabilities` 为空 | 代码 + 测试 |
| 6 | 是否仍存在 first-wins / last-wins / 顺序依赖 | ✅ 解析层已阻断；`find()` 的 `findFirst` 因此不可达重复 | 代码审读 |
| 7 | 是否误认为已完成 L4（sourceCode 归属校验） | ✅ 未做：全文无 `sourceCode` 与 `ds_source.code` 的比对 | `grep` |

**同 Connector 不同 `sourceCode` 判重复**这条超出了 M2 字面要求（任务卡只说
"不同 Source 的相同 code 按完整匹配键判断"）。实现的选择是：键不含 `sourceCode`，
因此同 connector + 同 code + 不同 source **判重复**。理由（下游按 connector+code 匹配，
必然撞键）成立，且报告已声明——**不构成越权**，但需评审确认这个解释被接受。

---

## 四、测试与运行证据（实际执行）

### 4.1 全量

```
Java   253/253   (Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS)
Python  76/76    (registry 23 + server 17 + evidence 16 + snapshot 20)
容器    5/5 healthy（只读查看，未重启）
```

### 4.2 定向

```
CapabilityReconcilerTest      43/43   (0.582s)
DesiredCapabilityParserTest   13/13   (0.045s)
SnapshotCacheTest             18/18   (6.753s)   ← 含 FRESH/STALE/UNAVAILABLE 三态与并发
合计                          74/74
```

测试数量与实施报告一致（FIX-01 报 41、FIX-02 报 43；以实际执行为准：**43**，
因为 FIX-02 又加了 2 条）。

### 4.3 运行时 API（只读调用，未改环境）

```
GET /api/v1/source/sources
字段集合: adapterCode, capability, code, desired, desiredError, effective,
          id, name, remark, snapshotDetail, snapshotState, sourceType, status
snapshotState: FRESH | desiredError: null | 旧 capability 仍在: True

COMMENT        -> AVAILABLE   / NONE          exec=true
CONTENT_DETAIL -> AVAILABLE   / NONE          exec=true
KEYWORD_SEARCH -> UNREGISTERED/ NOT_DECLARED  exec=false
COMMENT_REPLY  -> UNREGISTERED/ NOT_DECLARED  exec=false
```

Effective 记录字段：`category, code, detail, effectiveReason, effectiveStatus, executable`
——**`desiredError` 未进入 Effective**，确认未污染（对应 M1 第 6 项）。

### 4.4 单元测试证据 vs 运行时证据（严格区分）

| 场景 | 证据性质 |
|---|---|
| 六值 × 四态矩阵 | **单元测试 + 独立探针**（构造快照）。`PARTIAL` / 字段缺失两行**只有探针**，无正式测试 |
| `IMPLEMENTED` / `DEGRADED` 回归 | **单元测试**（7 条） |
| 重复能力四类 | **单元测试**（4 条） |
| `desiredError` 正常为 null | **运行时 API 实测** |
| `desiredError` 非 null（配置非法） | **无任何证据**（禁止改库，且无 Service 层测试） |
| Registry 不可达 + Desired 非法 的优先级 | **无任何证据**（候选为空，不可观测） |
| FRESH / STALE / UNAVAILABLE | FIX-01 期间有运行时实测；**本轮未重跑**（禁止重启环境），以 `SnapshotCacheTest` 18 条为证据 |

---

## 五、缺陷清单

### F1-1（MEDIUM · 契约-实现不一致，fail-open）

**位置**：`backend/src/main/java/com/coolpilot/insight/source/application/CapabilityReconciler.java:240-262`
（证据闸门全部通过后无"未验证状态"守卫）→ `:279` 返回 `AVAILABLE`

**契约位置**：`docs/architecture/collection/SOURCE-04-04-TASKCARD.md:342`

> `UNVERIFIED` | 证据接受：⚠️ 不可能（证据接受即升 `VERIFIED`）；**若出现按不可执行处理**

**实际行为**：`UNVERIFIED + evidence.accepted=true` → `AVAILABLE` / `NONE` / `executable=true`。

**触发条件**：Python 输出 `implementationStatus=UNVERIFIED` 且 `evidence.accepted=true`
（自相矛盾的组合）。当前 Python 的 `apply_evidence()` 在接受证据时会把状态升为
`VERIFIED`，所以**现在不可达**；一旦该不变式被放宽（例如"证据只校验不升级"的改动），
即生效。

**可复现证据**：§2.2 表格第 1 行（探针实跑）。

**违反的契约**：§3.2.1 矩阵该格明写"按不可执行处理"；ADR-008 §4.3.1 铁律 3
（"`accepted=false` 一律不可执行"）的对称面——数据自相矛盾时应 fail-close。

**修复建议**：在证据闸门通过后、`IMPLEMENTED` 判定处，把守卫从
`if (IMPLEMENTED)` 扩展为 `if (IMPLEMENTED || UNVERIFIED)`，或显式判
`if (!VERIFIED.equals(impl) && !DEGRADED.equals(impl))` → `VERIFICATION_REQUIRED`。
若判断"证据优先"更合理，则应改契约而非留空。

**是否阻断 04-03 验收**：否（潜伏，当前不可达）。

---

### F1-2（MEDIUM · 契约未覆盖的取值，fail-open）

**位置**：`CapabilityReconciler.java:240-279`（无"状态是否合法"守卫）
＋ `backend/src/main/java/com/coolpilot/insight/integration/capability/CapabilityContractParser.java:177`
（只读取 `implementationStatus`，**不校验是否属于六值枚举**）

**实际行为**：`implementationStatus = "PARTIAL"`（非法）或该字段缺失 + `evidence.accepted=true`
→ `AVAILABLE` / `NONE` / `executable=true`。

**触发条件**：Python 输出新状态值（如未来的 `PARTIAL`）或漏写该字段。

**可复现证据**：§2.2 表格最后两行（探针实跑，`PARTIAL` 与字段缺失均 `AVAILABLE`）。

**违反的契约**：这是本次评审自己总结的教训的反面——"未列举的取值默认落到最后一步，
等于给它们开一条通往 `AVAILABLE` 的暗门"。§3.2.1 只覆盖六值，对非法值没有规定；
解析器也不拒绝。

**修复建议**（二选一，建议都做）：
1. 解析层：`CapabilityContractParser.parseItem()` 校验
   `CapabilityStatus.ALL.contains(implementationStatus)`，不合法即 `BROKEN_CAPABILITY`（fail-close）；
2. 核对层：证据闸门后加兜底——不在 `{VERIFIED, DEGRADED}` 内一律
   `UNKNOWN` + `VERIFICATION_REQUIRED`。

**是否阻断 04-03 验收**：否（需要 Python 违约才会触发；且 Schema 主版本不匹配会被
更早拦下）。但与 F1-1 是同一类缺陷，建议同批修。

---

### F1-3（LOW · 白名单对 `DEGRADED` 忽略 `reason`）

**位置**：`backend/src/main/java/com/coolpilot/insight/source/domain/EffectiveStatus.java:53-60`

```java
return DEGRADED.equals(status);   // 不看 reason
```

**实测**：

```
DEGRADED / DEGRADED                -> executable=true
DEGRADED / EVIDENCE_INVALID        -> executable=true   ← 语义上不该允许
DEGRADED / VERIFICATION_REQUIRED   -> executable=true   ← 同上
```

**说明**：`ADR-008 §4.3` 的示例代码正是 `|| "DEGRADED".equals(effectiveStatus)`，
所以实现是**忠于 ADR** 的——松的是 ADR 本身。当前核对器只产出
`DEGRADED / DEGRADED`，因此不可达。

**修复建议**：`DEGRADED` 也要求 `reason == DEGRADED`；同步改 ADR-008 §4.3 示例。

**是否阻断 04-03 验收**：否。

---

### F1-4（LOW · 注释未同步）

**位置**：`CapabilityReconciler.java:101`（`§4 第 1~4 步`）、`:149`（`§4 第 5~19 步`）

FIX-02 在判定链第 4 位插入了 `DESIRED_UNPARSEABLE`，其后全部 +1，
当前应为 **1~5 步** 与 **6~20 步**。文档已同步，代码注释没有。

**是否阻断**：否。

---

### F2-1（MEDIUM · 契约文档自相矛盾）

**位置**：`docs/architecture/collection/SOURCE-04-04-TASKCARD.md:273`（§2.7 兼容读取）

> `schemaVersion` 主版本 ≥ 3 | 拒绝解析，该 Source 的 Desired 判为 `UNKNOWN` + `SCHEMA_UNSUPPORTED`

同一份文档的 §3.2 第 4 行（`:303`）与 §3.3 已经改为 `DESIRED_UNPARSEABLE`。
**同一文档内两处对同一事实给出不同结论**，而 §2.7 是"迁移/兼容读取"章节，
04-06 实施迁移时最可能被读到的就是它——按它实现会**重新引入 M1**。

**修复建议**：`:273` 改为 `UNKNOWN` + `DESIRED_UNPARSEABLE`，并加一句指向 §3.2 第 4 行。

**是否阻断 04-03 验收**：否（行为已正确），但**阻断 04-06 的迁移实现**——应在启动 04-06 前修。

---

### F2-2（LOW · 实施文档仍描述旧行为）

**位置**：`docs/architecture/collection/SOURCE-04-03-IMPLEMENTATION.md:78-80`

该表仍写着"Desired 不可解析 → `SCHEMA_UNSUPPORTED`"，且位置画在 ② 之前
——正是 M1 描述的**缺陷形态**。FIX-01 只在文末加了"以 FIX-01 报告为准"的指引，
FIX-02 未更新这张表。

**修复建议**：更新该行（原因码 + 位置），或在该表上方加显式"已被 FIX-02 取代"标记。

**是否阻断**：否（历史实施文档）。

---

### F2-3（LOW · M1 的关键补口无测试）

**位置**：`backend/src/main/java/com/coolpilot/insight/source/application/SourceQueryService.java:97`
（`v.setDesiredError(desired.error());`）

`desiredError` 是"两边都坏时调用方仍能诊断"的**唯一**依据（候选为空 → `effective` 为空）。
但全仓库**没有任何测试**断言它的填充：

```
$ grep -rn "desiredError" --include=*.java backend/src/test/
CapabilityReconcilerTest.java:227   ← 只出现在注释里
```

**影响**：`desiredError` 若被误改为 `null` 或漏设，测试不会红；M1 的闭环会静默失效。

**修复建议**：补一条 Service 层测试（构造非法 `capability_json` + UNAVAILABLE 快照，
断言 `desiredError != null` 且 `effective` 为空）。

**是否阻断**：否。

---

### O1（OBSERVATION · 优先级断言不可观测）

任务卡 M1 第 4 项要求"Registry 不可达是否优先于 Desired 解析失败"。
实测：该组合下 **Desired 无能力码 + Observed 无骨架 ⇒ 候选为空 ⇒ 返回空列表**，
没有任何一行可以承载 reason。因此：

- 代码的**结构顺序**正确（`:112-129`，Desired 判定在快照判定之后）；
- 但该组合的优先级**无法被观测或断言**，原 `registryUnreachableOutranks...` 用例
  已被替换为 `bothBrokenWithoutCandidatesYieldsNoRows`（只断言空集合）。

可观测的只有 STALE 组合（`staleOutranksDesiredUnparseable` ✅）。
补偿机制是 `desiredError` + `snapshotState` 两个字段——设计上成立，
但如 F2-3 所述缺测试。

**建议**：接受这个设计，并在 `04-04` 或 ADR 中写明"该组合不产出 Effective 行，
诊断走 `desiredError` / `snapshotState`"，避免后被误认为漏判。

---

### O2（OBSERVATION · `IMPLEMENTED` 缺证据的 detail 与 `UNVERIFIED` 相同）

实测两者 detail 均为"能力已注册但从未获得有效验证证据"。
`missingEvidenceDetail` 只在 `VERIFIED` / `DEGRADED` 上做了区分。
`IMPLEMENTED` 与 `UNVERIFIED` 在"缺证据"这一点上事实相同，共用文案可以接受，
但若希望排障时能直接看出"是本地测试过还是完全没测过"，可以补一句。
非缺陷。

---

### O3（OBSERVATION · 提交卫生）

1. 任务卡引用的 FIX-02 提交号 `283096d` 并非代码提交（实际为 `d7a4077`）。
2. `283096d` 与 `01584b9` 是同一段记忆内容被提交两次，导致
   `.workbuddy/memory/2026-11-16-supplement-review.md` 中
   `## SOURCE-04-03 修复：FIX-01...` 段落出现 **2 次**（第 3 行、第 36 行）。

不影响代码/契约，但会让"提交号 → 内容"的追溯失真，建议后续引用以 `d7a4077` 为准。

---

## 六、结论

### 6.1 FIX-01

**PASS WITH FINDINGS**

| 项 | 结果 |
|---|---|
| H1 原缺陷（`IMPLEMENTED`/`DEGRADED` 跳过证据校验） | ✅ **已关闭**，六值 × 四态实测全部符合预期 |
| 契约（§3.2 / §3.2.1 / ADR-008） | ✅ 已修订且口径一致 |
| 新增发现 | F1-1（MEDIUM）、F1-2（MEDIUM）、F1-3（LOW）、F1-4（LOW） |
| BLOCKER / HIGH | 0 / 0 |

F1-1 与 F1-2 都属"契约沉默或不可达的格子默认放行"，与 H1 同一类，
但触发都要求 Python 侧违约（自查不可达），故不入 HIGH。

### 6.2 FIX-02

**PASS WITH FINDINGS**

| 项 | 结果 |
|---|---|
| M1（原因码 + 顺序 + `desiredError`） | ✅ 行为正确 |
| M2（重复拒绝 + 四类组合 + 不误认 L4） | ✅ 行为正确 |
| 新增发现 | F2-1（MEDIUM）、F2-2（LOW）、F2-3（LOW）、O1、O3 |
| BLOCKER / HIGH | 0 / 0 |

F2-1 是**同一份契约文档内的自相矛盾**，虽不影响当前行为，但会在 04-06 迁移时
把 M1 带回来。

### 6.3 SOURCE-04-03 整体

**PASS WITH FINDINGS**

| 门禁 | 要求 | 实际 |
|---|---|---|
| BLOCKER | 0 | ✅ 0 |
| HIGH | 0 | ✅ 0 |
| Java / Python 全量 | 全绿 | ✅ 253/253、76/76 |
| 三态（FRESH/STALE/UNAVAILABLE） | 正确 | ✅ 代码 + 18 条缓存测试；FIX-01 期间有运行时实测 |
| 未授权改动 | 无 | ✅ |
| H1 / M1 / M2 关闭 | 全部关闭 | ✅ 行为层面全部关闭 |

**建议的收尾顺序**（不阻断验收，但应在 04-06 之前完成）：

1. F2-1（契约自相矛盾）——**优先级最高**，它会在 04-06 直接误导实现；
2. F1-1 + F1-2（同一类 fail-open，一处 ~5 行，可同批）；
3. F2-3（补 `desiredError` 测试）；
4. F1-3 / F1-4 / F2-2 / O1 文档与注释类收尾。

---

## 七、交付与声明

| 项 | 内容 |
|---|---|
| FIX-01 评审对象 | `d6610f9`（基线 `c7c2c17`） |
| FIX-02 评审对象 | `d7a4077`（任务卡记为 `283096d`，实为记忆提交，见 O3） |
| 本轮新增 | 仅 `docs/architecture/collection/SOURCE-04-03-FIX-INDEPENDENT-REVIEW.md` |
| 工作区 | 评审开始时 `git status --short` 为空；结束时仅新增本报告 |
| 测试 | Java 253/253、Python 76/76、定向 74/74，均实际执行 |
| 环境 | 未重建、未重启、未改数据库；API 仅做只读 GET |

**未做的事**：未修改任何源码/测试/契约/迁移/前端；未修复本报告列出的任何问题
（按要求"只记录，不现场修复"）；未启动 04-06 / 04-07 / 04-08。
