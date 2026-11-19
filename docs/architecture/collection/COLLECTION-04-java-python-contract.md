# COLLECTION-04 · Java ↔ Python 通信契约

版本：v2　现状：`CollectionClient` 接口已存在，Python 端 stub 服务已可用（9 条测试）


## 裁决记录（DECISION-02，2026-11-16）

| # | 事项 | 裁决 |
|---|---|---|
| 1 | `task_item` 唯一键 | 保留 `UK(task_id,video_id)` + `UK(video_id,active_flag)`，**不加 run_id**；唯一性跨 Run 生效 |
| 2 | Task↔Source | 首期一任务一源，保留 `collection_task.source_id`，**不建 `col_task_target`**；跨平台编排留待未来上层 Collection Plan |
| 3 | `failure_type` | 七类固定：AUTHENTICATION / AUTHORIZATION / RATE_LIMIT / NETWORK / PLATFORM / DATA / SYSTEM；细节走 `error_code`；用户取消不算 failure |
| 4 | checkpoint | 不加字段；`last_cid/page_no/cursor_value` 保留；首期仅 CID_SET；修复 Java `saveCheckpoint` 写 `last_cid`/`page_no` |

**实测修正**：`collection_task_item` 真实唯一键为 `uk_task_video(task_id,video_id)` +
`uk_video_active(video_id,active_flag)`；历史 4 个任务为 2 FAILED + 2 SUCCESS，**无 PENDING**。


## 1. 通信方式

首期：**Java 同步提交 + 轮询/回调上报**，HTTP JSON。

```
Java ──POST /internal/v1/executions──► Python（立即返回 202）
Java ──GET  /internal/v1/executions/{id}──► 轮询状态
Python ──POST /internal/v1/events──► Java（批量事件）
Python ──POST /internal/v1/results──► Java（分批结果）
```

不上消息队列（裁决未要求，现有基础设施无 MQ）。

## 2. 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal/v1/health` | 存活 + 适配器清单 |
| GET | `/internal/v1/capabilities` | 各平台能力（含 status/verifiedAt） |
| POST | `/internal/v1/executions` | 提交执行，返回 202 + executionId |
| GET | `/internal/v1/executions/{id}` | 状态与统计 |
| POST | `/internal/v1/executions/{id}/cancel` | 取消（协作式，Python 置标记） |
| POST | `/internal/v1/events` | 批量事件（幂等，按 eventId） |
| POST | `/internal/v1/results` | 批量内容（幂等，按 externalId + payloadHash） |

与现有实现的差异：`server.py` 现用 `/v1/jobs`，需改名为 `/internal/v1/executions`——**属 Batch D 代码工作，本设计不动**。

## 3. 请求 DTO

```json
{
  "requestId": "uuid",
  "executionId": 12345,
  "taskId": 1001,
  "runId": 2001,
  "sourceCode": "DOUYIN",
  "adapterCode": "douyin",
  "authMode": "PUBLIC",
  "credentialRef": null,
  "capability": "FETCH_COMMENTS",
  "target": {"externalContentId": "7703721523349239951", "maxItems": 300},
  "policySnapshot": {"schemaVersion": 1, "rules": {"operator": "AND", "conditions": []}},
  "checkpoint": {"type": "CID_SET", "lastCid": "7703943264597279522", "persistedCount": 180},
  "traceId": "abc123"
}
```

要点：

- `credentialRef` 为**引用**，不是凭证本身。PUBLIC 模式下为 `null`。
- `policySnapshot` 是**已展开的快照**（Java 从 `ds_policy.policy_json` 取出并附上 version），Python 不查库。
- `checkpoint.type` 首期只接受 `CID_SET`；`API_CURSOR` 传了应拒绝（避免误以为支持）。

## 4. 响应/上报 DTO

```json
{
  "requestId": "uuid",
  "executionId": 12345,
  "status": "PARTIAL",
  "stats": {"requestCount": 12, "fetchedCount": 300, "resultCount": 285, "duplicateCount": 15, "failedCount": 0},
  "failureType": null,
  "checkpoint": {"type": "CID_SET", "lastCid": "...", "persistedCount": 285},
  "adapterVersion": "douyin-1.4.0",
  "traceId": "abc123"
}
```

## 5. 幂等与一致性

| 风险 | 处理 |
|---|---|
| 重复派发 | `col_execution.idem_key`（`taskId:runNo:source:account:capability`）唯一键 |
| 回调重复 | results 按 `(source, externalId, payloadHash)` 幂等——`ct_raw_archive` 已有该唯一键 |
| 超时 | 不靠 HTTP 长连接，靠 execution 状态轮询 + `lease_until` |
| 崩溃 | Java 启动扫描 `RUNNING` 且 `lease_until < now()` → `FAILED(INTERRUPTED)` |
| 状态权威 | **只有 Java 写状态**；Python 上报的状态是"建议"，Java 按 05 的状态机校验后写入 |
| 取消 | Java 置 `CANCEL_REQUESTED`，Python 在下一次上报中确认 `CANCELLED`；超时未确认则 Java 强置 |

## 6. CID 集合续采流程（裁决 §10）

```
1 Java 读 checkpoint.last_cid（现为空，需补写）
2 提交 execution，checkpoint={type:CID_SET, lastCid, persistedCount}
3 Python: load_existing_cids(csv) → 已有集合 fromCsv
         载入 CSV 后与 lastCid 之前的记录比对，跳过已存
4 滚动采集，每页 save_progress(page, count, lastCid)
5 每批上报 results；Java 按 dedup_key 二次去重（cid 或指纹）
6 结束时上报 checkpoint.lastCid
7 Java 写回 collection_checkpoint.last_cid / persisted_count
```

**边界**：无 cid 的评论不参与 lastCid 推进，只靠 `dedup_key` 去重；
因此"续采"对这类数据是**全量重扫 + 去重**，不是精确续点。这一点必须在 UI 上说明，不能宣称"精确断点"。

## 7. 认证与可观测

- 服务间：`X-Internal-Token`（`server.py` 已支持，未配置则放行）。
- Trace：Java 生成的 `X-Trace-Id` 透传到 Python，Python 上报时原样带回；两端日志可按同一 id 检索。
- 适配器版本：`adapterVersion` 每次执行落库，便于"换版本后数据变了"的问题定位。
