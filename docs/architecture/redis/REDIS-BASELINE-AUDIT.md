# CP-REDIS-01 · 基线审计（Phase 0）

> 任务：CP-REDIS-01-01（只读）
> 代码基线：`96a57dd` · Flyway **V33** · 工作区**干净**
> 审计日期：2026-11-18
> 本轮**未安装 Redis、未改任何代码/配置/部署**

---

## 1. 代码与运行时基线

| 项 | 值 |
|---|---|
| HEAD | `96a57dd`（docs(memory) 提交） |
| 工作区 | clean（无未提交改动） |
| Spring Boot | **4.1.2**（parent） |
| JDK | **21** |
| 持久层 | MyBatis（`BaseCrudMapper` + XML/注解），非 JPA |
| 数据库 | MySQL 8，Flyway V33，47 张业务表 |
| 运行容器 | `cpi-mysql` / `cpi-backend` / `cpi-collection` / `cpi-flask` / `cpi-frontend` 全 healthy |

> 与你描述中的"Spring Boot 4.1.x"一致；但**持久层是 MyBatis**，
> 因此 Spring Cache 的注解式缓存无法直接作用于 Mapper 层，需在 Service 层显式接入。

---

## 2. Docker 与网络现状

### 2.1 网络

| 项 | 事实 |
|---|---|
| Compose 网络名 | `cpi-net`（`driver: bridge`，**compose 内部创建**） |
| 是否 external | **否**——`networks: cpi-net: driver: bridge`，由本 compose 项目创建 |
| 容器间访问 | 服务名解析（`backend`、`collection`、`mysql`） |

**结论**：部署设计里应使用 `cpi-net`，**不要**照抄"external network"的示例
（那会导致 `docker compose up` 因找不到外部网络而失败）。

### 2.2 已存在的 Redis 实例（重要）

主机上**已经有两个 Redis 容器，但都不是本项目的**：

| 容器 | 镜像 | 状态 | 端口 | 网络 | 判定 |
|---|---|---|---|---|---|
| `coolpilot-redis` | redis:7-alpine | **exited** | 16379→6379 | `coolpilot_default`（旧栈） | 11-07 的旧部署残留，同批的 `coolpilot-server/mysql/web/admin` 均已退出 10 天 |
| `dyp-redis` | redis:7-alpine | **exited** | 无映射 | `dyp_dyp-net` | 另一个项目，与本项目无关 |

**处理建议**：**不复用、不改动**。理由：

1. 它们不在 `cpi-net` 上，加入当前栈需要改网络与重启；
2. `coolpilot-redis` 属于已废弃的部署栈，数据与配置来源不明；
3. 复用会让"这个 Redis 到底服务于谁"变得不可考——正是本项目要避免的状态。

新 Redis 应作为 `cpi-net` 上的新服务（`cpi-redis`）独立部署，与旧残留无耦合。

### 2.3 端口与暴露面

现有对外端口：`5173`（前端）、`8080`（后端）、`5001`（采集）、`3307`（MySQL）。
**Redis 不应增加任何宿主端口映射**（仅容器网络内可达）。

---

## 3. 依赖现状

| 检查 | 结果 |
|---|---|
| `spring-boot-starter-data-redis` | ❌ 未引入 |
| Lettuce / Jedis | ❌ 无 |
| Redis 客户端（Python） | ❌ 无（`collection-service/requirements.txt` 不含 redis） |
| Spring Cache 抽象 | ❌ 未启用（无 `@EnableCaching`、无 `@Cacheable`） |
| 现有多实例部署 | ❌ 单实例（backend 单副本，collection 硬约束单副本） |

**含义**：引 Redis 是**新增依赖**，不是"替换已有的分布式缓存"。
收益主要来自"为将来多实例做准备"，而不是"现在缓存压力大"。

---

## 4. 现有缓存形态（概览，详表见 `CACHE-USAGE-INVENTORY.md`）

| 形态 | 位置 | 规模 | 性质 |
|---|---|---|---|
| `MemoryCache` | `common/util/MemoryCache.java` | 3 个 Key（`analysis:*`），TTL 5 分钟 | 纯进程内 Map |
| `SnapshotCache` | `integration/capability/SnapshotCache.java` | 1 个快照条目，TTL **60 秒** | **参与执行准入判定** |
| `TokenStore` | `utils/TokenStore.java` | 每登录一个 token | 会话状态，非缓存 |
| Python 侧 | `collection-service` registry | 无独立 TTL 缓存 | 每次请求重建 |

**一句话结论**：当前缓存面**非常小**（3 个 Key + 1 个快照），
Redis 的引入价值不在"减轻现有压力"，而在"统一缓存契约 + 为多实例铺路"。
这一点必须在立项时讲清楚，否则会做出一个"装完没感觉"的项目。

---

## 5. 审计中发现的两个既有缺陷（不属 Redis 项目，但会影响迁移判断）

### 5.1 分析缓存**失效逻辑实际不生效**

```java
// MemoryCache
if (e.getKey().startsWith(prefix)) { ... }

// 调用方（AnalysisServiceImpl:573 / CleanServiceImpl:110）
cache.deleteByPrefix(CACHE_PREFIX + "*");   // prefix = "analysis:*"
```

Key 实际形如 `analysis:wordcloud:123`，`startsWith("analysis:*")` **永远为 false**。
即：数据更新后缓存**不会失效**，只能等 5 分钟 TTL 自然过期。

- 影响：最多 5 分钟陈旧数据（词云/桑基图/主题）；
- 意义：迁移到 Redis 时**必须先把失效语义定对**，否则只是把 bug 搬过去。

### 5.2 `TokenStore` 重启即全员掉线

token 存在进程内 `ConcurrentHashMap`，backend 重启后所有登录态失效；
多实例时 `logout` / 封禁只影响当前实例。

- 这是 Redis 真正能解决的一类问题（共享会话），
- 但它属于**认证链路**，见 `CACHE-CANDIDATE-ANALYSIS.md` 的 D-1 决策，不宜顺手改。

---

## 6. Phase 0 结论

1. 网络用 `cpi-net`，不用 external；
2. 旧 Redis 残留不复用；
3. 引入 Redis 是新增依赖，**首期价值是统一契约与为多实例铺路**，不是降压；
4. 迁移前必须先修"失效语义"（5.1），否则 Redis 只是让 bug 更持久；
5. `SnapshotCache` 与 `TokenStore` 是**两个高风险对象**，不能当作普通缓存迁移。
