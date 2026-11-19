# SOURCE-04-02-FIX-01 实施报告：关闭第二轮评审遗留问题

> 状态：**已完成，待独立复核**
> 基线：`3b0b7ab`（第二轮评审通过）
> 任务边界（用户指定）：
> 修 N1、N2；把 N7 写入 04-03 任务卡；更新测试与实施记录。
> **不改数据库 / Flyway / Desired Schema / 账号 / Task-Run / 前端；
> 不启动 `CapabilityReconciler`，不提前实现 Effective。**

---

## 一、N2 修复：`platformScoped` 改为失败关闭

### 1.1 问题回顾

第二轮评审 **N2（MEDIUM，要求优先处理）**：

- Python `to_v1()` 无条件输出 `platformScoped`（`capability.py:253`）；
- Java 收到字段缺失时返回 `null`，`isPlatformScoped()` 又把它按 `true` 处理；
- 结果：**未知数据被当成真实平台接入** = 失败开放（fail-open）。

这是方向上最危险的一类：`platformScoped=false` 是排除 STUB 的结构化依据，
而"缺失 → true"恰好把这个依据在最需要它的时候关掉了。

### 1.2 Python 侧：构造期拒绝非布尔

**位置**：`collection-service/app/registry/capability.py` — `AdapterMetadata.__post_init__`

```python
def __post_init__(self) -> None:
    if not isinstance(self.platform_scoped, bool):
        raise TypeError(
            "platform_scoped 必须是 bool，实际为 "
            f"{type(self.platform_scoped).__name__}: {self.platform_scoped!r}"
        )
```

**为什么是 `raise` 而不是 `bool()` 转换**：`bool("false")` 是 `True`。
静默转换会把一个明显的配置错误变成一个语义相反的快照，且无人知晓。
构造期抛错让问题在启动时就暴露。

### 1.3 Java 侧：字段缺失 / 类型错误 → 拒绝整个快照

**位置**：`RegistrySnapshotParser.java:170-200`

新增 `Failure.BROKEN_ADAPTER`，并在解析 adapter 时做三项硬校验：

```java
String connectorCode = ...;
if (connectorCode == null || connectorCode.isBlank())
    return Result.failure(Failure.BROKEN_ADAPTER, "Adapter 条目缺少 connectorCode");

JsonNode psNode = n.get("platformScoped");
if (psNode == null || psNode.isNull())
    return Result.failure(Failure.BROKEN_ADAPTER,
            "Adapter " + connectorCode + " 缺少 platformScoped");
if (!psNode.isBoolean())
    return Result.failure(Failure.BROKEN_ADAPTER,
            "Adapter " + connectorCode + " 的 platformScoped 不是布尔值: " + psNode.getNodeType());
```

**关键点**：`psNode.isBoolean()` 严格判断节点类型，**不接受**字符串 `"false"` 或数字 `0`。
Jackson 的 `asBoolean()` 会对字符串做容错解析，这正是要避免的。

### 1.4 DTO 层面消除 `null` 的可能

`RegistrySnapshot.AdapterView` 的 `platformScoped` 从 `Boolean` 改为原始 `boolean`：

```java
public record AdapterView(String connectorCode, ..., boolean platformScoped, ...) {
    public boolean isPlatformScoped() {
        return platformScoped;   // 不再需要 null 兜底
    }
}
```

**这是结构性修复**：解析器保证字段存在且为布尔，DTO 用原始类型后，
`null → true` 这条路径在**类型层面**就不存在了，不依赖任何人"记得不要写 null"。

### 1.5 新增测试（4 条 Java + 3 条 Python）

| 测试 | 断言 |
|---|---|
| `rejectsMissingPlatformScoped` | 删掉字段 → `BROKEN_ADAPTER`（并先断言 `doesNotContain("platformScoped")` 确保真的删掉了） |
| `rejectsNullPlatformScoped` | `"platformScoped":null` → `BROKEN_ADAPTER` |
| `rejectsNonBooleanPlatformScoped` | 字符串 `"false"` 与数字 `0` 均 → `BROKEN_ADAPTER` |
| `rejectsAdapterWithoutConnectorCode` | 缺 `connectorCode` → `BROKEN_ADAPTER` |
| `test_platform_scoped_must_be_bool`（Python） | `"false"` / `0` / `1` / `None` 全部抛 `TypeError` |
| `test_platform_scoped_true_and_false_both_valid` | `True` / `False` 都能正常输出 |
| `test_snapshot_always_carries_boolean_platform_scoped` | 真实快照每个 adapter 都带 `bool` 类型 |

**修复前的辨别力**：在旧实现下 `rejectsMissingPlatformScoped` 会因
`result.error()` 为 `null` 而抛 NPE 失败——测试会红，说明它确实在测这件事。

---

## 二、N1 修复：`Error` 路径也要完成 Future

### 2.1 问题回顾

第二轮评审 **N1（MEDIUM）**：

- `catch (RuntimeException e)` 不捕获 `Error`；
- `finally` 会清理 `inFlight`，但 `future` **从未 complete**；
- 已持有该 `future` 的等待者 `join()` **永久阻塞**。

**核心区分**：清理 `inFlight` 解决的是"后续请求能重新发起"，
**不解决**"当前等待者醒来"。两者是不同的问题。

### 2.2 修复

**位置**：`SnapshotCache.java:176-197`

```java
try {
    result = performRefresh();
    future.complete(result);
    return result;
} catch (RuntimeException | Error e) {
    // **必须完成 future**，否则已持有它的等待者会永久阻塞。
    future.completeExceptionally(e);
    log.warn("[capability] 刷新过程异常（{}），等待线程共享失败: {}",
            e.getClass().getSimpleName(), e.toString());
    if (e instanceof Error error) {
        // JVM 级错误不能吞掉：完成通知之后继续向上抛出，
        // 让容器与监控能看到真实的致命故障。
        throw error;
    }
    return read();
} finally {
    synchronized (flightLock) {
        if (inFlight == future) { inFlight = null; }
    }
}
```

三件事同时满足用户要求：

| 要求 | 实现 |
|---|---|
| owner 无论正常/异常/Error，等待者都能得到完成结果 | 三条路径都 `complete` 或 `completeExceptionally` |
| `Error` 完成通知后继续向上抛，不吞掉 | `if (e instanceof Error) throw error;` |
| 无论哪条路径 `inFlight` 都必须清理 | `finally` 无条件执行 |

### 2.3 测试

**`ownerErrorStillCompletesWaiters`**（`SnapshotCacheTest`）

时序设计（这是测试有效性的关键）：

```
1. cache.refresh()            → 基线 old，ttl=1
2. sleep(1200)                → 超 TTL，制造需要刷新的场景
3. 启动 owner 线程 → provider 内 providerBlocked.countDown() 后阻塞在 gate
4. await(providerBlocked)     → 确认 owner 已进入 provider，inFlight 已创建
5. 启动 waiter 线程 → cache.get() → 必然成为等待者并 join
6. sleep(120)                 → 让 waiter 真正进入 join
7. gate.countDown()           → 放行 owner → 抛 StackOverflowError
8. waiter.join(3000)          → 若 future 未完成，这里会超时
```

断言：

| 断言 | 意义 |
|---|---|
| `waiter.isAlive() == false` | **等待者没有永久阻塞**（核心） |
| `waiterExited == true` | waiter 线程正常跑完，不是被中断 |
| `waiterResult.get().usableForExecution() == false` | Error 后不可能有可执行快照 |
| `ownerError.get() instanceof StackOverflowError` | **Error 被重新抛出，没有被吞掉** |
| 再次 `refresh()` 得到 `sha256:new` | **`inFlight` 已清理**，后续请求能正常刷新 |

**辨别力证据**：该用例单独执行耗时 **1.486s**（含 `sleep(1200)` 预热）。
若修复前 future 未完成，`waiter.join(3000)` 会耗满 3 秒，总耗时将超过 4.2s。
1.486s 反证了等待者在 Error 后**立即**被唤醒。

---

## 三、N7 冻结：写入 SOURCE-04-03 任务卡

**位置**：`docs/architecture/collection/SOURCE-04-TASKCARD.md` 04-03 章节新增
「快照消费的四条硬约束（N7 冻结）」小节，共 6 条：

| 编号 | 约束 |
|---|---|
| **N7-1** | 必须只遍历 `platformScopedAdapters()`，**禁止**遍历 `adapters()`；STUB 不得进入真实平台能力准入 |
| **N7-2** | `platformScoped=true` 仅代表真实平台范围，**不代表**能力已验证 |
| **N7-3** | `VERIFIED` 不代表当前可执行（还取决于 Source 状态、快照新鲜度、账号、访问模式、证据有效期） |
| **N7-4** | 只有 `FRESH` 快照可参与当前 Effective 准入；`STALE` / `UNAVAILABLE` 一律判 `UNKNOWN` |
| **N7-5** | 准入必须白名单，禁止 `!=` 形式的排除法 |
| **N7-6** | 19 个当前可达 `effectiveReason` 逐个正向测试；`FUNCTION_NOT_CALLABLE` 提供不可达性测试 |

同时把 04-03 的**验收标准**从"20 个 reason 正向用例"改为
"19 个正向 + 1 个不可达性测试"，并补了 N7-1 / N7-3 / N7-4 / N7-5 四条断言要求。

**N7-4 的关键补充**（用户强调）：

| 快照状态 | 参与 Effective 准入 | 对应 `effectiveStatus` |
|---|---|---|
| `FRESH` | ✅ | 按判定顺序推导 |
| `STALE` | ❌ | `UNKNOWN` + `OBSERVED_STALE` |
| `UNAVAILABLE` | ❌ | `UNKNOWN` + `REGISTRY_UNREACHABLE` |

并明确：**禁止**用 `snapshot != null` 判断可用性——`STALE` 也携带快照。

---

## 四、测试结果

| 套件 | FIX-01 前 | FIX-01 后 | 增量 |
|---|---|---|---|
| `RegistrySnapshotParserTest` | 18 | **22** | +4（N2 四条负向） |
| `SnapshotCacheTest` | 17 | **18** | +1（N1 Error 路径） |
| `CollectionSnapshotClientTest` | 10 | 10 | — |
| **Java 合计** | 192 | **197** | +5 |
| `test_snapshot.py` | 17 | **20** | +3（Python 类型校验） |
| **Python 合计** | 73 | **76** | +3 |

```
Java:   Tests run: 197, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
Python: test_registry 23/23 · test_server 17/17 · test_evidence 16/16 · test_snapshot 20/20
```

### 容器验证

```
cpi-backend      Up (healthy)
cpi-collection   Up (healthy)
cpi-flask        Up (healthy)
cpi-frontend     Up
cpi-mysql        Up (healthy)
```

真实快照实测：

```
所有 adapter 的 platformScoped 均为 bool: True
[('STUB_PUBLIC', False), ('DOUYIN_PUBLIC', True)]
```

---

## 五、N1 / N2 关闭判定

| 编号 | 关闭条件 | 是否满足 |
|---|---|---|
| **N1** | owner 抛 Error 时未来完成、等待者不阻塞、Error 不吞掉、inFlight 清理、有测试 | ✅ 全部满足 |
| **N2** | Python 强制布尔、Java 拒绝缺失/类型错误、不转成可执行 Adapter、有负向测试 | ✅ 全部满足 |

---

## 六、未实施声明

| 项目 | 状态 |
|---|---|
| MySQL 表结构 / 数据 | ❌ 未改（`capability_json` 仍为 v1 原值） |
| Flyway Migration | ❌ 未新增（仍停 V30） |
| `SOURCE-02-capability-schema.json` | ❌ 未改 |
| `ds_account` 状态模型 | ❌ 未改 |
| Task / Run / Execution / Item 状态机 | ❌ 未改 |
| 前端 | ❌ 未改 |
| `CapabilityReconciler` | ❌ **未创建** |
| Effective 推导 | ❌ **未实现** |
| SOURCE-04-03 | ❌ 未启动 |

**本轮实际改动**：4 个 Java 实现、2 个 Java 测试、1 个 Python 实现、
1 个 Python 测试、1 个任务卡文档、1 个新增报告。

---

## 七、遗留

| # | 事项 | 状态 |
|---|---|---|
| N3 | `clear()` 不清 `inFlight` | 未修（测试专用方法，影响小） |
| N4 | `capabilityCount` 与 `capabilities.size()` 未交叉校验 | 未修 |
| N5 | `CapabilityEvaluation` 非深层不可变 | 未修 |
| N6 | `snapshot()` 返回 dict 与 body 共享嵌套 list | 未修（Flask 不修改返回对象） |
| N8 | M8 仅文档关闭，无代码级保护 | 未修 |

以上均为 LOW / 观察项，不阻塞 04-03。
