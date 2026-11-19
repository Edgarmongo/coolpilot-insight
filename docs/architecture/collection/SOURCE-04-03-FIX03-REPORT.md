# SOURCE-04-03-FIX-03 · 契约统一与非法状态 fail-closed

> 触发：`SOURCE-04-03-FIX-INDEPENDENT-REVIEW.md`（`d19444d`）的 P1 三项：
> F2-1（§2.7 旧 Reason）、F1-1（契约与实现不一致）、F1-2（非法枚举 fail-open）。
>
> 基线：`d19444d`
> 范围：**只做 P1 三项**。F1-3 / F1-4 / F2-3 属 `FIX-04`，本任务未触碰。

---

## 一、F1-2：非法 `implementationStatus`（本轮最重要）

### 1.1 问题

`CapabilityContractParser:177` 只读取 `implementationStatus`，**从不校验是否属于六值枚举**；
核对层也没有兜底。独立复核实测：

```
PARTIAL（非法）    + 证据接受 -> AVAILABLE / NONE / executable=true
字段缺失（非法）    + 证据接受 -> AVAILABLE / NONE / executable=true
```

这是跨服务契约的典型 fail-open：Python 拼错一个字母、或新增了 Java 还不认识的状态，
Java 会当作正常状态继续核对，最终判"可执行"。

### 1.2 修复：两层都要守

**第一层——解析阶段拒绝整份快照**（`RegistrySnapshotParser:228-244`）：

```java
if (!CapabilityStatus.isKnown(item.implementationStatus())) {
    String raw = item.implementationStatus() == null
            ? "<缺失或 null>" : "\"" + item.implementationStatus() + "\"";
    return Result.failure(Failure.BROKEN_CAPABILITY,
            "能力 " + connectorLabel(doc) + "/" + item.code()
                    + " 的 implementationStatus 非法: " + raw
                    + "（合法值: " + KNOWN_STATUSES + "）");
}
```

四条明确的设计决定：

| 决定 | 理由 |
|---|---|
| **不设默认值、不映射** | 把非法值映射成 `UNVERIFIED` 之类，会让"读不懂"变成"读懂了但没验证"，掩盖契约破裂 |
| **不做大小写归一** | `verified` ≠ `VERIFIED`；容忍大小写等于默许拼写错误 |
| **拒绝整份快照，不跳过该条** | 跳过等于"悄悄少一条能力"，比拒绝更难发现 |
| **错误信息带能力码 + 非法值 + 合法值清单** | 可诊断；`KNOWN_STATUSES` 排序拼接，保证同输入同文案 |

**第二层——核对层兜底**（`CapabilityReconciler`）：

即使有人绕过解析器直接构造 `CapabilityItem`，未知状态也不能落到 `AVAILABLE`
（见 §二 的白名单守卫）。两层都守的理由写在注释里：跨语言枚举只靠一层，
必然会在某次重构中被绕过。

### 1.3 实测

```
PARTIAL      + accepted  -> 解析失败: BROKEN_CAPABILITY
字段缺失      + accepted  -> 解析失败: BROKEN_CAPABILITY
（六值全部正常解析，行为不变）
```

---

## 二、F1-1：`UNVERIFIED + 已接受证据` 被放行

### 2.1 问题

契约 §3.2.1 该格写着"⚠️ 不可能；**若出现按不可执行处理**"，
但实现只有一个 `if (IMPLEMENTED)` 的专用分支，`UNVERIFIED` 会一路落到最后的
`AVAILABLE`。独立复核实测 `UNVERIFIED + accepted=true → AVAILABLE / executable=true`。

### 2.2 修复：从"点名式"改成"白名单式"

```java
// 修订前：只点名一个状态，其余默认放行
if (CapabilityStatus.IMPLEMENTED.equals(impl)) { return UNKNOWN + VERIFICATION_REQUIRED; }

// 修订后：只有两个状态能继续
if (!CapabilityStatus.VERIFIED.equals(impl) && !CapabilityStatus.DEGRADED.equals(impl)) {
    return UNKNOWN + VERIFICATION_REQUIRED + unverifiedDetail(impl);
}
```

**为什么改结构而不补一个 `|| UNVERIFIED`**：补值只能解决今天。
F1-1 与 F1-2 其实是同一个病的两次发作——**"没被点名就放行"**。
点名式守卫必然随枚举增长而漏，白名单式守卫则天然覆盖未来新增值。

### 2.3 `unverifiedDetail` 区分三种语境

| 状态 | detail |
|---|---|
| `IMPLEMENTED` | 实现状态为 IMPLEMENTED（本地测试通过），尚未取得真实平台验证 |
| `UNVERIFIED`（却带已接受证据） | 实现状态与证据状态不一致：`implementationStatus=UNVERIFIED`，但 `evidence.accepted=true`。证据已被接受，但实现状态仍未标记为 `VERIFIED` |
| 其它 / `null` | 实现状态不在契约枚举内（`"PARTIAL"` / 缺失），按不可执行处理 |

三者 `status` + `reason` 相同（`UNKNOWN` / `VERIFICATION_REQUIRED`），
**只有 detail 能把排查方向区分开**——这和 FIX-01 里 `VERIFIED` 缺证据标"协议异常"
是同一个考虑。

### 2.4 实测（同一探针，修复前后对比）

| 输入 | 修复前 | 修复后 |
|---|---|---|
| `UNVERIFIED` + 证据接受 | `AVAILABLE` / `NONE` / **true** | `UNKNOWN` / `VERIFICATION_REQUIRED` / false |
| `IMPLEMENTED` + 证据接受 | `UNKNOWN` / `VERIFICATION_REQUIRED` / false | 不变 |
| `VERIFIED` + 证据接受 | `AVAILABLE` / `NONE` / true | 不变 |
| `DEGRADED` + 证据接受 | `DEGRADED` / `DEGRADED` / true | 不变 |
| `VERIFIED` / `DEGRADED` 证据缺失或被拒 | `UNKNOWN` / 各 reason / false | 不变 |

---

## 三、F2-1：§2.7 仍引用旧 Reason

`SOURCE-04-04-TASKCARD.md:273`（§2.7 兼容读取）原写：

> `schemaVersion` 主版本 ≥ 3 | 拒绝解析，该 Source 的 Desired 判为 `UNKNOWN` + `SCHEMA_UNSUPPORTED`

改为：

> 拒绝解析，该 Source 的 Desired 判为 `UNKNOWN` + **`DESIRED_UNPARSEABLE`**
> （见 §3.2 第 4 行；**不是** `SCHEMA_UNSUPPORTED`——那是 Observed 快照的故障码）

**为什么这条不改会出事**：§2.7 是"迁移/兼容读取"章节，04-06 实施 Desired v2 迁移时
最可能读到的就是它。按它实现会把 M1 原样带回来，而且是**在文档看起来权威的情况下**。

### 3.1 连带同步的三处

| 位置 | 改动 |
|---|---|
| §3.2.1 `UNVERIFIED` + 证据接受 | 由"若出现按不可执行处理"改为"→ `UNKNOWN` + `VERIFICATION_REQUIRED`（代码已强制执行）" |
| §3.2.1 新增一行 | **不在六值内 / 缺失 / `null`** → 解析阶段即拒绝（`BROKEN_CAPABILITY`） |
| §3.2.1 铁律新增第 4 条 | 非法状态不得进入核对；解析层拒绝 + 核对层兜底，两层都守 |
| `EFFECTIVE-REASON` 第 16 行 | 条件补上"即便证据 `accepted=true` 也归此"，并说明 `UNVERIFIED` + 证据属字段冲突 |

---

## 四、测试

### 4.1 新增 6 条

| 文件 | 用例 | 断言 |
|---|---|---|
| `RegistrySnapshotParserTest` | `rejectsUnknownImplementationStatus` | `PARTIAL` → `BROKEN_CAPABILITY`；报错含能力码、非法值、合法值清单 |
| | `rejectsMissingImplementationStatus` | 缺失 与 `null` 分别拒绝；报错含"缺失或 null" |
| | `rejectsWrongCaseImplementationStatus` | `verified`（小写）被拒——不做大小写归一 |
| | `acceptsAllSixImplementationStatuses` | 六值逐值均通过（防"校验写死成只允许某个值"） |
| `CapabilityReconcilerTest` | `unverifiedWithAcceptedEvidenceIsNotAvailable` | `UNKNOWN` / `VERIFICATION_REQUIRED` / false；detail 含两个冲突字段名 |
| | `unknownImplementationStatusNeverAvailable` | 直接构造 `PARTIAL` 绕过解析器 → 仍 `UNKNOWN`；detail 含"不在契约枚举内" |

两条核对层用例的存在意义：**证明兜底不是摆设**。
`unknownImplementationStatusNeverAvailable` 特意绕过解析器构造对象，
验证第二层守卫独立生效。

### 4.2 结果

| 套件 | 修复前 | 修复后 |
|---|---|---|
| `RegistrySnapshotParserTest` | 22 | **26** |
| `CapabilityReconcilerTest` | 43 | **45** |
| **Java 合计** | 253 | **259** |
| Python 合计 | 76 | **76**（本轮无 Python 改动） |
| 容器 | 5/5 healthy | 5/5 healthy |

### 4.3 真实链路无回归

```
snapshotState: FRESH | desiredError: null
COMMENT        -> AVAILABLE   / NONE         exec=true
CONTENT_DETAIL -> AVAILABLE   / NONE         exec=true
KEYWORD_SEARCH -> UNREGISTERED/ NOT_DECLARED exec=false
COMMENT_REPLY  -> UNREGISTERED/ NOT_DECLARED exec=false
```

真实快照的四条能力都是合法值，严格校验对它们**没有任何影响**——
这次改的是"非法输入的处理方式"，不是"合法输入的判定"。

---

## 五、验收对照

| 用户要求（P1） | 结果 |
|---|---|
| F2-1 旧文档会让 04-06 再次引入 M1 → 必须修 | ✅ §2.7 改为 `DESIRED_UNPARSEABLE` |
| F1-1 契约写了不可执行、代码却执行 → 必须修 | ✅ 白名单守卫，实测已不可执行 |
| F1-2 非法枚举值不能进入 `AVAILABLE` → 必须修 | ✅ 解析层拒绝 + 核对层兜底 |
| 解析阶段严格校验六值枚举 | ✅ `CapabilityStatus.isKnown()` |
| 缺失值 / `null` / 非法字符串均拒绝 | ✅ 三条用例逐项锁定 |
| 不允许映射为某个默认合法状态 | ✅ 无默认值、无大小写归一 |
| 错误必须可诊断 | ✅ 能力码 + 非法值 + 合法值清单 |
| 补充正式单元测试，不能只依赖临时探针 | ✅ 6 条正式用例 |

---

## 六、未做 / 边界

| 项目 | 状态 |
|---|---|
| **F1-3**（ADR 准入示例、`DEGRADED` 忽略 reason） | ❌ 属 `FIX-04`，本轮未动 |
| **F1-4**（注释步骤编号 1~4 / 5~19） | ❌ 属 `FIX-04` |
| **F2-3**（`desiredError` 诊断测试） | ❌ 属 `FIX-04` |
| **O1**（两边同时故障的诊断测试） | ❌ 属 `FIX-04`（与 F2-3 同批） |
| 评审的 L1~L5 | ❌ 未动，单独核查，不因本轮通过而关闭 |
| MySQL 表结构与数据 | ❌ 未改（`capability_json` 仍 v1 原值） |
| Flyway | ❌ 未新增（仍停 V30） |
| `SOURCE-02-capability-schema.json` | ❌ 未改（八值 `effectiveStatus` 与六值 `implementationStatus` 均零改动） |
| 账号认证模型 / Task-Run 状态机 / 前端 | ❌ 未改 |
| SOURCE-04-06 / 04-07 / 04-08 | ❌ 未启动 |

**变更文件**（6 个）：

```
backend/.../integration/capability/RegistrySnapshotParser.java    枚举严格校验 + 两个辅助
backend/.../source/application/CapabilityReconciler.java          守卫白名单化 + unverifiedDetail
backend/src/test/.../RegistrySnapshotParserTest.java             +4 用例
backend/src/test/.../CapabilityReconcilerTest.java               +2 用例
docs/architecture/collection/SOURCE-04-04-TASKCARD.md            §2.7 + §3.2.1（格 / 行 / 铁律 4）
docs/architecture/collection/SOURCE-04-04-EFFECTIVE-REASON.md    第 16 行条件与测试清单
docs/architecture/collection/SOURCE-04-03-FIX03-REPORT.md        本文件
```

---

## 七、待独立复核的点

1. **双层校验会不会造成"该拒的没拒、不该拒的拒了"**：
   解析层拒绝整份快照（而非跳过单条）是否过于激进？
   （我的判断：跳过会让能力**静默消失**，比整份拒绝更难发现。）
2. **白名单守卫是否覆盖全部路径**：`DISABLED`/`UNSUPPORTED` 在更早的步骤返回，
   因此不会走到这里——这是"提前返回"而非"豁免"，请核对顺序。
3. **`unverifiedDetail` 的三种语境是否真的可区分**：
   已按评审建议把 `UNVERIFIED + 已接受证据` 的文案从"数据自相矛盾"改为点明两个冲突字段
   （`implementationStatus=UNVERIFIED` / `evidence.accepted=true`）。
   注意 detail 只是诊断文本，机器判据仍是 `effectiveStatus` / `effectiveReason` / `executable`。
4. **F1-2 的第二层守卫是否有必要**：解析层已经拒绝，核对层兜底是否冗余？
   （我的理由：绕过解析器的构造路径存在——测试里就用到了；且跨语言枚举的破裂
   往往来自"某处新增了一条不经过该解析器的数据通路"。）
