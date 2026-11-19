-- =====================================================================
-- V12 · 对齐新旧主键（为读路径切换铺路）
--
-- 背景：V11 迁数据时用自增 id，导致
--   video.id 1,2,3  →  ct_content.id 7,8,9
--   comment.id 1..19 → ct_comment.id 63..81
--
-- 影响：comment.video_id、analysis_task.content_id、comment_analysis.comment_id 等
-- 都引用**旧 id**。若 id 不对齐，切换读路径时所有关联查询都会串数据。
--
-- 做法：清空 ct_* 中已迁移的行（旧表数据完好，可随时重放），
-- 然后按旧 id 显式插入，使 ct_content.id == video.id、ct_comment.id == comment.id。
-- 这样后续切读路径只需换表名与列名，不需要做 id 映射。
--
-- 注意：本迁移只清理由迁移产生的副本，不影响 video/comment/video_grant。
-- =====================================================================

DELETE FROM `ct_grant`;
DELETE FROM `ct_comment`;
DELETE FROM `ct_content`;
DELETE FROM `ct_author`;

ALTER TABLE `ct_content` AUTO_INCREMENT = 1;
ALTER TABLE `ct_comment` AUTO_INCREMENT = 1;
ALTER TABLE `ct_grant` AUTO_INCREMENT = 1;
ALTER TABLE `ct_author` AUTO_INCREMENT = 1;

-- 1) 作者（保持 id 连续，无旧 id 可对应）
INSERT INTO `ct_author` (`source_id`, `external_author_id`, `display_name`, `first_seen_at`, `last_seen_at`)
SELECT ds.`id`, v.`author_id`, MAX(v.`author_name`), MIN(v.`created_at`), MAX(v.`updated_at`)
FROM `video` v
JOIN `ds_source` ds ON ds.`code` = UPPER(v.`platform`) COLLATE utf8mb4_unicode_ci
WHERE v.`author_id` IS NOT NULL AND v.`author_id` <> ''
GROUP BY ds.`id`, v.`author_id`;

-- 2) 内容：显式 id = video.id
INSERT INTO `ct_content` (`id`, `source_id`, `external_content_id`, `content_type`, `title`, `source_url`,
                          `author_id`, `normalize_status`, `published_at`, `collected_at`, `metrics_json`)
SELECT v.`id`, ds.`id`, v.`aweme_id`, 'VIDEO', v.`title`, v.`cover_url`,
       a.`id`, 'NORMALIZED', v.`publish_time`, v.`last_collected_at`, v.`metadata_json`
FROM `video` v
JOIN `ds_source` ds ON ds.`code` = UPPER(v.`platform`) COLLATE utf8mb4_unicode_ci
LEFT JOIN `ct_author` a
       ON a.`source_id` = ds.`id` AND a.`external_author_id` = v.`author_id` COLLATE utf8mb4_unicode_ci;

-- 3) 评论：显式 id = comment.id
INSERT INTO `ct_comment` (`id`, `content_id`, `source_id`, `external_comment_id`, `parent_external_id`,
                          `is_reply`, `nickname`, `text`, `likes`, `ip_region`, `published_at`,
                          `dedup_key`, `dedup_strategy`)
SELECT cm.`id`, cm.`video_id`, ds.`id`, cm.`cid`, cm.`parent_cid`, COALESCE(cm.`is_reply`, 0),
       COALESCE(cm.`nickname`, ''), COALESCE(cm.`content`, ''), COALESCE(cm.`likes`, 0),
       cm.`ip_region`, cm.`publish_time`, cm.`dedup_key`, COALESCE(cm.`dedup_strategy`, 'CID')
FROM `comment` cm
JOIN `video` v ON v.`id` = cm.`video_id`
JOIN `ds_source` ds ON ds.`code` = UPPER(v.`platform`) COLLATE utf8mb4_unicode_ci
WHERE cm.`dedup_key` IS NOT NULL;

-- 4) 授权
INSERT INTO `ct_grant` (`grantee_user_id`, `content_id`, `permission`, `granted_by`, `expires_at`, `created_at`)
SELECT g.`grantee_user_id`, g.`video_id`, g.`permission`, g.`granted_by`, g.`expires_at`, g.`created_at`
FROM `video_grant` g;

-- 5) 自增起点设到旧表最大值之后，避免后续新数据撞 id
ALTER TABLE `ct_content` AUTO_INCREMENT = 100000;
ALTER TABLE `ct_comment` AUTO_INCREMENT = 100000;
