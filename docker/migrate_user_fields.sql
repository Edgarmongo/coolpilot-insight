-- ============================================================
-- 增量迁移：comment 表增加用户标识字段
--
-- 适用场景：数据库已存在（已执行过旧版 dy_comment.sql），
--          需要补充 douyin_id / douyin_uid / sec_uid 字段。
--
-- 特性：幂等。字段已存在则跳过，可重复执行。
-- 用法：mysql -uroot -p dy_comment < migrate_user_fields.sql
-- ============================================================

USE dy_comment;

-- 抖音号（对外可见的唯一标识）
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='dy_comment' AND TABLE_NAME='comment' AND COLUMN_NAME='douyin_id') > 0,
  'SELECT ''comment.douyin_id 已存在，跳过'' AS message',
  'ALTER TABLE comment ADD COLUMN `douyin_id` VARCHAR(64) NULL COMMENT ''评论者抖音号(unique_id)'' AFTER `nickname`'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 抖音 UID（内部数字 ID）
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='dy_comment' AND TABLE_NAME='comment' AND COLUMN_NAME='douyin_uid') > 0,
  'SELECT ''comment.douyin_uid 已存在，跳过'' AS message',
  'ALTER TABLE comment ADD COLUMN `douyin_uid` VARCHAR(32) NULL COMMENT ''评论者抖音UID'' AFTER `douyin_id`'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 安全 UID（拼接主页链接用）
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='dy_comment' AND TABLE_NAME='comment' AND COLUMN_NAME='sec_uid') > 0,
  'SELECT ''comment.sec_uid 已存在，跳过'' AS message',
  'ALTER TABLE comment ADD COLUMN `sec_uid` VARCHAR(128) NULL COMMENT ''评论者安全UID(主页链接用)'' AFTER `douyin_uid`'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 抖音号索引（加速按用户检索）
SET @sql := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA='dy_comment' AND TABLE_NAME='comment' AND INDEX_NAME='idx_douyin_id') > 0,
  'SELECT ''idx_douyin_id 已存在，跳过'' AS message',
  'ALTER TABLE comment ADD INDEX `idx_douyin_id` (`douyin_id`)'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 结果确认
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='dy_comment' AND TABLE_NAME='comment'
  AND COLUMN_NAME IN ('douyin_id','douyin_uid','sec_uid')
ORDER BY ORDINAL_POSITION;
