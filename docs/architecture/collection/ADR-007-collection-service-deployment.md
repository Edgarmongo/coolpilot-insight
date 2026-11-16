# ADR-007：Collection Service 部署方式（方案 A — 独立容器）

> 状态：**已接受并落地**（2026-11-16）
>
> 决策人：SOURCE-04 预审（用户明确选定方案 A）
> 关联：SOURCE-03 补充审查报告 §四、ADR-006（三态能力模型）、
> `docs/architecture/collection/SOURCE-02-CONTRACT.md`

---

## 一、背景

SOURCE-03 把采集服务的 `/health` 与 `/v1/capabilities` 从硬编码 stub 改造成
基于真实 Adapter / Capability Registry 的输出，但**没有部署这个服务**：
四个在跑的容器里没有任何一个跑 collection-service。

因此当时的状态是「模块存在、运行时不可用、跨服务未接通」——
接口代码写得再对，也没有任何调用方能观察到它。

同时，方案 B（并入 backend 容器）会引入新的问题：backend 容器已经同时承载
Spring Boot、JDK、Python 解释器与 Chrome，再塞一个 HTTP 服务会让部署单元更重。

## 二、候选方案

### 方案 A：独立容器（**已选**）

```
Java Backend ──HTTP──> Collection Service ──> Adapter Registry
     │                          │
     │                          └──> Chrome + DrissionPage（平台接入执行）
     └── 读 CSV（共享数据目录）
```

### 方案 B：并入 backend 容器

backend 容器内同时跑 Spring Boot 与 Collection Service 进程。

### 方案 C：不部署，Java 继续用子进程

维持现状，能力查询能力不落地。

## 三、决策

**采用方案 A：Collection Service 作为独立容器 `cpi-collection`。**

理由（按权重排序）：

1. **职责边界清晰**。采集服务回答"这个平台现在能取到什么"，
   backend 回答"这次任务要取什么、结果存到哪"。两者变化节奏不同，
   混在一个进程里会让任一侧的故障影响另一侧。
2. **Java 能直接查询 Registry**。这是 SOURCE-04 的核心前提：
   Java 不应该只看自己配置里的 `capability_json`，
   而要知道 Python **实际加载了什么**。这要求一个可寻址的 HTTP 端点。
3. **可独立重启与扩容**。某个平台的 Adapter 加载失败、Chrome 崩溃、
   依赖升级，都不需要重启整个 backend。
4. **避免方案 B 的资源与生命周期耦合**。backend 的健康检查、重启策略、
   日志轮转会同时影响两个性质完全不同的服务。

## 四、落地细节与取舍

### 4.1 CSV 数据目录共享（关键取舍）

采集服务写 CSV，backend 读 CSV——这是 SOURCE-03 冻结的硬契约之一
（"Java 读 `<aweme_id>.csv`" 是确认过的生产主链路）。

**做法**：两个容器挂**同一个宿主目录** `collection-service/datas`：

```yaml
collection:
  volumes:
    - ../../collection-service/datas:/app/collection-service/datas
backend:
  volumes:
    - ../../collection-service/datas:/app/collection-service/datas
```

**为什么不用共享卷（named volume）**：named volume 在两个容器之间共享没问题，
但宿主机上不可直接查看，运维排查 CSV 时不方便；bind mount 与现有约定一致
（backend 已经是这个写法）。

**遗留**：SOURCE-06 采集执行链路切到 HTTP 之后，backend 就不再需要读 CSV，
届时可以退化为"采集服务单写、Java 走 HTTP 拉结果"。在那之前保持双挂载。

### 4.2 Chrome profile 共享（已知约束）

Chrome 用户数据目录使用与 backend **同一个** named volume `chrome-data`。

- **理由**：抖音登录态存在 Chrome profile 里。分容器用独立 profile 会让登录态分裂，
  将来从子进程切到容器采集时需要迁移 profile，徒增复杂度。
- **代价（硬约束）**：**同一个 Chrome profile 不能被两个 Chrome 进程同时占用**。
  因此 collection 容器与 backend 容器**不得并发启动 Chrome**。
  SOURCE-06 之后采集执行已切到该容器（`app.collection.executor=http`），
  因此这条约束从"将来时"变成"现在时"：backend 不得再起采集子进程，
  回滚到 `executor=process` 时必须先停掉 collection 的采集作业。
- **兜底**：当前生产是匿名态采集（`use_login=False`），即使 profile 冲突也只影响登录态采集。

### 4.2.1 单副本部署（**硬约束**，SOURCE-04 后续 C-4）

`collection-service` **只允许部署 1 个副本**。这不是建议，是当前实现的前提。

**原因**：采集作业队列 `app/jobs/queue.py` 是**进程内**队列（一个 worker 线程 +
`queue.Queue`），它只在**单进程内**保证"任一时刻只有一个 Chrome 在跑"。
队列不跨进程、不跨容器，也没有外部分布式锁。

**多副本会发生什么**：

1. 两个副本各自从 `POST /v1/jobs` 收作业，各自起一个 Chrome；
2. 两个 Chrome 抢同一个 `chrome-data` volume 的 profile → `SingletonLock` 冲突；
3. 表现为**间歇性**浏览器启动失败（不是每次必现），
   而作业状态会被记为 `FAILED / BROWSER_ERROR`，看起来像"平台风控"，很难归因。

**注意区分两个不同的约束**：

| 约束 | 现状 | 说明 |
|---|---|---|
| backend 多副本 | **允许** | backend 无状态，靠 DB 行锁/租约协调 |
| collection 多副本 | **禁止** | 进程内队列 + 共享 Chrome profile |

**未来要扩容必须同时具备**（缺一不可）：

1. 跨进程作业协调（外部队列 / 选主 / DB 租约），保证全局只有一个 Chrome；
2. Chrome profile 按副本隔离（每副本独立 volume），并解决登录态的分配问题；
3. 作业状态从内存迁到持久存储，否则副本重启会丢在途作业；
4. 上述任一缺失时，扩容只会把"串行"变成"随机失败"。

**当前不做**：不修改副本数、不改 compose 部署结构、不引入外部队列。
本节的交付物只有这条约束本身。

### 4.3 服务间认证

- 采集服务的 `INTERNAL_TOKEN` 非空时，`/v1/capabilities` 与 `/v1/adapters*`
  要求携带 `X-Internal-Token`；
- `/health` **保持公开**：容器健康检查需要在没有令牌的情况下探活，
  且它的响应只含适配器元信息（模块名、版本、能力数量、证据统计），不含任何凭证信息；
- 本地开发留空 `INTERNAL_TOKEN` 时不校验（与现有 flask 服务的约定一致）。

### 4.4 端口与网络

| 项 | 值 |
|---|---|
| 容器内端口 | 5001 |
| 宿主端口 | `${COLLECTION_HOST_PORT:-5001}` |
| 容器间寻址 | `http://collection:5001` |
| Java 配置键 | `app.collection-service-url` / 环境变量 `COLLECTION_SERVICE_URL` |

**与 ai-service 的区分是硬性要求**：`http://collection:5001` 与
`http://flask:5000` 是两个不同服务，Java 侧必须分开配置、分开探活
（见 SOURCE-03 补充审查报告 §五）。

## 五、被否决的方案

| 方案 | 否决理由 |
|---|---|
| B：并入 backend 容器 | 部署简单是短期收益，但资源与生命周期耦合、Python 异常影响整体部署、后续扩展多平台 Adapter 时结构受限。不适合作为长期方案。 |
| C：不部署 | 与 SOURCE-04「Java 能知道 Python 实际加载了什么」这一核心目标直接冲突，能力模型就落不了地。 |

## 六、后果

### 正面

- Collection Service 有独立的生命周期与健康状态，可独立观测；
- Java 侧获得一个稳定的 Registry 查询入口，SOURCE-04-02/03 得以实施；
- 采集平台的能力升级不需要动 backend 镜像。

### 负面 / 待办

| 事项 | 归属 |
|---|---|
| 采集执行链路仍是 backend 的子进程（`python comment.py`），尚未切到 HTTP `/v1/jobs` | SOURCE-06 |
| 两个容器不得并发启动 Chrome | SOURCE-06（切换时显式停子进程） |
| `INTERNAL_TOKEN` 的分发与轮转尚无治理流程 | SOURCE-09 |
| 采集服务目前**没有**承载真实采集流量，负载与容量未验证 | SOURCE-06 |

## 七、验证

方案 A 落地后实测（2026-11-16）：

- `cpi-collection` 容器 healthy；
- `GET /health` → 200，`registry.loadedCount=2`、`capabilityCount=4`、`degraded=false`；
- `GET /v1/capabilities` → 200，`connectors[0].capabilities` 三项 VERIFIED 且带 `verifiedAt`；
- `GET /v1/adapters` → 200，`DOUYIN_PUBLIC.loadStatus=LOADED`；
- backend 的 CSV 读链路未受影响（生产 `comment.py` 实跑 19 条，表头/BOM/列名无变化）。
