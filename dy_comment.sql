CREATE DATABASE IF NOT EXISTS dy_comment DEFAULT CHARSET utf8mb4;
use dy_comment;

-- 用户表
CREATE TABLE `user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` VARCHAR(50) NOT NULL COMMENT '用户名',
  `phone` VARCHAR(11) NOT NULL COMMENT '手机号',
  `password` VARCHAR(128) NOT NULL COMMENT 'BCrypt加密密码',
  `role` TINYINT NOT NULL DEFAULT 0 COMMENT '角色: 0=普通用户, 1=管理员',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  INDEX `idx_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 评论表
CREATE TABLE `comment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '评论ID',
  `video_id` VARCHAR(32) NOT NULL COMMENT '视频编号',
  `nickname` VARCHAR(100) NOT NULL COMMENT '评论用户昵称',
  `douyin_id` VARCHAR(64) NULL COMMENT '评论者抖音号(unique_id)',
  `douyin_uid` VARCHAR(32) NULL COMMENT '评论者抖音UID',
  `sec_uid` VARCHAR(128) NULL COMMENT '评论者安全UID(主页链接用)',
  `region` VARCHAR(50) NOT NULL COMMENT '用户IP归属地区',
  `publish_time` DATETIME NOT NULL COMMENT '评论发布时间',
  `content` TEXT NOT NULL COMMENT '评论内容',
  `likes` INT NOT NULL DEFAULT 0 COMMENT '点赞数',
  `sentiment` TINYINT NOT NULL DEFAULT 0 COMMENT '情感: 1=积极, 0=中性, -1=消极',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  PRIMARY KEY (`id`),
  INDEX `idx_video_id` (`video_id`),
  INDEX `idx_video_region` (`video_id`, `region`),
  INDEX `idx_video_time` (`video_id`, `publish_time`),
  INDEX `idx_video_likes` (`video_id`, `likes`),
  INDEX `idx_douyin_id` (`douyin_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论数据表';

-- 分析结果表
CREATE TABLE `analysis_result` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `video_id` VARCHAR(32) NOT NULL COMMENT '视频编号',
  `total_comments` INT NOT NULL DEFAULT 0 COMMENT '评论总数',
  `total_users` INT NOT NULL DEFAULT 0 COMMENT '参与用户数',
  `total_likes` BIGINT NOT NULL DEFAULT 0 COMMENT '总点赞数',
  `avg_likes` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '平均点赞数',
  `region_stats` TEXT COMMENT '地区统计JSON',
  `sentiment_stats` TEXT COMMENT '情感统计JSON',
  `daily_stats` TEXT COMMENT '每日统计JSON',
  `top_comments` TEXT COMMENT '高赞评论JSON',
  `analyzed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '分析时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_video_id` (`video_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析结果表';

-- 数据隔离: 为 comment 表添加 user_id 字段
ALTER TABLE comment ADD COLUMN user_id BIGINT NULL COMMENT '用户ID' AFTER id;
-- 为 analysis_result 表添加 user_id 字段（可选，用于后续扩展）
ALTER TABLE analysis_result ADD COLUMN user_id BIGINT NULL COMMENT '用户ID' AFTER id;

-- 管理员
-- ⚠️ 安全提示：以下为占位凭据，不可直接用于生产。
--    首次部署后请立即登录并修改密码，或改用环境变量注入的初始化流程。
INSERT INTO dy_comment.user (username, phone, password, role, created_at)
VALUES ('管理员', '00000000001', MD5('CHANGE_ME_BEFORE_DEPLOY'), 1, NOW());
INSERT INTO dy_comment.user (username, phone, PASSWORD, role, created_at)
VALUES ('管理员', '00000000002', MD5('CHANGE_ME_BEFORE_DEPLOY'), 1, NOW());