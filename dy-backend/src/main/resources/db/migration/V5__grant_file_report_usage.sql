-- =====================================================================
-- V5 · 授权、文件、报告与成本治理域
-- =====================================================================

CREATE TABLE `video_grant` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `grantee_user_id` BIGINT UNSIGNED NOT NULL,
  `video_id`        BIGINT UNSIGNED NOT NULL,
  `permission`      VARCHAR(16)     NOT NULL DEFAULT 'READ' COMMENT '第一版仅 READ',
  `granted_by`      BIGINT UNSIGNED NOT NULL COMMENT '授权人（须为管理员，应用层校验）',
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at`      DATETIME        NULL COMMENT 'NULL=永久',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grant` (`grantee_user_id`, `video_id`, `permission`),
  KEY `idx_grantee` (`grantee_user_id`),
  KEY `idx_expires` (`expires_at`),
  KEY `idx_granted_by` (`granted_by`),
  CONSTRAINT `fk_vg_grantee` FOREIGN KEY (`grantee_user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_vg_video` FOREIGN KEY (`video_id`) REFERENCES `video` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_vg_granted_by` FOREIGN KEY (`granted_by`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='视频数据授权（L2 层）';

CREATE TABLE `file_asset` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `file_key`          VARCHAR(64)     NOT NULL COMMENT '服务端 UUID，对外标识',
  `owner_user_id`     BIGINT UNSIGNED NOT NULL,
  `biz_type`          VARCHAR(24)     NULL COMMENT '多态，无 FK',
  `biz_id`            BIGINT UNSIGNED NULL COMMENT '多态，无 FK',
  `file_type`         VARCHAR(32)     NOT NULL COMMENT 'CSV_IMPORT/REPORT_EXPORT/RAW_JSON',
  `storage_path`      VARCHAR(512)    NOT NULL COMMENT '服务端拼接',
  `original_filename` VARCHAR(255)    NULL COMMENT '仅展示',
  `mime_type`         VARCHAR(128)    NULL,
  `file_size`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `sha256`            CHAR(64)        NULL,
  `status`            TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1=有效 0=已删',
  `expires_at`        DATETIME        NULL,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_key` (`file_key`),
  KEY `idx_owner` (`owner_user_id`),
  KEY `idx_biz` (`biz_type`, `biz_id`),
  KEY `idx_sha256` (`sha256`),
  CONSTRAINT `fk_fa_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件资产';

CREATE TABLE `report` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `report_no`      VARCHAR(32)     NOT NULL,
  `title`          VARCHAR(255)    NOT NULL,
  `report_type`    VARCHAR(32)     NOT NULL COMMENT 'VIDEO/KEYWORD/COMPARISON',
  `scope_json`     JSON            NOT NULL COMMENT '数据范围快照',
  `owner_user_id`  BIGINT UNSIGNED NOT NULL,
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
  `current_version` INT UNSIGNED   NOT NULL DEFAULT 1,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at`     DATETIME        NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_no` (`report_no`),
  KEY `idx_owner_created` (`owner_user_id`, `created_at`),
  KEY `idx_type` (`report_type`),
  KEY `idx_deleted` (`deleted_at`),
  CONSTRAINT `fk_report_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报告';

CREATE TABLE `report_version` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `report_id`        BIGINT UNSIGNED NOT NULL,
  `version_no`       INT UNSIGNED    NOT NULL COMMENT '从 1 递增',
  `content_json`     JSON            NOT NULL COMMENT '唯一权威内容',
  `content_md`       MEDIUMTEXT      NULL COMMENT '渲染用，非权威',
  `analysis_task_id` BIGINT UNSIGNED NULL,
  `evidence_count`   INT UNSIGNED    NOT NULL DEFAULT 0,
  `generated_by`     BIGINT UNSIGNED NULL COMMENT '历史快照，无 FK',
  `generated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_version` (`report_id`, `version_no`),
  KEY `idx_task` (`analysis_task_id`),
  CONSTRAINT `fk_rv_report` FOREIGN KEY (`report_id`) REFERENCES `report` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_rv_task` FOREIGN KEY (`analysis_task_id`) REFERENCES `analysis_task` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报告版本';

CREATE TABLE `usage_record` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `biz_type`       VARCHAR(24)     NOT NULL COMMENT 'AI_ANALYSIS/COLLECTION',
  `biz_ref_id`     BIGINT UNSIGNED NULL COMMENT '多态，无 FK',
  `batch_id`       BIGINT UNSIGNED NULL COMMENT '批次级归集，用于 L3 重算',
  `owner_user_id`  BIGINT UNSIGNED NULL,
  `provider`       VARCHAR(32)     NULL,
  `model_name`     VARCHAR(64)     NULL,
  `input_tokens`   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '每次调用一条（权威源）',
  `output_tokens`  INT UNSIGNED    NOT NULL DEFAULT 0,
  `cost_amount`    DECIMAL(12,6)   NOT NULL DEFAULT 0,
  `price_version`  VARCHAR(32)     NULL,
  `latency_ms`     INT UNSIGNED    NULL,
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'OK' COMMENT 'OK/ERROR/TIMEOUT',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_biz` (`biz_type`, `biz_ref_id`),
  KEY `idx_batch` (`batch_id`),
  KEY `idx_created_owner` (`created_at`, `owner_user_id`),
  CONSTRAINT `fk_ur_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `user` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_ur_batch` FOREIGN KEY (`batch_id`) REFERENCES `analysis_task_batch` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用量与成本（唯一权威源）';
