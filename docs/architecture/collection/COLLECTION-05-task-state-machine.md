# COLLECTION-05 · 任务状态机

版本：v2（按 DECISION-01 §5、§9）


## 裁决记录（DECISION-02，2026-11-16）

| # | 事项 | 裁决 |
|---|---|---|
| 1 | `task_item` 唯一键 | 保留 `UK(task_id,video_id)` + `UK(video_id,active_flag)`，**不加 run_id**；唯一性跨 Run 生效 |
| 2 | Task↔Source | 首期一任务一源，保留 `collection_task.source_id`，**不建 `col_task_target`**；跨平台编排留待未来上层 Collection Plan |
| 3 | `failure_type` | 七类固定：AUTHENTICATION / AUTHORIZATION / RATE_LIMIT / NETWORK / PLATFORM / DATA / SYSTEM；细节走 `error_code`；用户取消不算 failure |
| 4 | checkpoint | 不加字段；`last_cid/page_no/cursor_value` 保留；首期仅 CID_SET；修复 Java `saveCheckpoint` 写 `last_cid`/`page_no` |

**实测修正**：`collection_task_item` 真实唯一键为 `uk_task_video(task_id,video_id)` +
`uk_video_active(video_id,active_flag)`；历史 4 个任务为 2 FAILED + 2 SUCCESS，**无 PENDING**。


## 1. 三层状态分离

| 层 | 状态存放 | 语义 |
|---|---|---|
| Task | `collection_task.status` | 长期定义态：**IDLE / RUNNING / PAUSED / DISABLED**，由 Run 汇总而来 |
| Run | `col_run.status` | 单次执行：**PENDING** / RUNNING / COMPLETED / PARTIAL_SUCCESS / FAILED / PAUSED / CANCELLED |
| Execution | `col_execution.status` | 单执行单元：PENDING / RUNNING / SUCCEEDED / PARTIAL / FAILED / CANCELLED / SKIPPED |
| Item | `collection_task_item.status` | 内容粒度：PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / CANCELLED（沿用现有） |

**裁决要点**：`PENDING` 只属于 Run（及其下层）。Task **不再有 PENDING**——历史 4 行任务的 `PENDING` 在回填时归入新建的 Run。

## 2. Task 状态机

```
        ┌──────── DISABLED ◄──── 用户停用
        ▼              ▲
      IDLE ──触发──► RUNNING ──run 结束且无失败──► IDLE
        ▲              │
        │              └──用户暂停──► PAUSED ──恢复──► IDLE
        └───────────────────────────────────────────┘
```

- 由 Run 汇总驱动，**不允许直接改**（除 DISABLED/PAUSED 两个用户动作）。
- 聚合规则：有 RUNNING 的 run → RUNNING；否则按"最近一次 run 的终态"回落到 IDLE/PAUSED。

## 3. Run 状态机

```
PENDING ──领取──► RUNNING ──┬──全部成功──► COMPLETED
                            ├──部分成功──► PARTIAL_SUCCESS
                            ├──全部失败──► FAILED
                            ├──用户取消──► CANCELLED
                            └──用户暂停──► PAUSED ──恢复──► RUNNING
```

非法转换（必须拒绝，返回 409）：

- `COMPLETED/PARTIAL_SUCCESS/FAILED/CANCELLED` → 任何状态（终态不可逆，只能新建 run）
- `PENDING` → `PAUSED`（还没开始，没有可暂停的）
- 任何状态 → `PENDING`

## 4. Execution 与失败分类

Execution 除状态外必须记 `failure_type`，否则无法区分"没采到"与"没需求"。建议取值：

| failure_type | 含义 | 是否可重试 |
|---|---|---|
| `CAPABILITY_UNSUPPORTED` | 平台不支持该能力 | ❌ |
| `AUTH_EXPIRED` | 账号授权过期/失效 | ❌（需重新授权） |
| `RATE_LIMITED` | 触发平台限流 | ✅（退避后） |
| `NOT_ACCESSIBLE` | 内容已删除或不可访问 | ❌ |
| `NETWORK_ERROR` | 网络/超时 | ✅ |
| `PARSE_ERROR` | 数据格式异常 | ✅（有限次） |
| `ADAPTER_ERROR` | 适配器异常 | ✅（有限次） |

现状对照：`collection_task_item` 只有 `error_code VARCHAR(64)` + `error_message TEXT`，**没有分类**。`failure_type` 只加在 `col_execution`，item 通过 `error_code` 前缀映射（如 `RATE_LIMITED:*`），避免再加一列。

## 5. 现有状态使用位置（核对结果）

| 位置 | 用法 |
|---|---|
| `CollectServiceImpl.submit` | 建任务 + item，状态 PENDING |
| `dispatch()` | 原子领取：`status=PENDING` → RUNNING，写 `worker_id/lease_until` |
| `markItem` | 终态 + `clearActiveFlag` 置 NULL |
| `cancel()` | 仅非终态可取消，否则 409（`CollectStateMachineTest` 4 条守护） |
| `retry()` | 仅 FAILED/PARTIAL_SUCCESS 可重试，否则 409 |
| `listTasks/getStatus` | 直接读 `task.status` 与 `item.status` |

**迁移影响**：`dispatch()` 的领取条件要从"task.status=PENDING"改为"run.status=PENDING"，`active_flag` 的释放语义不变。这是 V22 回填后必须一起改的代码点（本次不动代码）。

## 6. 定时调度

| 项 | 方案 |
|---|---|
| 触发 | 复用现有 `@Scheduled`：每分钟扫 `next_run_at <= now()` 的 ENABLED 任务 |
| 并发 | 同一 task 同时只允许 1 个 RUNNING run（`UK(task_id, run_no)` + 应用层检查） |
| 幂等 | 触发前写入 `run_no = max+1`，唯一键冲突即视为已触发 |
| 重复触发防护 | `next_run_at` 触发后立即推进 |
| 重启恢复 | 启动时把 `RUNNING` 且 `lease_until < now()` 的 run 标记为 `FAILED(INTERRUPTED)` |
| 时区 | 统一 Asia/Shanghai，cron 按此时区解释 |
