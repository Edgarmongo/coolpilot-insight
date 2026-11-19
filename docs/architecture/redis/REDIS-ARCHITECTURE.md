# Redis 架构与接入设计（REDIS-ARCHITECTURE）

> 状态：**设计稿**，本轮不实施
> 依据：`ADR-REDIS-001` 已定职责边界
> 基线：Spring Boot 4.1.2 / JDK 21 / MyBatis / Docker Compose（网络 `cpi-net`）

---

## 1. 部署形态

| 项 | 决策 |
|---|---|
| 版本 | Redis 7.x，**固定具体 tag**（示例 `redis:7.4-alpine`），验证后建议再固定 digest |
| 部署 | Docker Compose，服务名 `redis`，容器名 `cpi-redis` |
| 网络 | 加入现有 **`cpi-net`**（compose 自己创建，**不是 external**） |
| 端口 | **不做任何宿主映射**（仅容器网络内 `redis:6379`） |
| 认证 | `requirepass` 或 ACL 用户；密码经 `.env` 注入（`.env` 已 gitignore） |
| 内存 | `maxmemory 256mb` 起，按监控调整 |
| 淘汰 | `allkeys-lru` |
| 持久化 | 首期**关闭**（`appendonly no`、`save ""`）——纯缓存，不需要 RDB/AOF |
| 健康检查 | `redis-cli ping`（带认证参数） |
| 时区 | 容器 `TZ=Asia/Shanghai` 与其余服务一致；TTL 本身与时钟无关，无需 UTC 特殊处理 |

> 与示例配置的差异说明：你给的示例用了 `external: true` 的 `coolpilot-internal` 网络，
> 但本仓库的网络是 compose 内部创建的 `cpi-net`。**照抄示例会导致 `up` 失败**，
> 这里按实际网络改写。

### 1.1 参考配置（设计稿，未落盘）

```yaml
  redis:
    image: redis:7.4-alpine
    container_name: cpi-redis
    restart: unless-stopped
    command:
      - redis-server
      - --maxmemory
      - 256mb
      - --maxmemory-policy
      - allkeys-lru
      - --appendonly
      - "no"
      - --save
      - ""
      - --requirepass
      - ${REDIS_PASSWORD}
    environment:
      TZ: Asia/Shanghai
    networks:
      - cpi-net
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a $$REDIS_PASSWORD ping | grep -q PONG"]
      interval: 10s
      timeout: 3s
      retries: 5
    # 不写 ports: —— 刻意不对外暴露
```

**已知风险**：`--requirepass` 会让密码出现在 `docker inspect` 的 Command 字段中。
生产建议改为挂载 ACL 配置文件（`/usr/local/etc/redis/users.acl`），密码只在文件里。

---

## 2. Java 接入设计

### 2.1 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 客户端 | **Lettuce**（`spring-boot-starter-data-redis` 默认） | 线程安全、异步、Spring 生态默认 |
| 版本管理 | 交给 Spring Boot BOM | 不手写版本号，避免与 Boot 4.1.2 冲突 |
| 缓存抽象 | **自定义 `CacheService`**，不用 `@Cacheable` 起步 | MyBatis 无实体级缓存语义；业务需要"显式失效 + 降级"控制 |
| 序列化 | **JSON（Jackson）** | 可读、可跨语言、不反序列化任意 Java 类型 |

> ⚠️ 本项目同时存在 **Jackson 2（`com.fasterxml`）与 Jackson 3（`tools.jackson`）**。
> 缓存序列化必须**显式指定** Jackson 2 `ObjectMapper`（业务代码通用），
> 不能依赖容器默认注入，否则会因版本不同产生静默的格式差异。

### 2.2 包结构（按现有包风格）

```
com.coolpilot.insight.common.cache/
    CacheService.java          接口：get/set/delete/deleteByPrefix
    RedisCacheService.java     实现（Lettuce + JSON）
    CacheKey.java              Key 构造器（版本化前缀）
    CacheTtl.java              TTL 常量与抖动策略
    NoopCacheService.java      降级实现（Redis 关闭时装配）
    CacheMetrics.java          命中/未命中/失败计数
```

### 2.3 接口（示意）

```java
public interface CacheService {
    <T> Optional<T> get(String key, Class<T> type);
    <T> Optional<List<T>> getList(String key, Class<T> elementType);
    void set(String key, Object value, Duration ttl);
    void delete(String key);
    void deleteByPrefix(String prefix);   // 必须真正可用（见 §2.5）
    boolean isAvailable();
}
```

**约定**：所有方法**只抛受检不到业务异常**——内部吞掉 Redis 异常并记录，
返回 `Optional.empty()` 表示"未命中或不可用"，由调用方回源。

### 2.4 Key 规范

```
cp:insight:v1:<domain>:<name>[:<id>][:<page>]
```

| 示例 | 用途 |
|---|---|
| `cp:insight:v1:source:list` | 数据源列表 |
| `cp:insight:v1:source:capabilities:DOUYIN` | 能力三态 |
| `cp:insight:v1:overview:summary` | 概览统计 |
| `cp:insight:v1:analysis:wordcloud:123` | 词云 |
| `cp:insight:v1:comment:page:123:1:20` | 评论分页 |

规则：

- 版本段 `v1` 在**结构变更**时递增，旧 Key 自然过期（不做批量迁移）；
- 前缀删除只用**到 domain 粒度**，禁止 `cp:insight:*` 这种全库删除；
- Key 中不得包含用户凭证、手机号等敏感值。

### 2.5 必须重写的失效逻辑（对标现有缺陷）

现状 `deleteByPrefix("analysis:*")` 永不命中（见 `CACHE-USAGE-INVENTORY.md` §2.3）。
新实现要求：

- `deleteByPrefix` 用 **`SCAN` 游标**（不是 `KEYS`）遍历匹配；
- 前缀由 `CacheKey` 生成，调用方**不手写带 `*` 的字符串**；
- 单次删除量设上限（如 1000 个 Key），超出改为"版本号递增"整体失效。

### 2.6 连接与超时（防止线程堆积）

| 参数 | 建议值 | 说明 |
|---|---|---|
| `connect-timeout` | 500ms | 建连失败快速放弃 |
| `timeout`（命令） | 300ms | **必须小于业务请求超时** |
| `max-active` | 16 | 与 Tomcat 线程数匹配，不是越大越好 |
| `max-idle` | 8 | |
| `min-idle` | 2 | |
| `max-wait` | 500ms | 池满时快速失败 |

要求：**Redis 抖动不得导致 Tomcat 线程池耗尽**——这是故障演练的必测项。

### 2.7 降级开关

```yaml
app:
  cache:
    enabled: ${CACHE_ENABLED:true}      # 总开关，关闭时装配 NoopCacheService
    redis:
      host: ${REDIS_HOST:redis}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
```

- `enabled=false` → 装配 `NoopCacheService`（全部返回未命中），业务逻辑不变；
- Redis 连续失败 N 次（如 5 次/30 秒）→ 熔断 30 秒，期间直接走 Noop 语义。

---

## 3. 监控设计（与缓存层同批交付）

### 3.1 服务端指标（`INFO` 采集）

`used_memory`、`maxmemory`、`keyspace_hits`、`keyspace_misses`、
`evicted_keys`、`connected_clients`、`rejected_connections`、`commandstats` 延迟。

### 3.2 应用侧指标（自建，Micrometer）

| 指标 | 用途 |
|---|---|
| `cache.requests{key_prefix,result=hit/miss/error}` | 命中率与错误率 |
| `cache.latency` | 包装层耗时（区分 Redis 慢 vs 业务慢） |
| `cache.circuit_open` | 熔断状态 |
| `cache.evictions` | 应用感知到的 Key 丢失（TTL 到期） |

命中率公式：`hits / (hits + misses)`。

**注意**：命中率高不等于设计合理——若缓存了大量低价值 Key，会挤占内存并触发淘汰，
因此同时要看 `evicted_keys` 与内存占用。

---

## 4. 与现有实现的共存策略

| 现状 | 处理 |
|---|---|
| `MemoryCache` | 迁移完 R-03 后**保留类**，作为 `NoopCacheService` 之外的进程内二级缓存（可选）；**不要两套同时生效**，避免双写不一致 |
| `SnapshotCache` | **不动** |
| `TokenStore` | **不动**（决策 D-1 之前） |
| `LayeredHealthService` | 增加一层 `redis` 探测（PING + 延迟），与 db/collection/ai 并列 |

---

## 5. 部署验证清单（Phase 2 用）

```bash
docker compose config          # 语法与变量替换
docker compose up -d redis
docker ps --filter name=cpi-redis
docker exec cpi-redis redis-cli -a "$REDIS_PASSWORD" ping        # PONG
docker exec cpi-backend sh -c 'getent hosts redis'               # 服务名解析
docker exec cpi-collection sh -c 'redis-cli -h redis ping'       # 应失败（未装客户端/未授权）
ss -lntp | grep 6379                                             # 宿主不应有监听
```

预期：容器内 PONG；宿主机无 6379 监听；未认证连接返回 `NOAUTH`。
