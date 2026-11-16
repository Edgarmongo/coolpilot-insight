# SOURCE-04-02-FIX-01 独立代码复核报告

> 复核对象：`43f8107`（基线 `3b0b7ab`）
> 分支：`feat/boot4-jdk21-mybatis`
> 复核日期：2026-11-16
> 性质：**只读复核**。未修改 Java/Python 实现、测试实现、数据库、Flyway、
> SOURCE-02 Schema、SOURCE-04-03 业务实现；未创建 `CapabilityReconciler`；
> 未实现 Desired/Observed/Effective；未改变分支与提交历史。
>
> **最终结论：PASS WITH FINDINGS**
> BLOCKER 0 / HIGH 0。N1 所有异常路径有确定性结论；N2 所有非法输入失败关闭。
> 但 **N7-2 未进入验收标准**，与「六项约束同时出现在实现要求与验收标准」的通过标准不符。
> 另有 2 项 LOW。

---

## 一、Git 基线与目标提交

```
$ git log --oneline -4
43f8107 fix(source): SOURCE-04-02-FIX-01 关闭评审遗留 N1/N2，N7 冻结进 04-03
3b0b7ab docs(source): SOURCE-04-02 第二轮代码评审（结论：通过，附 2 个 MEDIUM）
a204165 fix(source): SOURCE-04-02 第二轮修复——结构化错误、Adapter 完整性、Single-flight、三态与独立版本
8655f11 docs(source): SOURCE-04-02 代码与契约评审（结论：不通过，2 个 HIGH）

$ git status --short
（空，复核开始时工作区干净）
```

基线 `3b0b7ab` ✅，目标 `43f8107` ✅，均在同一分支线上，无历史改写。

## 二、实际差异文件范围

```
$ git show --stat --oneline 43f8107
 11 files changed, 642 insertions(+), 14 deletions(-)
```

| 文件 | 行数 | 是否在授权范围 |
|---|---|---|
| `SnapshotCache.java` | +13 / -3 | ✅ N1 |
| `RegistrySnapshot.java` | +16 / -6 | ✅ N2 |
| `RegistrySnapshotParser.java` | +25 / -3 | ✅ N2 |
| `CollectionServiceClient.java` | +3 / -1 | ✅ N2（新 Failure 的穷举分支） |
| `RegistrySnapshotParserTest.java` | +45 | ✅ 测试 |
| `SnapshotCacheTest.java` | +74 | ✅ 测试 |
| `capability.py` | +16 | ✅ N2 |
| `test_snapshot.py` | +52 | ✅ 测试 |
| `SOURCE-04-TASKCARD.md` | +90 / -1 | ✅ N7 |
| `SOURCE-04-02-FIX01-REPORT.md` | +280（新增） | ✅ 报告 |
| `.workbuddy/memory/*` | +42 | ⚠️ 见下 |

**唯一范围外项**：`.workbuddy/memory/2026-11-16-supplement-review.md`（+42 行）。
这是项目记忆文件，不属代码、数据库、Schema、前端，也未触及被禁止的对象。
但严格按本轮「不改实现」的边界，它属于**过程记录**而非实现改动，
不影响功能正确性。记为观察，不判缺陷。

**实际改动方向**：与 N1、N2、N7 三个目标一一对应，无夹带。

---

## 三、N1 逐条代码路径审查

**文件**：`backend/src/main/java/com/coolpilot/insight/integration/capability/SnapshotCache.java`

### 3.1 修复后的关键代码（第 170-200 行）

```java
CompletableFuture<SnapshotRead> future;
boolean owner;
synchronized (flightLock) {
    if (inFlight == null) {
        inFlight = new CompletableFuture<>();
        future = inFlight;
        owner = true;
    } else {
        future = inFlight;
        owner = false;
    }
}

if (!owner) {
    try {
        return future.join();
    } catch (CompletionException | CancellationException e) {
        return read();
    }
}

SnapshotRead result;
try {
    result = performRefresh();
    future.complete(result);
    return result;
} catch (RuntimeException | Error e) {
    future.completeExceptionally(e);
    log.warn("[capability] 刷新过程异常（{}），等待线程共享失败: {}",
            e.getClass().getSimpleName(), e.toString());
    if (e instanceof Error error) {
        throw error;
    }
    return read();
} finally {
    synchronized (flightLock) {
        if (inFlight == future) {
            inFlight = null;
        }
    }
}
```

### 3.2 八条路径逐条结论

| # | 用户要求 | 代码位置 | 结论 |
|---|---|---|---|
| 1 | owner 正常返回，Future 正常完成 | `:178` `future.complete(result)` → `:179` return | ✅ 完成 |
| 2 | owner 抛 `RuntimeException`，Future 异常完成 | `:180` catch → `:182` `completeExceptionally` | ✅ 完成 |
| 3 | owner 抛 `Error`，Future 异常完成 | `:180` catch `RuntimeException \| Error` → `:182` | ✅ 完成 |
| 4 | `Error` 继续向 owner 线程传播，没被吞掉 | `:187-190` `if (e instanceof Error error) throw error;` | ✅ 重抛 |
| 5 | 所有退出路径清理 `inFlight` | `:191-195` `finally` 无 return/throw 前的条件分支 | ✅ 覆盖三条出口 |
| 6 | waiter 能从 `join` 退出 | `:172-176` `join()` 被 `complete`/`completeExceptionally` 唤醒 | ✅ 见 3.3 路径分析 |
| 7 | 前一次 owner 失败后新请求能重新发起 | `finally` 清空 `inFlight` → 下一请求成为新 owner | ✅ 有测试断言 |
| 8 | 其他异常路径导致 Future 永不完成 | 见 3.4 逐个排查 | ✅ 未发现（除 provider 永久阻塞，见 F3） |

### 3.3 waiter 的退出路径（结合 `CompletableFuture` 语义）

`future.join()` 的行为取决于 future 的完成方式：

| owner 完成方式 | `join()` 行为 | waiter 代码路径 | 结果 |
|---|---|---|---|
| `complete(result)` | 返回 `result` | `:173` return | 得到同一 `SnapshotRead` 实例 |
| `completeExceptionally(RuntimeException)` | 抛 `CompletionException(cause=原异常)` | `:174` catch → `:175` `read()` | 降级为当前缓存状态 |
| `completeExceptionally(Error)` | 抛 `CompletionException(cause=Error)` | 同上 | 同上，**不会把 Error 抛给 waiter** |
| 从未完成 | **永久阻塞** | — | ❌ 这是修复前的问题，现已消除 |

**特别确认**：`join()` 对 `completeExceptionally(Error)` 会包装为 `CompletionException`
（JDK 实现：`if (x instanceof CompletionException) throw ... ; throw new CompletionException(x);`），
因此 waiter 的 `catch (CompletionException | CancellationException)` 能正常捕获，
不会因 Error 而线程死亡。

### 3.4 四项特别检查

**检查 A：`completeExceptionally()` 是否在清理 `inFlight` 前执行**

✅ **是**。`:182` 在 `catch` 块内，`:191` 的 `finally` 在 `catch` 之后执行。
Java 语义保证 catch 块先于 finally。

**检查 B：`finally` 是否覆盖所有退出路径**

逐条核对：

| 出口 | 是否经过 finally |
|---|---|
| `:179` `return result`（正常） | ✅ |
| `:184` `return read()`（RuntimeException） | ✅ |
| `:189` `throw error`（Error） | ✅ |
| `performRefresh()` 抛未捕获的 `Throwable`（如有） | ✅ |

`finally` 内无 `return`/`throw` 提前跳出，三个出口都会执行清理。

**检查 C：Future 已完成但 `inFlight` 被错误覆盖的竞态**

分析时序：

```
t1  owner: future.complete(result)      ← future 已完成，inFlight 仍指向 future
t2  新线程 T2 进入 refreshShared()
    → synchronized 块内 inFlight != null → T2 成为 waiter（不是 owner）
    → T2 join() 已完成 → 立即返回
t3  owner: finally → 清 inFlight
t4  新线程 T3 进入 → inFlight == null → T3 成为新 owner
```

**结论**：从 `complete` 到 `finally` 之间到达的线程只会成为 waiter，不会创建新 owner，
因此不存在"Future 已完成但被新 future 覆盖"的竞态。✅

**检查 D：多个 owner / 旧 owner 清理新 owner 的 `inFlight`**

| 场景 | 分析 | 结论 |
|---|---|---|
| 同时多个 owner | owner 的产生条件是 `inFlight == null`，且判定在 `synchronized` 内。任一时刻至多一个 owner | ✅ 不可能 |
| 旧 owner 清理新 owner | `:193` `if (inFlight == future)` 用引用相等判断，只清自己创建的那个 | ✅ 不会误清 |
| 清理竞态 | 判定与置空在同一个 `synchronized (flightLock)` 块内 | ✅ 原子 |

**检查 E：`read()` 路径是否可能抛异常，导致 `performRefresh()` 的返回值构造失败**

`read()`（`:88-113`）有 4 处 `new SnapshotRead(...)`，而 `SnapshotRead` 的
compact constructor 会校验三条不变量，违反即抛 `IllegalArgumentException`：

| 构造点 | 传入 | 校验结果 |
|---|---|---|
| `:95` `e == null` | `(null, UNAVAILABLE, false)` | ✅ 合法（UNAVAILABLE 要求 snapshot 为 null） |
| `:104` `e.stale()` | `(e.snapshot(), STALE, false)` | ✅ 合法（STALE 要求携带快照） |
| `:109` 超 TTL | `(e.snapshot(), STALE, false)` | ✅ 合法 |
| `:112` 正常 | `(e.snapshot(), FRESH, true)` | ✅ 合法（FRESH 允许 true） |

`e.snapshot()` 恒非 null：`Entry` 只在 `applyResult` 的 `result.ok()` 分支创建，
而 `FetchResult.ok()` 的定义是 `kind == OK && snapshot != null`。
因此 `read()` **不会**因不变量校验抛异常。

即便将来某条分支违反了不变量，异常也会传播到 `refreshShared()` 的
`catch (RuntimeException | Error)` → `completeExceptionally` → waiter 照常退出。
**不存在"Future 永不完成"的残留路径。**

**检查 F：`performRefresh()` 内部 `synchronized (flightLock) { applyResult(result); }` 抛异常**

若 `applyResult` 抛 `RuntimeException`/`Error`（例如 `result.snapshot()` 意外为 null
导致的 NPE），异常会穿过 `performRefresh()` 传播到 `refreshShared()` 的 catch，
同样完成 future 并清理 `inFlight`（`:191` 的 finally 覆盖该出口）。

### 3.5 N1 结论

**所有异常路径均有确定性结论，Future 不存在"永远未完成"的代码路径**（除 F3 的接口层超时缺失）。

---

## 四、N2 Python / Java 双侧审查

### 4.1 Python 侧

**位置**：`collection-service/app/registry/capability.py:245-261`

```python
def __post_init__(self) -> None:
    if not isinstance(self.platform_scoped, bool):
        raise TypeError(
            "platform_scoped 必须是 bool，实际为 "
            f"{type(self.platform_scoped).__name__}: {self.platform_scoped!r}"
        )
```

**构造入口全量核查**（`grep -rn "AdapterMetadata(" --include=*.py`）：

| 位置 | 性质 | 是否经过校验 |
|---|---|---|
| `adapter_registry.py:43` `AdapterEntry.to_metadata()` | 生产路径 | ✅ 构造即校验 |
| `test_snapshot.py:216 / 233` | 测试 | ✅ |

**关键确认：`adapter_registry.py` 的 `AdapterMetadata` 是否同一个类？**

```python
# adapter_registry.py:16-21
from app.registry.capability import (
    AdapterMetadata, CapabilitySpec, LoadStatus, PLATFORM_SOURCE_CODES,
)
```

✅ **是同一个类**（import 而非本地重定义）。`AdapterEntry` 是另一个 dataclass，
但它的 `to_metadata()` 会把值传入 `AdapterMetadata(...)`，触发校验。

**是否存在反序列化绕过**：`grep "def from_dict\|from_json\|object_hook"` 在
`app/registry/` 只命中 `evidence.py` 的 `EvidenceScope.from_dict` 与
`EvidenceRecord.from_dict`，与 `AdapterMetadata` 无关。✅ 无绕过路径。

**`to_v1()` 类型是否稳定**：`capability.py:269` 直接输出 `self.platform_scoped`，
而 `__post_init__` 已保证其为 `bool`，所以输出恒为 JSON boolean。✅

**运行时实证**（容器内 stdin 执行，未写任何项目文件）：

```
=== 非法取值是否被拒绝（N2 fail-close） ===
  AdapterMetadata(platform_scoped='false') -> TypeError ✅
  AdapterMetadata(platform_scoped='true')  -> TypeError ✅
  AdapterMetadata(platform_scoped=0)       -> TypeError ✅
  AdapterMetadata(platform_scoped=1)       -> TypeError ✅
  AdapterMetadata(platform_scoped=None)    -> TypeError ✅
  AdapterMetadata(platform_scoped=[])      -> TypeError ✅
  AdapterMetadata(platform_scoped={})      -> TypeError ✅

=== 合法取值 ===
  platform_scoped=True  -> to_v1()['platformScoped']=True  type=bool is_bool=True
  platform_scoped=False -> to_v1()['platformScoped']=False type=bool is_bool=True
```

**注意 `1` 也被拒绝**：`isinstance(1, bool)` 为 `False`（Python 中 `bool` 是 `int` 的子类，
方向单向），所以整数不会被误当成布尔。这一点很关键——若用 `isinstance(x, int)` 就会漏。

### 4.2 Java 侧

**位置**：`RegistrySnapshotParser.java:170-200`

```java
String connectorCode = CapabilityContractParser.text(n, "connectorCode");
if (connectorCode == null || connectorCode.isBlank()) {
    return Result.failure(Failure.BROKEN_ADAPTER, "Adapter 条目缺少 connectorCode");
}

JsonNode psNode = n.get("platformScoped");
if (psNode == null || psNode.isNull()) {
    return Result.failure(Failure.BROKEN_ADAPTER,
            "Adapter " + connectorCode + " 缺少 platformScoped");
}
if (!psNode.isBoolean()) {
    return Result.failure(Failure.BROKEN_ADAPTER,
            "Adapter " + connectorCode + " 的 platformScoped 不是布尔值: " + psNode.getNodeType());
}
```

| 用户要求 | 结论 | 依据 |
|---|---|---|
| 缺失 → 拒绝 | ✅ | `:178-181` `psNode == null \|\| isNull()` |
| `null` → 拒绝 | ✅ | 同上（`isNull()` 覆盖显式 null） |
| 字符串 → 拒绝 | ✅ | `:182` `!psNode.isBoolean()`。`"false"` 是 `TextNode`，被拒 |
| 数字 → 拒绝 | ✅ | 同上。`0` 是 `IntNode`，被拒 |
| 错误归类为 `BROKEN_ADAPTER` | ✅ | 三处均用该码 |
| 拒绝整个快照、不接受部分 Adapter | ✅ | `Result.failure(...)` 直接 return，**不是**跳过该条继续解析 |
| 错误快照不覆盖历史合法快照 | ✅ | `SnapshotCache.applyResult` 在 `result.ok()==false` 时走失败分支 |
| primitive boolean DTO 是否有绕过入口 | ✅ 无 | `AdapterView` 是 record，构造器唯一；无 `@JsonCreator`；不被反序列化 |

**为什么 `!isBoolean()` 比 `asBoolean()` 严格**：Jackson 的 `asBoolean()` 对
`TextNode("false")` 会返回 `false`、对 `IntNode(1)` 返回 `true`，属于容错解析。
`isBoolean()` 只对 `BooleanNode` 返回 true，从类型层面杜绝了"字符串被当布尔"。

**DTO 的 `boolean` 是结构性保证**：

```java
public record AdapterView(..., boolean platformScoped, ...) {
    public boolean isPlatformScoped() { return platformScoped; }
}
```

修复前是 `Boolean` + `null → true`。现在参数类型是原始 `boolean`，**无法传 null**。
即便将来有人用 Jackson 反序列化 `RegistrySnapshot`，缺字段时原始型默认 `false`
（fail-close，安全方向）。

### 4.3 真实往返行为

```
所有 adapter 的 platformScoped 均为 bool: True
[('STUB_PUBLIC', False), ('DOUYIN_PUBLIC', True)]
```

STUB 被正确标记为 `false`，DOUYIN 为 `true` ✅

### 4.4 N2 结论

**所有非法输入失败关闭**。Python 与 Java 双侧均有运行时验证，
不是只靠类型标注。

---

## 五、N7 任务卡核对

**位置**：`docs/architecture/collection/SOURCE-04-TASKCARD.md`

### 5.1 六项约束在两处的覆盖情况

| 编号 | 内容 | 实现要求（§快照消费的四条硬约束） | 验收标准 |
|---|---|---|---|
| N7-1 | 只遍历 `platformScopedAdapters()`，禁止 `adapters()` | ✅ 第 289 行起，含正误代码对照 | ✅ 「断言核对只遍历 `platformScopedAdapters()`；用含 STUB 的真实快照验证 STUB 不产生任何 Effective 结果」 |
| **N7-2** | `platformScoped=true` 不代表能力已验证 | ✅ 第 302 行起 | ❌ **无对应断言** |
| N7-3 | `VERIFIED` 不代表当前可执行 | ✅ | ✅ 「断言 `VERIFIED` 但快照 STALE 的能力不可执行」 |
| N7-4 | 只有 FRESH 可准入；STALE/UNAVAILABLE → UNKNOWN；禁止 `snapshot != null` | ✅ 含状态对照表 | ✅ 「断言 STALE / UNAVAILABLE 快照下所有能力为 UNKNOWN，不得出现 AVAILABLE」 |
| N7-5 | 白名单准入 | ✅ 含正误代码对照 | ✅ 「断言准入判断里不存在 `!=` 形式的排除法」 |
| N7-6 | 19 个可达 reason 正向 + `FUNCTION_NOT_CALLABLE` 不可达 | ✅ | ✅ 改为「19 个……逐条有正向用例，`FUNCTION_NOT_CALLABLE` 提供不可达性测试」 |

### 5.2 结论

**六项中 5 项同时进入实现要求与验收标准；N7-2 仅停留在实现要求（背景说明），验收标准无对应断言。**

这与用户通过标准「N7 六项约束进入任务卡验收标准」不完全一致，判 **F1（MEDIUM）**。

**N7-2 其实完全可以写成可测断言**，例如：

> 断言 `platformScoped=true` 且 `implementationStatus=UNVERIFIED` 的能力，
> 其 `effectiveStatus` 不是 `AVAILABLE`（证明"真实平台接入"与"已验证"是正交的两个维度）。

---

## 六、实际测试命令与结果

### 6.1 Java 全量

```
$ cd backend && mvn -s ../deploy/docker/maven-settings.xml -o test
[INFO] Tests run: 197, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 6.2 Python 全量

```
$ docker exec cpi-collection bash -lc "cd /app/collection-service && python tests/<f>.py"
test_registry      23/23 passed
test_server        17/17 passed
test_evidence      16/16 passed
test_snapshot      20/20 passed
                 ─────────────
                    76/76
```

### 6.3 N1 Error 异常路径（单独执行，看耗时）

```
$ mvn -o test -Dtest='SnapshotCacheTest#ownerErrorStillCompletesWaiters'
Tests run: 1, Failures: 0, Errors: 0 — Time elapsed: 1.487 s
```

**耗时的判定价值**：该用例含 `sleep(1200)` 预热超 TTL。
若 `future` 在 Error 后未完成，`waiter.join(3000)` 会耗满 3 秒，总耗时必然 > 4.2s。
实测 1.487s ⇒ 等待者在 Error 后**立即**被唤醒。这不是"测试通过"能替代的证据。

### 6.4 N1 并发不变量（4 条，非单线程）

```
$ mvn -o test -Dtest='SnapshotCacheTest#concurrentFirstFetchIssuesSingleHttpCall
  +concurrentRefreshOfStaleCacheIssuesSingleHttpCall
  +concurrentFailureYieldsConsistentResult
  +readDuringRefreshSeesCompleteOldSnapshot'
Tests run: 4, Failures: 0, Errors: 0 — Time elapsed: 2.951 s
```

这些用例断言的是 **HTTP 调用次数恰好为 1**（20 线程），不是"跑通"。
若 Single-flight 失效，计数会是 20，测试立即变红。

### 6.5 N2 Java 非法输入（4 条）

```
$ mvn -o test -Dtest='RegistrySnapshotParserTest#rejectsMissingPlatformScoped
  +rejectsNullPlatformScoped+rejectsNonBooleanPlatformScoped+rejectsAdapterWithoutConnectorCode'
Tests run: 4, Failures: 0, Errors: 0 — Time elapsed: 0.268 s
```

### 6.6 N2 Python 类型校验

见 §4.1 运行时实证（7 种非法取值全部 `TypeError`，2 种合法取值输出 `bool`）。

### 6.7 真实 HTTP 往返

```
$ mvn -o test -Dtest='CollectionSnapshotClientTest'
Tests run: 10, Failures: 0, Errors: 0 — Time elapsed: 0.680 s
```

其中 4 条直连 `localhost:5001`，不读静态样本、不用 `assume` 跳过。
实测 `STUB_PUBLIC=false`、`DOUYIN_PUBLIC=true`。

### 6.8 五容器健康

```
cpi-backend      Up 10 minutes (healthy)
cpi-collection   Up 11 minutes (healthy)
cpi-flask        Up 7 hours (healthy)
cpi-frontend     Up 7 hours
cpi-mysql        Up 7 hours (healthy)
```

---

## 七、新发现问题

### F1（MEDIUM · **文档/验收缺陷**，非代码缺陷）：N7-2 未进入验收标准

**位置**（准确行号）：

| 位置 | 内容 |
|---|---|
| `SOURCE-04-TASKCARD.md:302` | `**N7-2　\`platformScoped=true\` 不等于能力已验证**`（实现要求，存在） |
| `SOURCE-04-TASKCARD.md:361-374` | 04-03 验收标准段，**无 N7-2 对应条目** |

**实际行为**：N7-2 只写在实现要求里（`:302`），验收标准（`:361-374`）没有对应断言。
对照其余五项：

| 编号 | 实现要求行 | 验收标准行 |
|---|---|---|
| N7-1 | `:289` | `:370` |
| **N7-2** | `:302` | **无** |
| N7-3 | `:308` | `:372` |
| N7-4 | `:314` | `:373` |
| N7-5 | `:335` | `:374` |
| N7-6 | `:346` | `:362` |

**为什么算缺陷**：用户通过标准明确要求「六项约束进入任务卡验收标准」。
仅写在背景说明中的约束，在实施时容易被当作"理解性描述"跳过；
写入验收标准才是可拒收的硬条件。

**缺陷分类**：**文档/验收缺陷**（不是代码缺陷，也不是测试实现缺陷）——
代码侧无对应实现可改，需改的是任务卡的验收条目。

**建议补的断言**：

> 断言 `platformScoped=true` 且 `implementationStatus=UNVERIFIED` 的能力，
> `effectiveStatus` 不是 `AVAILABLE`。

**影响**：不阻塞 04-03 启动，但建议在启动前补进任务卡 `:361-374`。

---

### F2（LOW · 代码缺陷）：Python 校验点不统一，`/health` 路径未覆盖

**位置**：
- `collection-service/app/registry/adapter_registry.py:33` `AdapterEntry.platform_scoped: bool = True`（无校验）
- `collection-service/app/registry/bootstrap.py:271` `"platformScoped": e.platform_scoped`（直接读，不经 `to_metadata()`）

**实际行为**：

| 路径 | 是否经 `AdapterMetadata.__post_init__` |
|---|---|
| `snapshot()`（`bootstrap.py:318`） | ✅ 经 `entry.to_metadata().to_v1()` |
| `adapter_views()`（`bootstrap.py:255`） | ✅ 经 `entry.to_metadata().to_v1()` |
| `health_payload()`（`bootstrap.py:271`） | ❌ 直接 `e.platform_scoped` |

**影响**：若 `AdapterEntry.platform_scoped` 被传入非 bool（`register_loader` 无运行时校验），
`/health` 会直接输出该非法值。但 `/health` 只用于探活，**不参与能力准入判定**，
且 `snapshot()` / `adapter_views()` 会在同一状态下抛 `TypeError` 导致 500，
下游表现为 `HTTP_ERROR → REGISTRY_UNREACHABLE`（fail-close）。

**缺陷分类**：**代码缺陷**（校验点位置不当），非测试缺陷。

**判定为 LOW 的理由**：不产出可被误用为"真实平台接入"的数据；错误暴露时机偏晚
（请求期 500 而非启动期失败），但不造成正确性风险。

**建议**：把校验前移到 `AdapterEntry`（或 `register_loader`），让非法配置在启动期就失败。

---

### F3（LOW · 设计缺陷）：`SnapshotProvider` 接口无超时契约

**位置**：`backend/src/main/java/com/coolpilot/insight/integration/capability/SnapshotProvider.java`

```java
@FunctionalInterface
public interface SnapshotProvider {
    /** 拉取一次快照。 */
    CollectionServiceClient.FetchResult fetchSnapshot();
}
```

**实际行为**：若某实现永久阻塞（无超时），owner 会一直卡在
`performRefresh()` → `provider.fetchSnapshot()`，`future` 永不完成，
所有 waiter 永久 `join`。

**缺陷分类**：**设计缺陷**（契约缺失），非代码缺陷、非测试缺陷。

**当前风险评估**：生产唯一实现 `CollectionServiceClient` 有
connect 2s + request 5s 超时，因此实际不会触发。
但**接口契约没有写明这个要求**，将来新增实现（如本地缓存实现、测试替身）容易遗漏。

**建议**：在 javadoc 明确"实现必须有超时，且不得无限阻塞"。

---

### F4（观察，不计缺陷）：记忆文件在实现提交中

`.workbuddy/memory/2026-11-16-supplement-review.md`（+42 行）随 `43f8107` 提交。
它不属于代码、数据库、Schema、前端，也不在被禁止对象内，属过程记录。
仅提示：若希望实现提交保持纯净，可后续把记忆文件单独提交。

---

## 八、最终结论

### 8.1 通过标准逐项核对

| 用户设定标准 | 结果 |
|---|---|
| BLOCKER = 0 | ✅ 0 |
| HIGH = 0 | ✅ 0 |
| N1 所有异常路径有确定性结论 | ✅ §3 逐条给出（含 4 项特别检查） |
| N2 所有非法输入失败关闭 | ✅ §4 双侧验证 + 运行时实证 |
| **N7 六项约束进入任务卡验收标准** | ❌ **5/6**（N7-2 缺失，F1） |
| Java / Python 全量测试通过 | ✅ 197/197、76/76 |

### 8.2 缺陷分类汇总

| 编号 | 级别 | 分类 | 是否阻塞 04-03 |
|---|---|---|---|
| F1 N7-2 未进验收标准 | MEDIUM | **文档/验收缺陷** | 建议启动前补 |
| F2 Python 校验点不统一 | LOW | **代码缺陷** | 否 |
| F3 Provider 无超时契约 | LOW | **设计缺陷** | 否 |
| F4 记忆文件随实现提交 | 观察 | 过程记录 | 否 |

本轮**未发现**：测试实现缺陷（4 类测试均实际执行并通过，且断言有辨别力）、
BLOCKER、HIGH。

### 8.3 判定

**PASS WITH FINDINGS**

- N1、N2 **已真正关闭**，不是"测试通过"层面的关闭，而是控制流层面可论证的关闭；
- N7 冻结**基本到位**，但 N7-2 漏在验收标准之外，未完全满足"六项都进验收标准"的要求；
- 另有 2 项 LOW（F2、F3），均为"当前不造成正确性风险、但应尽早收敛"的类型。

### 8.4 对启动 SOURCE-04-03 的建议

| 事项 | 建议 |
|---|---|
| F1（N7-2） | **启动 04-03 前补进验收标准**（一句话断言，成本极低） |
| F2（Python 校验点） | 可在 04-03 期间顺带前移，不阻塞 |
| F3（Provider 超时契约） | 补一行 javadoc，不阻塞 |

**结论**：N1、N2 可以判定为关闭；建议先用一个极小改动补上 N7-2 的验收断言，
再正式批准 04-03 核心实现。

---

## 九、复核期间的只读声明

| 项目 | 状态 |
|---|---|
| Java / Python 实现 | ❌ 未修改 |
| 测试实现 | ❌ 未修改 |
| 数据库 / Flyway | ❌ 未修改（仍停 V30） |
| `SOURCE-02-capability-schema.json` | ❌ 未修改 |
| SOURCE-04-03 业务实现 | ❌ 未创建 `CapabilityReconciler`，未实现 Desired/Observed/Effective |
| 分支 / 提交历史 | ❌ 未变 |
| 临时验证文件 | 无。Python 运行时验证通过 **stdin** 传给容器内解释器，未写任何文件 |

**本轮唯一新增文件**：本报告 `SOURCE-04-02-FIX01-REVIEW.md`。
