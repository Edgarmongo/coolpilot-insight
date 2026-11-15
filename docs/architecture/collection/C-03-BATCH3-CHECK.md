# C-03 · Batch 3 提交前检查报告

日期：2026-11-16 03:12　测试：120/120（对应当前工作区代码）

## 1. 检查结果

| # | 检查项 | 结论 |
|---|---|---|
| 1 | git status | 3 项改动：`CollectServiceImpl`、`ColRunMapper`、新增 `RunClaimTest` |
| 2 | `cancel()` 状态转换 | ⚠️ **存在决策冲突**（见 §2） |
| 3 | `runTask()` 是否还写 Task 终态 | ✅ 已移除；只回写 `success_items/failed_items`，返回计数 |
| 4 | `runService.finish()` | ✅ 终态校验、Task 回落 IDLE/PAUSED、异常路径有兜底 |
| 5 | Run 永久 RUNNING 路径 | ❌ 原存在 → ✅ **本次修复**（§3） |
| 6 | 测试与工作区一致 | ✅ 120/120 在含 sweep 的代码上跑通 |
| 7 | PAUSED 在活动 Run 计数中的定义 | ✅ `countActiveByTask` 统计 PENDING/RUNNING/PAUSED，与 C-01 一致 |

## 2. 发现一个决策冲突（未在本批修改）

`cancel()` 会把 Task 置为 `CANCEL_REQUESTED`：

```java
} else if ("RUNNING".equals(task.getStatus())) {
    up.setStatus("CANCEL_REQUESTED");     // ← 不在 C-01 允许的四态内
```

而 C-01 决策明确 Task 只有 `IDLE/RUNNING/PAUSED/DISABLED`。

**为什么没在本次改**：取消语义整体要从"改 Task 状态"迁移到"作用于 Run"，
属于 Batch 4（Execution 生命周期）范围。现在半改会同时留下两套取消检查。

**影响**：`runTask` 仍读 `task.status='CANCEL_REQUESTED'` 判断取消，功能可用但违反决策。

**另有两处随之而来的语义问题**：

1. Task 在 IDLE 时调用 cancel → 返回 409「任务已结束」，实际应为「当前没有进行中的运行」
2. `retry()` 要求 `task.status ∈ {FAILED, PARTIAL_SUCCESS}`，迁移后这些值不存在 → **重试永远 409，属死路径**

## 3. 本次修复：僵尸 Run 清扫

原设计缺口：worker 崩溃或进程重启后，Run 停在 `RUNNING`，
而 `claimIdle` 要求 Task 为 `IDLE` → **该任务再也无法触发**。

新增 `sweepStaleRuns()`（启动 20s 后 + 每 60s）：

```sql
SELECT * FROM col_run WHERE status='RUNNING'
 AND started_at < DATE_SUB(NOW(), INTERVAL 30 MINUTE);
-- 命中则置 FAILED(error_summary='worker interrupted or timeout') 并释放 Task → IDLE
```

阈值 30 分钟的依据：单次采集超时上限 10 分钟（`ProcessRunner`），留 3 倍余量避免误杀。

## 4. 提交结论

dispatch 改动本身**无阻塞问题**，可提交。
`cancel`/`retry` 的语义问题**是既有问题、非本批引入**，列入 Batch 4 必修项。
