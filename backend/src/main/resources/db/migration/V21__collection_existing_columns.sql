-- V21 · 现有表加列（只加列，不改名、不加外键）
-- item 的 run_id/execution_id 外键在 V22 回填之后创建（裁决 §3：先回填再建约束）。

ALTER TABLE `collection_task`
  ADD COLUMN `source_id`           BIGINT UNSIGNED NULL AFTER `task_type`,
  ADD COLUMN `execution_mode`      VARCHAR(16) NOT NULL DEFAULT 'MANUAL' AFTER `status`,
  ADD COLUMN `schedule_expression` VARCHAR(64) NULL AFTER `execution_mode`,
  ADD COLUMN `next_run_at`         DATETIME    NULL AFTER `schedule_expression`,
  ADD KEY `idx_task_source` (`source_id`);

ALTER TABLE `collection_task_item`
  ADD COLUMN `run_id`       BIGINT UNSIGNED NULL AFTER `task_id`,
  ADD COLUMN `execution_id` BIGINT UNSIGNED NULL AFTER `run_id`,
  ADD KEY `idx_item_run` (`run_id`);

-- idx_log_run：现有索引为 idx_task_created(task_id,created_at) 与 idx_item(task_item_id)，
-- 与 run_id 无重叠，不存在重复索引。列顺序按实际查询（按 run 查日志、按时间倒序）定为 (run_id, id)。
ALTER TABLE `collection_task_log`
  ADD COLUMN `run_id`       BIGINT UNSIGNED NULL AFTER `task_id`,
  ADD COLUMN `execution_id` BIGINT UNSIGNED NULL AFTER `run_id`,
  ADD KEY `idx_log_run` (`run_id`, `id`);

ALTER TABLE `ds_source`
  ADD COLUMN `last_checked_at`   DATETIME    NULL,
  ADD COLUMN `last_check_result` VARCHAR(32) NULL;
