# SOURCE-04-02 代码与契约评审报告

> 评审对象：`61150cc`（父提交 `1438d3d`）
> 分支：`feat/boot4-jdk21-mybatis`
> 评审日期：2026-11-16
> 性质：**只读评审。本轮未修改任何代码、数据库、Flyway、Schema 或配置。**
>
> **最终结论：不通过。存在 2 个 HIGH 缺陷，须先修复并重新评审。**
> 未发现 BLOCKER。Effective 推导未实施属 04-03 范围，不构成本次缺陷。

---

## 一、评审基线

```
$ git status --short
（工作区干净，无未提交改动）

$ git show --stat --oneline 61150cc
61150cc feat(source): SOURCE-04-02 实现统一 Registry 快照（方案 A）
 19 files changed, 2673 insertions(+), 4 deletions(-)

$ git rev-parse 61150cc^
1438d3d5676a583f4173483390bb177f9f24dcd9
```

变更构成：

| 类别 | 文件数 |
|---|---|
| Python 实现 | 3（`capability_registry.py` / `bootstrap.py` / `server.py`） |
| Python 测试与工具 | 2 |
| Java 实现 | 5 新增 + 1 修改 |
| Java 测试 | 3 新增 |
| 测试资源 | 1（真实容器导出） |
| 文档 | 2 |

---

## 二、缺陷汇总

| 编号 | 缺陷 | 等级 | 位置 |
|---|---|---|---|
| **H1** | 错误分类靠**错误文本 contains 匹配**，违反"禁止自由文本作机器判据" | **HIGH** | `CollectionServiceClient.java:89-93` |
| **H2** | `AdapterView` 丢弃 `platformScoped` / `module`，Java 无法区分链路验证替身与真实接入 | **HIGH** | `RegistrySnapshot.java:46-47` |
| M1 | `get()` 无锁 + `refresh()` 不复查 → 并发时重复请求 Python | MEDIUM | `SnapshotCache.java:69-79, 118` |
| M2 | 5 秒退避只在 `get()` 入口判断，`refresh()` 内部不判断 → 并发失败时仍请求风暴 | MEDIUM | `SnapshotCache.java:75, 118` |
| M3 | `contractVersion` 解析但**未校验** | MEDIUM | `RegistrySnapshotParser.java:173` |
| M4 | `SnapshotRead` 无显式三态枚举，只看 `snapshot != null` 会误判"可用" | MEDIUM | `SnapshotCache.java:188-191` |
| M5 | `SNAPSHOT_CONTRACT_VERSION = SCHEMA_VERSION` 绑死，两版本无法独立演进 | MEDIUM | `bootstrap.py:42` |
| M6 | **零并发测试**，用户关注的线程安全问题未被覆盖 | MEDIUM | `SnapshotCacheTest.java` |
| M7 | `tokenHandling` 测试依赖"服务未配 token"的环境假设，非确定性 | MEDIUM | `CollectionSnapshotClientTest.java:93-98` |
| M8 | Python `snapshot()` 线程安全依赖"运行期只读"隐含假设，未文档化 | MEDIUM | `bootstrap.py:315` |
| L1 | `Entry.lastFailureAt` 是死字段（`read()` 用实例字段） | LOW | `SnapshotCache.java:175` |
| L2 | `canonical_json` 的 `default=str` 兜底可能产生不稳定哈希 | LOW | `evidence.py:canonical_json` |
| L3 | 哈希依赖**数组顺序**，但"顺序有语义"未文档化 | LOW | `bootstrap.py:352` |
| L4 | `CapabilityEvaluation` 标 `frozen` 但字段是可变 list | LOW | `capability_registry.py` |

---

## 三、逐项检查

### 检查 1：`snapshotId` 的完整哈希输入

**位置**：`collection-service/app/registry/bootstrap.py:344-360`

```python
body: dict[str, Any] = {
    "contractVersion": SNAPSHOT_CONTRACT_VERSION,
    "schemaVersion": SCHEMA_VERSION,
    "adapters": adapters,
    "capabilities": connectors,
    "diagnostics": diagnostics,
}
return {
    "snapshotId": "sha256:" + sha256_of(body),
    "generatedAt": _utc_now(),
    **body,
}
```

**实际行为**：五项全部参与哈希，`generatedAt` 在 body **之外**，不进哈希。

**对照评审要求**：

| 要求项 | 是否覆盖 |
|---|---|
| `schemaVersion` | ✅ |
| `contractVersion` | ✅ |
| Adapter 信息 | ✅（含 loadStatus、连接版本、能力列表） |
| `capabilities` | ✅ |
| `diagnostics` | ✅ |

**对应测试**：`test_evidence_change_changes_id`、`test_declaring_new_capability_changes_id`、`test_broken_function_changes_both_diagnostics_and_id`、`test_generated_at_does_not_affect_id`。

**结论**：符合。**但有一处冗余需说明**：`adapters[i].capabilities` 与顶层 `capabilities[].capabilities` 表达同一批能力（前者是 Adapter 视角，后者是平台文档视角）。能力变化会在两处同时体现。这不影响正确性（哈希仍是内容敏感），但让 `snapshotId` 对同一份事实做了两次编码。

---

### 检查 2：JSON 规范化规则

**位置**：`collection-service/app/registry/evidence.py` → `canonical_json` / `sha256_of`

```python
def canonical_json(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, default=str)

def sha256_of(obj):
    return hashlib.sha256(canonical_json(obj).encode("utf-8")).hexdigest()
```

| 规范化维度 | 规则 | 评价 |
|---|---|---|
| Object 字段顺序 | `sort_keys=True` | ✅ 顺序无关 |
| 空白 | `separators=(",", ":")` | ✅ 紧凑确定 |
| 编码 | `.encode("utf-8")` | ✅ 固定 UTF-8 |
| 非 ASCII | `ensure_ascii=False` | ✅ 中文原样，不转义 |
| 未知类型 | `default=str` | ⚠️ **L2**：兜底可能不稳定 |
| 空值/缺失 | 由 `CapabilitySpec.to_v1()` 决定：`None` 字段**省略**而非输出 `null` | ✅ 规则确定 |
| **Array 顺序** | **不排序，保留原顺序** | ⚠️ **L3**：有语义但未文档化 |

**L3 的实际影响**：`snapshotId` 依赖 `_specs` 的声明顺序与 `adapters.list()` 的注册顺序。这两个顺序当前稳定（`LOADERS` 元组固定顺序、`declare()` 按加载顺序），所以 ID 稳定。但**若将来有人对能力列表排序**（例如 UI 展示需要按字母排序而复用了同一列表），`snapshotId` 会全部变化，而**没有任何机制会提示这属于语义变更**。

**建议**（不在本轮实施）：在 `snapshot()` 的 docstring 或契约文档里明确「数组顺序参与哈希，且顺序具有语义（声明顺序 / 注册顺序），不得为展示目的排序后再计算 ID」。

---

### 检查 3：`capabilities` 与 `diagnostics` 是否同源

**位置**：`capability_registry.py:evaluate_all()`、`bootstrap.py:snapshot()`

**实际实现**：

```python
# snapshot() 内
evaluation = self.capabilities.evaluate_all()      # 唯一一次评估
# 之后全部从 evaluation 派生：
#   evaluation.specs_for(...)   → adapters[i].capabilities、capabilities[]
#   evaluation.diagnostics      → diagnostics[]
```

**关键负向验证**：`snapshot()` 内部**没有**调用 `self.capabilities.list_capabilities()`、`self.capabilities.diagnostics()` 或 `self.v1_documents()`。已逐行确认（`bootstrap.py:315-360`）。

**与评审担心的隐性结构对比**：

```
担心的错误结构：
evaluate_all()
  +-- list_capabilities()   // 又评估一次
  +-- diagnostics()         // 又评估一次

实际结构：
evaluate_all()  ← 单次遍历 _specs
  +-- observed（在同一循环内收集）
  +-- diagnostics（在同一循环内收集）
```

`evaluate_all()` 的循环体（`capability_registry.py:216-236`）在一次 `for` 内同时填充 `observed` 与 `diagnostics`，**不存在第二次遍历**。

**对应测试**：`test_capabilities_and_diagnostics_are_disjoint`、`test_snapshot_after_mutation_is_fully_consistent`。

**结论**：符合，这一点做得对。

---

### 检查 4：Java 快照原子替换

**位置**：`SnapshotCache.java:44, 118-152`

```java
private volatile Entry entry;          // volatile 引用

public synchronized SnapshotRead refresh() {
    ...
    this.entry = new Entry(incoming, Instant.now(), false, null, null);  // 整体替换
    ...
}
```

| 要求 | 实现 | 评价 |
|---|---|---|
| 以完整 Snapshot 为最小更新单位 | `Entry` 是 `record`，字段全 final，只能整体 `new` | ✅ 结构上无法分字段更新 |
| 替换对读线程原子可见 | `volatile Entry` | ✅ |
| 并发替换串行化 | `refresh()` 为 `synchronized` | ✅ |
| 不做字段级合并 | 从不修改 `entry` 内部字段 | ✅ |

**结论**：原子性本身**符合**。但并发控制有缺口，见 M1 / M2。

---

### 检查 5：多线程并发请求是否避免重复获取

**结论：没有完全避免。这是本次评审确认的一个真实问题。**

**位置**：`SnapshotCache.java:69-79`

```java
public SnapshotRead get() {                    // ← 无锁
    SnapshotRead current = read();
    if (current.fresh()) {
        return current;                        // 新鲜 → 直接返回
    }
    if (current.lastAttemptTooRecent()) {
        return current;                        // 退避期内 → 返回旧值
    }
    return refresh();                          // ← 进入 synchronized 方法
}
```

**并发时序（两个线程 T1 / T2 同时调用 `get()`）**：

```
t1  T1: read() → 不新鲜，lastAttemptTooRecent=false
t2  T2: read() → 不新鲜，lastAttemptTooRecent=false
t3  T1: 进入 refresh()（获得锁），发起 HTTP 请求，成功后 entry 更新
t4  T2: 阻塞在 refresh() 的锁上
t5  T1: 释放锁
t6  T2: 进入 refresh() —— **不复查 entry 是否已新鲜**，再次发起 HTTP 请求
```

**根因**：`refresh()` 内部没有 double-check。它无条件 `client.fetchSnapshot()`。

**后果**：N 个并发请求会触发 N 次 HTTP 调用（而非 1 次）。

**同时确认 M2**：`MIN_RETRY_SECONDS = 5` 的退避只在 `get()` 入口（第 75 行）判断。当多个线程同时通过入口检查后，全部排队进入 `refresh()`，**每个都会真实请求一次**——退避在串行排队时不生效。所以"5 秒保护防止并发请求风暴"这个说法**不成立**。

**对应测试**：**无**。`SnapshotCacheTest` 全部是单线程，仅有一处 `Thread.sleep(1200)` 用于测 TTL。**M6**：用户明确点名的并发问题，测试完全没有覆盖。

**修复方向**（不在本轮实施）：
1. `refresh()` 内先 `read()`，若已新鲜则直接返回（double-check）；
2. 或在 `refresh()` 内检查 `lastFailureAt` 是否在退避期内，是则直接返回；
3. 补并发测试（如 `CountDownLatch` 让 N 个线程同时 `get()`，断言 `fetchSnapshot` 只被调用 1 次）。

---

### 检查 6：STALE 是否真正失去执行资格

**位置**：`SnapshotCache.java:95-106`

```java
boolean withinTtl = age.compareTo(Duration.ofSeconds(ttlSeconds)) <= 0;
boolean fresh = !e.stale() && withinTtl;
return new SnapshotRead(e.snapshot(), fresh, fresh, detail, tooRecent, e.lastError());
//                                 fresh ↑    ↑ usableForExecution
```

`usableForExecution` 与 `fresh` 是**同一个值**，即 `!stale && withinTtl`。

| 状态 | `stale` | `withinTtl` | `fresh` / `usableForExecution` |
|---|---|---|---|
| 正常 | false | true | **true** |
| 超 TTL | false | false | **false** |
| 刷新失败 | true | 任意 | **false** |

**结论：逻辑正确**，STALE 与超 TTL 都不可用于执行准入。

**但有两点必须指出**：

1. **M4：没有显式三态枚举。** 评审建议的模型是：
   ```
   FRESH       —— 当前获取成功且未超 TTL
   STALE       —— 历史快照存在，但当前获取失败或已过期
   UNAVAILABLE —— 从未获取成功
   ```
   当前用「`snapshot == null` 判断 UNAVAILABLE + 两个布尔判断另两态」组合表达。
   下游若只看 `read().snapshot() != null` 就认为"有快照=可用"，**正是 STALE 被误用的典型路径**。
   `usableForExecution` 这个名字是唯一的防线，但它是一个布尔，容易被忽略。

2. **当前无实际执行点。** 缓存尚未接入任何业务代码（`SnapshotCache` 是 Bean 但无调用方），
   所以"STALE 不得执行"目前**只是约定，没有强制点**。这符合 04-02 边界，
   但意味着 04-03 接入时必须显式使用 `usableForExecution` 而非 `snapshot != null`。

**建议**：把 `SnapshotRead` 改为携带 `Freshness` 枚举（`FRESH`/`STALE`/`UNAVAILABLE`），
并让 `usableForExecution` 由该枚举派生，避免"两个布尔组合"被误读。

---

### 检查 7：`contractVersion` 是否完整返回、Java 是否校验

**Python 侧：完整返回 ✅**

真实响应（容器实测）：

```json
{"snapshotId":"sha256:06e2...","contractVersion":"1.1","schemaVersion":"1.1",
 "generatedAt":"2026-11-16T12:59:32Z","adapters":[...],"capabilities":[...],"diagnostics":[]}
```

**Java 侧：解析了，但完全没有校验 ❌（M3）**

**位置**：`RegistrySnapshotParser.java:173`

```java
CapabilityContractParser.text(payload, "contractVersion"),   // 仅取值，无任何校验
```

解析器对 `schemaVersion` 有主版本校验（`major > SUPPORTED_SCHEMA_MAJOR` 则拒绝），
对 `contractVersion` **没有对应的检查**。

**为什么这是问题**：`contractVersion` 描述的是**快照协议本身**的结构版本，
`schemaVersion` 描述的是**能力文档**的结构版本。两者是不同的东西。
当前 Python 侧 `SNAPSHOT_CONTRACT_VERSION = SCHEMA_VERSION`（M5）让它们恰好相等，
所以问题被掩盖。一旦两者分开演进（例如快照新增字段而能力 Schema 不变），
Java 会在 `contractVersion` 不兼容时**静默接受**。

**对应测试**：`rejectsUnsupportedSchemaMajor` 只测了 `schemaVersion`，**没有** `contractVersion` 的用例。

---

### 检查 8：格式不兼容时是否拒绝缓存

**位置**：`SnapshotCache.java:121`、`RegistrySnapshotParser`、`CollectionServiceClient`

| 场景 | 行为 | 验证 |
|---|---|---|
| 非 JSON / 空 | `FetchResult.Kind.UNPARSEABLE` | `rejectsEmptyOrBrokenBody` ✅ |
| 缺 `snapshotId` | 拒绝 | `rejectsMissingSnapshotId` ✅ |
| `snapshotId` 是时间戳 | 拒绝 | `rejectsTimestampAsSnapshotId` ✅ |
| `schemaVersion` 主版本不支持 | 拒绝 | `rejectsUnsupportedSchemaMajor` ✅ |
| 缺任一数组 | 拒绝 | `rejectsMissingArrays` ✅ |
| 能力缺 `adapterFunction` | 拒绝 | `rejectsCapabilityWithoutFunction` ✅ |
| 内部不一致 | 拒绝 | `rejectsInconsistentFunctionDiagnostic` / `rejectsInconsistentEvidenceDiagnostic` ✅ |
| 已有合法快照时收到非法响应 | **不覆盖**，保留旧快照标 stale | `invalidPayloadDoesNotOverwriteValidSnapshot` ✅ |

**结论**：符合。这一块做得扎实，尤其"内部不一致"的两条相反规则
（`INVALID_IMPLEMENTATION` 要求能力不在清单、`EVIDENCE_*` 要求能力在清单）
不是简单求交集，避免误杀合法的"已兑现但证据过期"，判断准确。

---

### 检查 9：原两个接口的响应契约是否保持不变

**变更范围确认**：`git diff 61150cc^ 61150cc -- collection-service/app/server.py` 中，
**只有新增** `/v1/registry/snapshot` 端点与文档字符串，未触碰 `/v1/capabilities`、
`/v1/adapters` 及其子路径的任何一行。

**运行验证**：

| 端点 | 状态码 |
|---|---|
| `/v1/capabilities` | 200 |
| `/v1/adapters` | 200 |
| `/health` | 200 |
| `/v1/registry/snapshot` | 200（新增） |

**对应测试**：`test_legacy_endpoints_still_work`（覆盖 4 个旧端点）、
`test_snapshot_capabilities_match_capabilities_endpoint`（断言快照能力与旧端点语义一致）。

**结论**：符合，未破坏 SOURCE-03 冻结契约。

---

### 检查 10：真实 HTTP 测试是否依赖真实容器

**位置**：`CollectionSnapshotClientTest.java`

```java
private static String baseUrl() {
    String env = System.getenv("COLLECTION_TEST_URL");
    return (env == null || env.isBlank()) ? "http://localhost:5001" : env;
}
```

| 检查点 | 结论 |
|---|---|
| 是否依赖真实容器 | ✅ 直连 `http://localhost:5001`，可用 `COLLECTION_TEST_URL` 覆盖 |
| 容器不可用时是否明确失败 | ✅ 用 `assertThat(result.ok()).as(..., result.detail()).isTrue()`，**不用 `assume` 跳过** |
| 是否误用本地静态 JSON 代替真实服务 | ✅ **未读取** `registry-snapshot-sample.json` |
| 能否区分 HTTP 不可达 / 格式错误 / 契约不兼容 | ⚠️ 部分。`FetchResult.Kind` 有五分类，但**分类本身靠文本匹配**（H1），所以区分能力不可靠 |

`registry-snapshot-sample.json` 只被 `RegistrySnapshotParserTest` 使用（解析器单测），
用法正确——解析器就该用样本测，跨服务链路就该用真实 HTTP 测。两者没有混淆。

**M7**：`tokenHandling` 测试（第 93-98 行）断言"带 dummy token 也应成功"，
前提是服务端**未配置** `INTERNAL_TOKEN`。若在配了令牌的环境运行，该测试会失败。
这是环境相关断言，不是确定性契约断言。

---

## 四、两个 HIGH 缺陷详解

### H1：错误分类靠文本匹配，违反项目自己的原则

**位置**：`CollectionServiceClient.java:89-93`

```java
String first = parsed.errors().isEmpty() ? "未知解析错误" : parsed.errors().get(0);
FetchResult.Kind kind = first.contains("主版本") || first.contains("schemaVersion")
        ? FetchResult.Kind.SCHEMA_UNSUPPORTED
        : first.contains("不一致")
        ? FetchResult.Kind.INCONSISTENT
        : FetchResult.Kind.UNPARSEABLE;
```

**问题**：

1. **直接违反 SOURCE-03 03-05 定下的规则**：错误码表 `errors.py` 的头注释写着
   「机器判断只看 `code` 与 `failureType`；`message` 只给人看、只进日志」。
   这里却用 `message` 的 `contains` 做机器分派。
2. **脆弱**：把"主版本"改成"major version"、或把"不一致"改成"内部矛盾"，
   分类立刻错位，而且**不会有任何测试失败**（现有测试只断言 `ok()==false`，不断言 `Kind`）。
3. **`RegistrySnapshotParser.Result` 只有 `List<String> errors`**，没有结构化错误码——
   调用方除了读文本别无选择。这是设计层面的缺口，不是这一行的疏忽。

**影响**：`Kind` 被用于日志与（将来的）上层 `effectiveReason` 映射
（`UNREACHABLE/HTTP_ERROR → REGISTRY_UNREACHABLE`，
`UNPARSEABLE/SCHEMA_UNSUPPORTED/INCONSISTENT → SCHEMA_UNSUPPORTED`）。
分类错误会让 04-03 的诊断原因失真。

**修复方向**（不在本轮实施）：
给 `RegistrySnapshotParser.Result` 增加 `FailureKind` 枚举
（`MALFORMED_ID` / `UNSUPPORTED_SCHEMA` / `UNSUPPORTED_CONTRACT` / `MISSING_ARRAY` /
`BROKEN_CAPABILITY` / `INCONSISTENT`），由解析器在产生错误的同时给出结构化码。
`CollectionServiceClient` 直接读该枚举，删除全部 `contains`。

---

### H2：`AdapterView` 丢弃 `platformScoped`，Java 无法区分链路验证替身与真实接入

**位置**：`RegistrySnapshot.java:46-47`

```java
public record AdapterView(String connectorCode, String sourceCode, String connectorVersion,
                          String loadStatus, Integer capabilityCount) {
```

**Python 实际输出**（`capability.py` `AdapterMetadata.to_v1()`）：

```json
{
  "connectorCode": "STUB_PUBLIC",
  "sourceCode": "STUB",
  "connectorVersion": "1.0.0",
  "loadStatus": "LOADED",
  "module": "app.adapters.stub",
  "platformScoped": false,          ← Java 丢弃
  "capabilityCount": 0,
  "capabilities": []
}
```

真实样本（`registry-snapshot-sample.json`）已确认 `platformScoped` 存在且值为 `false`。

**丢弃的两个字段**：`platformScoped`（语义关键）、`module`（诊断用）。

**为什么 `platformScoped` 是 HIGH**：

`platformScoped=false` 是 Python 侧表达「这不是真实平台接入，只是链路验证替身」的
**唯一结构化标记**。Java 丢掉它之后：

- 只能靠 `sourceCode == "STUB"` 硬编码推断——但 `sourceCode` 是业务枚举，
  STUB 不在 SOURCE-02 的 `PLATFORM_SOURCE_CODES`（`DOUYIN/XIAOHONGSHU/KUAISHOU/WEB`）里；
- 或靠 `capabilityCount == 0` 推断——这只是当前状态，不是定义。

**当前实际影响有限**：STUB 当前 `capabilityCount=0`，即使被当成普通接入，
核对结果也是"无能力"。**但这是靠巧合成立的**，不是靠契约。

**对 04-03 的风险**：`CapabilityReconciler` 遍历 `snapshot.adapters()` 时，
会无法排除 STUB；若将来 STUB 被赋予能力（用于链路验证），就会与真实接入混淆。

**修复方向**（不在本轮实施）：`AdapterView` 增加 `platformScoped` 与 `module` 字段，
解析器同步映射；`RegistrySnapshot` 增加 `platformScopedAdapters()` 便捷方法过滤。

---

## 五、评审十问的回答

| # | 问题 | 回答 |
|---|---|---|
| 1 | `snapshotId` 完整哈希输入？ | `{contractVersion, schemaVersion, adapters, capabilities, diagnostics}` 五项全覆盖，`generatedAt` 排除在外。**符合**。冗余：`adapters[].capabilities` 与顶层 `capabilities[].capabilities` 重复编码同一事实。 |
| 2 | JSON 规范化规则？ | `sort_keys=True`（object 顺序无关）+ 紧凑分隔符 + `ensure_ascii=False` + UTF-8 编码。**符合**。两处未文档化：数组顺序参与哈希（L3）、`default=str` 兜底（L2）。 |
| 3 | diagnostics 与 capabilities 同源？ | **是**。`snapshot()` 只调一次 `evaluate_all()`，该方法的单次 `for` 循环内同时收集两产物；未调用 `list_capabilities()` / `diagnostics()`。已逐行确认。 |
| 4 | Java 如何保证原子替换？ | `volatile Entry` + `synchronized refresh()` + `record` 字段全 final 整体 `new`。**结构上无法分字段更新，符合**。但并发控制有缺口（M1/M2）。 |
| 5 | 多线程如何避免重复获取？ | **未避免**。`get()` 无锁，`refresh()` 不复查已有快照，N 线程 → N 次 HTTP（M1）。5 秒退避只在 `get()` 入口判断，`refresh()` 内不判断，并发排队时失效（M2）。**无并发测试**（M6）。 |
| 6 | STALE 如何阻止下游执行？ | 逻辑上 `usableForExecution == fresh == (!stale && withinTtl)`，**正确**。但（a）无显式三态枚举，`snapshot != null` 易被误读为可用（M4）；（b）**当前无调用方**，约束尚未有强制点。 |
| 7 | Python `contractVersion` 完整返回？Java 是否校验？ | Python **完整返回** `"contractVersion":"1.1"`。Java **解析但完全不校验**（M3）；且 Python 侧 `SNAPSHOT_CONTRACT_VERSION = SCHEMA_VERSION` 把两者绑死（M5），掩盖了问题。 |
| 8 | 快照格式不兼容时是否拒绝缓存？ | **是**。7 类非法输入全部拒绝，且已有合法快照时不被非法响应覆盖。有对应测试。**符合**。 |
| 9 | 原两接口响应契约是否变？ | **未变**。diff 确认未触碰旧端点代码；运行时 200；有 `test_legacy_endpoints_still_work` 与语义一致性测试。**符合**。 |
| 10 | 真实 HTTP 测试是否依赖真实容器？ | **是**。直连 `localhost:5001`，不读静态样本，不可达即失败不跳过。样本文件仅用于解析器单测。**符合**。分类可靠性受 H1 影响。 |

---

## 六、最终评审结论

### 6.1 判定

**不通过。**

| 门槛 | 结果 |
|---|---|
| BLOCKER | 0 |
| **HIGH** | **2（H1、H2）** |
| MEDIUM | 8 |
| LOW | 4 |

按用户设定的门禁：「如果存在 BLOCKER 或 HIGH：先修复，再重新评审」——
本次存在 2 个 HIGH，因此**不能宣布 04-02 通过，也不进入 04-03**。

### 6.2 做对了的部分（应保留）

这几处不是"测试数量多"，而是设计上确实解决了原始问题：

1. **`evaluate_all()` 真正只遍历一次**。没有退化成"靠缓存命中保证一致"。
2. **`snapshotId` 排除 `generatedAt`**。这是缓存能工作的前提。
3. **一致性校验的两条相反规则**。`INVALID_IMPLEMENTATION` 要求能力**不在**清单，
   `EVIDENCE_*` 要求能力**在**清单——没有草率地写成"两集合不能有交集"，
   避免误杀合法的"已兑现但证据过期"。
4. **非法响应不覆盖合法快照**。有测试支撑。
5. **真实 HTTP 测试不跳过**。样本文件只用于解析器单测，两种测试没有混淆。

### 6.3 必须修复的（重新评审前置）

| 编号 | 修复要点 |
|---|---|
| H1 | `RegistrySnapshotParser.Result` 增加结构化 `FailureKind`；`CollectionServiceClient` 删除全部 `contains` 文本匹配；补 `Kind` 断言测试 |
| H2 | `AdapterView` 补 `platformScoped` + `module`；解析器映射；补测试断言 STUB 的 `platformScoped=false` |

### 6.4 建议同批修复（避免 04-03 返工）

| 编号 | 理由 |
|---|---|
| M1 + M2 + M6 | 并发问题是用户重点关切且零测试覆盖。三者一起修（double-check + 退避内移 + 并发测试）成本最低 |
| M3 | `contractVersion` 校验缺失会在两版本分道演进时静默放过 |
| M4 | 三态枚举直接影响 04-03 的接入写法；先定模型再写核对逻辑，避免后续改签名 |
| M5 | `SNAPSHOT_CONTRACT_VERSION = SCHEMA_VERSION` 与 M3 同源，一起改 |

### 6.5 可延后（不影响 04-03 启动）

M7（测试环境假设）、M8（Python 并发假设需文档化）、L1~L4。

---

## 七、未实施声明

本轮**只做评审**，未产生任何实现变更：

| 项目 | 状态 |
|---|---|
| Java / Python 代码 | ❌ 未修改 |
| 数据库 / Flyway | ❌ 未修改（仍停 V30） |
| `SOURCE-02-capability-schema.json` | ❌ 未修改 |
| 配置 | ❌ 未修改 |
| 分支 / 提交历史 | ❌ 未变（仍在 `61150cc`，工作区干净） |
| SOURCE-04-03 | ❌ 未启动 |

**本轮唯一新增文件**：本报告 `SOURCE-04-02-CODE-REVIEW.md`。

---

## 八、下一步

1. 按 §6.3 修复 H1、H2（建议连同 §6.4 的 M1/M2/M3/M4/M5/M6 一并处理）；
2. 补齐对应测试：`FailureKind` 分类断言、`platformScoped` 映射断言、并发只请求一次断言、`contractVersion` 不兼容拒绝断言；
3. 重新提交后再次评审；
4. 评审通过后方可进入 SOURCE-04-03（Desired/Observed/Effective 核对）。

> 再次强调：04-02 交付的是**可靠的 Observed 快照获取能力**。
> 它不表示 Desired/Observed/Effective 已完成核对——那属于 04-03。
