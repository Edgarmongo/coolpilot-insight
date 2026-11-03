-- =====================================================================
-- V3 · 评论采集域
-- comment 双唯一键：uk_platform_cid（NULL 放行）+ uk_platform_dedup（恒非空兜底）
-- =====================================================================

CREATE TABLE `collection_task` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no`       VARCHAR(32)     NOT NULL,
  `task_type`     VARCHAR(24)     NOT NULL DEFAULT 'COLLECTION' COMMENT 'COLLECTION/REFRESH',
  `status`        VARCHAR(24)     NOT NULL DEFAULT 'PENDING'
                  COMMENT 'PENDING/RUNNING/RETRY_WAIT/SUCCESS/PARTIAL_SUCCESS/FAILED/CANCEL_REQUESTED/CANCELLED',
  `owner_user_id` BIGINT UNSIGNED NOT NULL,
  `params_json`   JSON            NULL,
  `total_items`   INT UNSIGNED    NOT NULL DEFAULT 0,
  `success_items` INT UNSIGNED    NOT NULL DEFAULT 0,
  `failed_items`  INT UNSIGNED    NOT NULL DEFAULT 0,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at`    DATETIME        NULL,
  `finished_at`   DATETIME        NULL,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_owner_created` (`owner_user_id`, `created_at`),
  KEY `idx_status_created` (`status`, `created_at`),
  CONSTRAINT `fk_ct_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采集主任务';

CREATE TABLE `collection_task_item` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`         BIGINT UNSIGNED NOT NULL,
  `video_id`        BIGINT UNSIGNED NOT NULL,
  `status`          VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
  `active_flag`     TINYINT UNSIGNED NULL DEFAULT 1 COMMENT '活动任务标记，终态置 NULL',
  `worker_id`       VARCHAR(64)     NULL,
  `retry_count`     INT UNSIGNED    NOT NULL DEFAULT 0,
  `max_retry`       INT UNSIGNED    NOT NULL DEFAULT 3,
  `requested_max`   INT UNSIGNED    NOT NULL DEFAULT 300,
  `collected_count` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `skipped_count`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `heartbeat_at`    DATETIME        NULL,
  `lease_until`     DATETIME        NULL,
  `started_at`      DATETIME        NULL,
  `finished_at`     DATETIME        NULL,
  `error_code`      VARCHAR(64)     NULL,
  `error_message`   TEXT            NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_video_active` (`video_id`, `active_flag`),
  UNIQUE KEY `uk_task_video` (`task_id`, `video_id`),
  KEY `idx_task_status` (`task_id`, `status`),
  KEY `idx_status_lease` (`status`, `lease_until`),
  KEY `idx_video_status` (`video_id`, `status`),
  CONSTRAINT `ck_active_flag` CHECK (`active_flag` IS NULL OR `active_flag` = 1),
  CONSTRAINT `fk_cti_task` FOREIGN KEY (`task_id`) REFERENCES `collection_task` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_cti_video` FOREIGN KEY (`video_id`) REFERENCES `video` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采集子任务（单视频）';

CREATE TABLE `collection_checkpoint` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_item_id`     BIGINT UNSIGNED NOT NULL,
  `cursor_value`     VARCHAR(2048)   NULL COMMENT '仅 cursor 验证通过后使用',
  `page_no`          INT UNSIGNED    NOT NULL DEFAULT 0,
  `last_cid`         VARCHAR(64)     NULL,
  `persisted_count`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已确认入库数',
  `last_success_at`  DATETIME        NULL,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item` (`task_item_id`),
  CONSTRAINT `fk_ckpt_item` FOREIGN KEY (`task_item_id`) REFERENCES `collection_task_item` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='断点信息';

CREATE TABLE `collection_task_log` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`      BIGINT UNSIGNED NOT NULL,
  `task_item_id` BIGINT UNSIGNED NULL,
  `event_type`   VARCHAR(32)     NOT NULL COMMENT 'STATUS_CHANGE/PAGE_DONE/ERROR/RETRY/CANCEL',
  `event_level`  VARCHAR(12)     NOT NULL DEFAULT 'INFO' COMMENT 'INFO/WARN/ERROR',
  `message`      VARCHAR(512)    NULL,
  `details_json` JSON            NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_created` (`task_id`, `created_at`),
  KEY `idx_item` (`task_item_id`),
  CONSTRAINT `fk_log_task` FOREIGN KEY (`task_id`) REFERENCES `collection_task` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_log_item` FOREIGN KEY (`task_item_id`) REFERENCES `collection_task_item` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务日志（仅关键事件）';

CREATE TABLE `comment` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `platform`        VARCHAR(16)     NOT NULL DEFAULT 'douyin',
  `cid`             VARCHAR(64)     NULL COMMENT '平台评论 ID（存在性未验证，允许 NULL）',
  `dedup_key`       VARCHAR(64)     NOT NULL COMMENT '幂等键（本地生成，恒非空）',
  `dedup_strategy`  VARCHAR(16)     NOT NULL DEFAULT 'CID' COMMENT 'CID/FINGERPRINT',
  `video_id`        BIGINT UNSIGNED NOT NULL COMMENT '内部 video.id',
  `parent_cid`      VARCHAR(64)     NULL COMMENT '父评论 cid（无 FK）',
  `root_cid`        VARCHAR(64)     NULL,
  `is_reply`        TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `reply_count`     INT UNSIGNED    NOT NULL DEFAULT 0,
  `nickname`        VARCHAR(100)    NOT NULL,
  `douyin_id`       VARCHAR(64)     NULL COMMENT '评论者抖音号',
  `douyin_uid`      VARCHAR(32)     NULL,
  `sec_uid`         VARCHAR(128)    NULL,
  `ip_region`       VARCHAR(50)     NOT NULL DEFAULT '未知' COMMENT 'IP 归属地',
  `content`         TEXT            NOT NULL,
  `likes`           INT UNSIGNED    NOT NULL DEFAULT 0,
  `publish_time`    DATETIME        NOT NULL,
  `source_item_id`  BIGINT UNSIGNED NULL COMMENT '来源子任务',
  `collected_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_platform_cid` (`platform`, `cid`),
  UNIQUE KEY `uk_platform_dedup` (`platform`, `dedup_key`),
  KEY `idx_video_time` (`video_id`, `publish_time`),
  KEY `idx_video_likes` (`video_id`, `likes`),
  KEY `idx_video_region` (`video_id`, `ip_region`),
  KEY `idx_parent` (`parent_cid`),
  KEY `idx_douyin_id` (`douyin_id`),
  KEY `idx_source_item` (`source_item_id`),
  CONSTRAINT `ck_dedup_strategy` CHECK (`dedup_strategy` IN ('CID', 'FINGERPRINT')),
  CONSTRAINT `fk_comment_video` FOREIGN KEY (`video_id`) REFERENCES `video` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_comment_source_item` FOREIGN KEY (`source_item_id`) REFERENCES `collection_task_item` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论事实数据';
