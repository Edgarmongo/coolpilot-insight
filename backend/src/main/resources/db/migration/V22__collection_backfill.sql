-- =====================================================================
-- V22 · 历史回填 + item 外键（裁决 §3、§5）
--
-- 原则：
-- 1. 全部幂等（NOT EXISTS / IS NULL 守卫），可重复执行；
-- 2. source_id 依据真实证据（item→video.platform），无证据则留 NULL，不推断；
-- 3. Task 状态改为聚合态前，先把原值快照到 _bak_collection_task_status；
-- 4. 无法确认 Run/Execution 归属的 item 允许 NULL，不伪造关系。
-- =====================================================================

-- 0) 状态快照表：Task 状态语义变更的恢复材料（裁决"必须具备完整数据恢复材料"）
CREATE TABLE IF NOT EXISTS `_bak_collection_task_status` (
  `task_id`      BIGINT UNSIGNED NOT NULL,
  `old_status`   VARCHAR(24)     NOT NULL,
  `old_started`  DATETIME        NULL,
  `old_finished` DATETIME        NULL,
  `snapshot_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='迁移前 Task 状态快照（回滚用，迁移稳定后可删）';

INSERT INTO `_bak_collection_task_status` (`task_id`, `old_status`, `old_started`, `old_finished`)
SELECT t.`id`, t.`status`, t.`started_at`, t.`finished_at`
FROM `collection_task` t
WHERE NOT EXISTS (SELECT 1 FROM `_bak_collection_task_status` b WHERE b.`task_id` = t.`id`);

-- 1) 每个历史任务建 1 条 run（run_no=1），按终态映射
INSERT INTO `col_run` (`task_id`, `run_no`, `trigger_type`, `status`, `config_snapshot`,
                       `total_execution_count`, `success_count`, `failed_count`,
                       `started_at`, `finished_at`, `created_at`)
SELECT t.`id`, 1, 'MANUAL',
       CASE t.`status`
         WHEN 'SUCCESS' THEN 'COMPLETED'
         WHEN 'FAILED'  THEN 'FAILED'
         WHEN 'PARTIAL_SUCCESS' THEN 'PARTIAL_SUCCESS'
         WHEN 'CANCELLED' THEN 'CANCELLED'
         ELSE 'FAILED'                    -- 无法判定一律记 FAILED，不假装成功
       END,
       t.`params_json`,
       (SELECT COUNT(*) FROM `collection_task_item` i WHERE i.`task_id` = t.`id`),
       COALESCE(t.`success_items`, 0), COALESCE(t.`failed_items`, 0),
       t.`started_at`, t.`finished_at`, t.`created_at`
FROM `collection_task` t
WHERE NOT EXISTS (SELECT 1 FROM `col_run` r WHERE r.`task_id` = t.`id` AND r.`run_no` = 1);

-- 2) 回填 source_id：只依据真实证据（item 关联的视频所属平台），无证据保持 NULL
UPDATE `collection_task` t
JOIN (
  SELECT i.`task_id`, MIN(v.`platform`) AS platform, COUNT(DISTINCT v.`platform`) AS platforms
  FROM `collection_task_item` i
  JOIN `video` v ON v.`id` = i.`video_id`
  WHERE v.`platform` IS NOT NULL AND v.`platform` <> ''
  GROUP BY i.`task_id`
  HAVING COUNT(DISTINCT v.`platform`) = 1          -- 仅单一平台可判定
) ev ON ev.`task_id` = t.`id`
JOIN `ds_source` ds ON ds.`code` = UPPER(ev.`platform`) COLLATE utf8mb4_unicode_ci
SET t.`source_id` = ds.`id`
WHERE t.`source_id` IS NULL;

-- 3) item 关联到回填的 run（execution 无证据，保持 NULL，不伪造）
UPDATE `collection_task_item` i
JOIN `col_run` r ON r.`task_id` = i.`task_id` AND r.`run_no` = 1
SET i.`run_id` = r.`id`
WHERE i.`run_id` IS NULL;

-- 4) Task 状态收敛为聚合态（原值已在第 0 步快照）
UPDATE `collection_task`
SET `status` = 'IDLE'
WHERE `status` IN ('SUCCESS', 'FAILED', 'PARTIAL_SUCCESS', 'CANCELLED');

-- 外键在 V23 单独创建：本文件只做数据回填，保证可重复执行（迁移中断后可重试）
