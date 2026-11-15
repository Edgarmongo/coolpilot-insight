# 临时库演练报告

日期：2026-11-16 02:15　库：`cpi_drill` / `cpi_drill2`（与目标库隔离）
**目标库未执行任何 DDL**（flyway 仍为 18，无新表）

## 1. 演练方式

`mysqldump` 目标库 → 导入临时库 → 依次应用 V19~V23 → 校验。
V22 连续执行两次验证幂等。

## 2. 结构变化

| 项 | 迁移前 | 迁移后 |
|---|---:|---:|
| 表数 | 43 | 48（+4 新表 +1 快照表） |
| `ds_keyword.group_id` | 无 | 有 + FK `fk_kw_group` |
| `collection_task` | 无 source_id 等 4 列 | 已加 + `idx_task_source` |
| `collection_task_item` | 无双键列 | 加 run_id/execution_id + FK + `idx_item_run` |
| `collection_task_log` | 无 | 加 run_id/execution_id + `idx_log_run(run_id,id)` |

## 3. 数据变化

| 项 | 前 | 后 |
|---|---:|---:|
| `col_run` | — | 4 |
| item 关联 run | — | 5/5（0 条 NULL） |
| `source_id` 为空 | — | 0（4 条均由 item→video.platform 证据判定为 douyin） |
| Task 状态 | FAILED×2 / SUCCESS×2 | IDLE×4 |
| 状态快照 | — | 4 行（FAILED×2 / SUCCESS×2） |

## 4. 约束验证

| 测试 | 期望 | 实测 |
|---|---|---|
| AUTHENTICATED 无账号 | 拒绝 | ✅ `ck_exec_auth_account` 违反 |
| PUBLIC 带账号 | 拒绝 | ✅ 同上 |
| 合法组合 | 通过 | ✅ |
| 同视频重复活动 item | 拒绝 | ✅ `uk_task_video` 冲突 |
| 不存在的 run_id | 拒绝 | ✅ `fk_item_run` 违反 |

## 5. 中断与恢复

- **发现的问题**：初版 V22 把回填与 `ADD CONSTRAINT` 混在一起，重跑时因"外键已存在"失败 → 迁移中断后无法重试。
- **修复**：拆为 V22（纯 DML，幂等）+ V23（纯 DDL）。重跑验证：V22 连跑两次均 OK，runs 仍为 4。
- 状态回滚：按快照恢复后为 FAILED×2 / SUCCESS×2，与迁移前一致。

## 6. 测试范围说明

本阶段未修改任何 Java/Python 代码，应用层测试仍为 109（Java）+ 9（Python），与迁移前一致，无需重跑。
`MapperCrudSemanticsTest` 中 `active_flag` 置 NULL 语义未受影响（唯一键未改动）。

## 7. 遗留

1. `execution_id` 全为 NULL —— 历史数据无"执行单元"证据，按裁决不伪造关系。
2. 回滚脚本未覆盖"删除快照表"，故意保留为回滚凭证。
3. 回填后 `collection_task.started_at/finished_at` 语义变为"最近一次 run"，API 文档需同步（未改代码）。
