# SOURCE-06 任务卡：采集执行链路切 HTTP + 按 Registry 派发

> 状态：**两端均已实施**（默认 http 执行）；遗留与一个未定性现象见实施报告 §8.4/§8.5
> 实施报告：`SOURCE-06-IMPLEMENTATION.md`
> 前置：SOURCE-04 已收口（04-01 ~ 04-08 全部执行完毕）
> 关联：`ADR-006`（三态）、`ADR-007`（独立容器 / CSV 共享目录 / Chrome 不得并发）、
> `SOURCE-04-04-EFFECTIVE-REASON.md`（准入判定）、`COLLECTION-04-java-python-contract.md`

---

## 0. 为什么必须做

现在同一个"采集能力"，有两个互不知道对方的真相：

| 视角 | 事实来源 | 问题 |
|---|---|---|
| 声明的能力 | `ds_source.capability_json` → Effective | 只有它能判"业务允许执行" |
| 实际执行 | backend `ProcessRunner` 起子进程 `python comment.py` | **完全不看 Effective**：哪怕 Effective 是 `UNREGISTERED`，只要点采集它照样跑 |

后果不是理论问题：

- 把 `COMMENT` 的 Desired 改成 `DISABLED`、或证据过期判 `EVIDENCE_EXPIRED`，
  Effective 显示"不可执行"，但**采集按钮仍然能跑通**——三态模型被绕过；
- 采集容器里已经注册了 Adapter / 能力 / 证据，backend 却仍在起子进程，
  等于两套执行路径各自演进；
- `/v1/jobs` 是 stub，永远走 `StubAdapter`，与实际平台无关。

SOURCE-06 要解决的就是这一刀：**执行必须经过同一套准入判定，并由 Registry 决定派发给谁。**

---

## 1. 三个不可让步的约束

### C1　派发必须来自 Registry，不得硬编码

`POST /v1/jobs` 必须携带 `connectorCode` + `capabilityCode`，服务端在 Registry 里
**按这两个键查** `adapterFunction` 并调用。

```
❌ if sourceCode == "DOUYIN": run_douyin_comments()   // 硬编码，新平台要改代码
✅ spec = registry.capabilities.lookup(connector, capability)
   fn = spec.adapter_function → import → 调用
```

查不到就**报错**，不准"猜一个能跑的"。

### C2　不得静默回退 STUB

当前 `/v1/jobs` 无论传什么都用 `StubAdapter`。这是最危险的一种 fail-open：
调用方以为在采真实数据，实际拿到的是造出来的评论。

**规则**：`StubAdapter` 只能在两条件同时成立时使用——
`connectorCode == STUB_PUBLIC` **且** 环境变量 `COLLECTION_ALLOW_STUB=1`。
否则一律拒绝，并在错误信息里写明"该 Connector 不提供此能力"。

### C3　Chrome 单实例，作业必须排队

`ADR-007` 已记录：backend 与 collection 共享 `chrome-data` 卷，两个容器不得并发启动 Chrome。
HTTP 化之后执行全在 collection 内部，因此**排队必须做在 collection 侧**：
单 worker 线程 + 队列，任何时刻最多一个 `RUNNING`。

排队期间状态保持 `ACCEPTED`（不新增 `QUEUED` 状态，避免动已经存在的作业状态词表）。
`GET /v1/jobs/{id}` 额外返回 `queuePosition`，让上游能解释"为什么还没开始"。

---

## 2. 接口定稿（在现有 stub 形状上扩展，不推翻）

### 2.1 `POST /v1/jobs`

```json
{
  "jobId": "task-1234-item-5678",
  "sourceCode": "DOUYIN",
  "connectorCode": "DOUYIN_PUBLIC",
  "capabilityCode": "COMMENT",
  "accessMode": "PUBLIC",
  "externalContentId": "7703721523349239951",
  "maxItems": 20,
  "params": { "useLogin": false }
}
```

响应（`202`）：

```json
{ "jobId": "…", "status": "ACCEPTED", "queuePosition": 1 }
```

幂等：同一 `jobId` 重复提交返回既有作业，不重复入队（现有行为保留）。

### 2.2 `GET /v1/jobs/{id}`

在现有字段上增加 `queuePosition`、`startedAt`、`finishedAt`。状态词表不变：
`ACCEPTED / RUNNING / SUCCEEDED / PARTIAL / FAILED / CANCELLED`。

### 2.3 `GET /v1/jobs/{id}/result`

保持现有分页游标语义（`cursor` / `size` / `nextCursor` / `done`），
条目字段沿用 `externalCommentId / parentExternalId / text / nickname / likes / ipRegion / publishedAt / dedupStrategy`。

### 2.4 `POST /v1/jobs/{id}/cancel`（新增）

**尽力取消**：排队中的作业直接置 `CANCELLED` 并从队列摘除；
已在 `RUNNING` 的作业置 `cancelRequested=true`，在当前页结束处停止并置 `CANCELLED`，
**不强行杀 Chrome**（强杀会留下 profile 锁，下一次启动直接失败）。

---

## 3. 派发前的判定（服务端这一侧）

服务端只做**Observed 层**判定（它没有 Desired）。逐条短路，命中即拒绝：

| # | 条件 | HTTP | 错误码 |
|---|---|---|---|
| 1 | 未配置 Token / Token 错 | 401 | `UNAUTHORIZED` |
| 2 | `connectorCode` / `capabilityCode` 缺失或类型错 | 400 | `PARAM_INVALID` |
| 3 | Connector 不在 Registry | 404 | `CONNECTOR_NOT_FOUND` |
| 4 | Adapter `loadStatus != LOADED` | 409 | `ADAPTER_NOT_LOADED` |
| 5 | 该 Connector 下无此能力 | 409 | `CAPABILITY_NOT_REGISTERED` |
| 6 | `adapterFunction` 解析不到 | 409 | `FUNCTION_UNRESOLVED` |
| 7 | `accessMode` 不在该能力声明的 `accessModes` 内 | 409 | `ACCESS_MODE_UNSUPPORTED` |
| 8 | `implementationStatus != VERIFIED` 或 `evidence.accepted != true` | 409 | `NOT_EXECUTABLE` |
| 9 | 需要 AUTHENTICATED 但无可用凭证 | 409 | `ACCOUNT_REQUIRED`（本期只做占位判定，凭证体系见 SOURCE-09） |

对照 `SOURCE-04-04-EFFECTIVE-REASON.md`：这 9 条与 Java 侧 `effectiveReason`
一一对应，**两侧用同一套理由词**，上游拿到的错误可以直接映射成 Effective 结论。

---

## 4. Java 侧改造

| 项 | 内容 |
|---|---|
| 新增 | `integration/collection/HttpCollectionClient`：提交 → 轮询状态 → 拉结果，总超时沿用 600s / 300s |
| 保留 | `ProcessCollectionClient` 保留一个版本周期，配置 `app.collection.executor=http|process`，**默认 `http`**；`process` 标注 deprecated |
| 提交前判定 | `CollectServiceImpl` 在提交 job 前先取该 Source 的 Effective，取不到 `executable=true` 的对应能力 → **直接拒绝**，不发 HTTP |
| 产出 | 不变：仍是 `<aweme_id>.csv`（13 列 + BOM），由 collection 侧写共享目录，Java 侧照旧读 CSV 落库 |

> **为什么不改成直接返回 JSON 落库**：CSV 是 current 事实上的跨模块契约
> （`CollectServiceImpl:336`、`CleanServiceImpl` 都按 `<aweme_id>.csv` 读）。
> 一次改两处，风险远大于收益。HTTP 化只换"谁执行"，不换"产出是什么"。

---

## 5. 验收标准

| # | 断言 |
|---|---|
| A1 | 派发走 Registry：篡改 Registry（改名能力）后，同一请求返回 `CAPABILITY_NOT_REGISTERED`，**不会**偷偷跑通 |
| A2 | 未设 `COLLECTION_ALLOW_STUB=1` 时请求 `STUB_PUBLIC` → 拒绝；设置后才允许（证明没有默认回退） |
| A3 | 并发提交 3 个 job：任一时刻 `RUNNING` 数量 ≤ 1，其余为 `ACCEPTED` 且 `queuePosition` 递增 |
| A4 | 同一 `jobId` 重复提交只产生一个作业，第二次返回既有状态 |
| A5 | `implementationStatus=UNVERIFIED` 的能力 → `NOT_EXECUTABLE`；`evidence.accepted=false` → 同样拒绝 |
| A6 | 非法 `accessMode` → `ACCESS_MODE_UNSUPPORTED` |
| A7 | `cancel` 排队中的作业 → 立即 `CANCELLED` 且不出现在结果里 |
| A8 | Java 侧：Effective 不可执行时**不发出 HTTP**（用假 client 断言零调用） |
| A9 | Java 侧：HTTP 客户端对 5xx / 超时 / 409 分别映射为可读失败原因，不吞错 |
| A10 | 端到端：真实 `DOUYIN/COMMENT` 采集跑通，CSV 13 列 + BOM 与旧链路逐字节同格式 |
| A11 | 回退开关 `executor=process` 仍能跑通（切换期保障） |

Python 测试落在 `tests/test_jobs.py`（新增），Java 测试落在
`HttpCollectionClientTest` + `CollectServiceImpl` 的准入断言。

---

## 6. 明确不做

- 不动 `collection_task` / `collection_run` / `item` 状态机（沿用现有）；
- 不改 `/v1/capabilities`、`/v1/adapters`、`/v1/registry/snapshot` 的既有字段；
- 不引入写能力（发布 / 互动）——本阶段仍然只读；
- 不做凭证体系（`ACCOUNT_REQUIRED` 只是占位判定，真正实现属 SOURCE-09）；
- 不做前端（采集页与任务页的展示逻辑不动，最多显示失败原因）。

---

## 7. 需要裁决的三点

| # | 问题 | 我的建议 |
|---|---|---|
| D1 | 服务端是否重复判 `implementationStatus==VERIFIED && evidence.accepted`（Java 侧已判过） | **要判**。两侧判的不是同一件事：Java 判"业务允许 + 快照新鲜"，服务端判"我这份 Registry 现在真的能跑"。少了任一侧，另一次的 Registry 漂移就会漏过去 |
| D2 | 取消只做"尽力"（不杀 Chrome） | **接受**。强杀会留 profile 锁，下次启动直接失败，代价高于"晚几秒停" |
| D3 | `ProcessCollectionClient` 保留多久 | 保留到 SOURCE-06 验收通过后的下一个版本；期间默认 `http`，`process` 仅作回滚通道 |
