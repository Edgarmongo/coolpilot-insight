# SOURCE-04-03-FIX-01 · IMPLEMENTED / DEGRADED 证据准入修复

> 触发：`SOURCE-04-03-CODE-REVIEW.md`（`edcb034`）判 FAIL，H1 为 HIGH。
> 用户裁决：采用**方案 A**（`IMPLEMENTED` 归入 `VERIFICATION_REQUIRED`），
> 并要求 `DEGRADED` 单独审查，不得只修一处。
>
> 基线：`4e534a8`（04-03 实施）
> 本任务范围：**只修 H1**。M1 / M2 属 `SOURCE-04-03-FIX-02`，本任务未触碰。
>
> **阅读注意**：本文中的步骤号（"第 14~16 步"等）以 **FIX-01 当时的 20 步表**为准。
> `FIX-02` 在判定链第 4 位插入了 `DESIRED_UNPARSEABLE`，其后所有步骤号 +1；
> 当前权威编号见 `SOURCE-04-04-TASKCARD.md` §3.2 与
> `SOURCE-04-04-EFFECTIVE-REASON.md` §四。

---

## 一、缺陷回顾（一句话）

> `CapabilityReconciler` 第 14~16 步的证据校验只在
> `implementationStatus ∈ {UNVERIFIED, VERIFIED}` 时执行，
> 于是 `IMPLEMENTED` 与 `DEGRADED` **整段跳过证据校验**，
> 连 `evidence.accepted=false` 都会被放行到 `AVAILABLE` / 可执行。

这不是"漏了一个边界"，而是**准入推导没有穷举 Schema 允许的取值**。
`implementationStatus` 是六值枚举（`CapabilityStatus.ALL`），
只处理常见两值、其余"默认落到最后一步"，等于给未列举的值开了一条通往
`AVAILABLE` 的暗门。

**契约侧同源**：`SOURCE-04-04-TASKCARD.md` §3.2 原第 19 行写作
`∈ {VERIFIED, IMPLEMENTED}` → `AVAILABLE`，把"本地测试通过"与
"真实平台运行验证"当成了同一件事。所以本次**先改契约，再改代码**
——否则改了代码也与文档冲突。

---

## 二、契约修订（3 个文件）

### 2.1 `SOURCE-04-04-TASKCARD.md` §3.2 判定表

| 行 | 修订前 | 修订后 |
|---|---|---|
| 14 | `implementationStatus=UNVERIFIED` → `VERIFICATION_REQUIRED` | `∈ {UNVERIFIED, IMPLEMENTED}` → `VERIFICATION_REQUIRED` |
| 15 | `UNVERIFIED` + 过期 | **任意状态** + 过期 |
| 16 | `UNVERIFIED` + 无效 | **任意状态** + 无效 |
| 18 | `DEGRADED` → 降级执行 | `DEGRADED` **且持有已接受证据** → 降级执行 |
| 19 | `∈ {VERIFIED, IMPLEMENTED}` 且全通过 → `AVAILABLE` | **`VERIFIED` 且证据已接受** 且全通过 → `AVAILABLE` |

### 2.2 新增 §3.2.1「实现状态 × 证据矩阵」

这是本次修订的核心交付：把**六值 × 三态证据**（接受 / 缺失 / 被拒）共 18 格
全部写死，没有一格留白。

| `implementationStatus` | 证据接受 | 证据缺失 | 证据被拒 |
|---|---|---|---|
| `UNVERIFIED` | 不可能（接受即升 VERIFIED），按不可执行 | `VERIFICATION_REQUIRED` | `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` |
| `IMPLEMENTED` | **`VERIFICATION_REQUIRED`** | `VERIFICATION_REQUIRED` | `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` |
| `VERIFIED` | 继续 17~19 步 | `VERIFICATION_REQUIRED`（协议异常） | `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` |
| `DEGRADED` | `DEGRADED`（降级执行） | `VERIFICATION_REQUIRED` | `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` |
| `DISABLED` / `UNSUPPORTED` | 第 11/12 步已返回，不进矩阵 | 同左 | 同左 |

配套三条铁律（写入文档，作为后续改动的判据）：

1. 证据闸门对**所有未被第 11/12 步拦下的状态一律生效**，不存在豁免。
2. `IMPLEMENTED` **永不产出** `AVAILABLE`。
3. `evidence.accepted=false` **一律不可执行**，与状态无关。

### 2.3 `SOURCE-04-TASKCARD.md` 16 行表同步 + `ADR-008` §4.3.1

- 04-03 卡片里的 16 行推导表同步为同一口径，并加「注意 4」记录本次修订。
- ADR-008 新增 §4.3.1，说明「白名单解决的是结论能否执行，证据闸门解决的是
  结论本身怎么得出」——首版把这两件事混在一起，才让错误结论进了白名单。

> **为什么把一般性教训也写进 ADR**：黑名单漏新状态、穷举不全漏旧状态，
> 是同一个错误的两个面。前者已经被 §4.3 写死，后者这次才暴露。

---

## 三、代码修复

**文件**：`backend/src/main/java/com/coolpilot/insight/source/application/CapabilityReconciler.java`

### 3.1 证据闸门改为无条件生效

```java
// 修订前
if ("UNVERIFIED".equals(impl) || "VERIFIED".equals(impl)) {
    ...校验证据...
}

// 修订后
CapabilityItem.Evidence ev = observed.evidence();
if (ev == null) {
    return UNKNOWN + VERIFICATION_REQUIRED + missingEvidenceDetail(impl);
}
if (!Boolean.TRUE.equals(ev.accepted())) {
    return UNKNOWN + (EXPIRED ? EVIDENCE_EXPIRED : EVIDENCE_INVALID);
}
if (CapabilityStatus.IMPLEMENTED.equals(impl)) {
    // 证据有效，但实现只到"本地测试通过"
    return UNKNOWN + VERIFICATION_REQUIRED;
}
```

三处关键决定：

1. **去掉外层 `if`**，而不是往条件里再补两个值。补值只能解决今天，
   下次新增状态还会漏——守卫本身就不该按状态分支。
2. **`IMPLEMENTED` 的判定放在证据校验之后**。这样"证据被拒"会先给出
   `EVIDENCE_INVALID`（更精确），只有证据合格时才落到 `VERIFICATION_REQUIRED`。
   顺序反了会把"证据被篡改"误报成"从未验证"，排查方向就错了。
3. **`missingEvidenceDetail(impl)` 区分三种缺失语境**：`VERIFIED` 缺证据是
   **协议异常**（Python 侧证据接受才升 VERIFIED），`DEGRADED` 缺证据是
   "未经验证不得降级执行"，其余是"从未验证"。三者 status/reason 相同，
   但 detail 必须不同，否则排障时无法区分。

### 3.2 未改的部分（有意为之）

| 项 | 为什么不动 |
|---|---|
| `EffectiveStatus` 八值 | 方案 A 明确不扩充枚举 |
| `EffectiveReason` 20 值 | 复用 `VERIFICATION_REQUIRED`，不新造 `IMPLEMENTED_UNVERIFIED` |
| 判定顺序 1~13、17~19 | H1 只涉及 14~16；动顺序会破坏冻结表 |
| `allowsExecution` 白名单 | 它本来就是对的，错在它看到的 status 已经错了 |

---

## 四、测试

### 4.1 新增 7 条（`CapabilityReconcilerTest`）

| 用例 | 场景 | 断言 |
|---|---|---|
| `implWithoutEvidence` | `IMPLEMENTED` + 无证据 | `UNKNOWN` / `VERIFICATION_REQUIRED` / `exec=false` |
| `implWithRejectedEvidence` | `IMPLEMENTED` + 证据被拒 | `UNKNOWN` / `EVIDENCE_INVALID` / `exec=false` |
| `implWithAcceptedEvidenceStillNotAvailable` | `IMPLEMENTED` + 证据有效 | **仍** `UNKNOWN` / `VERIFICATION_REQUIRED` / `exec=false` |
| `degradedWithRejectedEvidence` | `DEGRADED` + 证据过期 | `UNKNOWN` / `EVIDENCE_EXPIRED` / `exec=false` |
| `verifiedWithoutEvidence` | `VERIFIED` + 无证据 | `UNKNOWN` / `VERIFICATION_REQUIRED`；detail 含「协议异常」 |
| `verifiedWithTamperedEvidence` | `VERIFIED` + 证据被篡改 | `EVIDENCE_INVALID`；detail 含 `EVIDENCE_TAMPERED` |
| `rejectedEvidenceNeverExecutable` | 四个状态 × 证据被拒 | 逐条断言 `UNKNOWN` + `exec=false` |

> **关于 `EVIDENCE_TAMPERED`**：裁决表里写的是 `UNKNOWN / EVIDENCE_TAMPERED`，
> 但冻结的 `SOURCE-04-04` §3.3 已明确把 `EVIDENCE_TAMPERED` 合并进
> `EVIDENCE_INVALID`，且用户要求"以冻结的 EFFECTIVE-REASON 文档为准，
> 不能为了满足测试而自行创造新语义"。因此断言的是 `EVIDENCE_INVALID`，
> 并额外断言 detail 里保留原始拒绝码 `EVIDENCE_TAMPERED`——
> 诊断信息没丢，枚举也没被污染。

### 4.2 修改 1 条

`reasonDegraded` 原先用 `cap("COMMENT","DEGRADED", null)`（无证据）断言
`DEGRADED` + 可执行。按新契约这是**错的**。改为：
有已接受证据 → `DEGRADED` + 可执行；同一用例内再加一段对照
（无证据 → `UNKNOWN` / 不可执行）。

### 4.3 `rejectedEvidenceNeverExecutable` 的防漏设计

除了遍历四个状态，末尾还加了一条自检：

```java
assertThat(List.of("UNVERIFIED","IMPLEMENTED","VERIFIED","DEGRADED","DISABLED","UNSUPPORTED"))
        .containsExactlyInAnyOrderElementsOf(CapabilityStatus.ALL);
```

若将来有人在 `CapabilityStatus` 里新增一个值却忘了更新本用例，这条断言会红。
**这是本次缺陷的直接教训**：测试自身的覆盖清单也会漏，所以要让它与枚举源挂钩。

---

## 五、运行时验证

### 5.1 探针：同一输入，修复前后对比

用真实 `RegistrySnapshotParser` 解析构造的快照（探针编译在 `%TEMP%\probe`，
未进项目目录、未入版本控制）：

| 输入 | 修复前 | 修复后 |
|---|---|---|
| `IMPLEMENTED` + 无证据 | `AVAILABLE` / `NONE` / **true** | `UNKNOWN` / `VERIFICATION_REQUIRED` / false |
| `IMPLEMENTED` + 证据被拒 | `AVAILABLE` / `NONE` / **true** | `UNKNOWN` / `EVIDENCE_INVALID` / false |
| `DEGRADED` + 证据被拒 | `DEGRADED` / `DEGRADED` / **true** | `UNKNOWN` / `EVIDENCE_EXPIRED` / false |
| `UNVERIFIED` + 证据被拒（对照） | `UNKNOWN` / `EVIDENCE_INVALID` / false | 不变 |

**这组对比是 H1 的可证伪证据**：输入完全相同，只是代码换了，行为从"全部放行"
变成"全部拒绝"。

### 5.2 三种快照状态（真实容器，修复后重建镜像）

| 状态 | 触发方式 | 结果 |
|---|---|---|
| `FRESH` | 正常 | `COMMENT`/`CONTENT_DETAIL` = `AVAILABLE`，`COMMENT_REPLY` = `NOT_DECLARED` |
| `STALE` | 停 collection + 等 TTL 60s | `snapshotState=STALE`；4 条能力全部 `UNKNOWN` / `REGISTRY_UNREACHABLE` / 不可执行 |
| `UNAVAILABLE` | 停 collection + 重启 backend 清缓存 | `snapshotState=UNAVAILABLE`；3 条候选全部 `UNKNOWN` / 不可执行 |
| 恢复 | 启 collection | 自动回 `FRESH` + `AVAILABLE`，无需干预 |

**真实 Snapshot 往返**（容器内直连）：

```
snapshotId: sha256:f37f934b2fc4436dc05...
schemaVersion 1.1 | contractVersion 1.0
implementations: COMMENT/COMMENT_REPLY/CONTENT_DETAIL = VERIFIED, KEYWORD_SEARCH = UNVERIFIED
```

### 5.3 测试结果

| 套件 | 修复前 | 修复后 |
|---|---|---|
| `CapabilityReconcilerTest` | 34 | **41** |
| **Java 合计** | 241 | **248** |
| Python 合计 | 76 | **76**（本轮无 Python 改动） |
| 容器 | 5/5 healthy | 5/5 healthy |

---

## 六、验收对照

| 用户要求 | 结果 |
|---|---|
| 修订 04-04 推导表，明确 IMPLEMENTED 语义 | ✅ §3.2 第 14/19 行 + §3.2.1 矩阵 |
| 明确 DEGRADED 的准入规则 | ✅ §3.2 第 18 行 + 矩阵 + 代码闸门 |
| 修复第 14~16 步证据校验 | ✅ 去掉状态守卫，改为无条件生效 |
| 补充正向和负向测试 | ✅ 7 条新增（含 1 条矩阵穷举）+ 1 条修正 |
| IMPLEMENTED 无证据 → `UNKNOWN`/`VERIFICATION_REQUIRED` | ✅ `implWithoutEvidence` |
| IMPLEMENTED 证据被拒 → `UNKNOWN`/`EVIDENCE_INVALID` | ✅ `implWithRejectedEvidence` |
| DEGRADED 无证据 → 禁止未经验证放行 | ✅ `reasonDegraded` 对照段 |
| DEGRADED 证据被拒 → `UNKNOWN`/`EVIDENCE_INVALID` | ✅ `degradedWithRejectedEvidence`（过期分支） |
| VERIFIED 有效证据 → 继续完整准入 | ✅ `reasonNone` + 端到端 |
| VERIFIED 证据过期 → `EVIDENCE_EXPIRED` | ✅ `reasonEvidenceExpired` |
| VERIFIED 证据被篡改 → 不可执行 | ✅ `verifiedWithTamperedEvidence`（`EVIDENCE_INVALID` + detail 保留原码） |
| 不自行创造新语义 | ✅ 未新增 reason、未扩充 status |

---

## 七、未做 / 边界

| 项目 | 状态 |
|---|---|
| **M1 的 Desired 故障分类** | ❌ 未改，属 `SOURCE-04-03-FIX-02` |
| **M2 的重复能力拒绝** | ❌ 未改，属 `SOURCE-04-03-FIX-02` |
| 评审报告的 5 个 LOW | ❌ 未改（L1 contractVersion 缺失跳过校验、L2 STALE reason 合并、L3 跨 Connector 测试、L4 sourceCode 未校验、L5 账号粒度） |
| MySQL 表结构与数据 | ❌ 未改（`capability_json` 仍为 v1 原值） |
| Flyway | ❌ 未新增（仍停 V30） |
| `SOURCE-02-capability-schema.json` | ❌ 未改（八值枚举零改动） |
| 账号认证模型 / Task-Run 状态机 | ❌ 未改 |
| 前端 | ❌ 未改 |
| Effective 落库 | ❌ 未做 |
| SOURCE-04-06 / 04-07 / 04-08 | ❌ 未启动 |

**变更文件**（6 个）：

```
backend/src/main/java/.../source/application/CapabilityReconciler.java   修复 + 类文档
backend/src/test/java/.../source/CapabilityReconcilerTest.java           +7 用例，改 1 用例
docs/architecture/collection/SOURCE-04-04-TASKCARD.md                   §3.2 + §3.2.1
docs/architecture/collection/SOURCE-04-TASKCARD.md                      16 行表 + 注意 4
docs/architecture/collection/ADR-008-...md                              §4.3.1
docs/architecture/collection/SOURCE-04-03-FIX01-REPORT.md               本文件
```

---

## 八、待独立复核的点

本任务**不自判通过**。复核时建议重点看三处，因为它们最容易被"看起来对了"糊弄：

1. **去掉外层 `if` 是否真的覆盖全部路径**：`DISABLED`/`UNSUPPORTED` 在第 11/12 步
   已返回，所以闸门对它们是不可达的——这是"豁免"还是"提前返回"？
   （答案应是后者：它们根本没走到证据这一步，不是被允许跳过。）
2. **`IMPLEMENTED` + 证据有效**这条用例的语义：`assertThat(...).isFalse()` 是否
   真的由"IMPLEMENTED 判定"产生，而不是被前面的某个分支意外拦下？
   —— 已加 `assertThat(r.detail()).contains("IMPLEMENTED")` 锁定：若走的是
   "证据缺失"分支，detail 会是"从未获得有效验证证据"，该断言即失败。
3. **`rejectedEvidenceNeverExecutable` 的自检断言**是否真会失败：
   它是结构性的（把硬编码清单与 `CapabilityStatus.ALL` 比对），
   枚举一旦增删而测试未同步就会变红。本任务未实际改动枚举来验证这点，
   复核者可自行试跑确认。
