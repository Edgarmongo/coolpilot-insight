# COLLECTION-01 · 数据采集模块架构

版本：v2（按 DECISION-01 裁决修订）　日期：2026-11-16
依据：真实源码与数据库核对（见 §5），非文档推断


## 裁决记录（DECISION-02，2026-11-16）

| # | 事项 | 裁决 |
|---|---|---|
| 1 | `task_item` 唯一键 | 保留 `UK(task_id,video_id)` + `UK(video_id,active_flag)`，**不加 run_id**；唯一性跨 Run 生效 |
| 2 | Task↔Source | 首期一任务一源，保留 `collection_task.source_id`，**不建 `col_task_target`**；跨平台编排留待未来上层 Collection Plan |
| 3 | `failure_type` | 七类固定：AUTHENTICATION / AUTHORIZATION / RATE_LIMIT / NETWORK / PLATFORM / DATA / SYSTEM；细节走 `error_code`；用户取消不算 failure |
| 4 | checkpoint | 不加字段；`last_cid/page_no/cursor_value` 保留；首期仅 CID_SET；修复 Java `saveCheckpoint` 写 `last_cid`/`page_no` |

**实测修正**：`collection_task_item` 真实唯一键为 `uk_task_video(task_id,video_id)` +
`uk_video_active(video_id,active_flag)`；历史 4 个任务为 2 FAILED + 2 SUCCESS，**无 PENDING**。


## 1. 模块边界

| 模块 | 负责 | 不负责 |
|---|---|---|
| `source` | 平台定义、能力声明、账号、关键词分组与策略 | 任务执行 |
| `collection` | Task / Run / Execution / Item、调度、断点、日志 | 内容标准化 |
| `content` | 原始归档、标准化内容、评论、作者、去重 | 采集执行 |
| `integration` | 与 Python 采集服务的通信契约 | 业务状态 |

`analysis` 消费 content，不参与采集。

## 2. 层级模型（裁决 §9）

```
collection_task           长期任务定义（不含单次执行结果）
  └── col_run             一次手动/定时触发
        └── col_execution 一次 run 内的执行单元（平台+账号+策略版本）
              └── collection_task_item   一个具体内容对象（一个视频）
```

语义要点：

- **Task 不被 Run 结果覆盖**：任务状态是聚合态，单次结果写在 Run。
- **Item 是内容粒度**，不是执行单元。`collection_task_item` 现有列（`video_id/status/active_flag/worker_id/retry_count/max_retry/requested_max/collected_count/skipped_count/heartbeat_at/lease_until/started_at/finished_at/error_code/error_message`）全部保留。
- 新增 `run_id`、`execution_id` 到 item：**需在 DDL 前确认现有唯一键 `UK(task_id, video_id, active_flag)` 是否受影响**（见 COLLECTION-02 §5 待确认项）。

## 3. 账号模式（裁决 §7）

```
PUBLIC            → 不创建账号记录；execution.account_id 必须为 NULL
AUTHENTICATED     → 必须绑定 ds_account 中真实账号，且授权状态有效
```

`ds_account` **只存真实平台账号**，不因支持公开采集而造虚拟账号。
模式属于**任务/执行配置**，不属于账号属性——因此 `ds_account` 不需要 `auth_mode` 列。

## 4. Java / Python 职责

| 能力 | Java | Python |
|---|---|---|
| 任务定义、调度、状态机 | ✅ 权威 | — |
| 幂等、事务、审计 | ✅ | — |
| 平台适配（DOM 滚动、CID 提取） | — | ✅ |
| 业务数据入库 | ✅ `ContentIngestService` | ❌ 不接触数据库 |
| 状态最终写入权 | ✅ | 只上报事件与结果 |

现状一致：Python 写 CSV（`comment.py`），Java 读 CSV 入库（`clean.py` 由 Java 起子进程）。
改 HTTP 后**保持"Python 不碰数据库"这条边界不变**。

## 5. 本次核对证据（10 项）

| # | 核对项 | 结论摘要 | 证据 |
|---|---|---|---|
| 1 | `ds_source` 字段与索引 | 11 列；`UK(code)`；`capability_json` 为 JSON | information_schema |
| 2 | `ds_account` 字段与索引 | 12 列；`UK(source_id, external_account_id)`；0 行数据 | 同上 |
| 3 | `ds_keyword` 唯一键 | `UK(source_id, owner_user_id, normalized)` | 同上 |
| 4 | `collection_task` 字段 | 无 `source_id`、无 `run` 概念；`status` 被当作"最后一次结果" | 同上 + `CollectServiceImpl` |
| 5 | `task_item` 粒度 | 一个视频一条（`video_id` + `UK(task_id,video_id,active_flag)`） | 表结构 |
| 6 | `checkpoint` 读写 | 表有 `last_cid/page_no/cursor_value`，但 Java `saveCheckpoint()` **只写 `persisted_count`** | `CollectServiceImpl:360` |
| 7 | `task_log` 内容 | `event_type/event_level/message/details_json` | 表结构 |
| 8 | `ProcessRunner` / `CollectionClient` | 超时强杀 + 异常翻译；`collectComments/searchByKeyword` | `integration/*` |
| 9 | `ContentIngestService` | 内容按 `(source, external_id)` 幂等；评论按 `dedup_key`；原始归档按 hash | `content/application` |
| 10 | `comment.py` | `scroll_comment_panel()` DOM 滚动；`load_existing_cids()`；`save_progress(lastCid)` | 源码 85/99/138 行 |

**关键事实**：断点所需的 `last_cid` 在 Python 侧已产出、表里也有列，**唯独 Java 没有写进去**。这是"半成品"的准确位置。

## 6. 数据流

```
1 创建任务（校验源/能力/账号/策略）→ 存配置快照
2 触发（手动/定时）→ 建 Run
3 展开目标 → 建 Execution（平台+账号+策略快照）
4 派发 → Python 执行（带 checkpoint.lastCid）
5 Python 上报事件/结果 → Java 校验状态机 → 入库（Ingest）
6 更新 Execution/Item 统计 → 汇总 Run → 回写 Task 聚合态
```

## 7. ER 关系（逻辑）

```
ds_source 1─n ds_account
ds_source 1─n ds_keyword_group 1─n ds_keyword
ds_keyword_group 1─n ds_policy（版本，发布后不可改）
ds_source 1─n collection_task 1─n col_run 1─n col_execution 1─n collection_task_item
col_execution 1─1 collection_checkpoint（按 item 落，见 02 §3）
col_execution 1─n collection_task_log（复用现有表，加 execution_id）
col_execution n─1 ds_account（PUBLIC 时为 NULL）
col_execution n─1 ds_policy（绑定具体版本）
```

## 8. 待确认

1. item 加 `run_id/execution_id` 与现有唯一键 `UK(task_id, video_id, active_flag)` 的兼容性。
2. 多目标任务（一次 run 多平台）是否需要 `col_task_target`——裁决 §6 未列，首期按"一任务一源"设计。
