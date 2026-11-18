# CP-REDIS-01 实施任务卡

> 项目：单机 Redis 缓存基础设施建设
> 基线：`96a57dd` · Spring Boot 4.1.2 / JDK 21 / MyBatis / 网络 `cpi-net`
> **本轮仅授权 01、02（只读审计与设计）**；03 及之后需单独授权。

---

## 授权状态

| 任务 | 名称 | 类型 | 状态 |
|---|---|---|---|
| 01 | Redis 现状与缓存审计 | 只读 | ✅ 本轮完成 |
| 02 | Redis 架构 ADR 与技术设计 | 设计 | ✅ 本轮完成（Proposed） |
| 03 | Docker Redis 单机部署 | 实施 | ⏸ 待授权 |
| 04 | Java Redis 接入与统一缓存层 | 实施 | ⏸ 待授权 |
| 05 | 现有缓存业务迁移 | 实施 | ⏸ 待授权 |
| 06 | 缓存安全与数据分级 | 实施 | ⏸ 待授权 |
| 07 | 监控、故障降级与运维 | 实施 | ⏸ 待授权 |
| 08 | 集成测试、压力测试与验收 | 测试 | ⏸ 待授权 |

---

## 01 · Redis 现状与缓存审计（只读）

**交付**

- `REDIS-BASELINE-AUDIT.md`
- `CACHE-USAGE-INVENTORY.md`
- `CACHE-CANDIDATE-ANALYSIS.md`

**验收**

- 明确 HEAD、依赖、网络、既有 Redis 残留的处置；
- `MemoryCache` 全链路（键、TTL、失效）逐条列出；
- 候选清单含"禁止缓存"清单；
- 不修改任何代码/配置/部署。

**结论摘要**：见三份文档。关键发现三条——缓存面极小（3 Key + 1 快照）、
分析缓存失效实际不生效、`SnapshotCache` 与 `TokenStore` 不可当普通缓存迁移。

---

## 02 · Redis 架构 ADR 与技术设计

**交付**

- `ADR-REDIS-001.md`（Proposed）
- `REDIS-ARCHITECTURE.md`
- `CACHE-STRATEGY.md`

**验收**

- 职责边界写清（允许/不允许/本期不进）；
- 三条架构约束（缓存非事实源、Python 不碰 Redis、Redis 不做任务协调）；
- 部署设计适配 `cpi-net`（非 external）、无宿主端口；
- Java 接入层含 Key 规范、序列化、超时、降级、监控；
- 每类缓存写全 7 项策略。

---

## 03 · Docker Redis 单机部署（待授权）

**范围**：仅 `deploy/compose/docker-compose.yml` + `.env`

**步骤**

1. 新增 `redis` 服务（镜像固定 tag、`maxmemory 256mb`、`allkeys-lru`、无持久化、无 ports）；
2. 密码经 `.env` 注入（`REDIS_PASSWORD`）；
3. 加入 `cpi-net`；
4. 健康检查 `redis-cli ping`（带认证）。

**验收**

- `docker compose config` 通过；
- `docker ps` 显示 `cpi-redis` healthy；
- 容器内 `redis-cli ping` 返回 PONG；
- **宿主机 `ss -lntp | grep 6379` 无监听**；
- 未认证连接返回 `NOAUTH`；
- `cpi-backend` 能解析 `redis` 主机名。

**禁止**：给 Redis 映射宿主端口；复用旧的 `coolpilot-redis`。

---

## 04 · Java Redis 接入与统一缓存层（待授权）

**范围**：`backend/pom.xml`、新增 `common/cache/`、`application.yml`

**步骤**

1. 引入 `spring-boot-starter-data-redis`（版本交给 BOM）；
2. 实现 `CacheService`（Lettuce + **显式 Jackson 2** 序列化）；
3. `CacheKey`（`cp:insight:v1:...`）、`CacheTtl`（含抖动）；
4. `NoopCacheService`（`CACHE_ENABLED=false` 或熔断时装配）；
5. `CacheMetrics`（hit/miss/error/latency）；
6. 超时与连接池（connect 500ms / command 300ms / max-active 16）。

**验收**

- 单元测试：序列化往返、Key 生成、前缀删除（**`SCAN` 实现，必须真的能删到**）、
  Redis 异常时返回空而不抛；
- 关闭 `CACHE_ENABLED` 时行为与当前完全一致（回归测试通过）；
- 不引入 `@Cacheable`（避免 Mapper 层语义混乱）。

**风险**：项目内 Jackson 2/3 并存，序列化必须显式指定，否则出现静默格式差异。

---

## 05 · 现有缓存业务迁移（待授权，分四批）

| 批次 | 对象 | 关键断言 |
|---|---|---|
| 1 | R-03 分析结果（词云/桑基/主题） | 改数据后 5 秒内可见（修掉"等 5 分钟"） |
| 2 | R-01 数据源列表/能力三态 | Desired 更新后立即生效；**Effective 不能整体缓存 5 分钟** |
| 3 | R-02 概览聚合 | 含用户维度时 Key 必须带 userId，不得串数据 |
| 4 | R-04 评论分页 | 先做键空间估算，再定 TTL |

**验收**：每批独立提交、独立回滚；每批都要有"缓存结果 vs 直查结果"的一致性对照。

---

## 06 · 缓存安全与数据分级（待授权）

**分级**

| 等级 | 内容 | 策略 |
|---|---|---|
| L1 公开 | 内容列表、统计结果 | 允许缓存 |
| L2 内部 | 配置、聚合结果 | 最小化 + 按权限分片 |
| L3 敏感 | 密码、Cookie、API Key、凭证明文 | **禁止进缓存** |

**必做**

- ACL 最小权限用户（禁用 `FLUSHALL`/`CONFIG` 等高危命令）；
- 日志不打印缓存值（现有日志脱敏规则同样适用）；
- **缓存命中不得跳过权限校验**（`ContentAccessService` 必须在缓存读取之前执行）；
- 删除数据时有主动失效方案。

**验收**：构造"无权限用户访问他人视频"，命中缓存时仍返回 403。

---

## 07 · 监控、故障降级与运维（待授权）

**监控**：`used_memory`、`evicted_keys`、`keyspace_hits/misses`、`connected_clients`、
`rejected_connections`、命令延迟；应用侧 `cache.*` 指标；`/api/system/health/layers` 增加 `redis` 层。

**故障演练（6 项，必须全过）**

1. 停 Redis 容器 → 核心接口仍 200；
2. 重启 Redis → 缓存重建，无业务影响；
3. 清空数据（`FLUSHDB`）→ 无业务影响；
4. 打满 `maxmemory` → 淘汰生效，无 OOM；
5. 网络延迟/丢包 → 快速失败，**Tomcat 线程不被拖死**；
6. 认证失败 → 不重试风暴，熔断生效。

---

## 08 · 集成测试、压力测试与验收（待授权）

**测试**

- 集成：四类缓存各自的命中/失效/降级；
- 压力：迁移前后同压测脚本，记录 P95 与 MySQL `Questions` 增量；
- 一致性：灰度期同 Key 双读比对。

**验收门槛**

1. 命中率与延迟有**数字**（不是"感觉快了"）；
2. 6 项故障演练全过；
3. 回滚演练：`CACHE_ENABLED=false` 一键回到当前行为；
4. 无 SOURCE-04 契约变更、无任务状态机变更、无 Python 依赖 Redis。

---

## 周期估算（非承诺）

| 阶段 | 估算 |
|---|---|
| 01 + 02（本轮） | 1 天（已完成） |
| 03 部署 | 0.5 天 |
| 04 Java 接入 | 1–2 天 |
| 05 迁移（4 批） | 1–2 天 |
| 06 + 07 安全与监控 | 1 天 |
| 08 测试验收 | 0.5–1.5 天 |

**合计 5–8 天**（单人、环境正常、无大规模重构）。与你的估算一致。

---

## 前置风险（动手前必须确认）

| 风险 | 影响 | 处置 |
|---|---|---|
| 现有分析缓存失效 bug 未修 | 迁过去只是"更持久的脏数据" | 迁移前先修（任务 04 的 `deleteByPrefix`） |
| 无命中率/延迟基线 | 灰度无法证明收益 | 指标先于迁移落地 |
| `SnapshotCache` 被误当缓存迁移 | 准入判定抖动，甚至用陈旧快照放行 | ADR 已明确禁止，评审时要守住 |
| 旧 `coolpilot-redis` 被复用 | 环境不可考 | 部署新容器，不复用 |
