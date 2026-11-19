-- =====================================================================
-- V18 · ct_content 补过渡列（为读写路径切换做准备）
--
-- 背景：切换 VideoMapper/CommentMapper 到 ct_* 时，旧表有 5 个语义无法
-- 直接映射的列，若不带过来会让首页/发现结果的数据变空：
--   status           旧: TINYINT 业务状态    新模型: normalize_status 是「处理状态」
--   first_discovered_at / last_collected_at  新表只有 collected_at
--   digg_count / comment_count / share_count 新模型放在 metrics_json
--   author_name      新模型应查 ct_author.display_name
--
-- 本迁移**临时**补齐为独立列，使切换退化为「表名 + 列名替换」，
-- 风险从"字段语义错位"降到"改漏会编译失败/查不到行"。
-- R3-05b 切库时按 V1 基线重建，这些列会并入 metrics_json / ct_author 后消失。
-- =====================================================================

ALTER TABLE `ct_content`
  ADD COLUMN `biz_status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '过渡列：旧 video.status',
  ADD COLUMN `first_discovered_at` DATETIME    NULL COMMENT '过渡列：首次发现时间',
  ADD COLUMN `digg_count`         INT          NOT NULL DEFAULT 0 COMMENT '过渡列：点赞数',
  ADD COLUMN `comment_count`      INT          NOT NULL DEFAULT 0 COMMENT '过渡列：评论数',
  ADD COLUMN `share_count`        INT          NOT NULL DEFAULT 0 COMMENT '过渡列：分享数',
  ADD COLUMN `author_name`        VARCHAR(128) NULL COMMENT '过渡列：冗余作者名，正式模型走 ct_author';

-- 回填：按 id 对齐（V12 已保证 ct_content.id == video.id）
UPDATE `ct_content` c
JOIN `video` v ON v.`id` = c.`id`
SET c.`biz_status`          = COALESCE(v.`status`, 1),
    c.`first_discovered_at` = v.`first_discovered_at`,
    c.`digg_count`          = COALESCE(v.`digg_count`, 0),
    c.`comment_count`       = COALESCE(v.`comment_count`, 0),
    c.`share_count`         = COALESCE(v.`share_count`, 0),
    c.`author_name`         = v.`author_name`,
    c.`collected_at`        = COALESCE(c.`collected_at`, v.`last_collected_at`);
