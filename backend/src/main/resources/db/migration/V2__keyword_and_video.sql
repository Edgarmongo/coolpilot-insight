-- =====================================================================
-- V2 · 关键词与视频域
-- ADR-003：video.id 为内部主键，aweme_id 为平台标识
-- =====================================================================

CREATE TABLE `keyword` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `keyword`       VARCHAR(128)    NOT NULL COMMENT '原始输入',
  `normalized`    VARCHAR(128)    NOT NULL COMMENT '归一化：小写+去空格+全角转半角',
  `description`   VARCHAR(512)    NULL,
  `owner_user_id` BIGINT UNSIGNED NOT NULL,
  `status`        TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_owner_norm` (`owner_user_id`, `normalized`),
  KEY `idx_status` (`status`),
  CONSTRAINT `fk_keyword_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='关键词';

CREATE TABLE `video` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
  `platform`           VARCHAR(16)     NOT NULL DEFAULT 'douyin',
  `aweme_id`           VARCHAR(64)     NOT NULL COMMENT '平台视频 ID',
  `title`              VARCHAR(512)    NULL,
  `author_name`        VARCHAR(128)    NULL,
  `author_id`          VARCHAR(128)    NULL COMMENT '作者 sec_uid',
  `cover_url`          VARCHAR(1024)   NULL,
  `publish_time`       DATETIME        NULL,
  `digg_count`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `comment_count`      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '平台显示值',
  `share_count`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `status`             TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1=正常 0=下架',
  `first_discovered_at` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_collected_at`  DATETIME        NULL COMMENT '增量判断依据',
  `metadata_json`      JSON            NULL COMMENT '仅放非查询字段',
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_platform_aweme` (`platform`, `aweme_id`),
  KEY `idx_publish_time` (`publish_time`),
  KEY `idx_last_collected` (`last_collected_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='视频';

CREATE TABLE `keyword_video` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `keyword_id`    BIGINT UNSIGNED NOT NULL,
  `video_id`      BIGINT UNSIGNED NOT NULL,
  `discovered_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_keyword_video` (`keyword_id`, `video_id`),
  KEY `idx_video` (`video_id`),
  CONSTRAINT `fk_kv_keyword` FOREIGN KEY (`keyword_id`) REFERENCES `keyword` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_kv_video` FOREIGN KEY (`video_id`) REFERENCES `video` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='关键词-视频关联（不承担发现历史）';

CREATE TABLE `discovery_task` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no`        VARCHAR(32)     NOT NULL,
  `keyword_id`     BIGINT UNSIGNED NOT NULL,
  `status`         VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
  `owner_user_id`  BIGINT UNSIGNED NOT NULL,
  `requested_max`  INT UNSIGNED    NOT NULL DEFAULT 50,
  `found_count`    INT UNSIGNED    NOT NULL DEFAULT 0,
  `new_video_count` INT UNSIGNED   NOT NULL DEFAULT 0,
  `params_json`    JSON            NULL COMMENT '排序/时间筛选',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at`     DATETIME        NULL,
  `finished_at`    DATETIME        NULL,
  `error_message`  TEXT            NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_status` (`status`),
  KEY `idx_owner_created` (`owner_user_id`, `created_at`),
  CONSTRAINT `fk_disc_keyword` FOREIGN KEY (`keyword_id`) REFERENCES `keyword` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_disc_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发现主任务';

CREATE TABLE `discovery_task_item` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`    BIGINT UNSIGNED NOT NULL,
  `video_id`   BIGINT UNSIGNED NOT NULL,
  `position`   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '搜索结果排序位',
  `is_new`     TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否本任务首次发现',
  `raw_json`   JSON            NULL,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_video` (`task_id`, `video_id`),
  KEY `idx_video` (`video_id`),
  CONSTRAINT `fk_dti_task` FOREIGN KEY (`task_id`) REFERENCES `discovery_task` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_dti_video` FOREIGN KEY (`video_id`) REFERENCES `video` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发现任务结果（历史载体）';
