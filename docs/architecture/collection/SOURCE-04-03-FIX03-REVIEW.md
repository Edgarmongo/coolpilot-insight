# SOURCE-04-03-FIX-03 独立只读评审

> 评审性质：**只读**。评审期间未修改任何源码、测试、契约、迁移、前端；
> 未修改数据库；未启动 04-06 / 04-07 / 04-08。唯一新增文件为本报告。
>
> 基线：`d19444d`（FIX-03 实施前）
> 评审对象：`36eeb87`（FIX-03 主体）＋ `e7f08f6`（`unverifiedDetail` 文案细化，按评审建议追加）

---

## 一、对象与范围

| 提交 | 内容 | 是否在本次评审范围 |
|---|---|---|
| `36eeb87` | 双层枚举校验 + 白名单守卫 + §2.7 契约统一 | ✅ |
| `e7f08f6` | `unverifiedDetail` 改为点明冲突字段（用户指令） | ✅ 一并核对（改的是诊断文本，判定逻辑未动） |

`d19444d..e7f08f6` 共 8 个文件、+428/−11：

```
RegistrySnapshotParser.java      +29   枚举严格校验 + 两个辅助
CapabilityReconciler.java        +36    守卫白名单化 + unverifiedDetail
RegistrySnapshotParserTest.java  +68    4 条
CapabilityReconcilerTest.java    +32    2 条
SOURCE-04-04-TASKCARD.md         +10    §2.7 / §3.2.1 / 铁律 4
SOURCE-04-04-EFFECTIVE-REASON.md +4     第 16 行条件
SOURCE-04-03-FIX03-REPORT.md     +236   实施报告
（记忆文件去重为独立提交，不在代码范围）
```

未出现 `frontend/`、`db/migration/`、`SOURCE-02-capability-schema.json` 改动。

---

## 二、七项重点验证

### ① 非法值与缺失值是否在解析层拒绝**整份快照**

**✅ 成立。**

`RegistrySnapshotParser:228-244`：

```java
if (!CapabilityStatus.isKnown(item.implementationStatus())) {
    ... return Result.failure(Failure.BROKEN_CAPABILITY, ...);
}
```

`Result.failure` 的实现（`:87-89`）返回 `new Result(null, error)` —— **snapshot 为 null**，
即整份快照不可用，不是"跳过这一条继续"。这点很关键：跳过会让能力静默消失。

实测（探针，真实 `RegistrySnapshotParser`）：

```
PARTIAL（非法）  + accepted  -> 解析失败: BROKEN_CAPABILITY
字段缺失（非法）  + accepted  -> 解析失败: BROKEN_CAPABILITY
小写 verified     + accepted  -> BROKEN_CAPABILITY（不做大小写归一）
```

报错内容含能力码、非法值、以及排序后的合法值清单（可诊断）。
正式用例 4 条：`rejectsUnknownImplementationStatus` / `rejectsMissingImplementationStatus` /
`rejectsWrongCaseImplementationStatus` / `acceptsAllSixImplementationStatuses`，
`RegistrySnapshotParserTest` **26/26 通过**。

### ② 绕过解析器直接构造对象时，核对层是否仍 fail-closed

**✅ 成立，且测试确实走了这条路径。**

`CapabilityReconciler:265-268`：

```java
if (!CapabilityStatus.VERIFIED.equals(impl) && !CapabilityStatus.DEGRADED.equals(impl)) {
    return UNKNOWN + VERIFICATION_REQUIRED + unverifiedDetail(impl);
}
```

`unknownImplementationStatusNeverAvailable` 用 `cap("COMMENT","PARTIAL", goodEvidence())`
**直接构造** `CapabilityItem`（不经解析器），断言 `UNKNOWN` / `executable=false` /
detail 含"不在契约枚举内"与 `PARTIAL`。探针侧同样验证：绕过解析后不会落 `AVAILABLE`。

### ③ `UNVERIFIED + accepted evidence` 是否稳定返回三元组

**✅ 稳定成立**，且文案已按建议细化：

```
UNVERIFIED + accepted -> UNKNOWN / VERIFICATION_REQUIRED / executable=false
detail: 实现状态与证据状态不一致：implementationStatus=UNVERIFIED，
        但 evidence.accepted=true。证据已被接受，但实现状态仍未标记为 VERIFIED
```

用例 `unverifiedWithAcceptedEvidenceIsNotAvailable` 断言两个**字段名**与"不一致"，
不再断言"自相矛盾"这种措辞——措辞变了测试不会误红，但字段名变了会红。

**关于"detail 不得成为机器判据"**：已全文核对 `detail()` 的使用点，
只有消息拼接与展示（`CapabilityReconciler:112/118/204`、`SourceQueryService:101`），
**没有任何 `if (detail.contains(...))` 式分支**。判据仍是 `(status, reason, executable)`。

### ④ `VERIFIED + 有效证据` 是否继续正常准入

**✅ 成立，且是唯一能到 `AVAILABLE` 的路径。**

- 探针：`VERIFIED + accepted -> AVAILABLE / NONE / executable=true`
- 运行时：真实 API 返回 `COMMENT`、`CONTENT_DETAIL` 均为 `AVAILABLE`（见 ⑦）
- `DEGRADED + accepted -> DEGRADED / DEGRADED / true`（唯一非 `AVAILABLE` 的可执行分支）

### ⑤ 六值枚举是否与 SOURCE-02 一致

**✅ 逐值相同**（机器比对，非目测）：

```
Schema 六值 : ['DEGRADED','DISABLED','IMPLEMENTED','UNSUPPORTED','UNVERIFIED','VERIFIED']
Java  六值  : ['DEGRADED','DISABLED','IMPLEMENTED','UNSUPPORTED','UNVERIFIED','VERIFIED']
完全一致    : True      仅在 Schema: set()    仅在 Java: set()
```

`SOURCE-02-capability-schema.json` 的 `$defs.capability.properties.implementationStatus.enum`
与 `CapabilityStatus` 常量集合零差异。本次修复**未改 Schema**（`git status` 确认）。

### ⑥ §2.7 / §3.2.1 / EFFECTIVE-REASON 是否完全一致

**✅ 三处已收敛到同一口径。**

| 位置 | 现状 |
|---|---|
| `04-04` §2.7 `:273` | `UNKNOWN` + **`DESIRED_UNPARSEABLE`**，并显式注明"不是 `SCHEMA_UNSUPPORTED`" |
| `04-04` §3.2 第 4 行 `:303` | 同一个 reason，位置在第 4 位（快照判定之后） |
| `04-04` §3.2.1 `:342` | `UNVERIFIED + 接受` → "→ `UNKNOWN` + `VERIFICATION_REQUIRED`（代码已强制执行）" |
| `04-04` §3.2.1 `:348` | 新增行：不在六值内 / 缺失 / `null` → 解析阶段拒绝 |
| `04-04` §3.2.1 铁律 4 | 非法状态不得进入核对；解析层拒绝 + 核对层兜底 |
| `EFFECTIVE-REASON` `:57` | 第 16 行条件补"即便 `accepted=true` 也归此" |

全文检索"以肯定语气把 Desired 故障说成 `SCHEMA_UNSUPPORTED`"的残留：
仅剩两类——历史评审/实施报告里的**引用**（原文引用旧状态，属正确用法），
以及 `SOURCE-04-03-IMPLEMENTATION.md:81`（见 §五 O2）。

### ⑦ 合法生产快照是否没有回归

**✅ 无回归。**

真实 API（容器内真实数据，只读 GET）：

```
snapshotState: FRESH | desiredError: null
COMMENT        -> AVAILABLE   / NONE          exec=true
CONTENT_DETAIL -> AVAILABLE   / NONE          exec=true
KEYWORD_SEARCH -> UNREGISTERED/ NOT_DECLARED  exec=false
COMMENT_REPLY  -> UNREGISTERED/ NOT_DECLARED  exec=false
```

真实快照往返（`/v1/registry/snapshot`）：`schema 1.1 / contract 1.0`，
四条能力状态为 `VERIFIED ×3 + UNVERIFIED ×1`，全部合法，通过新校验。

---

## 三、六值 × 四态全矩阵复测（探针实跑）

| `implementationStatus` | 接受 | 缺失 | 篡改 | 过期 |
|---|---|---|---|---|
| `UNVERIFIED` | `UNKNOWN`/`VERIF_REQ`/false | `UNKNOWN`/`VERIF_REQ`/false | `UNKNOWN`/`EV_INVALID`/false | `UNKNOWN`/`EV_EXPIRED`/false |
| `IMPLEMENTED` | `UNKNOWN`/`VERIF_REQ`/false | `UNKNOWN`/`VERIF_REQ`/false | `UNKNOWN`/`EV_INVALID`/false | `UNKNOWN`/`EV_EXPIRED`/false |
| `VERIFIED` | **`AVAILABLE`/`NONE`/true** | `UNKNOWN`/`VERIF_REQ`/false | `UNKNOWN`/`EV_INVALID`/false | `UNKNOWN`/`EV_EXPIRED`/false |
| `DEGRADED` | **`DEGRADED`/`DEGRADED`/true** | `UNKNOWN`/`VERIF_REQ`/false | `UNKNOWN`/`EV_INVALID`/false | `UNKNOWN`/`EV_EXPIRED`/false |
| `DISABLED` | `UNREGISTERED`/`CAPABILITY_DISABLED`/false（四种证据态一致） | | | |
| `UNSUPPORTED` | `UNREGISTERED`/`PLATFORM_UNSUPPORTED`/false（同上） | | | |
| **非法值 / 缺失** | **解析失败 `BROKEN_CAPABILITY`（四种证据态一致）** | | | |

与 FIX-01 之后的版本相比，只有两处变化，且都是本次目标：
`UNVERIFIED + 接受` 由 `AVAILABLE` 改为不可执行；非法值由 `AVAILABLE` 改为解析拒绝。
其余 22 格完全未动——说明改动没有溢出到既有判定。

---

## 四、测试执行（实际运行）

```
Java 全量        259/259   (Failures 0, Errors 0, Skipped 0, BUILD SUCCESS)
Python 全量       76/76    (registry 23 + server 17 + evidence 16 + snapshot 20)
RegistrySnapshotParserTest   26/26
CapabilityReconcilerTest     45/45
容器              5/5 healthy
```

FIX-03 新增 6 条全部实际执行通过。测试数量与实施报告一致（259）。
本轮无 Python 改动，76/76 为回归确认。

---

## 五、新发现问题

### O1（OBSERVATION · 校验覆盖不一致）

**位置**：`RegistrySnapshotParser:193-196`（`adapters[].capabilities` 只解析、不校验）
vs `:222`（`documents[].capabilities` 校验）

同一份 `implementationStatus` 在快照里**出现两次**：`capabilities[].capabilities[]` 与
`adapters[].capabilities[]`。新校验只覆盖前者。运行时实测确认后者确实带状态：

```
adapter 内嵌: [('STUB_PUBLIC', []), ('DOUYIN_PUBLIC', ['VERIFIED','VERIFIED','VERIFIED','UNVERIFIED'])]
```

**为什么仍判 OBSERVATION 而非缺陷**：核对器的判定走 `findObserved()` → 文档侧（已校验）；
候选集合虽从 adapter 侧取（`Reconciler:308-310`），但只取 `connectorCode + code + category`，
`implementationStatus` 不参与。若某能力只存在于 adapter 侧，`observed == null` →
`CAPABILITY_NOT_REGISTERED`。**当前不构成绕过路径。**

**建议（不阻断）**：要么对 adapter 内嵌能力一并校验，要么在解析时断言两侧一致。
两份副本迟早会分叉，届时"校验了其中一份"会给人虚假的安全感。

### O2（LOW · 旧实施文档仍描述旧映射，此前评审 F2-2 未关闭）

**位置**：`docs/architecture/collection/SOURCE-04-03-IMPLEMENTATION.md:81`

```
| — | Desired 不可解析 | `UNKNOWN` | `SCHEMA_UNSUPPORTED` |
```

该表仍写着旧 reason，且位置画在 ② 之前——正是 M1 的缺陷形态。
FIX-02 只修了 `04-04` §2.7，FIX-03 只修了 §2.7/§3.2.1，**这份实施文档没有列入任何一轮范围**。

**影响**：`SOURCE-04-03-IMPLEMENTATION.md` 是 04-03 的实施说明，读者按它理解会得到错误认知。
不阻断（行为已正确），建议并入 FIX-04 一起收尾。

### O3（LOW · 措辞残留）

**位置**：`docs/architecture/collection/SOURCE-04-04-EFFECTIVE-REASON.md:57`

第 16 行标题仍写"含**数据自相矛盾**的情形"，而代码与报告已改为"字段冲突"的精确表述。
语义不矛盾，但同一事实两套说法。一行改动，建议顺手统一。

---

## 六、结论

### FIX-03：**PASS WITH FINDINGS**

| 门禁 | 要求 | 实际 |
|---|---|---|
| BLOCKER | 0 | ✅ 0 |
| HIGH | 0 | ✅ 0 |
| MEDIUM | 0 | ✅ 0 |
| 七项重点验证 | 全部成立 | ✅ 7/7 |
| Java / Python 全量 | 全绿 | ✅ 259/259、76/76 |
| 生产快照回归 | 无 | ✅ 实测 |

| 发现 | 等级 | 是否阻断 |
|---|---|---|
| O1 校验只覆盖文档侧，adapter 侧同名字段未校验 | OBSERVATION | 否（不构成绕过路径） |
| O2 `IMPLEMENTATION.md:81` 仍写旧 reason | LOW | 否（建议并入 FIX-04） |
| O3 `EFFECTIVE-REASON:57` 措辞未统一 | LOW | 否 |

**FIX-03 的三项 P1 全部关闭**：

- **F1-2**：非法/缺失值在解析层拒绝整份快照（实测 + 4 条正式用例），核对层另有兜底（绕过解析器仍不可执行）。
- **F1-1**：`UNVERIFIED + 已接受证据` 稳定返回 `UNKNOWN` / `VERIFICATION_REQUIRED` / `false`，detail 点明两个冲突字段。
- **F2-1**：`§2.7`、`§3.2 第 4 行`、`§3.2.1`、`EFFECTIVE-REASON` 四处口径一致。

### 对 FIX-04 的输入

按授权边界，本轮**未触碰** F1-3 / F1-4 / F2-3，也未启动 04-06。
建议 FIX-04 范围在原三项基础上并入 O2、O3（都是文档一行级），O1 是否处理由项目决定：

| 项 | 内容 |
|---|---|
| F1-3 | `allowsExecution(DEGRADED, *)` 忽略 reason；ADR-008 §4.3 示例需同步收紧 |
| F1-4 | `CapabilityReconciler:101/149` 注释步骤范围（应为 1~5 / 6~20） |
| F2-3 | `desiredError` 填充路径无测试（含 O1 中的"双故障域"断言） |
| O2 | `IMPLEMENTATION.md:81` 旧 reason |
| O3 | `EFFECTIVE-REASON:57` 措辞 |

---

## 七、只读声明与工作区证明

| 项 | 内容 |
|---|---|
| 评审基线 | `d19444d` |
| 评审对象 | `36eeb87`（主体）、`e7f08f6`（文案细化） |
| 本轮新增 | 仅 `docs/architecture/collection/SOURCE-04-03-FIX03-REVIEW.md` |
| 源码改动 | 无（评审期间 `git diff` 为空） |
| 数据库 / Flyway | 未动（仍 V30） |
| 环境 | 未重启（评审在 `e7f08f6` 重建后的容器上做只读 GET） |
| 04-06 / 04-07 / 04-08 | 未启动 |

> `e7f08f6` 的文案细化是在本评审开始**之前**、按用户指令完成的实现动作，
> 不属于评审期间的修改；本报告已将其纳入评审范围一并核对。
