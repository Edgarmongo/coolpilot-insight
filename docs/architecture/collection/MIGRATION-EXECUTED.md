# 目标库迁移执行记录

执行时间：2026-11-16 02:34　授权：用户明确授权
备份：`deploy/backup/pre-collection-ddl-20261116_0233.sql`（142K）

## 1. 前置检查（执行前）

| 项 | 值 |
|---|---|
| 基线表数 | 43 |
| 任务 / item | 4 / 5 |
| Task 状态种类 | 2 |
| 新表已存在 | 0（符合预期） |
| flyway 版本 | 18 |
| 双唯一键 | `uk_task_video`、`uk_video_active` 均存在 |

## 2. 执行结果

Flyway V19→V24 全部 `success=1`。应用启动 healthy。

## 3. 迁移后验证（post-verify.sql）

| 检查 | 期望 | 实测 |
|---|---|---|
| 新表数 | 4 | **4** |
| col_run 行数 | ≥ 任务数 | **4** |
| item 未关联 run | 0 | **0** |
| source_id 为空 | 需人工确认 | **0**（全部有证据） |
| 非聚合态状态 | 0 | **0** |
| 状态快照行数 | = 任务数 | **4** |
| 约束 | 7 个 | **7 个全在**（`uk_group_code`、`uk_policy_version`、`uk_run_no`、`uk_exec_idem`、`ck_exec_auth_account`、`fk_item_run`、`fk_item_execution`） |

四条任务现状：全部 `IDLE`，各 1 个 run，item 5/5 已关联 run。

## 4. 回归验证

- Java 测试：**109/109 通过**
- 接口：`/api/health`、`/api/collect/tasks`、`/api/collect/tasks/4`、`/api/overview`、`/api/comment/list` 全部 200

## 5. 演练库清理

`cpi_drill`、`cpi_drill2`、`cpi_drill3`、`cpi_before` 已全部 DROP。

## 6. 已知遗留（不影响运行）

| # | 项 | 说明 |
|---|---|---|
| 1 | 前端未映射 `IDLE` | `TaskCenterView` 的 `statuses` 数组不含 IDLE，列表会直接显示英文 "IDLE"。属展示问题，不影响功能；建议前端补映射为"空闲" |
| 2 | `execution_id` 全 NULL | 历史数据无执行单元证据，按裁决不伪造 |
| 3 | Task 时间字段语义 | 现为"最近一次 run"，代码尚未回写；新采集前需补逻辑 |
| 4 | `dispatch()` 仍查 `task.status='PENDING'` | 迁移后无 PENDING 任务，调度不会触发；改造 Run 状态机时一并处理 |

## 7. 回滚

备份已就位。回滚脚本见 `sql/rollback.sql`（含快照恢复）。快照表 `_bak_collection_task_status`（4 行）保留至确认稳定后删除。
