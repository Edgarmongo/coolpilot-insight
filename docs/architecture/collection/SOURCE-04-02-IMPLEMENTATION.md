# SOURCE-04-02 实施报告：Java 消费 Python Registry（统一快照）

> 状态：**已实施，待代码与契约评审**
> 方案：**A —— 新增统一快照接口 `/v1/registry/snapshot`**（用户裁决）
> 关联：`SOURCE-04-TASKCARD.md` 04-02、`SOURCE-04-04-TASKCARD.md` §3、`ADR-008`

---

## 一、为什么是统一快照，而不是读两个端点

计算 Effective 需要两类数据：**能力清单**与**Adapter 诊断**。

分两次请求会引入一个真实窗口：

```
t1  GET /v1/capabilities  → 能力 A 已注册
t2  Python 侧 Adapter 重载，A 的函数失效
t3  GET /v1/adapters      → 诊断显示 A 函数解析失败
t4  合并 → 「A 已注册且 A 函数失败」——这个组合从未存在过
```

更隐蔽的是：这类错误**不会报错**，只会给出一个看起来合理的错误结论。
统一快照从源头消除这个窗口。

**只读 `/v1/capabilities` 还有第二个缺陷**：它无法区分
「能力没注册」与「函数解析不到」—— 两者在 Observed 清单里都表现为"没有这条能力"。
必须读 `diagnostics`。这也要求两类数据同源。

---

## 二、Python 侧变更

### 2.1 `CapabilityRegistry.evaluate_all()`

新增**一次遍历**同时产出 Observed 能力与诊断：

```python
def evaluate_all(self, connector_code=None) -> CapabilityEvaluation:
    observed, diagnostics = [], []
    for code, spec in self._specs:
        ok, reason, detail = self._evaluate(code, spec)
        if ok:
            observed.append((code, spec))
        else:
            diagnostics.append(CapabilityDiagnostic(...))
    return CapabilityEvaluation(observed, diagnostics)
```

**为什么不直接用现有的 `list_capabilities()` + `diagnostics()`**：它们各自遍历一次，
虽然共享 `_evaluations` 缓存、结果必然一致，但"一致"靠的是缓存命中这个**隐含前提**。
统一快照要求显式地只遍历一次，把两个产物绑在同一次计算上。

### 2.2 `CollectionRegistry.snapshot()`

一次 `evaluate_all()` 派生全部内容：

| 字段 | 来源 |
|---|---|
| `adapters` | 适配器注册表 + 该接入的兑现能力 |
| `capabilities` | 平台能力文档（语义等同 `/v1/capabilities` 的 `connectors`） |
| `diagnostics` | `evaluate_all()` 的诊断 + Adapter 加载失败 + 证据拒绝 |
| `snapshotId` | `"sha256:" + sha256(canonical_json(body))`，**body 不含 generatedAt** |
| `generatedAt` | UTC 时间，**仅观察用** |

**关键**：`snapshotId` 计算时排除 `generatedAt`。否则每次请求 ID 都变，
缓存永远失效，一致性判断也失去意义。

### 2.3 新端点

```
GET /v1/registry/snapshot
→ {code:0, data:{snapshotId, contractVersion, schemaVersion, generatedAt,
                 adapters, capabilities, diagnostics}, error:null, meta:{traceId}}
```

- 只读本地注册表，**不触发平台请求**；
- 配置了 `INTERNAL_TOKEN` 时要求 `X-Internal-Token`（与其它查询端点一致）；
- **原有 `/v1/capabilities` 与 `/v1/adapters` 完全保留**，未改动其语义。

### 2.4 响应示例（真实容器实测）

```json
{
  "code": 0,
  "data": {
    "snapshotId": "sha256:06e258abbffcd2d2b1ab8a367a5e9f5ee1cd9d35072eb4c313155c363ee5933d",
    "contractVersion": "1.1",
    "schemaVersion": "1.1",
    "generatedAt": "2026-11-16T12:59:32Z",
    "adapters": [
      {"connectorCode":"STUB_PUBLIC","loadStatus":"LOADED","capabilityCount":0,...},
      {"connectorCode":"DOUYIN_PUBLIC","loadStatus":"LOADED","capabilityCount":4,...}
    ],
    "capabilities": [
      {"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","connectorVersion":"1.0.0",
       "capabilities":[
         {"code":"COMMENT","implementationStatus":"VERIFIED","semantics":"READ",...},
         {"code":"COMMENT_REPLY","implementationStatus":"VERIFIED",...},
         {"code":"CONTENT_DETAIL","implementationStatus":"VERIFIED",...},
         {"code":"KEYWORD_SEARCH","implementationStatus":"UNVERIFIED",...}
       ]}
    ],
    "diagnostics": []
  }
}
```

---

## 三、Java 侧变更

### 3.1 新增类

| 类 | 职责 |
|---|---|
| `RegistrySnapshot` | 不可变快照 DTO。含 `snapshotId` / `adapters` / `capabilities` / `diagnostics`，提供 `findConnector` / `findAdapter` / `findDiagnostic` / `hasFunctionProblem` |
| `RegistrySnapshotParser` | **严格**解析与校验。任何一项不满足整体拒绝 |
| `CollectionServiceClient` | HTTP 客户端。只调一个端点，失败分类明确 |
| `SnapshotProvider` | 快照来源接口（让缓存状态机可独立测试，不需 mock 框架） |
| `SnapshotCache` | **原子**缓存。单位是完整快照，不是每个端点一份 |

### 3.2 解析器的六道拒绝（验收 A07）

| # | 情况 | 结果 |
|---|---|---|
| 1 | 空 / 非 JSON / 非 object | 拒绝 |
| 2 | 缺 `snapshotId` 或格式不是 `sha256:<64 hex>` | 拒绝（**不接受时间戳**） |
| 3 | 缺 `schemaVersion` 或主版本 > 1 | 拒绝 |
| 4 | 缺 `capabilities` / `diagnostics` / `adapters` 任一数组 | 拒绝 |
| 5 | 某能力缺 `code` 或 `adapterFunction` | 拒绝 |
| 6 | **内部不自洽** | 拒绝 |

### 3.3 一致性校验的两个相反要求（本机制的核心）

| 诊断原因 | 该能力是否应出现在 Observed 清单 | 违反时 |
|---|---|---|
| `INVALID_IMPLEMENTATION` | **不应**（函数解析不到，进不了清单） | 拒绝 |
| `EVIDENCE_*`（篡改/过期/版本不符…） | **应当**（能力兑现了，只是证据无效） | 拒绝 |

**这两条方向相反**，所以不能简单写"两个集合不能有交集"——
那会把合法的"能力已兑现但证据过期"误杀。实测真实快照里 `diagnostics` 为空，
但测试里对两种相反情况都做了断言。

`NOT_LOADED`（Adapter 级，`capabilityCode` 为 null）不参与一致性判定。

### 3.4 缓存状态机（验收 A06 / A08）

| 场景 | 动作 |
|---|---|
| 首次获取成功 | 保存完整快照 |
| 成功且 ID 相同 | 只刷新获取时间与健康状态 |
| 成功且 ID 不同 | **原子替换**整个快照（`volatile` 引用替换，绝不字段级合并） |
| 失败且存在历史 | 保留历史快照，标记 `STALE` |
| 失败且无历史 | 无可用快照 |
| 结构 / 版本不合法 | 拒绝更新缓存，记录诊断 |

**执行准入的唯一判据**是 `SnapshotRead.usableForExecution()`，它**等同**于 `fresh`：

```java
boolean fresh = !stale && withinTtl;
// usableForExecution == fresh
```

所以 `STALE` 与"超 TTL"都不可用于执行准入，但快照本身保留供排障。

**两个实现细节，都是测试逼出来的真实缺陷**：

1. **TTL 用 `Duration.compareTo` 而不是 `getSeconds()`**
   后者截断：TTL=1 秒时，1.2 秒后被算成 1 秒，仍判"在 TTL 内"。
   这是测试 `expiredTtlBlocksExecution` 抓出来的。

2. **失败时间单独存 `lastFailureAt`，不放在 `Entry` 里**
   "失败且无历史"时 `entry == null`，若把失败时间塞进 `Entry`，
   就没有地方记录，导致 Registry 不可用期间**每次查询都打一次网络请求**。
   测试 `doesNotRetryImmediately` 抓出来的。现在有 5 秒最小重试间隔。

---

## 四、验收对照（A01~A10）

| 编号 | 验收项 | 结果 | 证据 |
|---|---|---|---|
| A01 | `/v1/registry/snapshot` 返回完整数据 | ✅ | 容器实测：7 个顶层字段齐全 |
| A02 | 能力与诊断来自同一份内部快照 | ✅ | `evaluate_all()` 一次遍历；`test_capabilities_and_diagnostics_are_disjoint` |
| A03 | 相同内容 ID 稳定，内容变化 ID 改变 | ✅ | Python 4 条测试；真实往返两次 ID 相同 |
| A04 | 原两个端点仍正常工作 | ✅ | `/v1/capabilities` 200、`/v1/adapters` 200（实测） |
| A05 | Java 能获取并解析完整快照 | ✅ | `CollectionSnapshotClientTest` 5 条真实 HTTP 往返 |
| A06 | 不允许分别缓存两个端点 | ✅ | 缓存单位是 `RegistrySnapshot`，结构上不可能分开 |
| A07 | 网络错误、格式错误、版本错误均有明确处理 | ✅ | 解析器六道拒绝 + `FetchResult.Kind` 五分类 |
| A08 | STALE 快照不能作为当前执行准入依据 | ✅ | `usableForExecution == fresh`；`failureKeepsHistoryButBlocksExecution` |
| A09 | Python、Java 测试及真实容器接口验证通过 | ✅ | Python 73、Java 174、容器 5 个 healthy |
| A10 | 不修改数据库、Flyway、前端及其他业务状态机 | ✅ | `git status` 仅含代码与文档；Flyway 仍停 V30 |

### 4.1 用户要求的集成测试

> 模拟 Registry 在两次请求之间发生变化，验证统一快照接口始终返回完整的一致版本。

**Python 侧**（`test_snapshot_after_mutation_is_fully_consistent`）：
先取快照 → 声明新能力 → 再取快照，断言：ID 变化、新快照含新能力、旧能力不丢、
内部仍自洽、**已取出的旧快照对象不被污染**（不可变）。

**Java 侧**（`differentIdReplacesSnapshot`）：
ID 不同时整体替换，断言替换后是完整的新快照而非混合。

---

## 五、测试结果

| 套件 | 结果 | 说明 |
|---|---|---|
| `test_registry.py` | 23/23 | SOURCE-03 原有，无回归 |
| `test_server.py` | 17/17 | 原有，无回归 |
| `test_evidence.py` | 16/16 | 原有，无回归 |
| `test_snapshot.py` | **17/17** | 新增 |
| **Python 合计** | **73/73** | |
| `RegistrySnapshotParserTest` | **13/13** | 新增 |
| `SnapshotCacheTest` | **9/9** | 新增 |
| `CollectionSnapshotClientTest` | **5/5** | 新增（真实 HTTP 往返） |
| **Java 合计** | **174/174** | 原 147 + 27 |

容器：5 个全部 healthy（mysql / flask / backend / collection / frontend）。

---

## 六、已知限制与遗留

| # | 事项 | 说明 |
|---|---|---|
| L1 | **Effective 推导未实现** | 属 04-03。本任务只做快照获取、校验、缓存与 Observed 数据转换 |
| L2 | **无调用方** | `SnapshotCache` 目前是 Bean，但还没有业务代码使用它。接入点在 04-03 |
| L3 | Registry 运行期只读假设 | `declare()` 仅在 `build_registry()` 启动时调用。若将来支持运行期热加载，`snapshot()` 需要加锁 |
| L4 | `snapshotId` 不是防伪造标识 | 它是内容哈希，用于一致性判断，**不**证明来源可信。防伪属 SOURCE-09 |
| L5 | 真实 HTTP 测试依赖容器 | `CollectionSnapshotClientTest` 刻意不跳过：容器不在则测试失败。CI 需先起 `cpi-collection` |
| L6 | `FUNCTION_NOT_CALLABLE` 仍不可达 | Python 只有单一诊断码 `INVALID_IMPLEMENTATION`，见 `SOURCE-04-04-EFFECTIVE-REASON.md` Q9 |

---

## 七、明确未做的事（边界声明）

| 项目 | 状态 |
|---|---|
| MySQL 表结构 / 数据 | ❌ 未改（`capability_json` 仍为 v1 原值） |
| Flyway Migration | ❌ 未新增（仍停 V30） |
| `SOURCE-02-capability-schema.json` | ❌ 未改（枚举零改动） |
| 账号认证 / 授权 | ❌ 未改 |
| Task/Run/Execution/Item 状态机 | ❌ 未改 |
| 前端 | ❌ 未改 |
| SOURCE-04-03 Effective 推导 | ❌ **未启动** |
| Desired 回写 | ❌ 未做（Observed 绝不写回 `capability_json`） |
| 原有两个端点 | ❌ 未删除、未改语义 |

---

## 八、变更文件清单

**Python（4 个）**
- `collection-service/app/registry/capability_registry.py` —— 新增 `evaluate_all()` / `CapabilityEvaluation`
- `collection-service/app/registry/bootstrap.py` —— 新增 `snapshot()` / `_utc_now()` / `SNAPSHOT_CONTRACT_VERSION`
- `collection-service/app/server.py` —— 新增 `/v1/registry/snapshot` 端点
- `collection-service/tests/test_snapshot.py` —— 新增（17 条）
- `collection-service/tools/dump_snapshot_sample.py` —— 新增（样本导出工具）

**Java（6 个新增 + 2 个修改）**
- 新增 `RegistrySnapshot.java`
- 新增 `RegistrySnapshotParser.java`
- 新增 `CollectionServiceClient.java`
- 新增 `SnapshotProvider.java`
- 新增 `SnapshotCache.java`
- 新增 `RegistrySnapshotParserTest.java` / `SnapshotCacheTest.java` / `CollectionSnapshotClientTest.java`
- 修改 `CapabilityContractParser.java`（`parseDocument` / `parseItem` / `text` 改为包内可见，供快照解析器复用）
- 修改 `application.yml`（新增 `app.capability.observed-ttl-seconds`）

**测试资源**
- 新增 `backend/src/test/resources/registry-snapshot-sample.json`（从真实容器导出）
