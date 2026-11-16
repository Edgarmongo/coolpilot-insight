# SOURCE-04-02 第二轮代码评审报告

> 评审基线：`a204165`（第二轮修复）｜对照 `61150cc`（首轮实施）与 `8655f11`（首轮评审）
> 分支：`feat/boot4-jdk21-mybatis`
> 评审日期：2026-11-16
> 性质：**只读评审。未修改任何实现、数据库、Flyway、Schema、前端、分支或提交历史。**
>
> **最终结论：通过。** 无 BLOCKER、无 HIGH。首轮 2 个 HIGH 与 8 个 MEDIUM 全部闭环。
> 新发现 1 个 MEDIUM（N1，极罕见触发路径）与若干 LOW，已附处理计划，**不阻塞进入 04-03**。

---

## 一、变更范围核对

```
$ git status --short
（空，工作区干净）

$ git show --stat --oneline a204165
a204165 fix(source): SOURCE-04-02 第二轮修复——结构化错误、Adapter 完整性、Single-flight、三态与独立版本
 13 files changed, 1299 insertions(+), 287 deletions(-)

$ git diff --stat 61150cc a204165
 13 files changed, 1858 insertions(+), 287 deletions(-)
```

| 类别 | 文件 | 是否在授权范围 |
|---|---|---|
| Java 实现 | `CollectionServiceClient` / `RegistrySnapshot` / `RegistrySnapshotParser` / `SnapshotCache` | ✅ |
| Java 测试 | 3 个测试类 | ✅ |
| 测试资源 | `registry-snapshot-sample.json`（重新导出） | ✅ |
| Python 实现 | `bootstrap.py`（版本常量 + docstring） | ✅ |
| Python 测试 | `test_snapshot.py`（contractVersion 断言） | ✅ |
| 文档 | 实施报告 + 首轮评审报告追加 | ✅ |
| **数据库 / Flyway** | 无变更 | ✅ 仍停 V30 |
| **SOURCE-02 Schema** | 无变更 | ✅ |
| **前端 / 账号 / 状态机** | 无变更 | ✅ |

范围与授权一致，无越界。

---

## 二、第一轮问题逐项闭环

### H1 结构化错误分类 — ✅ 已关闭

**位置**：`RegistrySnapshotParser.java:44-70`（`Failure` 枚举 + `Error` record）、
`CollectionServiceClient.java:100-112`（`mapFailure`）

**实际行为**：

```java
public enum Failure {
    MALFORMED_BODY, MISSING_SNAPSHOT_ID, MALFORMED_SNAPSHOT_ID,
    UNSUPPORTED_SCHEMA, UNSUPPORTED_CONTRACT, MISSING_ARRAY,
    BROKEN_CAPABILITY, INCONSISTENT
}
public record Error(Failure failure, String message) { }
public record Result(RegistrySnapshot snapshot, Error error) { }
```

分类走**穷举 switch 表达式**：

```java
static FetchResult.Kind mapFailure(RegistrySnapshotParser.Failure failure) {
    return switch (failure) {
        case UNSUPPORTED_SCHEMA -> Kind.SCHEMA_UNSUPPORTED;
        case UNSUPPORTED_CONTRACT -> Kind.CONTRACT_UNSUPPORTED;
        case INCONSISTENT -> Kind.INCONSISTENT;
        case MALFORMED_BODY, MISSING_SNAPSHOT_ID, MALFORMED_SNAPSHOT_ID,
             MISSING_ARRAY, BROKEN_CAPABILITY -> Kind.MALFORMED_RESPONSE;
    };
}
```

| 用户要求 | 复核 |
|---|---|
| 不依赖自由文本 | ✅ `grep contains` 在 `CollectionServiceClient` 已无匹配 |
| 八种 Failure 均有独立测试 | ✅ 八值全部有对应用例（见 §四） |
| 不同 message 不影响 Failure | ✅ `classificationIsIndependentOfMessageWording` 用同一结构问题、不同空白格式断言 Failure 相同 |
| 未知错误不能静默放行 | ✅ switch 是穷举表达式，**新增枚举值直接编译报错**，不存在 default 兜底 |

**评价**：比"补一个枚举"更到位的是——穷举 switch 让"新增错误类型但忘记分类"成为**编译错误**而非运行时静默。这是结构性保证。

### H2 AdapterView 字段完整性 — ✅ 已关闭

**位置**：`RegistrySnapshot.java:52-70`、`RegistrySnapshotParser.java:170-190`

映射从 5 字段扩到 10 字段：

```java
public record AdapterView(String connectorCode, String sourceCode, String connectorVersion,
                          String loadStatus, String module, Boolean platformScoped,
                          Integer capabilityCount, String errorCode, String errorMessage,
                          List<CapabilityItem> capabilities) { }
```

| 用户要求 | 复核 |
|---|---|
| `platformScoped` 保留 | ✅ `RegistrySnapshotParser.java:184` 解析；真实样本断言 STUB=`false`、DOUYIN=`true` |
| `module` 保留 | ✅ 断言 `module="app.adapters.stub"` |
| 缺失字段有明确契约 | ⚠️ 有契约但方向可议，见 **N2** |
| `platformScopedAdapters()` 不误排除真实平台 | ✅ 见下方专项 |

**`platformScopedAdapters()` 是否误排除**（用户点名）：

```java
public boolean isPlatformScoped() {
    return platformScoped == null || Boolean.TRUE.equals(platformScoped);
}
```

判定表：

| 输入 | 结果 | 是否误排除 |
|---|---|---|
| `true` | 保留 | 否 |
| `false` | 排除 | 否（正确排除替身） |
| `null`（缺失） | **保留** | 否（宁可多留，不误杀真实接入） |

**结论**：不会误排除真实平台接入。`platformScopedAdapters()` 对真实样本的过滤结果经测试断言为 `containsExactly("DOUYIN_PUBLIC")`——STUB 被排除，DOUYIN 被保留。

### Single-flight 并发 — ✅ 已关闭

**位置**：`SnapshotCache.java:157-192`（`refreshShared`）、`197-217`（`performRefresh`）

**实际行为**：

```java
synchronized (flightLock) {
    if (inFlight == null) { inFlight = new CompletableFuture<>(); owner = true; }
    else { future = inFlight; owner = false; }
}
if (!owner) { return future.join(); }        // 等待者共享结果
try {
    result = performRefresh();               // 网络在锁外
    future.complete(result);
    return result;
} catch (RuntimeException e) {
    future.completeExceptionally(e);
    return read();
} finally {
    synchronized (flightLock) { if (inFlight == future) inFlight = null; }
}
```

| 用户要求 | 复核 | 证据 |
|---|---|---|
| 多线程首次获取只一次 HTTP | ✅ | `concurrentFirstFetchIssuesSingleHttpCall`：20 线程 → `calls == 1` |
| 多线程过期刷新只一次 HTTP | ✅ | `concurrentRefreshOfStaleCacheIssuesSingleHttpCall`：增量 `== 1` |
| 等待者共享同一结果 | ✅ | 等待者 `join()` 返回 owner 的同一个 `SnapshotRead` 实例 |
| owner 成功清理 | ✅ | `finally` + `if (inFlight == future)` 防误清 |
| owner 失败清理 | ✅ | catch 后走 finally |
| owner **超时**清理 | ✅ | `CollectionServiceClient` 有 connect 2s + request 5s；超时被 `catch (Exception)` 转为失败结果 |
| owner **异常**清理 | ⚠️ **部分**：`RuntimeException` 已处理；`Error` 路径 future 永不完成，见 **N1** | jshell 实证：`finally 执行=true` 但 `future 从未完成` |
| 失败后退避对所有入口生效 | ✅ | 退避在 `get()` 入口（`read().lastAttemptTooRecent()`）**与** `performRefresh()` 开头**双重检查**；`manualRefreshIsAlsoThrottled` 证明 `refresh()` 也受约束 |
| 不存在永久未完成 Future | ⚠️ **Error 路径存在**，见 N1 | 同上 |
| 不发生部分快照更新 | ✅ | `Entry` 为 record（字段全 final），整体 `new` 替换；`readDuringRefreshSeesCompleteOldSnapshot` 断言刷新中读到完整旧快照 |

**并发测试的辨别力**（这是判断测试是否"真在测"的关键）：

| 用例 | 实测耗时 | 若串行应为 | 说明 |
|---|---|---|---|
| `concurrentFirstFetchIssuesSingleHttpCall` | **0.249s** | 20 × 80ms = 1.6s | 0.249s ≈ 单次 80ms + 开销 → **确为真并发**，且只发 1 次 |
| `concurrentFailureYieldsConsistentResult` | **0.224s** | 20 × 60ms = 1.2s | 同上 |
| `concurrentRefreshOfStaleCacheIssuesSingleHttpCall` | 1.564s | — | 含 `sleep(1200)` 预热超 TTL，符合预期 |

如果 Single-flight 失效，`calls` 会是 20，`assertThat(calls.get()).isEqualTo(1)` 立即失败。
这些测试**有辨别力**，不是"跑通即通过"。

### 三态模型 — ✅ 已关闭

**位置**：`SnapshotCache.java:287-320`（`SnapshotCacheState` + `SnapshotRead`）

```java
public record SnapshotRead(RegistrySnapshot snapshot, SnapshotCacheState state,
                           boolean usableForExecution, ...) {
    public SnapshotRead {   // compact constructor 强制不变量
        if (state != FRESH && usableForExecution)
            throw new IllegalArgumentException("非 FRESH 状态不得标记为可执行: " + state);
        if (state == UNAVAILABLE && snapshot != null)
            throw new IllegalArgumentException("UNAVAILABLE 不应携带快照");
        if (state != UNAVAILABLE && snapshot == null)
            throw new IllegalArgumentException(state + " 必须携带快照");
    }
}
```

| 用户要求 | 复核 |
|---|---|
| FRESH / STALE / UNAVAILABLE 不变量正确 | ✅ 三条不变量在 canonical constructor 中强制 |
| 不存在构造绕过不变量 | ✅ record 的 canonical constructor 必然执行；`SnapshotRead` 不是反序列化入口，无 Jackson 绕过路径 |
| STALE 不得执行 | ✅ `usableForExecution` 在 `STALE` 时传 `false`，且构造器拒绝 `true` |
| UNAVAILABLE 不得携带历史快照 | ✅ 构造器强制 `snapshot == null` |

**`usableForExecution` 是否只有 FRESH 能通过**（用户额外检查项）：

构造层：`STALE + true` → 抛异常；`UNAVAILABLE + true` → 抛异常。所以**只有 FRESH 可以标记 true**。
反向（`FRESH + false`）是允许的——这更保守，不构成问题。

测试 `nonFreshCannotBeExecutable` 用 `assertThatThrownBy` 锁定这两条。

### 版本管理 — ✅ 已关闭

| 用户要求 | 复核 | 证据 |
|---|---|---|
| schemaVersion 独立校验 | ✅ | `RegistrySnapshotParser.java:132-141`，`UNSUPPORTED_SCHEMA` |
| contractVersion 独立校验 | ✅ | `RegistrySnapshotParser.java:144-153`，`UNSUPPORTED_CONTRACT` |
| 不兼容版本拒绝缓存更新 | ✅ | 解析失败 → `FetchResult.ok()==false` → `applyResult` 走失败分支，不覆盖合法快照（`invalidPayloadDoesNotOverwriteValidSnapshot`） |
| 不能再通过常量绑定两个版本 | ✅ | Python `SNAPSHOT_CONTRACT_VERSION = "1.0"`（`bootstrap.py:42`），`SCHEMA_VERSION = "1.1"` |

**运行时实证**（容器实测）：

```
contractVersion = 1.0
schemaVersion   = 1.1
两者独立        = True
```

这是"两个版本真的独立"的**可观测证据**，不是口头声明。

**独立校验的可证伪性**：`rejectsUnsupportedContractMajor` 用"Schema 合法（1.1）+ Contract 2.0"，
断言必须报 `UNSUPPORTED_CONTRACT`。如果两者被合并判断，这个用例会拿到 `UNSUPPORTED_SCHEMA` 或直接通过——测试会红。

### Token 与错误处理 — ✅ 已关闭（M7）

**位置**：`CollectionSnapshotClientTest.java:159-190`

改为启动本地 `HttpServer` 记录请求头：

```java
seen.set("__unset__");
new CollectionServiceClient(url, "").fetchSnapshot();
assertThat(seen.get()).as("未配置令牌时不应发送该头").isNull();

seen.set(null);
new CollectionServiceClient(url, "secret-token").fetchSnapshot();
assertThat(seen.get()).isEqualTo("secret-token");
```

不再依赖"服务恰好没配 `INTERNAL_TOKEN`"这种环境假设。**确定性**。

同时补齐了四种错误分类断言：`HTTP_ERROR` / `MALFORMED_RESPONSE` / `SCHEMA_UNSUPPORTED` / `CONTRACT_UNSUPPORTED` / `UNREACHABLE`。

---

## 三、额外检查（用户点名六项）

| # | 检查项 | 结论 | 依据 |
|---|---|---|---|
| 1 | Python `CapabilityEvaluation` 深层不可变性 | ⚠️ **部分**（LOW） | `@dataclass(frozen=True)` 只阻止字段重赋值，`observed: list` 仍可 `append`。元素 `CapabilitySpec` 本身是 frozen。实际影响小：`snapshot()` 每次新建 evaluation 且只读。见 **N5** |
| 2 | `snapshotId` 与规范化内容始终一致 | ✅ | `body` 五字段参与哈希，`generatedAt` 在 body 外；`canonical_json` 用 `sort_keys=True`。残余风险见 **N6**（浅拷贝） |
| 3 | `platformScoped=false` 能否被业务层可靠排除 | ⚠️ **工具已备，未强制**（观察项） | `platformScopedAdapters()` 提供入口，但 `adapters()` 仍返回全部。**没有任何机制强制 04-03 使用前者**。见 **N7** |
| 4 | `usableForExecution` 是否只有 FRESH 可通过 | ✅ | compact constructor 强制；`nonFreshCannotBeExecutable` 锁定 |
| 5 | HTTP 超时及异常是否释放 Single-flight | ⚠️ **部分** | 超时 ✅（客户端有 2s/5s 超时，异常转失败结果）；`Error` ❌（future 永不完成）。见 **N1** |
| 6 | 第一轮 M7、M8 是否真正关闭 | M7 ✅ / M8 ⚠️ | M7 改确定性测试；M8 只加了 docstring 注释，**无代码级保护**。见 **N8** |

---

## 四、测试执行结果（实际运行）

### Python（容器内 `cpi-collection`）

```
test_registry      23/23 passed
test_server        17/17 passed
test_evidence      16/16 passed
test_snapshot      17/17 passed
                 ─────────────
合计                73/73
```

### Java（`mvn -o test`）

```
Tests run: 192, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

构成：原有 147 + `RegistrySnapshotParserTest` 18 + `SnapshotCacheTest` 17 + `CollectionSnapshotClientTest` 10。

### 并发用例逐条

```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.157 s
```

8 条分别为：首次获取单飞、过期刷新单飞、失败结果一致、退避内不重试、
退避后允许、刷新中读完整旧快照、读不被刷新阻塞、主动刷新受退避约束。

### 真实 Collection Service HTTP 往返

`CollectionSnapshotClientTest` 中 4 条直连 `localhost:5001`，**不读静态样本、不用 assume 跳过**，
容器不可达即失败。样本文件仅被 `RegistrySnapshotParserTest` 用于解析器单测，两者未混淆。

### 五容器健康

```
cpi-backend      Up 4 minutes (healthy)
cpi-collection   Up 6 minutes (healthy)
cpi-flask        Up 7 hours (healthy)
cpi-frontend     Up 6 hours
cpi-mysql        Up 7 hours (healthy)
```

### 容器间实测

```
backend → http://collection:5001/v1/registry/snapshot
  contractVersion = 1.0
  schemaVersion   = 1.1
  adapters: [('STUB_PUBLIC', False), ('DOUYIN_PUBLIC', True)]
```

---

## 五、新发现的问题

### N1（MEDIUM）：owner 抛 `Error` 时 future 永不完成，等待者永久阻塞

**位置**：`SnapshotCache.java:180` ‑ `catch (RuntimeException e)`

**实际行为**：

```java
try {
    result = performRefresh();
    future.complete(result);
    return result;
} catch (RuntimeException e) {          // ← 不捕获 Error
    future.completeExceptionally(e);
    return read();
} finally {
    synchronized (flightLock) { if (inFlight == future) inFlight = null; }
}
```

若 `performRefresh()` 抛 `Error`（`OutOfMemoryError` / `StackOverflowError` /
`NoClassDefFoundError` 等）：

1. `catch (RuntimeException)` **不匹配**；
2. `finally` 执行，`inFlight` 被清为 `null`；
3. `future` **从未 complete**；
4. 已持有该 `future` 引用的等待者 `join()` **永久阻塞**。

**实证**（jshell 等价复现，未触碰项目代码）：

```
finally 执行(模拟清 inFlight): true
RESULT: future 从未完成 -> 等待者 join() 永久阻塞
```

**影响范围（须准确表述）**：

- **不是**服务永久死锁：`inFlight` 已被清理，**后续新请求会创建新 future 正常刷新**；
- 受影响的是**当时正在等待的那批线程**，它们会一直占住线程池线程；
- 若并发度高到把请求线程池占满，会升级为服务级不可用——但前置条件仍是 JVM 已抛 `Error`。

**等级判断**：MEDIUM。理由：触发条件是 `Error`（通常意味着 JVM 已处病危状态），
不是可预期的运行时故障；但后果（线程永久泄漏）是真实的，且修复成本极低。

**建议修复**（3 行）：

```java
} catch (RuntimeException | Error e) {
    future.completeExceptionally(e);
    ...
}
```

或更稳妥地用 `catch (Throwable t)` + 区分处理（`Error` 记录后重新抛出，保证可见性）。

**是否影响 04-03**：不阻塞。04-03 调用 `get()` 的路径在正常情况下由 `CollectionServiceClient`
的 2s/5s 超时保护，不会走到 Error 路径。建议在 04-03 实施时顺手修掉。

### N2（MEDIUM）：`platformScoped` 缺失时静默按 `true`，掩盖协议异常

**位置**：`RegistrySnapshotParser.java:184`、`RegistrySnapshot.java:67-69`

```java
// Parser：缺失 → null
n.hasNonNull("platformScoped") ? n.get("platformScoped").asBoolean() : null

// DTO：null → true
public boolean isPlatformScoped() {
    return platformScoped == null || Boolean.TRUE.equals(platformScoped);
}
```

**问题**：Python 侧 `AdapterMetadata.to_v1()` **无条件输出** `platformScoped`（`capability.py:253`）。
所以 Java 收到 `null` 只能说明**协议异常**或上游回退。

当前按 `true` 处理 = 把"未知"当成"真实平台接入" = **误纳入**方向。这与项目一贯的
"不静默、不猜测"原则不一致——解析器对其他缺失字段都是直接拒绝（`BROKEN_CAPABILITY`），
唯独这里做了静默兜底。

**为什么不是 HIGH**：当前真实快照一定带该字段，实际不会触发；且"保留"比"排除"更保守
（多算一个接入不会导致漏采，只会导致 04-03 多报一条"未声明"）。

**建议**：缺失时按 `BROKEN_CAPABILITY` 拒绝快照，而不是静默选边。
若坚持兜底，应把该约定写进 `SOURCE-04-02-IMPLEMENTATION.md` 的契约章节，
而不是只写在 javadoc 里。

### N3（LOW）：`clear()` 不清 `inFlight`

**位置**：`SnapshotCache.java:259-264`

```java
public void clear() {
    synchronized (flightLock) {
        this.entry = null;
        this.lastFailureAt = null;
    }
    // inFlight 未清理
}
```

若清空时有刷新在进行，清空后刷新仍会把 `entry` 填回去。测试专用方法，实际影响小。
建议注释说明"clear 不打断进行中的刷新"。

### N4（LOW）：`capabilityCount` 与 `capabilities.size()` 未交叉校验

`AdapterView.capabilityCount` 来自 JSON 字段，`capabilities` 来自数组。两者不一致时
Java 静默接受。建议在解析器加一条断言（不一致 → `BROKEN_CAPABILITY`）。

### N5（LOW）：`CapabilityEvaluation` 非深层不可变

**位置**：`capability_registry.py:239-247`

```python
@dataclass(frozen=True)
class CapabilityEvaluation:
    observed: list[tuple[str, CapabilitySpec]]
    diagnostics: list[CapabilityDiagnostic]
```

`frozen=True` 阻止字段重赋值，但 `observed` 是可变 list，外部可 `append`。
元素 `CapabilitySpec` 本身是 frozen，所以只影响 list 结构。

实际影响小（`snapshot()` 每次新建且只读），但若将来有代码复用 evaluation 对象需注意。
建议改 `tuple` 或加 `tuple(...)` 包装。

### N6（LOW）：`snapshot()` 返回的 dict 与参与哈希的 `body` 共享嵌套 list

`**body` 是浅拷贝，`adapters` / `capabilities` / `diagnostics` 三个 list 是同一对象引用。
`snapshotId` 在返回前已算好，若调用方随后修改返回值的 list，ID 会与内容不一致。

当前 Flask 直接 `jsonify` 不修改，实际无风险。建议在 docstring 注明返回对象应视为只读。

### N7（观察，非缺陷）：`platformScopedAdapters()` 提供了但未强制

`adapters()` 仍返回全部接入（含替身）。是否排除完全取决于 04-03 是否调用
`platformScopedAdapters()`。

这正是本轮 H2 修复的目标——**提供结构化依据**。但"提供了工具"不等于"必然使用"。
建议在 04-03 的任务卡里明确写出「核对必须遍历 `platformScopedAdapters()`，
不得遍历 `adapters()`」，并加一条测试断言。

### N8（LOW）：M8 只在文档层面关闭

`snapshot()` 的 docstring 写明了运行期只读前提与热加载需加锁，**但没有代码级保护**
（无 assert、无锁、无测试）。若将来有人在运行期调 `declare()`，仍会出现
`RuntimeError: list changed size during iteration`。

建议至少加一条注释性测试或用 `_frozen` 标志在 `declare()` 时检测运行期调用。

---

## 六、第一轮问题闭环总表

| 编号 | 原等级 | 本轮结论 |
|---|---|---|
| H1 结构化错误 | HIGH | ✅ **已关闭** |
| H2 AdapterView 完整性 | HIGH | ✅ **已关闭** |
| M1 并发重复请求 | MEDIUM→HIGH | ✅ **已关闭**（Single-flight + 3 条单飞测试） |
| M2 锁内未二次检查 | MEDIUM | ✅ **已关闭**（退避双重检查） |
| M3 不校验 contractVersion | MEDIUM | ✅ **已关闭**（独立 Failure + 独立测试） |
| M4 三态不清晰 | MEDIUM | ✅ **已关闭**（枚举 + compact constructor） |
| M5 版本常量绑定 | MEDIUM | ✅ **已关闭**（1.0 / 1.1 独立，运行时可见） |
| M6 退避位置 / 无并发测试 | MEDIUM | ✅ **已关闭**（7 条并发用例，有辨别力） |
| M7 token 测试环境假设 | MEDIUM | ✅ **已关闭**（本地 HttpServer） |
| M8 Python 并发假设 | MEDIUM | ⚠️ **文档关闭，代码未保护**（N8） |
| L1 `Entry.lastFailureAt` 死字段 | LOW | ✅ 已关闭 |
| L2 `default=str` 兜底 | LOW | ⚠️ 保留（已记录理由） |
| L3 数组顺序语义 | LOW | ✅ 已关闭（docstring 明示） |
| L4 `frozen` + 可变 list | LOW | ⚠️ 保留（见 N5） |

---

## 七、最终结论

### 7.1 判定

**通过。**

| 门槛 | 结果 |
|---|---|
| BLOCKER | 0 |
| HIGH | **0** |
| 新发现 MEDIUM | **2**（N1、N2） |
| 新发现 LOW | 5（N3~N6、N8）+ 1 观察（N7） |

用户设定的六项重点检查：

| # | 重点 | 结果 |
|---|---|---|
| 1 | 错误分类完全不依赖自由文本 | ✅ 成立 |
| 2 | `platformScoped` 不再丢失 | ✅ 成立 |
| 3 | 并发刷新确实只有一次 HTTP | ✅ 成立（有耗时证据支撑） |
| 4 | Schema 与 Contract 独立校验 | ✅ 成立 |
| 5 | 缓存历史数据不能被误认为当前有效能力 | ✅ 成立 |
| 6 | Token 与错误处理 | ✅ 成立（M7 已确定性化） |

按用户门禁「只有 MEDIUM/LOW → 记录问题和处理计划，评估是否影响 04-03」：
本次属该情形，**不阻塞进入 SOURCE-04-03**。

### 7.2 对 04-03 的影响评估

| 问题 | 是否影响 04-03 | 说明 |
|---|---|---|
| N1（Error 路径） | **不阻塞** | 正常路径由客户端超时保护；建议 04-03 实施时顺手修（3 行） |
| N2（platformScoped 缺失兜底） | **不阻塞** | 真实快照必带该字段；但建议在 04-03 前把契约写清 |
| N7（未强制用 platformScopedAdapters） | **需在 04-03 明确** | 应在 04-03 任务卡写死"必须遍历 `platformScopedAdapters()`"并加测试 |

### 7.3 做对了的地方（值得保留）

1. **穷举 switch 而非 default 兜底**——把"新增错误类型忘记分类"变成编译错误。
2. **锁内只决定 owner，网络在锁外**——读线程不被刷新阻塞（有 500ms 断言）。
3. **compact constructor 强制不变量**——"非 FRESH 却可执行"在构造对象时就不可能存在，不靠调用方自觉。
4. **退避双重检查**——入口 + 发请求前，堵住"并发排队绕过入口"的漏洞。
5. **`refresh()` 也受退避约束**——避免主动刷新成为后门，且用测试固化。
6. **并发测试断言调用次数而非"跑通"**——0.249s 的耗时反证了确实并发。

### 7.4 建议的处理顺序

1. **N1**（3 行）—— 进入 04-03 时一并处理，或单独提一个小修复提交；
2. **N2** —— 在 04-03 任务卡里补一条契约说明（缺失即拒绝），避免 04-03 依赖静默兜底；
3. **N7** —— 写入 04-03 验收要求；
4. N3~N6、N8 —— 记录，按需处理。

---

## 八、未实施声明

| 项目 | 状态 |
|---|---|
| Java / Python 实现 | ❌ 未修改 |
| 数据库 / Flyway | ❌ 未修改（仍停 V30） |
| `SOURCE-02-capability-schema.json` | ❌ 未修改 |
| 前端 | ❌ 未修改 |
| SOURCE-04-03 | ❌ 未启动 |
| 分支 / 提交历史 | ❌ 未变（仍在 `a204165`） |

评审期间在 `backend/target/verify/` 生成过一个临时 jshell 验证类用于证明 N1，
**已删除**，未进入版本控制（`target/` 为构建产物目录）。

**本轮唯一新增文件**：本报告。
