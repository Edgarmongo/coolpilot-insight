# C-01 · Task / Run / Execution 状态机定稿

基线：`e1832df`　本文件为设计定稿，未改代码

## 1. 三层职责

| 层 | 表 | 状态含义 |
|---|---|---|
| Task | `collection_task.status` | **长期定义态**：IDLE / RUNNING / PAUSED / DISABLED |
| Run | `col_run.status` | **单次执行态**：PENDING / RUNNING / COMPLETED / PARTIAL_SUCCESS / FAILED / PAUSED / CANCELLED |
| Execution | `col_execution.status` | **执行单元态**：PENDING / RUNNING / SUCCEEDED / PARTIAL / FAILED / CANCELLED / SKIPPED |

`PENDING` 只存在于 Run 与 Execution，**Task 永不出现 PENDING**。

## 2. Task 状态转换

| 当前 | 事件 | 目标 | 触发方 |
|---|---|---|---|
| IDLE | 创建 Run 成功 | RUNNING | 系统（与 Run 同事务） |
| RUNNING | 最新 Run 进入终态 | IDLE | 系统 |
| RUNNING | 用户暂停 | PAUSED | 用户 |
| PAUSED | 用户恢复 | IDLE | 用户 |
| IDLE/PAUSED | 用户禁用 | DISABLED | 用户 |
| DISABLED | 用户启用 | IDLE | 用户 |
| DISABLED | 触发 Run | **拒绝**（409） | — |

**规则**：
- `RUNNING` 期间禁止写入 `DISABLED`（需先取消 run）。
- `PAUSED` 必须**阻断调度**：`trigger()` 与调度器都校验，不是只改状态字段。
- Task 状态**不得直接复制 Run 状态**：Run 的 `FAILED` 对应 Task 回到 `IDLE`（定义态未变）。

## 3. Run 状态转换

| 当前 | 事件 | 目标 | 非法？ |
|---|---|---|---|
| PENDING | 调度器原子领取 | RUNNING | |
| PENDING | 用户取消 | CANCELLED | |
| PENDING | 用户暂停 | — | ❌ 非法（未开始无可暂停） |
| RUNNING | 全部 execution 成功 | COMPLETED | |
| RUNNING | 部分成功 | PARTIAL_SUCCESS | |
| RUNNING | 全部失败 | FAILED | |
| RUNNING | 用户取消 | CANCELLED | |
| RUNNING | 用户暂停 | PAUSED | |
| RUNNING | worker 崩溃/租约超时 | FAILED（`failure_type=SYSTEM`） | |
| PAUSED | 用户恢复 | RUNNING | |
| 终态（COMPLETED/PARTIAL_SUCCESS/FAILED/CANCELLED） | 任何操作 | — | ❌ 终态不可逆，只能新建 Run |

## 4. Execution 状态转换

```
PENDING → RUNNING → SUCCEEDED | PARTIAL | FAILED | CANCELLED
PENDING → SKIPPED（能力不支持/账号失效，未实际执行就跳过）
```

- `CANCELLED` 仅由用户取消产生，**不计入 failure_type**（裁决 DECISION-02 §3）。
- `SKIPPED` 不计入 `failed_count`，但必须计 `skipped_count`（避免"没采到"被当成"没需求"）。

## 5. 汇总规则（Run ← Execution）

| Run 状态 | 条件 |
|---|---|
| COMPLETED | 全部 execution ∈ {SUCCEEDED, SKIPPED} 且至少 1 个 SUCCEEDED |
| PARTIAL_SUCCESS | 有 SUCCEEDED 且有 FAILED |
| FAILED | 全部 execution ∈ {FAILED} 或 全部 SKIPPED 且原因非能力缺失 |
| CANCELLED | 存在用户取消 |

统计字段：`total_execution_count / success_count / failed_count` 由 execution 实时累加，**不用前端计算**。

## 6. 并发领取设计（C-03 前置）

现有：`claimPending(id, startedAt)` 条件更新，affected==1 才执行——**这个模式可直接复用到 run**：

```sql
UPDATE col_run SET status='RUNNING', started_at=NOW()
 WHERE id=? AND status='PENDING';
```

必须同时满足：

| 要求 | 做法 |
|---|---|
| 原子领取 | 条件更新 affected==1 |
| 多 Worker 不重复 | DB 条件更新即天然互斥；`busy` 仅作单机优化 |
| Worker 异常退出 | `lease_until` 超时扫描 → 置 `FAILED(SYSTEM)` |
| 不永久 RUNNING | 启动时 + 每轮扫描 `RUNNING 且 lease_until < now()` |
| 记录时间 | `started_at`（领取时）、`finished_at`（终态时） |

**租约字段**：`col_run` 当前**没有** `lease_until`/`worker_id`。C-03 若要完整支持多 Worker，需要新增两列——这属于 Schema 变更，**按禁令必须单独提交评审**，不能在本批直接加。

首期折中（无需改表）：单实例 + `busy` 互斥 + 启动时把残留 `RUNNING` 置 FAILED。

## 7. 非法转换处理

统一 `BizException(STATE_CONFLICT)` → HTTP 409，message 说明当前状态。
**不允许**静默忽略：例如对已 COMPLETED 的 run 再 cancel，必须返回 409 而不是 200。

## 8. 与现有代码的冲突点

| 冲突 | 处理 |
|---|---|
| `runTask` 直接写 `task.status=SUCCESS/FAILED` | 改为写 `run.status`，Task 回落 IDLE |
| `dispatch` 查 `task.status='PENDING'` | 改为查 `col_run.status='PENDING'` |
| 取消检查在 item 循环里 | 下沉到 execution 边界，每 execution 前检查 |
| `finishWithError` 直接置 task FAILED | 改为置 run FAILED + task IDLE |

## 9. 待确认

1. 暂停语义：`PAUSED` 是否允许"当前 execution 跑完再停"（推荐）还是立即中断（需 Python 支持协作式取消）。
2. 是否允许同一 Task 并发多个 Run（当前 `uk_run_no` 允许，但需业务定夺并发上限）。
3. C-03 若需多 Worker，是否批准给 `col_run` 加 `worker_id`/`lease_until` 两列。
