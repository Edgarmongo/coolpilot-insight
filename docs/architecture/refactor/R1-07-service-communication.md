# R1-07 · Java 与 Python 通信规范

## 1. 职责划分

| 侧 | 职责 |
|---|---|
| Java | 业务编排、事务、状态机、落库、权限、审计 |
| Collection Service | 平台适配、页面/接口采集、原始数据返回 |
| AI Service | LLM 调用、结构化输出、评分、报告渲染 |

**Python 不写业务库**。现有 `clean.py` 用 pymysql 直写 `comment`/`video` 的做法在目标架构中取消：
改为返回结构化结果，由 Java 的 `content.ContentIngestService` 落库。

## 2. 通信方式总览

| 调用 | 方式 | 理由 |
|---|---|---|
| Java → Collection（单条内容采集） | **异步任务**：提交 + 轮询 | 采集耗时 10–120s，HTTP 同步易超时 |
| Java → Collection（健康/能力查询） | 同步 HTTP | 快速返回 |
| Java → AI（评论级批量分析） | **异步任务**：提交 + 轮询 | 批量 20–300 条，LLM 耗时分钟级 |
| Java → AI（单条/洞察生成） | 同步 HTTP（超时 60s） | 单次调用可接受 |
| Java → AI（报告渲染） | 同步 HTTP（超时 120s） | 生成 PDF/XLSX 较慢但可等 |
| Java → CoolSkyAI | 同步 HTTP（超时 10s） | 只读产品/订单 |

不引入 Kafka/RabbitMQ。异步用「提交任务 → 返回 job_id → 轮询状态」，状态持久化在 Java 侧任务表。

## 3. Collection Service 契约

### 3.1 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 存活 + Chrome 可用性 |
| GET | `/v1/capabilities` | 各平台能力状态（供 `ds_source.capability_json` 同步） |
| POST | `/v1/jobs` | 创建采集作业 |
| GET | `/v1/jobs/{jobId}` | 作业状态与进度 |
| POST | `/v1/jobs/{jobId}/cancel` | 取消 |
| GET | `/v1/jobs/{jobId}/result` | 拉取结果（分页游标） |

### 3.2 请求/响应 Schema

```json
// POST /v1/jobs
{
  "jobId": "col-item-1024",          // Java 侧生成，用作幂等键
  "sourceCode": "DOUYIN",
  "contentType": "VIDEO",
  "externalContentId": "7703721523349239951",
  "maxItems": 300,
  "useLogin": true,
  "traceId": "a1b2c3d4"
}
// 202 Accepted
{ "jobId": "col-item-1024", "status": "ACCEPTED" }

// GET /v1/jobs/{jobId}
{ "jobId": "...", "status": "RUNNING", "collected": 87, "total": 300,
  "errorCode": null, "startedAt": "...", "updatedAt": "..." }

// GET /v1/jobs/{jobId}/result?cursor=0&size=100
{
  "items": [{
    "externalCommentId": "7312...", "parentExternalId": null,
    "text": "...", "nickname": "...", "likes": 3,
    "ipRegion": "辽宁", "publishedAt": "2026-11-03T09:20:00+08:00",
    "dedupStrategy": "CID"
  }],
  "nextCursor": 100, "done": false
}
```

`status`：`ACCEPTED / RUNNING / SUCCEEDED / PARTIAL / FAILED / CANCELLED`

## 4. AI Service 契约

### 4.1 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 存活 + 模型可达性 |
| POST | `/v1/analyze/comments` | 评论级分析（同步，批量 ≤50 条/次） |
| POST | `/v1/analyze/insight` | 视频级洞察（同步） |
| POST | `/v1/render/report` | 报告渲染（同步） |
| POST | `/v1/score/lead` | 线索五维评分（同步，P1） |
| POST | `/v1/embeddings` | 向量化（P2，未实现） |

### 4.2 Schema

```json
// POST /v1/analyze/comments
{
  "traceId": "a1b2c3d4",
  "profile": { "code": "java-ai", "version": 3,
               "taxonomyVersion": "v1", "promptVersion": "p7",
               "model": "deepseek-chat" },
  "items": [ { "commentId": 101, "text": "想学 RAG 但不知道从哪开始" } ]
}
// 200
{
  "results": [{
    "commentId": 101,
    "demandCategory": "TECH_LEARNING",
    "technologyTags": ["RAG"],
    "intentType": "course_inquiry",
    "buyingStage": "AWARENESS",
    "intentScore": 62,
    "confidence": 0.83,
    "evidence": [{ "text": "想学 RAG", "reason": "明确表达学习需求" }],
    "model": "deepseek-chat",
    "promptVersion": "p7"
  }],
  "usage": { "promptTokens": 820, "completionTokens": 310, "cost": 0.0042 }
}
```

约束：

- 输出必须可被 Java DTO 反序列化；未知字段忽略，**缺失必填字段视为该条失败**
- 无法判断时返回 `demandCategory=UNKNOWN` 且 `confidence < 0.5`，**不得编造分类**
- 每条结果必须带 `model` 与 `promptVersion`，用于追溯（对应 `an_result` 的快照列）

## 5. 超时与重试

| 调用 | 连接超时 | 读超时 | 重试 | 说明 |
|---|---|---|---|---|
| Collection `/health`、`/capabilities` | 2s | 5s | 1 次 | 失败即视为不可用，不重试 |
| Collection `POST /v1/jobs` | 3s | 10s | 2 次（指数退避） | `jobId` 幂等，可安全重试 |
| Collection 结果轮询 | 3s | 10s | 不重试（下一轮询） | 间隔 5s，最长 30 分钟 |
| AI 评论分析 | 3s | 90s | 1 次 | 仅对 5xx / 超时重试 |
| AI 洞察/评分 | 3s | 60s | 1 次 | 同上 |
| AI 报告渲染 | 5s | 120s | 0 次 | 渲染失败由用户重试，避免重复生成 |
| CoolSkyAI | 2s | 10s | 2 次 | 只读接口 |

**重试原则**：只对幂等操作重试；4xx 不重试；重试必须带同一个 `jobId`/幂等键。

## 6. 幂等

| 场景 | 幂等键 | 落点 |
|---|---|---|
| 采集作业 | `col_task_item.id`（作为 jobId） | Collection 侧按 jobId 去重 |
| 评论入库 | `(source_id, external_comment_id)` + `dedup_key` | `ct_comment` 唯一键 |
| 分析结果 | `(comment_id, profile_id)` | `an_result` 唯一键 |
| 需求信号 | `(result_id, signal_type)` | `ld_signal` 唯一键 |
| 线索 | `lead_code`（可由 author+category 派生） | `ld_lead` 唯一键 |

## 7. Trace 与日志

- Java 生成 `traceId`（UUID 前 8 位），经请求头 `X-Trace-Id` 传给 Python
- Python 日志每行携带 `traceId` 与 `jobId`
- Java 侧把 `jobId` 写入 `col_task_log` / `an_usage`，实现跨服务对齐
- 报错时前端返回的 `traceId` 可直接用于检索两端日志

## 8. 认证

- Python 服务不对外暴露端口，仅在 Docker 网络内可达
- Java → Python 携带 `X-Internal-Token: ${FLASK_INTERNAL_TOKEN}`，Python 侧校验
- 不引入 OAuth/mTLS（首期单机，成本不匹配）

## 9. 健康检查与降级

| 服务 | 健康判定 | 不可用时 Java 行为 |
|---|---|---|
| Collection `/health` | 进程存活 + Chrome 可启动 | 禁止创建采集任务，前端提示"采集服务不可用" |
| AI `/health` | 进程存活 + 模型配置存在 | 允许提交任务但标记 `PENDING`，恢复后补跑；报告渲染直接报错 |
| 未配置 `DEEPSEEK_API_KEY` | `/health` 返回 `degraded` | AI 分析降级为传统统计（沿用现有行为） |

## 10. 与 CURRENT 的差异

| 现状 | 目标 | 迁移动作 |
|---|---|---|
| 子进程 `python comment.py <id>` | HTTP 作业接口 | R3 给 collection-service 加 FastAPI/Flask 包装 |
| Flask 6 个路由，路径无版本 | `/v1/...` 7 个路由 | R4 调整路由并统一响应包装 |
| Python 直写 MySQL（`clean.py`） | 只返回结果，Java 落库 | R3 取消直写，改由 `ContentIngestService` |
| 无 traceId | `X-Trace-Id` 贯穿 | R5 |
| 无重试/超时规范 | 见 §5 | R5 在 integration 客户端实现 |
