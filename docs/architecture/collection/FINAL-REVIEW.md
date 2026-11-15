# FINAL-REVIEW · V19~V24 迁移安全评审

日期：2026-11-16 02:20　基线提交：`4e1da6d`（本次在其上拆分 V23）
**目标库未执行任何 DDL**（flyway 仍为 18）

## 1. V23 部分执行恢复（发现问题 → 已修复）

### 修复前的隐患

原 V23 一个文件里加两个约束：

```sql
ADD CONSTRAINT fk_item_run, ADD CONSTRAINT fk_item_execution
```

MySQL 单条 ALTER 内部原子，但**Flyway 把一个文件当一个版本**：若第一条生效、第二条失败，该版本标记失败；`repair` 后重跑会因 `fk_item_run` 已存在而再次失败——**无法自动恢复**。

### 修复：一个约束一个文件

| 版本 | 内容 |
|---|---|
| V23 | `ADD CONSTRAINT fk_item_run` |
| V24 | `ADD CONSTRAINT fk_item_execution` |

失败的那条必然尚未生效，重跑即成功，无需手工 DROP。

### 演练（`cpi_drill3`）

| 步骤 | 结果 |
|---|---|
| 应用 V19~V23 | OK |
| 手工预置 `fk_item_execution` 模拟"已存在" | — |
| 重跑 V24 | ❌ `Duplicate foreign key constraint name`（符合预期） |
| 删除重复约束后重跑 | ✅ 成功 |
| 最终约束清单 | `fk_cti_task`、`fk_cti_video`、`fk_item_run`、`fk_item_execution`——**无重复** |

恢复步骤（写进运维手册）：`ALTER TABLE collection_task_item DROP FOREIGN KEY fk_item_execution;` → Flyway `repair` → 重跑。

## 2. 历史 4 条 Task 逐字段对照

| ID | 状态（前→后） | total | success | failed | started_at | finished_at | params_json 长度 | Run 状态 |
|---|---|---|---|---|---|---|---|---|
| 1 | FAILED → **IDLE** | 2→2 | 0→0 | 2→2 | 04:32:18 同 | 04:33:26 同 | 105→105 | **FAILED** |
| 2 | SUCCESS → **IDLE** | 1→1 | 1→1 | 0→0 | 04:37:34 同 | 04:38:10 同 | 82→82 | **COMPLETED** |
| 3 | SUCCESS → **IDLE** | 1→1 | 1→1 | 0→0 | 04:47:14 同 | 04:47:48 同 | 82→82 | **COMPLETED** |
| 4 | FAILED → **IDLE** | 1→1 | 0→0 | 1→1 | 06:16:48 同 | 06:17:19 同 | 81→81 | **FAILED** |

结论：**除 `status` 外全部字段逐字节不变**；原结果完整保存在 `col_run`（`config_snapshot` 长度与 `params_json` 逐一相等，证明快照拷贝正确）。

## 3. `execution_id` NULL 兼容性

| 检查 | 结果 |
|---|---|
| 代码引用 | `grep execution_id\|executionId` 在 backend/frontend **零命中** |
| 历史 Item 保持 NULL | ✅ V22 只回填 `run_id`，`execution_id` 不动 |
| 现有查询 | 均基于 `task_item`，不涉及该列，不受影响 |
| 新建 Item 关联 Execution | 列可写，FK 已建（V24），应用层后续实现 |
| Run 统计依赖 | `col_run` 统计由 `col_execution` 汇总，不依赖历史 Item |
| 前端展示 | 历史详情走 `task_item`，不读 `execution_id` |

**结论：完全兼容，无阻断。**

## 4. `started_at` / `finished_at` API 语义影响

| 项 | 现状 |
|---|---|
| 暴露位置 | `CollectionQueryService.taskRow()` → `startedAt`/`finishedAt` |
| 前端消费 | `TaskCenterView.vue` 第 35、58 行（"结束时间"列与详情） |
| 迁移后语义 | 变为"最近一次 run 的开始/结束"，历史值不变（上表已证） |

**风险**：新任务的 run 结束后，若代码不回写 `task.started_at/finished_at`，前端会显示旧值。

**建议（不修改代码，仅记录）**：
1. Run 结束时把 `col_run.started_at/finished_at` 同步回 `collection_task`（保持前端语义）
2. API 增加 `lastRunStartedAt`/`lastRunFinishedAt`（从 col_run 取），与 task 字段并存过渡
3. 前端详情页优先展示 Run 维度时间，task 字段仅作列表摘要

现有消费者只有 `TaskCenterView` 一处，改造面小。**无兼容风险**（字段名不变、历史值不变）。

## 5. 快照完整性

| 项 | 值 |
|---|---|
| 表 | `_bak_collection_task_status` |
| 记录数 | 4（= 任务数） |
| 创建时间 | 2026-11-16 02:16:08 |
| 对应版本 | V22 |
| 覆盖字段 | `old_status`、`old_started`、`old_finished` |

### 覆盖范围说明（诚实列出）

| 表 | 迁移是否改数据 | 是否需要快照 |
|---|---|---|
| `collection_task` | **是**（status、source_id） | ✅ 已快照 status/时间；source_id 原为 NULL，回滚即 DROP 列 |
| `collection_task_item` | 是（run_id） | ❌ 不需要：原值即 NULL，回滚 DROP 列即还原 |
| `collection_checkpoint` | **否** | ❌ 不涉及 |
| `ds_keyword` | 否（仅加列） | ❌ 不涉及 |

### 恢复 SQL

```sql
UPDATE collection_task t JOIN _bak_collection_task_status b ON b.task_id = t.id
SET t.status = b.old_status, t.started_at = b.old_started, t.finished_at = b.old_finished;
```

### 校验结果（drill 实测）

恢复前 `IDLE×4`；恢复后 `FAILED×2 / SUCCESS×2`，与快照值完全一致。

## 6. 结论

**A：所有检查通过，可以申请目标库迁移授权。**

- V23 拆分为 V23/V24，中断可恢复（已演练）
- 4 条任务逐字段核对，除 status 外无变化，原结果完整移入 Run
- `execution_id` 零引用，NULL 完全兼容
- 时间字段语义变化已定位到唯一消费者，无兼容风险
- 快照覆盖与恢复经实测验证

### 执行前仍需完成

1. 目标库 `pre-check.sql` 通过 + 完整备份
2. 在临时库按 V19→V24 完整跑一遍（已做，建议授权后再做一次留痕）
3. 执行后跑 `post-verify.sql`

> 提醒：执行顺序必须是 V19→V24 且**不可回滚的 DDL 用临时库演练**；
> 不要在目标库"试跑后回滚"（自增不回滚，V11 已因此 id 跳号）。
