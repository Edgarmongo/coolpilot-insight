-- =====================================================================
-- V4 · AI 分析域
-- 版本四维收敛到 analysis_profile，三表统一用 profile_id（D9）
-- analysis_task_item.batch_id 必须为单列 FK（R7，禁复合 SET NULL）
-- analysis_evidence 两条 FK 均 CASCADE（S1，避免 SET NULL 触发 CHECK 失败）
-- =====================================================================

CREATE TABLE `analysis_profile` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `profile_key`      VARCHAR(64)     NOT NULL COMMENT '服务端生成 ap_20261103_a3f2c1',
  `provider`         VARCHAR(32)     NOT NULL,
  `model_name`       VARCHAR(64)     NOT NULL,
  `prompt_version`   VARCHAR(32)     NOT NULL,
  `schema_version`   VARCHAR(32)     NOT NULL,
  `taxonomy_version` VARCHAR(32)     NOT NULL,
  `params_json`      JSON            NULL,
  `params_hash`      CHAR(64)        NOT NULL COMMENT 'params_json 按 norm-v1 规范化后的 SHA-256',
  `norm_version`     VARCHAR(16)     NOT NULL DEFAULT 'norm-v1',
  `status`           TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用（不删除）',
  `description`      VARCHAR(255)    NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_profile` (`provider`, `model_name`, `prompt_version`, `schema_version`, `taxonomy_version`, `params_hash`),
  UNIQUE KEY `uk_profile_key` (`profile_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 分析版本档案（只插入不更新）';

CREATE TABLE `analysis_task` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no`        VARCHAR(32)     NOT NULL,
  `task_type`      VARCHAR(32)     NOT NULL COMMENT 'COMMENT_LEVEL/VIDEO_LEVEL/COMPARISON',
  `status`         VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
  `owner_user_id`  BIGINT UNSIGNED NOT NULL,
  `scope_type`     VARCHAR(24)     NOT NULL COMMENT 'VIDEO/VIDEO_SET/KEYWORD',
  `scope_json`     JSON            NULL,
  `profile_id`     BIGINT UNSIGNED NOT NULL COMMENT '唯一版本来源',
  `total_items`    INT UNSIGNED    NOT NULL DEFAULT 0,
  `success_items`  INT UNSIGNED    NOT NULL DEFAULT 0,
  `failed_items`   INT UNSIGNED    NOT NULL DEFAULT 0,
  `input_tokens`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `output_tokens`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `cost_amount`    DECIMAL(12,6)   NOT NULL DEFAULT 0,
  `price_version`  VARCHAR(32)     NULL,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at`     DATETIME        NULL,
  `finished_at`    DATETIME        NULL,
  `error_message`  TEXT            NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_owner_created` (`owner_user_id`, `created_at`),
  KEY `idx_status_created` (`status`, `created_at`),
  CONSTRAINT `fk_at_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_at_profile` FOREIGN KEY (`profile_id`) REFERENCES `analysis_profile` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 分析主任务';

CREATE TABLE `analysis_task_batch` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`        BIGINT UNSIGNED NOT NULL,
  `batch_no`       INT UNSIGNED    NOT NULL,
  `status`         VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
  `input_count`    INT UNSIGNED    NOT NULL DEFAULT 0,
  `success_count`  INT UNSIGNED    NOT NULL DEFAULT 0,
  `failed_count`   INT UNSIGNED    NOT NULL DEFAULT 0,
  `input_tokens`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `output_tokens`  BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `error_message`  TEXT            NULL,
  `started_at`     DATETIME        NULL,
  `finished_at`    DATETIME        NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_batch` (`task_id`, `batch_no`),
  KEY `idx_task_status` (`task_id`, `status`),
  CONSTRAINT `fk_atb_task` FOREIGN KEY (`task_id`) REFERENCES `analysis_task` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分析批次';

CREATE TABLE `analysis_task_item` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`                BIGINT UNSIGNED NOT NULL,
  `batch_id`               BIGINT UNSIGNED NULL COMMENT '未分批时 NULL；单列 FK',
  `target_type`            VARCHAR(16)     NOT NULL COMMENT 'COMMENT/VIDEO',
  `target_id`              BIGINT UNSIGNED NOT NULL,
  `status`                 VARCHAR(24)     NOT NULL DEFAULT 'PENDING'
                           COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
  `retry_count`            INT UNSIGNED    NOT NULL DEFAULT 0,
  `allocated_input_tokens`  INT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '分摊估算，非权威',
  `allocated_output_tokens` INT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '分摊估算，非权威',
  `allocated_cost_amount`   DECIMAL(12,6)  NOT NULL DEFAULT 0 COMMENT '分摊估算，非权威',
  `error_code`             VARCHAR(64)     NULL,
  `error_message`          VARCHAR(512)    NULL,
  `created_at`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at`            DATETIME        NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_target` (`task_id`, `target_type`, `target_id`),
  KEY `idx_batch_status` (`batch_id`, `status`),
  CONSTRAINT `ck_target_type` CHECK (`target_type` IN ('COMMENT', 'VIDEO')),
  CONSTRAINT `fk_ati_task` FOREIGN KEY (`task_id`) REFERENCES `analysis_task` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_ati_batch` FOREIGN KEY (`batch_id`) REFERENCES `analysis_task_batch` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分析对象（单评论/单视频）';

CREATE TABLE `comment_analysis` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `comment_id`             BIGINT UNSIGNED NOT NULL,
  `profile_id`             BIGINT UNSIGNED NOT NULL,
  `analysis_task_id`       BIGINT UNSIGNED NULL,
  `analysis_task_item_id`  BIGINT UNSIGNED NULL,
  `sentiment`              VARCHAR(16)     NULL COMMENT 'positive/neutral/negative/unknown',
  `intent`                 VARCHAR(32)     NULL COMMENT 'complaint/praise/question/purchase/other',
  `topics_json`            JSON            NULL,
  `pain_points_json`       JSON            NULL,
  `user_need`              VARCHAR(512)    NULL,
  `purchase_intent`        VARCHAR(24)     NULL COMMENT 'none/low/medium/high',
  `scenario_tags_json`     JSON            NULL,
  `product_suggestions_json` JSON          NULL,
  `competitor_mentions_json` JSON          NULL,
  `confidence`             DECIMAL(4,3)    NULL,
  `is_uncertain`           TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `created_at`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_profile` (`comment_id`, `profile_id`),
  KEY `idx_sentiment` (`sentiment`),
  KEY `idx_task` (`analysis_task_id`),
  KEY `idx_item` (`analysis_task_item_id`),
  CONSTRAINT `ck_confidence` CHECK (`confidence` IS NULL OR (`confidence` >= 0 AND `confidence` <= 1)),
  CONSTRAINT `fk_ca_comment` FOREIGN KEY (`comment_id`) REFERENCES `comment` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_ca_profile` FOREIGN KEY (`profile_id`) REFERENCES `analysis_profile` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_ca_task` FOREIGN KEY (`analysis_task_id`) REFERENCES `analysis_task` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_ca_item` FOREIGN KEY (`analysis_task_item_id`) REFERENCES `analysis_task_item` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论级结构化分析';

CREATE TABLE `video_insight` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `video_id`         BIGINT UNSIGNED NOT NULL,
  `analysis_task_id` BIGINT UNSIGNED NULL,
  `profile_id`       BIGINT UNSIGNED NOT NULL,
  `insight_type`     VARCHAR(32)     NOT NULL COMMENT 'OVERVIEW/PAIN_POINTS/PRODUCT_OPPORTUNITY',
  `scope_json`       JSON            NULL COMMENT '数据快照范围',
  `sample_count`     INT UNSIGNED    NOT NULL DEFAULT 0,
  `result_json`      JSON            NOT NULL,
  `summary_md`       MEDIUMTEXT      NULL COMMENT '可读摘要（非权威）',
  `evidence_count`   INT UNSIGNED    NOT NULL DEFAULT 0,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_video_created` (`video_id`, `created_at`),
  KEY `idx_task` (`analysis_task_id`),
  CONSTRAINT `fk_vi_video` FOREIGN KEY (`video_id`) REFERENCES `video` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_vi_task` FOREIGN KEY (`analysis_task_id`) REFERENCES `analysis_task` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_vi_profile` FOREIGN KEY (`profile_id`) REFERENCES `analysis_profile` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='视频级洞察（多版本并存）';

CREATE TABLE `analysis_evidence` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `insight_id`          BIGINT UNSIGNED NULL COMMENT '视频级证据',
  `comment_analysis_id` BIGINT UNSIGNED NULL COMMENT '评论级证据',
  `comment_id`          BIGINT UNSIGNED NOT NULL COMMENT '必指向原始评论',
  `evidence_type`       VARCHAR(32)     NOT NULL COMMENT 'SUPPORTING/CONTRADICTING/REPRESENTATIVE',
  `relevance_score`     DECIMAL(4,3)    NULL,
  `explanation`         VARCHAR(512)    NULL,
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_insight` (`insight_id`),
  KEY `idx_comment_analysis` (`comment_analysis_id`),
  KEY `idx_comment` (`comment_id`),
  CONSTRAINT `ck_evidence_target` CHECK (`insight_id` IS NOT NULL OR `comment_analysis_id` IS NOT NULL),
  CONSTRAINT `fk_ev_insight` FOREIGN KEY (`insight_id`) REFERENCES `video_insight` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_ev_ca` FOREIGN KEY (`comment_analysis_id`) REFERENCES `comment_analysis` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_ev_comment` FOREIGN KEY (`comment_id`) REFERENCES `comment` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='洞察证据（可追溯）';
