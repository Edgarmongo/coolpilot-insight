# 缓存使用清单（CACHE-USAGE-INVENTORY）

> 任务：CP-REDIS-01-01（只读）
> 基线：`96a57dd`
> 范围：`backend/src/main/java`、`collection-service/app`、`ai-service/app`
> 方法：全仓库检索 + 逐处阅读，**未运行压测**，热度判断基于调用路径而非实测 QPS

---

## 1. 汇总

| # | 缓存对象 | 实现 | Key 数 | TTL | 失效方式 | 跨进程 | 风险 |
|---|---|---|---|---|---|---|---|
| C-01 | 词云结果 | `MemoryCache` | 每视频 1 | 5 分钟 | 前缀删除（**实际失效**） | 否 | 中 |
| C-02 | 桑基图数据 | `MemoryCache` | 每视频 1 | 5 分钟 | 同上 | 否 | 中 |
| C-03 | 主题分析结果 | `MemoryCache` | 每视频 1 | 5 分钟 | 同上 | 否 | 中 |
| C-04 | 能力快照（Observed） | `SnapshotCache` | 1（全局） | **60 秒** | TTL + single-flight | 否 | **高（参与准入）** |
| C-05 | 登录 token | `TokenStore` | 每会话 1 | 与 JWT 一致 | 显式 remove / 过期 | 否 | **高（会话一致性）** |
| C-06 | Python Registry | `collection-service` | 无缓存 | — | 每请求重建 | 否 | 低（构建成本小） |

---

## 2. `MemoryCache` 全链路（C-01 ~ C-03）

### 2.1 实现

`common/util/MemoryCache.java`

```java
private final Map<String, CacheEntry> map = new ConcurrentHashMap<>();
// put(key, String value, ttlMs) / get / delete / deleteByPrefix
```

三个特征值得注意：

1. **只存 String**（`CacheEntry.value` 是 String）→ 调用方自己 `JSON.toJSONString` / `JSON.parseArray`；
2. **无容量上限**：没有 LRU、没有最大条目数，只有 TTL；
3. **无统计**：没有命中/未命中计数，无法评估效果。

### 2.2 调用点（全部）

| 位置 | 方法 | Key | TTL | 行为 |
|---|---|---|---|---|
| `AnalysisServiceImpl.java:280 / :295` | `getWordCloud(videoId)` | `analysis:wordcloud:{videoId}` | 5 min | 命中反序列化返回，未命中计算后写入 |
| `AnalysisServiceImpl.java:310 / :357` | 桑基图 | `analysis:sankey:{videoId}` | 5 min | 同上 |
| `AnalysisServiceImpl.java:406 / :420` | 主题分析 | `analysis:topic:{videoId}` | 5 min | 同上（值为纯字符串） |
| `AnalysisServiceImpl.java:573` | 视频统计刷新后 | 前缀 `analysis:*` | — | **失效调用**（见 2.3） |
| `CleanServiceImpl.java:110` | 清洗成功后 | 前缀 `analysis:*` | — | 同上 |

常量：`CACHE_PREFIX = "analysis:"`，`CACHE_TTL = 5 * 60 * 1000L`。

### 2.3 失效路径实际不生效（缺陷）

`deleteByPrefix` 用 `startsWith(prefix)` 匹配，而调用方传的是 `"analysis:*"`
（字面量带星号）。Key 形如 `analysis:wordcloud:123`，前缀匹配失败 →
**缓存只能靠 TTL 过期**。

影响：数据变更后最长 5 分钟内仍读到旧结果（词云 / 桑基图 / 主题）。
不致命，但迁移到 Redis 前必须先修——否则只是把一个"不生效的失效"搬到远端。

### 2.4 并发与一致性

- 无 single-flight：热点 Key 过期瞬间，多个请求会同时计算并重复写；
- 无互斥：两个请求可能写入不同结果（后写覆盖），但同一 Key 语义相同，可接受；
- 进程重启即全部丢失 → 重新计算（可接受，属于纯派生数据）。

---

## 3. `SnapshotCache`（C-04）——**不是普通缓存**

`integration/capability/SnapshotCache.java` + `application.yml`：

```yaml
app.capability.observed-ttl-seconds: ${CAPABILITY_SNAPSHOT_TTL_SECONDS:60}
# 注释原文：超 TTL 未刷新则不得作为执行准入依据
```

设计要点（代码实测）：

| 特性 | 实现 |
|---|---|
| 条目替换 | `volatile Entry`，整体替换，不就地改字段 |
| 并发加载 | `CompletableFuture` single-flight，**只保护 inFlight，不包住网络请求** |
| 失败退避 | `lastFailureAt` + `minRetrySeconds = 5`，避免 Registry 不可用期间疯狂重试 |
| 陈旧语义 | `SnapshotCacheState.{FRESH, STALE, UNAVAILABLE}` → `CapabilityReconciler` 据此判定 |

**为什么它不能当普通缓存迁移**：

- 它的状态直接决定**采集能否执行**（`STALE`/`UNAVAILABLE` → 准入拒绝）；
- 迁到 Redis 后，"缓存命中"变成一次网络往返，Redis 抖动会被放大成"准入判定抖动"；
- 若为了容错在 Redis 故障时回退到进程内旧副本，就会出现**用陈旧快照放行执行**的安全问题。

→ 建议：**首期保持进程内**，Redis 只做"快照原文的多实例备份"（可选，见候选分析）。

---

## 4. `TokenStore`（C-05）——会话状态，不是缓存

`utils/TokenStore.java`：`ConcurrentHashMap<token, userId>` + `expires`。

使用者：`JwtInterceptor`（每次请求校验）、`UserServiceImpl`（登录/登出/改密）、
`UserAdminService`（封禁踢下线）。

| 现状 | 后果 |
|---|---|
| 进程内 Map | backend 重启 → 所有用户需重新登录 |
| 无跨实例共享 | 多实例时，"登出/封禁"只对当前实例生效（安全漏洞面） |
| `cleanExpired()` 懒清理 | 长尾 token 会短暂滞留，量级可控 |

**这是 Redis 能真正解决的一类问题**，但它属于认证链路（见候选分析 D-1）。

---

## 5. 其他可能被误认为缓存的结构

| 结构 | 是否是缓存 | 说明 |
|---|---|---|
| `LayeredHealthService` | 否 | 每次请求实时探测，无缓存（有意为之） |
| `ContentAccessService` / `GovernanceService` | 否 | 每次查库做权限判定，**不应缓存权限结果**（易越权） |
| `CapabilityReconciler` 内部 `_hash_cache`（Python Registry 侧） | 是（局部） | 仅在一次构建内复用哈希，不跨请求 |
| `executor._ADAPTERS` | 否 | 静态映射表 |

**明确不缓存**：权限判定结果、任务状态、凭证。

---

## 6. 数据来源与读取路径（用于候选评估）

| 读取路径 | 现状 | 是否已有缓存 |
|---|---|---|
| `/api/overview`（多表 count + 趋势 + TopN） | 每次实时聚合 | ❌ |
| `/api/comment/page`（分页 + 情感 join） | 每次实时查询 | ❌ |
| `/api/v1/source/sources`（含 Effective 三态） | 每次查 DB + 快照 | 快照有 60s 缓存，DB 无 |
| `/api/analysis/{wordcloud,sankey,topic}` | 计算/聚合较重 | ✅ 5 min |
| `/api/report` 列表与详情 | 每次查 DB | ❌ |
| `/api/system/health/layers` | 实时探测 | ❌（有意） |

---

## 7. 结论

1. **缓存面很小**：真正被缓存的只有 3 类分析结果 + 1 个快照；
2. **一处真实缺陷**：分析缓存的前缀失效不生效（C-01~C-03 共用）；
3. **两个高风险对象不能当普通缓存**：`SnapshotCache`（准入语义）、`TokenStore`（会话一致性）；
4. **没有任何命中率数据**：现有实现无计数器，迁移前后都无从对比 →
   监控埋点必须与迁移同步落地，否则"灰度验证"没有判据。
