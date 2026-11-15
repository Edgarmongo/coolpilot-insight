# COLLECTION-02 · 数据库设计

版本：v2（按 DECISION-01）　**本文件仅为设计，未执行任何 DDL**


## 裁决记录（DECISION-02，2026-11-16）

| # | 事项 | 裁决 |
|---|---|---|
| 1 | `task_item` 唯一键 | 保留 `UK(task_id,video_id)` + `UK(video_id,active_flag)`，**不加 run_id**；唯一性跨 Run 生效 |
| 2 | Task↔Source | 首期一任务一源，保留 `collection_task.source_id`，**不建 `col_task_target`**；跨平台编排留待未来上层 Collection Plan |
| 3 | `failure_type` | 七类固定：AUTHENTICATION / AUTHORIZATION / RATE_LIMIT / NETWORK / PLATFORM / DATA / SYSTEM；细节走 `error_code`；用户取消不算 failure |
| 4 | checkpoint | 不加字段；`last_cid/page_no/cursor_value` 保留；首期仅 CID_SET；修复 Java `saveCheckpoint` 写 `last_cid`/`page_no` |

**实测修正**：`collection_task_item` 真实唯一键为 `uk_task_video(task_id,video_id)` +
`uk_video_active(video_id,active_flag)`；历史 4 个任务为 2 FAILED + 2 SUCCESS，**无 PENDING**。


## 1. 新增表（4 张，裁决 §6）

### 1.1 `ds_keyword_group`

| 列 | 类型 | 约束 |
|---|---|---|
| id | BIGINT UNSIGNED | PK |
| source_id | BIGINT UNSIGNED | FK → ds_source |
| group_code | VARCHAR(64) | UK(source_id, group_code) |
| group_name | VARCHAR(128) | NOT NULL |
| description | VARCHAR(255) | NULL |
| status | VARCHAR(16) | ENABLED/DISABLED |
| created_at / updated_at | DATETIME | 自动 |

### 1.2 `ds_policy`（策略版本，发布后不可改）

| 列 | 类型 | 约束 |
|---|---|---|
| id | BIGINT UNSIGNED | PK |
| group_id | BIGINT UNSIGNED | FK → ds_keyword_group |
| version | INT | UK(group_id, version) |
| policy_json | JSON | 结构化表达式（见 §4 schema） |
| status | VARCHAR(16) | DRAFT / PUBLISHED / ARCHIVED |
| change_note | VARCHAR(255) | 变更说明 |
| published_at / published_by | DATETIME / BIGINT | 发布留痕 |
| created_by / created_at | BIGINT / DATETIME | — |

**不可变规则**：`status='PUBLISHED'` 后禁止 UPDATE `policy_json`（应用层拦截 + 触发器可选）。
修改 = 新建 `version+1` 的 DRAFT。

### 1.3 `col_run`（一次触发）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT UNSIGNED | PK |
| task_id | BIGINT UNSIGNED | FK → collection_task |
| run_no | INT | UK(task_id, run_no) |
| trigger_type | VARCHAR(16) | MANUAL / SCHEDULED |
| status | VARCHAR(24) | **PENDING 只在这里**：PENDING/RUNNING/COMPLETED/PARTIAL_SUCCESS/FAILED/PAUSED/CANCELLED |
| config_snapshot | JSON | 触发时的任务配置快照 |
| total_execution_count / success_count / failed_count | INT | 汇总 |
| started_at / finished_at | DATETIME | — |
| error_summary | VARCHAR(512) | NULL |
| created_at | DATETIME | — |

### 1.4 `col_execution`（run 内的执行单元）

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT UNSIGNED | PK |
| run_id | BIGINT UNSIGNED | FK → col_run |
| source_id | BIGINT UNSIGNED | FK → ds_source |
| account_id | BIGINT UNSIGNED | **PUBLIC 时必须 NULL**，FK → ds_account |
| auth_mode | VARCHAR(16) | PUBLIC / AUTHENTICATED |
| policy_id | BIGINT UNSIGNED | FK → ds_policy（绑定具体版本） |
| capability | VARCHAR(48) | SEARCH_CONTENT / FETCH_COMMENTS / … |
| status | VARCHAR(24) | 见 COLLECTION-05 |
| attempt_no | INT | 当前尝试次数 |
| request_count / fetched_count / inserted_count / duplicate_count / failed_count | INT | 统计 |
| failure_type | VARCHAR(32) | 见 §3 |
| error_code / error_message | VARCHAR / TEXT | — |
| adapter_version | VARCHAR(32) | 执行时适配器版本 |
| started_at / finished_at | DATETIME | — |
| idem_key | VARCHAR(96) | UK，幂等派发键 |

## 2. 现有表改造（仅加列，不改名）

| 表 | 新增列 | 理由 |
|---|---|---|
| `ds_source` | `last_checked_at DATETIME`、`last_check_result VARCHAR(32)` | 裁决 §3：健康检查降级为字段 |
| `ds_keyword` | `group_id BIGINT UNSIGNED NULL` | 裁决 §8：分组关联；旧数据可为 NULL（不阻断） |
| `collection_task` | `source_id BIGINT UNSIGNED NULL`、`execution_mode VARCHAR(16)`、`schedule_expression VARCHAR(64)`、`next_run_at DATETIME` | 事实 2：无 source_id 则 9 条创建校验无法落地 |
| `collection_task_item` | `run_id BIGINT UNSIGNED NULL`、`execution_id BIGINT UNSIGNED NULL` | 裁决 §9 层级；NULL 兼容历史 4 行数据 |
| `collection_task_log` | `run_id BIGINT NULL`、`execution_id BIGINT NULL` | 让日志能按 run/execution 检索 |

**不改的**：`ds_account`（不造虚拟账号、不加 auth_mode）、`collection_checkpoint`（已有 `last_cid/page_no/cursor_value`，够用，见 §3）。

## 3. 断点续采（裁决 §10）

现有 `collection_checkpoint` 列：`task_item_id, cursor_value, page_no, last_cid, persisted_count, last_success_at`。

**结论：表结构已够，缺的是 Java 没写 `last_cid`。**

| 项 | 现状 | 首期方案 |
|---|---|---|
| 类型 | 无 `checkpoint_type` 列 | **不加列**，用 `last_cid` 是否为空推断 CID_SET |
| CID 集合 | Python `save_progress(lastCid)` 已产出 | Java 在 `saveCheckpoint()` 补写 `last_cid` + `page_no` |
| DOM 滚动 | `scroll_comment_panel` | 作为执行手段，不单独记断点 |
| API_CURSOR | 未实现 | **不宣称支持**；`cursor_value` 列保留但不写入 |

无 CID 的评论：由 `dedup_key`（`sourceId|fp|指纹`）去重，与断点正交——**已在 `ContentIngestTest` 中验证**。

## 4. 策略 JSON Schema v1

```json
{
  "schemaVersion": 1,
  "schemaVersionNote": "v1 仅支持一层 conditions，不支持嵌套表达式",
  "rules": {
    "operator": "AND",
    "conditions": [
      {"type": "OR",  "values": ["Java RAG", "Java Agent"]},
      {"type": "EXACT","values": ["LangChain4j"]},
      {"type": "NOT", "values": ["招聘", "兼职"]}
    ]
  },
  "platformOverrides": {"douyin": {"searchMode": "LOCAL_FILTER"}}
}
```

约束：
- `schemaVersion` 必填，解析失败即拒绝发布。
- 不支持嵌套（`GROUP`），需要时升 v2——**避免表达式引擎变成脚本执行**。
- 平台不支持的条件 → `platformOverrides.searchMode = LOCAL_FILTER`，由采集侧本地过滤，不伪装成平台原生能力。

## 5. 索引与唯一约束变化

| 表 | 新增约束 |
|---|---|
| `ds_keyword_group` | `UK(source_id, group_code)` |
| `ds_policy` | `UK(group_id, version)`；`idx_policy_status(status)` |
| `col_run` | `UK(task_id, run_no)`；`idx_run_status(status, id)` |
| `col_execution` | `UK(idem_key)`；`idx_exec_run(run_id, id)`；`idx_exec_status(status)` |
| `collection_task` | `idx_task_source(source_id)` |
| `collection_task_item` | 新增 `idx_item_run(run_id)`；**现有 `UK(task_id, video_id, active_flag)` 是否加 run_id 待确认** |

## 6. Migration 计划（待批准后执行）

| 版本 | 内容 | 风险 |
|---|---|---|
| V19 | 建 `ds_keyword_group`、`ds_policy`；`ds_keyword` 加 `group_id` | 低（新表 + 可空列） |
| V20 | 建 `col_run`、`col_execution` | 低 |
| V21 | `collection_task` 加 4 列；`task_item` 加 2 列；`task_log` 加 2 列；`ds_source` 加 2 列 | **中**：item 唯一键需先验证 |
| V22（可选） | 历史数据回填：为 4 个既有任务补建 run/execution | 中 |

执行前必须：在独立临时库完整跑一遍。上次教训——**迁移前不要在目标库试跑后回滚**（自增不回滚，导致 V11 id 跳号）。

## 7. 待确认问题（阻塞 DDL）

1. `collection_task_item` 的 `UK(task_id, video_id, active_flag)` 加入 `run_id` 后是否仍能保证"同一视频同时只有一个活动采集"？两种方案需实测：
   - A：`UK(task_id, video_id, active_flag)` 不动，run_id 只作关联
   - B：改为 `UK(run_id, video_id, active_flag)`（允许同任务多 run 并行）
2. 一任务一源（加 `collection_task.source_id`）是否满足后续多平台需求；若否，需提前引入 `col_task_target`。
3. `col_execution.failure_type` 枚举取值需确认（见 05 §4）。
