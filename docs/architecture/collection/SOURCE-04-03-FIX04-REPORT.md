# SOURCE-04-03-FIX-04 · 六项收尾（F1-3 / F1-4 / F2-3 / O1 / O2 / O3）

> 触发：`SOURCE-04-03-FIX03-REVIEW.md`（`3c43f22`）的 1 个 OBSERVATION + 2 个 LOW，
> 加上前轮遗留的 F1-3 / F1-4 / F2-3。
>
> 基线：`3c43f22`
> 范围：**只做已批准的六项**。未启动 04-06 / 04-07 / 04-08。

---

## 一、O1：两份 `capabilities` 副本的审计结论

按用户要求，先查清五个问题，再决定改什么。

### 1.1 五问逐条

| # | 问题 | 结论 |
|---|---|---|
| 1 | 两份是否由同一份 Registry 数据生成？ | **是**。`bootstrap.snapshot()` 只做**一次** `evaluate_all()`，两个循环都用 `evaluation.specs_for(connector_code)` + `apply_evidence()` 派生，序列化都走同一个 `to_v1()` |
| 2 | 契约语义是否相同？ | **对 `platformScoped=true` 的接入相同**。唯一差异是覆盖面：`adapters[]` 收录全部接入（含 STUB），`capabilities[]` 只收 `platformScoped=true` 的 |
| 3 | 哪一份是权威？ | **状态以 `capabilities[]`（文档）为准**——核对器 `findObserved()` 只查文档。`adapters[]` 提供 `loadStatus` / `connectorVersion` / `platformScoped`，其内嵌 capabilities 目前只被用来**枚举候选码**（`Reconciler:308-310`） |
| 4 | 不一致时当前如何处理？ | **修复前没有任何检测**。可能出现"候选码来自 adapters、状态查自 documents"的混合事实；adapter 侧独有、文档侧缺失的能力会静默变成 `CAPABILITY_NOT_REGISTERED` |
| 5 | 是否存在未来绕过校验的可能？ | **存在**。新加的六值校验只覆盖文档侧；若 Python 将来在 adapter 侧附加不同集合（例如把"函数解析失败"的能力也列进去），Java 会拿它当候选，状态查不到 → 静默误判为"未注册" |

### 1.2 处置：加镜像校验，**不**复制第二套枚举校验

既然两份是同一份声明的两个视图，就没有理由各校验一遍——**校验内容一次，然后断言两份一致**：

```java
// RegistrySnapshotParser：文档循环之后
String mirrorProblem = checkCapabilityMirror(adapters, documents);
if (mirrorProblem != null) {
    return Result.failure(Failure.BROKEN_CAPABILITY, mirrorProblem);
}
```

比对范围与规则：

| 规则 | 说明 |
|---|---|
| 只比 `platformScoped=true` 的接入 | STUB 在文档侧本来就没有条目，比较会误杀合法快照 |
| 双向比较能力码集合 | adapter 独有 / 文档独有，都算分叉 |
| 比 `implementationStatus` | 这是"清单 + 状态"里唯一会被下游当判据的字段 |
| 报错带两个值 | 例：`XB/COMMENT 的 implementationStatus 两份副本不一致: adapters=UNVERIFIED，capabilities=VERIFIED` |

**为什么不用"再校验一遍 adapter 侧"的做法**：那会出现两处同逻辑校验，改动时容易只改一处；
而且它不解决"两份可能给出不同能力集合"这个更根本的问题。镜像断言同时解决两者。

### 1.3 测试（4 条）

| 用例 | 断言 |
|---|---|
| `rejectsMirrorStatusMismatch` | 状态不一致 → `BROKEN_CAPABILITY`，报错含 `X/COMMENT`、`adapters=UNVERIFIED`、`capabilities=VERIFIED` |
| `rejectsAdapterOnlyCapability` | 能力只在 adapter 侧 → 报错含"不在 `capabilities[]`" |
| `rejectsDocumentOnlyCapability` | 能力只在文档侧 → 报错含"不在 `adapters`" |
| `stubCapabilitiesExcludedFromMirror` | STUB 带不同状态的能力**不影响**解析（防误杀） |

**连带修正测试夹具**：`RegistrySnapshotParserTest.minimal()` 原先把 adapter 侧能力写成
`{"code":"COMMENT","adapterFunction":"app.x.y"}`——缺 `implementationStatus` 等字段，
**与真实 Python 载荷不符**。已补全为与文档侧同形。这条夹具失真正是"镜像校验第一次跑就红"的原因，
说明校验确实在工作。

---

## 二、F1-3：`DEGRADED` 的准入规则

### 2.1 五个问题逐一回答（实测，非推断）

| 组合 | status | reason | executable | 依据 |
|---|---|---|---|---|
| `DEGRADED` + 证据有效 | `DEGRADED` | `DEGRADED` | **true** | 矩阵：持有已接受证据才可降级执行 |
| `DEGRADED` + 证据缺失 | `UNKNOWN` | `VERIFICATION_REQUIRED` | false | 未经验证不得降级 |
| `DEGRADED` + 证据过期 | `UNKNOWN` | `EVIDENCE_EXPIRED` | false | 证据闸门在前 |
| `DEGRADED` + 证据无效 | `UNKNOWN` | `EVIDENCE_INVALID` | false | 同上 |
| `DEGRADED` + 其它 reason | —— | —— | **构造即抛异常** | 见 2.2 |

### 2.2 四处规则现在一致

| 位置 | 修订前 | 修订后 |
|---|---|---|
| `EffectiveStatus.allowsExecution` | `return DEGRADED.equals(status)`（**不看 reason**） | `return DEGRADED.equals(reason)`，且 status 必须是 `DEGRADED` |
| `EffectiveCapability` 构造期 | 沿用上者，故 `DEGRADED + EVIDENCE_INVALID + true` 能构造成功 | 同一判据，该组合构造即抛 `IllegalArgumentException` |
| `CapabilityReconciler` 推导 | 已正确（证据闸门在第 ⑮~⑰ 步，DEGRADED 只可能带已接受证据） | 不变 |
| 单元测试 | `n75WhitelistOnly` 只测了 `DEGRADED+DEGRADED` | 新增 5 个非法 reason 逐个断言 + 证据四态矩阵 |

### 2.3 `ADR-008` §4.3 示例同步收紧

原示例：

```java
|| "DEGRADED".equals(effectiveStatus);          // 只看 status
```

改为：

```java
boolean executable = ("AVAILABLE".equals(effectiveStatus)
                      && "NONE".equals(effectiveReason))
                  || ("DEGRADED".equals(effectiveStatus)
                      && "DEGRADED".equals(effectiveReason));
```

并在 ADR 里写明：**实现当初是忠于 ADR 的，松的是 ADR 的示例**——
这句话必须留在文档里，否则下次评审会以为是实现擅自放宽。

### 2.4 允许 `DEGRADED + 坏 reason + executable=false` 存在

构造期只禁止"标记为可执行"，不禁止该记录本身存在。
理由：如果将来需要展示"这条降级能力已失效"，`executable=false` 的记录是有意义的；
禁止它存在等于逼调用方丢弃诊断信息。测试里对这两种情况分别断言。

---

## 三、F1-4：核对器注释的步骤编号

| 位置 | 修订前 | 修订后 |
|---|---|---|
| `CapabilityReconciler:101` | `§4 第 1~4 步` | `§4 第 1~5 步` |
| `CapabilityReconciler:149` | `§4 第 5~19 步` | `§4 第 6~20 步` |

FIX-02 在判定链第 4 位插入 `DESIRED_UNPARSEABLE` 后，其后所有步骤号 +1；
文档早已同步，只有代码注释没跟。

---

## 四、F2-3：`desiredError` 的测试（新增 `SourceQueryServiceTest`）

### 4.1 四个场景

| 场景 | 结果 | 断言 |
|---|---|---|
| **A** Desired 正常 + Registry 正常 | 通过 | `desiredError == null`；`snapshotState=FRESH`；`desired` 3 条；旧 `capability` 字段仍在；`COMMENT` 可执行 |
| **B** Desired 坏 + Registry 正常 | 通过 | `desiredError != null`（含 `3.0`）；`desired` 为空；`effective` 每条都是 `UNKNOWN` / `DESIRED_UNPARSEABLE` / 不可执行 |
| **C** Desired 坏 + Registry 不可达 | 通过 | `desiredError != null` **且** `snapshotState=UNAVAILABLE` + `snapshotDetail` 非空——两个故障域都不被对方覆盖 |
| **D** 两边都坏 | 通过 | `effective` 为空（不伪造记录）；无任何可执行能力 |

### 4.2 一处需要你确认的解读差异

你在 FIX-04 要求里写的场景 B 是：

> Desired 无法解析 / Registry 正常 / **capabilities = []** / desiredError != null

我把这里的 `capabilities = []` 理解为 **Desired 的声明列表**（`view.desired`），
测试按此断言。**Effective 不是空的**：Registry 正常时 Observed 能提供能力码，
所以每条能力都会得到一行 `UNKNOWN / DESIRED_UNPARSEABLE`。

这与 §3.2 的推导一致（Desired 读不懂 → 全局判 UNKNOWN，但逐条仍要给出结论），
也符合"候选集合取 Desired ∪ Observed"的设计。若你的原意是 `effective` 也必须为空，
那需要改推导规则（读不懂就整份不展示），**这会丢掉 Observed 已有的信息**，我不建议。

场景 C 里 `effective` 才是空的——两边都没有能力码来源。这与 D 的断言重合。

### 4.3 为什么这层测试此前缺失

`desiredError` 只在 `SourceQueryService.toView()` 里赋值，而该方法是私有的、
Service 也没有任何测试。之前的用例全部停在 `CapabilityReconciler` 层——
那一层根本看不到 `desiredError`。所以 M1 的闭环一直只有代码、没有断言。

新测试用**记录型假 Mapper**（实现 4 个接口方法，不用 mock 框架）+ 真实快照样本 +
真实解析器，覆盖装配层，而不是再造一层桩。

---

## 五、O2：`IMPLEMENTATION.md` 全文修正

按"不能只改第 81 行"的要求，做了全文排查与重写：

| 位置 | 修订前 | 修订后 |
|---|---|---|
| `:45` | `EffectiveReason` 20 值 / 19 个可达 | 21 值 / 20 个可达 |
| `:50` | `CapabilityReconciler`：19 步短路 | 20 步短路 |
| `:76` | 标题「19 步判定顺序」 | 「20 步判定顺序」+ 说明本节曾被历次 FIX 拉出偏差 |
| `:81` | `— Desired 不可解析｜SCHEMA_UNSUPPORTED`，且画在 ② 之前 | 改为第 ⑤ 步 `DESIRED_UNPARSEABLE`，并注明"不是 SCHEMA_UNSUPPORTED；排在快照之后" |
| 判定表其余行 | ①~⑲ 旧编号、证据闸门只提"从未验证" | 全表重排为 ①~⑳，证据闸门明确"对所有未被 ⑫⑬ 拦下的状态生效" |
| `:123` | 「19 个 reason 的测试对照」 | 「20 个可达 reason 的测试对照」 |
| 测试对照表 | 20 行，缺 `DESIRED_UNPARSEABLE`；`SCHEMA_UNSUPPORTED` 行还引用已改名的 `...ByDesired` | 21 行；新增 `DESIRED_UNPARSEABLE` 行；`VERIFICATION_REQUIRED` 行补齐 `IMPLEMENTED` / 已接受证据 / `VERIFIED` 缺证据三种来源 |
| `:150` | `ALL` 20 / `REACHABLE` 19 | 21 / 20 |

另在判定表后补了两条硬约束（六值非法值 → 解析阶段拒绝；两份 capabilities 必须镜像）。

---

## 六、O3：术语统一

`EFFECTIVE-REASON:57` 标题由"（含数据自相矛盾的情形）"改为
"（含**实现状态与证据状态不一致**的情形）"，与代码 `unverifiedDetail` 的表述一致。

全文复查后剩余的"自相矛盾"只出现在**历史评审报告**里（引用当时的措辞），属正确用法。

---

## 七、测试

| 套件 | 修复前 | 修复后 |
|---|---|---|
| `RegistrySnapshotParserTest` | 26 | **30**（+4 O1） |
| `CapabilityReconcilerTest` | 45 | **47**（+2 F1-3） |
| `SourceQueryServiceTest` | — | **4**（新增，F2-3） |
| **Java 合计** | 259 | **269** |
| Python 合计 | 76 | **76**（本轮无 Python 改动） |
| 容器 | 5/5 healthy | 5/5 healthy |

### 运行时验证（真实容器，重建后）

```
snapshotState: FRESH | desiredError: null
COMMENT        -> AVAILABLE   / NONE         exec=true
CONTENT_DETAIL -> AVAILABLE   / NONE         exec=true
KEYWORD_SEARCH -> UNREGISTERED/ NOT_DECLARED exec=false
COMMENT_REPLY  -> UNREGISTERED/ NOT_DECLARED exec=false

真实快照两份副本镜像一致: DOUYIN_PUBLIC -> True
```

**镜像校验对真实载荷零影响**——这是 O1 改动最重要的回归证据：如果校验过严，
真实快照会被自己的新规则拒掉。

---

## 八、验收对照

| 用户要求 | 结果 |
|---|---|
| O1 五个问题查清并给结论 | ✅ §1.1 逐条；结论是"同一份声明的两个视图" |
| O1 有结论和测试，不能只写"建议统一" | ✅ 加了镜像校验 + 4 条测试 |
| O2 全文搜索旧 Reason / 旧步号 / 旧状态映射 | ✅ §五，8 处 |
| O3 统一为"实现状态与证据状态不一致" | ✅ |
| F1-3 回答四个证据组合 | ✅ §2.1 实测 |
| F1-3 是否还有 DEGRADED + 其它 reason 仍可执行 | ✅ 已收紧，构造期抛异常 |
| F1-3 四处规则一致（allowsExecution / 构造期 / 推导 / 测试 / ADR） | ✅ §2.2 + §2.3（共五处） |
| F2-3 场景 A / B / C / D | ✅ 4 条自动化测试 |
| F2-3 B/C/D 不能只靠代码审读 | ✅ 全部是正式测试 |

---

## 九、未做 / 边界

| 项目 | 状态 |
|---|---|
| L1~L5（首轮评审的 LOW） | ❌ 未动，单独核查，不因本轮通过而关闭 |
| MySQL 表结构与数据 | ❌ 未改（`capability_json` 仍 v1 原值） |
| Flyway | ❌ 未新增（仍停 V30） |
| `SOURCE-02-capability-schema.json` | ❌ 未改 |
| 账号认证模型 / Task-Run 状态机 / 前端 | ❌ 未改 |
| SOURCE-04-06 / 04-07 / 04-08 | ❌ 未启动 |

**变更文件**（10 个）：

```
backend/.../integration/capability/RegistrySnapshotParser.java   镜像校验 + 辅助
backend/.../source/domain/EffectiveStatus.java                   DEGRADED 需 reason 匹配
backend/.../source/application/CapabilityReconciler.java          注释步号
backend/src/test/.../RegistrySnapshotParserTest.java             +4 O1，夹具补全
backend/src/test/.../CapabilityReconcilerTest.java               +2 F1-3
backend/src/test/.../SourceQueryServiceTest.java                 新增 4 条 F2-3
docs/architecture/collection/ADR-008-...md                       §4.3 示例收紧
docs/architecture/collection/SOURCE-04-03-IMPLEMENTATION.md      全文修正（O2）
docs/architecture/collection/SOURCE-04-04-EFFECTIVE-REASON.md    术语统一（O3）
docs/architecture/collection/SOURCE-04-03-FIX04-REPORT.md        本文件
```

---

## 十、待独立复核的点

1. **镜像校验是否过严**：若 Python 将来有意让 adapter 侧收录"未解析"的能力（当前不会），
   该快照会被整份拒绝。请判断这是"该拒绝"还是"应放宽为只比交集"。
2. **`DEGRADED + 坏 reason + executable=false` 允许存在**是否合理：
   我按"保留诊断信息"处理，另一种选择是连构造都禁止。
3. **场景 B 的解读**（§4.2）：我把 `capabilities = []` 理解为 Desired 声明列表，
   Effective 仍有行。若原意不同，需要改推导规则而不是改测试。
