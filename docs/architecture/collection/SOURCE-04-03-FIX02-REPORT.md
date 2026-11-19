# SOURCE-04-03-FIX-02 · Desired 解析与故障分类修复（M1 + M2）

> 触发：`SOURCE-04-03-CODE-REVIEW.md`（`edcb034`）的 M1、M2。
> 用户裁决：M1 必须区分 Desired 配置错误与 Observed Schema 错误；M2 重复能力必须
> 失败关闭，不能让数组顺序决定行为。
>
> 基线：`d6610f9`（FIX-01 完成）
> 本任务范围：**只修 M1、M2**。FIX-01 的 H1 已在上一个提交关闭。

---

## 一、M1：Desired 故障分类错误

### 1.1 缺陷回顾

原实现（`4e534a8`）：

```java
// 位于快照判定之前
if (!desired.supported()) {
    return new Blocked(EffectiveStatus.UNKNOWN,
            EffectiveReason.SCHEMA_UNSUPPORTED, "Desired 不可解析: " + desired.error());
}
```

两个问题叠在一起：

| # | 问题 | 后果 |
|---|---|---|
| 1 | 借用 `SCHEMA_UNSUPPORTED` | 该码语义是"Python 快照结构不可用"。Desired 是 Java 库内配置，会把人引向采集服务 |
| 2 | 位置在快照判定**之前** | 冻结顺序是 `REGISTRY_UNREACHABLE`(2) → `SCHEMA_UNSUPPORTED`(3)；"服务挂了"会被报成"配置坏了" |

### 1.2 修复一：新增专属原因码 `DESIRED_UNPARSEABLE`

| 项 | 内容 |
|---|---|
| 值 | `DESIRED_UNPARSEABLE` |
| `effectiveStatus` | `UNKNOWN`（八值枚举**零改动**） |
| 判定条件 | `DesiredCapabilityParser.parse()` 返回 `supported=false` **且** 快照为 `FRESH` |
| 数据来源 | `D`（`ds_source.capability_json`） |

**为什么不复用既有值**（完整论证见 `SOURCE-04-04-EFFECTIVE-REASON.md` §九）：

- `SCHEMA_UNSUPPORTED` → 指 Python 快照结构，故障域不同；
- `NOT_DECLARED` → 事实错误，配置存在，只是读不懂；
- `OBSERVED_STALE` → 与新鲜度无关；
- `UNKNOWN` + 空 reason → 违反 ADR-008，`UNKNOWN` 必须带精确原因。

这是 reason 枚举自 20 值扩到 21 值的**唯一一处新增**。用户的授权原话是
"Java Desired 无法解析，应使用明确的 Desired 配置错误分类"，
同时要求"不得为了实现方便修改已冻结的 SOURCE-02 枚举"——
`effectiveStatus` 未动，只动了 Java 内部的 reason（ADR-008 已声明它随实现演进）。

### 1.3 修复二：位置下移到快照判定之后

```
0 Source 已停用
1 Registry 不可达
2 Schema 不支持
3 仅有过期缓存
4 Desired 配置不可解析   ← 从这里开始才是 Java 自己的问题
5 Desired 未声明
...
```

**为什么不是第 0 位**（这是我在设计时反复的一点）：配置错误是"自己的问题"，
直觉上该最先判。但服务不可达时**连判定依据都没有**，此时报"你的配置读不懂"
会把排查方向从"服务挂了"引到"配置错了"。先报更基础的未知，再报配置错误。

### 1.4 修复三：`DataSourceView.desiredError`（评审时新发现的必要补口）

写优先级测试时撞上一个事实：**Desired 读不懂 + Registry 不可达时，候选集合为空**
（Desired 拿不到能力码，Observed 没有骨架），`reconcile` 返回**空列表**——
没有任何一行可以标注原因。

这个行为本身是对的（不能为了显示点什么就凭空造能力码），但代价是
**调用方什么也看不到**：`effective` 空列表既可能是"没配能力"，也可能是"配置坏了"。

因此新增一个字段：

```java
/** Desired 解析失败的原因；解析成功时为 null。 */
private String desiredError;
```

它让"行级结论为空"与"故障域可辨"两件事同时成立：
`snapshotState` 说明 Observed 侧，`desiredError` 说明 Desired 侧。

> 这条不在用户列出的 M1 要求里，但不加它，M1 的修复在真实场景（两边都坏）
> 下等于没修——调用方仍然无法知道是配置问题。已在报告里标注为**评审衍生修复**。

---

## 二、M2：重复能力必须失败关闭

### 2.1 缺陷回顾

`parseV2` 对缺字段、非法枚举、空数组都 fail-close，唯独"同一
`connectorCode + code` 出现两次"不检查，由 `findFirst` 按数组顺序取第一条，
`collectCandidates` 的 `putIfAbsent` 也是同样顺序。

**结果**：`ENABLED` 和 `DISABLED` 同时声明时，能力是否可用取决于**哪一行写在前面**
——那是编辑器行为，不是业务意图。

### 2.2 修复

```java
Set<String> seen = new HashSet<>();
...
if (!seen.add(connector + "/" + code)) {
    return DesiredCapabilitySet.unsupported(
            "v2 存在重复能力: " + connector + "/" + code
                    + "（同一 connectorCode + code 只能声明一次）");
}
```

**键的选择**：用 `connectorCode + code`，也就是**下游真正的匹配键**
（`DesiredCapabilitySet.find` 与 `Candidate` 的键都是这个）。
不用 `sourceCode` 参与，理由：一个 Desired 文档只属于一个 Source，
两条目 `sourceCode` 不同本身就说明配置矛盾；而且它们在下游必然撞键，
在解析阶段一并拒绝比留到核对阶段更早暴露。

### 2.3 四种组合的裁决（与用户要求逐条对应）

| 组合 | 裁决 | 用例 |
|---|---|---|
| `ENABLED` + `ENABLED` | **拒绝** | `rejectsDuplicateEnabled` |
| `ENABLED` + `DISABLED` | **拒绝** | `rejectsDuplicateContradictory` |
| 不同 `connectorCode`、相同 `code` | **允许**（不同键，不同能力） | `allowsSameCodeInDifferentConnector` |
| 相同 `connectorCode`、不同 `sourceCode` | **拒绝**（下游会撞键） | `rejectsSameConnectorWithDifferentSource` |

---

## 三、测试

### 3.1 新增用例

| 文件 | 用例 | 断言 |
|---|---|---|
| `CapabilityReconcilerTest` | `desiredUnparseableIsNotSchemaUnsupported` | `DESIRED_UNPARSEABLE`，且**显式断言不等于** `SCHEMA_UNSUPPORTED` |
| | `staleOutranksDesiredUnparseable` | 快照 STALE 时返回 `OBSERVED_STALE`（顺序 3 < 4） |
| | `bothBrokenWithoutCandidatesYieldsNoRows` | 两边都坏且无候选 → 返回空列表（不伪造结论） |
| `DesiredCapabilityParserTest` | 上述四条 M2 用例 | `supported=false` + error 文案含"重复能力" |

原 `reasonSchemaUnsupportedByDesired` 改名为 `desiredUnparseableIsNotSchemaUnsupported`
并改断言——**这是行为变更，不是为了让测试变绿而放宽**。

### 3.2 枚举计数同步

| 断言 | 修订前 | 修订后 |
|---|---|---|
| `EffectiveReason.ALL` | 20 | **21** |
| `EffectiveReason.REACHABLE` | 19 | **20** |

这两条断言的作用是"新增 reason 必须同步更新测试"，本次正好被它挡住一次。

### 3.3 结果

| 套件 | 修复前 | 修复后 |
|---|---|---|
| `CapabilityReconcilerTest` | 41 | **43** |
| `DesiredCapabilityParserTest` | 10 | **13** |
| **Java 合计** | 248 | **253** |
| Python 合计 | 76 | **76**（本轮无 Python 改动） |
| 容器 | 5/5 healthy | 5/5 healthy |

---

## 四、运行时验证

| 状态 | 结果 |
|---|---|
| `FRESH` | `COMMENT`/`CONTENT_DETAIL` = `AVAILABLE`；`desiredError = null`；3 条 desired |
| `STALE`（停服务 + 等 TTL 60s） | 4 条全部 `UNKNOWN` / `REGISTRY_UNREACHABLE` / 不可执行 |
| `UNAVAILABLE`（停服务 + 重启清缓存） | 3 条候选全部 `UNKNOWN` / 不可执行 |
| 恢复 | 自动回 `FRESH` + `AVAILABLE`，无需干预 |
| 真实 Snapshot 往返 | `sha256:f37f934b…`，schema 1.1 / contract 1.0 |

**诚实说明一处验证缺口**：M1 的 `desiredError` 与 M2 的重复拒绝**无法在运行时演示**
——库中 `capability_json` 是合法 v1（且本任务禁止改库），构造非法配置只能靠单测。
这两项的证据是解析器/核对器单测，不是接口实测。

---

## 五、验收对照

| 用户要求 | 结果 |
|---|---|
| Python Snapshot Schema 不支持 → `SCHEMA_UNSUPPORTED` | ✅ 未变 |
| Java Desired 无法解析 → 明确的 Desired 配置错误分类 | ✅ `DESIRED_UNPARSEABLE` |
| 不应让调用方误以为 Python 返回非法 Schema | ✅ 专属原因码 + `desiredError` |
| Desired 解析失败不得覆盖 Registry 不可达 | ✅ 下移到第 4 位 + 顺序论证 |
| 不要直接复用 `SCHEMA_UNSUPPORTED` 处理所有 JSON 失败 | ✅ 未复用 |
| ENABLED + DISABLED 重复 → 拒绝 | ✅ |
| ENABLED + ENABLED 重复 → 拒绝 | ✅ |
| 不同 Connector 相同 code → 允许 | ✅ |
| 不同 Source 相同 code → 按完整匹配键判断 | ✅ 拒绝（下游撞键，已说明理由） |
| 不让数组顺序决定系统行为 | ✅ 整份文档拒绝 |

---

## 六、未做 / 边界

| 项目 | 状态 |
|---|---|
| 评审的 5 个 LOW | ❌ 未改（L1 contractVersion 缺失跳过校验、L2 STALE reason 合并、L3 跨 Connector 同名能力测试、L4 sourceCode 未校验、L5 账号粒度） |
| MySQL 表结构与数据 | ❌ 未改（`capability_json` 仍 v1 原值） |
| Flyway | ❌ 未新增（仍停 V30） |
| `SOURCE-02-capability-schema.json` | ❌ 未改（八值 `effectiveStatus` 零改动） |
| 账号认证模型 / Task-Run 状态机 | ❌ 未改 |
| 前端 | ❌ 未改（新增字段 `desiredError` 向后兼容） |
| Effective 落库 | ❌ 未做 |
| 04-06 / 04-07 / 04-08 | ❌ 未启动 |

> **L4 与本次 M2 的关系需要说明**：L4 建议校验 `sourceCode == ds_source.code`，
> 本次**没有**做这条校验，但在重复检测里把"同 connector 不同 sourceCode"判为重复。
> 两者的区别：重复检测只处理"会撞键"的情况，L4 是完整的字段一致性校验，
> 需要独立实现和独立测试，仍留在 LOW 清单里。

**变更文件**（8 个）：

```
backend/src/main/java/.../source/domain/EffectiveReason.java           +DESIRED_UNPARSEABLE（21 值）
backend/src/main/java/.../source/application/CapabilityReconciler.java 位置下移 + 改原因码 + 重编号
backend/src/main/java/.../source/application/DesiredCapabilityParser.java 重复检测
backend/src/main/java/.../source/dto/DataSourceView.java               +desiredError
backend/src/main/java/.../source/application/SourceQueryService.java   填充 desiredError
backend/src/test/java/.../source/CapabilityReconcilerTest.java         +2 用例，改 1 用例，计数同步
backend/src/test/java/.../source/DesiredCapabilityParserTest.java      +4 用例
docs/architecture/collection/SOURCE-04-04-EFFECTIVE-REASON.md          21 值 + 判定顺序重排 + §九
docs/architecture/collection/SOURCE-04-04-TASKCARD.md                  §3.2 +§3.2.1 步号 +§3.3
docs/architecture/collection/SOURCE-04-TASKCARD.md                     16 行表重编号 + 注意 5
docs/architecture/collection/SOURCE-04-03-FIX02-REPORT.md              本文件
```

（`EffectiveReason.java` 等 5 个 Java 文件 + 2 个测试 + 4 个文档，共 11 个路径。）

---

## 七、待独立复核的点

1. **`DESIRED_UNPARSEABLE` 的必要性**：是否确实无法用既有 20 值表达？
   （我的论证是"故障域不同"，请复核是否成立。）
2. **第 4 位的排序**：`OBSERVED_STALE` 优先于 `DESIRED_UNPARSEABLE` 是否合理？
   （我的理由：没有依据时不该归因于配置。）
3. **重复检测的键**：把 `sourceCode` 排除在键外、并拒绝"同 connector 不同 source"，
   是否越过了 M2 的授权范围？（已说明：它们在下游必然撞键。）
4. **`desiredError` 是否算范围外新增**：它不在 M1 的明文要求里，
   但不加则"两边都坏"时 M1 的修复对调用方不可见。
