# SOURCE-04-03-FIX-04 独立只读评审

> 评审性质：**只读**。未修改任何实现、测试、契约、ADR、迁移、前端；未修改数据库；
> 未重启或重建容器；未启动 04-06 / 04-07 / 04-08。唯一新增文件为本报告。
>
> 基线：`3c43f22`
> 评审对象：`a3b498c`（FIX-04 六项实施）
> 不计入：`97f48f1`（记忆文件提交，不属代码修复）
>
> 探针类编译在 `C:\tmp\probe3`（项目目录之外），未进版本控制。

---

## 一、O1：capabilities 镜像一致性

### 1.1 十项核对

| # | 检查项 | 结论 | 依据 |
|---|---|---|---|
| 1 | 两份来自同一次 `evaluate_all`？ | ✅ | `bootstrap.snapshot()` 只调一次 `evaluate_all()`，两个循环共用 |
| 2 | `specs_for` / `apply_evidence` / `to_v1` 路径一致？ | ✅ | 两处都是 `apply_evidence(entry.connector_code, s)` + `s.to_v1()` |
| 3 | 两侧能力字段是否存在不同语义？ | ⚠️ **部分** | 状态语义相同，但**校验只覆盖 `implementationStatus` 与能力码**，见 M1 |
| 4 | 同一真实 Connector 能力集合严格一致？ | ✅ | 双向比较（adapter 独有 / 文档独有均拒绝） |
| 5 | `implementationStatus` / `evidence` / 其他状态字段一致？ | ❌ **只有 `implementationStatus`** | 见 M1 |
| 6 | STUB 是否被正确排除？ | ✅ | `if (!a.platformScoped()) continue;` + 用例 `stubCapabilitiesExcludedFromMirror` |
| 7 | 是否可能跨 Connector 错误比较？ | ✅ 不会 | 按 `connectorCode` 建索引后逐一配对 |
| 8 | 不一致是否拒绝整份快照？ | ✅ | `Result.failure(BROKEN_CAPABILITY)` → `snapshot=null` |
| 9 | 是否存在交集 / 静默跳过 / first-wins？ | ✅ 无 | 双向全比较，无 `retainAll` 类操作 |
| 10 | 真实生产快照是否通过？ | ✅ | 运行时实测通过（见 §1.3） |

### 1.2 反向构造实测（8 组临时输入）

用真实快照样本做定点变异，逐组解析：

| 输入 | 期望 | 实际 |
|---|---|---|
| `01_baseline` 完全一致 | 接受 | **ACCEPT** ✅ |
| `02_status_mismatch` 文档侧状态降为 `UNVERIFIED` | 拒绝 | **REJECT** `BROKEN_CAPABILITY`，报文含 `adapters=VERIFIED，capabilities=UNVERIFIED` ✅ |
| **`03_evidence_mismatch`** 文档侧 `evidence.accepted=false`（状态相同） | **拒绝** | **ACCEPT** ❌ **M1** |
| **`04_accessmodes_mismatch`** 文档侧 `accessModes` 改为只有 `AUTHENTICATED` | **拒绝** | **ACCEPT** ❌ **M1** |
| **`05_function_mismatch`** 文档侧 `adapterFunction` 改为 `app.other.fn` | **拒绝** | **ACCEPT** ❌ **M1**（影响较弱，见下） |
| `06_second_connector_mismatch` 第二个 Connector 的 adapter 多一条能力 | 拒绝，且只报 X2 | **REJECT**，报文 `X2_PUBLIC/EXTRA …不在 capabilities[] 文档中` ✅ 未串到 DOUYIN |
| `07_second_connector_consistent` 两个 Connector 各自一致 | 接受 | **ACCEPT** ✅ |
| `08_stub_extra_cap` STUB 带一条额外能力 | 接受（不参与比较） | **ACCEPT** ✅ |

### 1.3 真实生产快照（只读 GET，未改环境）

```
GET /api/v1/source/sources
snapshotState: FRESH | desiredError: null
COMMENT        -> AVAILABLE   / NONE         exec=true
CONTENT_DETAIL -> AVAILABLE   / NONE         exec=true
KEYWORD_SEARCH -> UNREGISTERED/ NOT_DECLARED exec=false
COMMENT_REPLY  -> UNREGISTERED/ NOT_DECLARED exec=false

真实快照逐字段比对：
  STUB(跳过): STUB_PUBLIC
  DOUYIN_PUBLIC: code=True status=True evidence=True accessModes=True
```

真实载荷四项字段本来就一致，所以当前**没有误杀**；问题在于校验不覆盖后三项。

---

### M1（MEDIUM · 校验范围不足）：镜像只比能力码与 `implementationStatus`

**位置**：`backend/src/main/java/com/coolpilot/insight/integration/capability/RegistrySnapshotParser.java:335-350`

```java
fromAdapter.put(c.code(), c.implementationStatus());   // :335
fromDoc.put(c.code(), c.implementationStatus());       // :339
...
if (!java.util.Objects.equals(e.getValue(), docStatus)) { ... }   // :346-350
```

**问题**：镜像键只有 `code`，值只有 `implementationStatus`。
`evidence`、`accessModes`、`adapterFunction` 全部不比较。

**为什么这是问题**（不只是"可以更严"）：

- `evidence.accepted` 直接决定第 ⑮~⑰ 步（证据闸门），是**判定依据**；
- `accessModes` 直接决定第 ⑭ 步（访问模式覆盖）；
- 这两者一旦两份不一致，Java 用文档侧算结论、用 adapter 侧枚举候选，
  正是本次校验想消除的"用 A 的清单 + B 的状态"，只是换成了"用 A 的清单 + B 的证据"。

**触发条件**：Python 侧两处派生出现任何差异（同一次评估下不会发生，但校验的全部意义
就是捕获"不该发生却发生了"）；或未来有人在某一个循环里加了分支。

**违反的契约**：用户裁决 §1「不应只比较能力码，还应检查参与状态判断的字段是否一致」，
以及本任务卡 §3 要求的测试项「关键证据字段不一致：拒绝」。

**可复现证据**：§1.2 中 `03` / `04` / `05` 三组均为 `ACCEPT`。

**修复建议**（按影响排序）：

1. 必加：`evidence.accepted`（两侧都为 null 才算相同）；`accepted=false` 时再比 `rejectionReason`；
2. 必加：`accessModes` 集合相等（顺序不敏感）；
3. 可选：`contractVersion`、`adapterFunction`（后者不参与判定，但差异说明两份已不同源）。

**是否阻断验收**：不阻断（真实载荷一致、文档侧仍受完整校验、推导结论不错误）。
但这是"防分叉"机制的**主要漏洞**——校验建好了却漏掉两个判定字段，建议在最终验收前一并补。

---

### OBS1（OBSERVATION · 文档侧重复 connectorCode 会被静默覆盖）

**位置**：`RegistrySnapshotParser.java:318-322`

```java
for (CapabilityDocument d : documents) {
    if (d.connectorCode() != null) byConnector.put(d.connectorCode(), d);   // 后者覆盖前者
}
```

若 `capabilities[]` 里出现两条同 `connectorCode` 的文档，索引只保留最后一条，
前面那条的镜像关系不会被检查。当前 Python 不会产生重复文档，风险极低；
但"重复即覆盖"与项目其它地方的"重复即拒绝"（Desired v2 重复能力）不一致。

**建议**：改为检测到重复 connectorCode 即 `BROKEN_CAPABILITY`。非阻断。

---

## 二、F1-3：`DEGRADED` 准入

### 2.1 六项核对

| # | 检查项 | 结论 | 证据 |
|---|---|---|---|
| 1 | `allowsExecution` 是否检查 reason | ✅ | `EffectiveStatus:53-68`，`DEGRADED` 分支要求 `reason == DEGRADED` |
| 2 | 有效证据是否允许 DEGRADED 执行 | ✅ | 探针 + `degradedEvidenceMatrix`：`DEGRADED / DEGRADED / true` |
| 3 | 缺失 / 过期 / 无效是否全部禁止 | ✅ | 三者均 `UNKNOWN` + 对应 reason + `false` |
| 4 | 其他 Reason + `executable=true` 是否构造期拒绝 | ✅ | 构造即抛 `IllegalArgumentException` |
| 5 | `DEGRADED` + 其他 Reason + `executable=false` 是否允许 | ✅ 允许 | 探针：构造成功，`executable=false` |
| 6 | Reconciler / 构造器 / ADR-008 / 测试是否同一规则 | ✅ | 见 §2.3 |

### 2.2 完整矩阵（探针：`DEGRADED` × 全部 21 个 reason）

```
DEGRADED / DEGRADED                   -> executable=true     ← 仅此一行
（其余 20 个 reason 全部为 false）
```

`AVAILABLE` 侧同样正确：`AVAILABLE/NONE → true`；`AVAILABLE/DEGRADED`、`AVAILABLE/EVIDENCE_INVALID` → `false`。

构造期：

```
DEGRADED + EVIDENCE_INVALID + executable=true   -> 抛 IllegalArgumentException ✅
DEGRADED + EVIDENCE_INVALID + executable=false  -> 构造成功，executable=false ✅（允许保留诊断）
```

### 2.3 四处口径一致

| 位置 | 规则 |
|---|---|
| `EffectiveStatus.allowsExecution` | `(AVAILABLE && reason==NONE) \|\| (DEGRADED && reason==DEGRADED)` |
| `EffectiveCapability` 构造期 | 直接复用上者，`executable != allowed` 即抛 |
| `CapabilityReconciler` | 证据闸门在 ⑮~⑰ 步，`DEGRADED` 只可能带已接受证据 |
| `ADR-008` §4.3 | 示例已改为两个分支都要求 reason，并注明"松的是 ADR 的示例" |
| 测试 | `n75WhitelistOnly`（5 个非法 reason 逐个断言）+ `degradedEvidenceMatrix`（四态）+ `degradedWithBadReasonCannotBeConstructedExecutable` |

---

### L1（LOW · 状态一致性校验不完整）：`AVAILABLE` + 非 `NONE` reason 可构造

**位置**：`backend/src/main/java/com/coolpilot/insight/source/domain/EffectiveCapability.java:35-48`

构造器只校验三件事：`executable == allowsExecution(status, reason)`、`status ∈ ALL`、
`reason ∈ ALL`。**没有校验 status 与 reason 的语义配对**。

**实测**：

```
new EffectiveCapability("C","COMMENT","AVAILABLE","EVIDENCE_INVALID", false, "标称可用但证据无效")
-> 构造成功，executable=false
```

即：一条记录可以同时声称"可用"和"证据无效"。它不可执行，所以没有执行风险；
但把它展示给运维会自相矛盾（`effectiveStatus=AVAILABLE` 却说证据无效）。

**违反的契约**：用户裁决 §2「不能仅因为 status=DEGRADED 就放行，
也不能因为 `executable=false` 就跳过其他必要的状态一致性校验」。
当前正是后者：`executable=false` 时，除枚举合法性外没有别的校验。

**是否阻断**：不阻断（`Reconciler` 不可能产出该组合，只有手工构造才可能）。
**建议**：加一张 `status → 允许的 reason 集合` 的配对表（白名单），
或在构造器里显式禁止 `AVAILABLE` 搭配非 `NONE`。约 10 行 + 1 条用例。

---

## 三、F2-3：`desiredError` 装配

### 3.1 四项覆盖（`SourceQueryServiceTest`，4/4 通过）

| 场景 | 断言 | 位置 |
|---|---|---|
| **A** Desired 正常 + Registry 正常 | `desiredError=null`；`snapshotState=FRESH`；`desired` 3 条；旧 `capability` 仍在；`COMMENT` 可执行 | `:55` |
| **B** Desired 坏 + Registry 正常 | `desiredError != null`（含 `3.0`）；`desired` 为空；`effective` 每条 `UNKNOWN` / `DESIRED_UNPARSEABLE` / 不可执行 | `:78` |
| **C** Desired 坏 + Registry 不可达 | `desiredError != null` **且** `snapshotState=UNAVAILABLE`、`snapshotDetail` 非空 | `:105` |
| **D** 双故障域 | `effective` 为空；无任何可执行能力 | `:120` |

**是否使用真实解析器与真实装配服务**：✅
`healthyCache()` 走真实 `RegistrySnapshotParser` + 真实样本文件；
`single()` 构造真实 `SourceQueryService`（真实 `CapabilityReconciler`），
只替换 `DataSourceMapper`（记录型假实现，非 mock 框架）与
`AccountAvailability`（lambda）。**不是**只测私有方法或手工拼 `DataSourceView`。

### 3.2 与修正后场景定义的一致性

用户在裁决中已把场景 B 修正为"Desired 声明为空、Effective 产生 `UNKNOWN/DESIRED_UNPARSEABLE`"，
实现与测试完全符合，无偏差。

---

### OBS2（OBSERVATION · C 用例未直接断言 Effective 为空）

`bothFaultDomainsRemainVisible`（C）只断言 `desiredError` 与 `snapshotState`；
"`effective` 为空"由 D 用例（`noFabricatedCapabilitiesWhenBothBroken`）用同一夹具断言。

当前覆盖是完整的（同一输入，两个用例合起来覆盖 C+D 全部要求），
但如果将来有人拆改夹具使两条用例用不同输入，C 的"列表为空"就会失去保护。
**建议**：C 用例补一行 `assertThat(view.getEffective()).isEmpty()`。非阻断。

---

## 四、O2 / O3：文档一致性

全文检索五个文件（`SOURCE-04-04-TASKCARD` / `EFFECTIVE-REASON` / `IMPLEMENTATION` /
`ADR-008` / `FIX04-REPORT`），逐项结果：

| 检索项 | 结果 |
|---|---|
| 旧 `SCHEMA_UNSUPPORTED` 映射（Desired） | ✅ 仅剩 3 处，全部是"历史/否定"语境（"在此之前…被映射为"、"语义是 Python 快照"、"修订前"） |
| 旧的"仅 `UNVERIFIED`/`VERIFIED` 校验证据" | ✅ 仅 1 处，明确写作"原第 14~16 行把证据校验写成…"（描述旧缺陷） |
| 旧 19 步编号 | ✅ 仅 2 处历史说明（"步号仍是 19 步"、"19 步短路→20 步"） |
| Reason 计数（20/21、19/20 可达） | ✅ 正式描述均为 21 值 / 20 可达；`IMPLEMENTATION:45/132` 已同步 |
| 旧术语"数据自相矛盾" | ✅ 正式契约已改；仅 `Reconciler:387` 注释在解释"不用这个说法"，属正确用法 |

`IMPLEMENTATION.md` 的判定表已重排为 ①~⑳，Desired 行位于第 ⑤ 步且写明
"不是 `SCHEMA_UNSUPPORTED`"；测试对照表 21 行，含 `DESIRED_UNPARSEABLE`。

### OBS3（OBSERVATION · 一处历史裁决记录仍写"20 值"）

`SOURCE-04-04-TASKCARD.md:619`：`| 6 | effectiveReason 20 值是否精简 | 暂不精简 …|`。
这是**当时评审的裁决记录**，语境正确；但与现行 21 值并读容易被误认为现状。
建议加"（现为 21 值）"括注。非阻断。

---

## 五、测试执行（实际运行）

```
Java 全量                 269/269   (Failures 0, Errors 0, Skipped 0, BUILD SUCCESS)
Python 全量                76/76    (registry 23 + server 17 + evidence 16 + snapshot 20)
RegistrySnapshotParserTest  30/30
CapabilityReconcilerTest    47/47
SourceQueryServiceTest       4/4
定向合计                    81/81
容器                        5/5 healthy（只读查看，未重启）
```

**证据性质区分**：

| 证据 | 性质 |
|---|---|
| 269 / 76 / 81 | 正式自动化测试 |
| 镜像 8 组输入 | **临时探针**（`C:\tmp\probe3`，未入版本控制） |
| `DEGRADED × 21 reason` 全表 | **临时探针**（正式测试只覆盖四态 + 5 个非法 reason） |
| API 与真实快照 | **真实运行时**（只读 GET，未重建环境） |

---

## 六、结论

### 分项

| 分项 | 结论 | BLOCKER | HIGH | MEDIUM | LOW | OBS |
|---|---|---|---|---|---|---|
| O1 镜像一致性 | **PASS WITH FINDINGS** | 0 | 0 | 1（M1） | 0 | 1 |
| F1-3 DEGRADED 准入 | **PASS WITH FINDINGS** | 0 | 0 | 0 | 1（L1） | 0 |
| F2-3 desiredError | **PASS** | 0 | 0 | 0 | 0 | 1 |
| O2 / O3 文档 | **PASS** | 0 | 0 | 0 | 0 | 1 |

### FIX-04 整体：**PASS WITH FINDINGS**

六项全部落地，无 BLOCKER / HIGH。已确认成立的关键点：

- O1：同源事实成立；双向比较、按 connector 配对、STUB 排除、拒绝整份快照、
  无交集/静默跳过；真实生产快照通过。
- F1-3：reason 级准入生效（`DEGRADED` × 21 reason 只有 `DEGRADED` 可执行），
  构造期不变量与 ADR、测试口径一致。
- F2-3：四个场景全部用真实解析器 + 真实装配服务覆盖。
- O2 / O3：正式文档无相互矛盾的旧表述。

### 遗留（不阻断，但需登记）

| 编号 | 等级 | 内容 | 建议时点 |
|---|---|---|---|
| **M1** | MEDIUM | 镜像只比码集 + `implementationStatus`，`evidence` / `accessModes` / `adapterFunction` 未比较 | 最终验收前补（约 15 行 + 2 条用例） |
| **L1** | LOW | `AVAILABLE` + 非 `NONE` reason + `executable=false` 可构造，status/reason 配对未校验 | 可与 M1 同批 |
| OBS1 | OBSERVATION | 文档侧重复 `connectorCode` 静默覆盖 | 随手 |
| OBS2 | OBSERVATION | C 用例未直接断言 `effective` 为空（D 覆盖同夹具） | 随手 |
| OBS3 | OBSERVATION | `04-04:619` 历史裁决记录仍写"20 值" | 随手 |

**关于 M1 的定位**：它不是"实现做错了"，而是"防分叉机制漏了两个判定字段"。
当前不产生错误结论（真实载荷一致、文档侧完整校验、推导只用文档侧），
所以不判 HIGH；但校验建好却漏掉 `evidence`，等于在最需要它的地方留了缺口。

### 与 L1~L5 的关系

L1~L5（首轮评审的 LOW）**未在本轮范围内，也未因本报告关闭**，
仍须单独审查。本报告不改变它们的开放状态。

---

## 七、只读声明

| 项 | 内容 |
|---|---|
| 评审基线 | `3c43f22` |
| 评审对象 | `a3b498c` |
| 本轮新增 | 仅 `docs/architecture/collection/SOURCE-04-03-FIX04-REVIEW.md` |
| 源码改动 | 无（评审期间 `git diff` 为空） |
| 数据库 / Flyway | 未动（仍 V30） |
| 环境 | 未重建、未重启；API 仅只读 GET |
| 04-06 / 04-07 / 04-08 | 未启动 |
