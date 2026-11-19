-- =====================================================================
-- V11 · 旧数据迁入新表（一次性，幂等）
--
-- 目的：content 读路径切到 ct_* 后，页面不能变空。
-- 策略：把 video / comment / video_grant 复制到 ct_*，旧表保留不动
--       （真正的删除在 R3-05b 切库时由 V1 重建完成）。
--
-- 幂等：每步都带 NOT EXISTS / 唯一键，重复执行不产生重复行。
-- 重要：旧表是 utf8mb4_0900_ai_ci、新表是 utf8mb4_unicode_ci，
--       跨表字符串比较必须显式 COLLATE，否则报
--       "Illegal mix of collations"。
-- 字段映射说明：
--   video.aweme_id → ct_content.external_content_id
--   video.author_id/author_name → ct_author（按 source + external_author_id 复用）
--   video.metadata_json → ct_content.metrics_json（原始响应归档留空，旧库没有存原始响应）
--   comment.cid → ct_comment.external_comment_id；comment.dedup_key 沿用旧值
-- =====================================================================

-- 1) 作者：从 video 的 author_id / author_name 提取
INSERT INTO `ct_author` (`source_id`, `external_author_id`, `display_name`, `first_seen_at`, `last_seen_at`)
SELECT ds.`id`, v.`author_id`, MAX(v.`author_name`), MIN(v.`created_at`), MAX(v.`updated_at`)
FROM `video` v
JOIN `ds_source` ds ON ds.`code` = UPPER(v.`platform`) COLLATE utf8mb4_unicode_ci
WHERE v.`author_id` IS NOT NULL AND v.`author_id` <> ''
  AND NOT EXISTS (
    SELECT 1 FROM `ct_author` a
    WHERE a.`source_id` = ds.`id` AND a.`external_author_id` = v.`author_id` COLLATE utf8mb4_unicode_ci
  )
GROUP BY ds.`id`, v.`author_id`;

-- 2) 内容
INSERT INTO `ct_content` (`source_id`, `external_content_id`, `content_type`, `title`, `source_url`,
                          `author_id`, `normalize_status`, `published_at`, `collected_at`, `metrics_json`)
SELECT ds.`id`, v.`aweme_id`, 'VIDEO', v.`title`, v.`cover_url`,
       a.`id`, 'NORMALIZED', v.`publish_time`, v.`last_collected_at`, v.`metadata_json`
FROM `video` v
JOIN `ds_source` ds ON ds.`code` = UPPER(v.`platform`) COLLATE utf8mb4_unicode_ci
LEFT JOIN `ct_author` a
       ON a.`source_id` = ds.`id` AND a.`external_author_id` = v.`author_id` COLLATE utf8mb4_unicode_ci
WHERE NOT EXISTS (
    SELECT 1 FROM `ct_content` c
    WHERE c.`source_id` = ds.`id` AND c.`external_content_id` = v.`aweme_id` COLLATE utf8mb4_unicode_ci
);

-- 3) 评论
INSERT INTO `ct_comment` (`content_id`, `source_id`, `external_comment_id`, `parent_external_id`,
                          `is_reply`, `nickname`, `text`, `likes`, `ip_region`, `published_at`,
                          `dedup_key`, `dedup_strategy`)
SELECT c2.`id`, ds.`id`, cm.`cid`, cm.`parent_cid`, COALESCE(cm.`is_reply`, 0),
       COALESCE(cm.`nickname`, ''), COALESCE(cm.`content`, ''), COALESCE(cm.`likes`, 0),
       cm.`ip_region`, cm.`publish_time`, cm.`dedup_key`,
       COALESCE(cm.`dedup_strategy`, 'CID')
FROM `comment` cm
JOIN `video` v ON v.`id` = cm.`video_id`
JOIN `ds_source` ds ON ds.`code` = UPPER(v.`platform`) COLLATE utf8mb4_unicode_ci
JOIN `ct_content` c2 ON c2.`source_id` = ds.`id` AND c2.`external_content_id` = v.`aweme_id` COLLATE utf8mb4_unicode_ci
WHERE cm.`dedup_key` IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM `ct_comment` x WHERE x.`dedup_key` = cm.`dedup_key` COLLATE utf8mb4_unicode_ci);

-- 4) 内容授权
INSERT INTO `ct_grant` (`grantee_user_id`, `content_id`, `permission`, `granted_by`, `expires_at`, `created_at`)
SELECT g.`grantee_user_id`, c2.`id`, g.`permission`, g.`granted_by`, g.`expires_at`, g.`created_at`
FROM `video_grant` g
JOIN `video` v ON v.`id` = g.`video_id`
JOIN `ds_source` ds ON ds.`code` = UPPER(v.`platform`) COLLATE utf8mb4_unicode_ci
JOIN `ct_content` c2 ON c2.`source_id` = ds.`id` AND c2.`external_content_id` = v.`aweme_id` COLLATE utf8mb4_unicode_ci
WHERE NOT EXISTS (
    SELECT 1 FROM `ct_grant` x
    WHERE x.`grantee_user_id` = g.`grantee_user_id`
      AND x.`content_id` = c2.`id`
      AND x.`permission` = g.`permission` COLLATE utf8mb4_unicode_ci
);
