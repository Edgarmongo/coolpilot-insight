-- =====================================================================
-- V10 · content 模块（过渡）
--
-- 与 R3-01 的 V1 对齐，差异仅在过渡期外键指向 `user` 而非 `sys_user`。
-- 本迁移只**新增**表，不改动 video/comment/video_grant，保证现有读路径不受影响。
--
-- 迁移路径：新采集链路写 ct_* → 旧链路继续读 video/comment →
-- R3-05b 统一切库时由 V1 重建并删除旧表。
-- =====================================================================

CREATE TABLE `ct_raw_archive` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`     BIGINT UNSIGNED NOT NULL,
  `external_id`   VARCHAR(64)     NOT NULL,
  `content_type`  VARCHAR(24)     NOT NULL DEFAULT 'VIDEO',
  `payload`       MEDIUMTEXT      NULL,
  `payload_hash`  VARCHAR(64)     NOT NULL,
  `collected_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_raw_source_ext_hash` (`source_id`, `external_id`, `payload_hash`),
  KEY `idx_raw_ext` (`external_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台原始响应归档';

CREATE TABLE `ct_author` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`           BIGINT UNSIGNED NOT NULL,
  `external_author_id`  VARCHAR(128)    NOT NULL,
  `display_name`        VARCHAR(128)    NULL,
  `profile_url`         VARCHAR(512)    NULL,
  `public_profile_json` JSON            NULL,
  `first_seen_at`       DATETIME        NULL,
  `last_seen_at`        DATETIME        NULL,
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`           BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_author_external` (`source_id`, `external_author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作者公开标识';

CREATE TABLE `ct_content` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`            BIGINT UNSIGNED NOT NULL,
  `external_content_id`  VARCHAR(64)     NOT NULL,
  `content_type`         VARCHAR(24)     NOT NULL DEFAULT 'VIDEO',
  `title`                VARCHAR(512)    NULL,
  `content_text`         MEDIUMTEXT      NULL,
  `source_url`           VARCHAR(512)    NULL,
  `author_id`            BIGINT UNSIGNED NULL,
  `raw_archive_id`       BIGINT UNSIGNED NULL,
  `content_hash`         VARCHAR(64)     NULL,
  `normalize_status`     VARCHAR(24)     NOT NULL DEFAULT 'RAW',
  `published_at`         DATETIME        NULL,
  `collected_at`         DATETIME        NULL,
  `metrics_json`         JSON            NULL,
  `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`            BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_external` (`source_id`, `external_content_id`),
  KEY `idx_content_published` (`published_at`),
  CONSTRAINT `fk_ct_content_source` FOREIGN KEY (`source_id`) REFERENCES `ds_source` (`id`),
  CONSTRAINT `fk_ct_content_author` FOREIGN KEY (`author_id`) REFERENCES `ct_author` (`id`),
  CONSTRAINT `fk_ct_content_raw` FOREIGN KEY (`raw_archive_id`) REFERENCES `ct_raw_archive` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标准化内容';

CREATE TABLE `ct_comment` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `content_id`          BIGINT UNSIGNED NOT NULL,
  `source_id`           BIGINT UNSIGNED NOT NULL,
  `external_comment_id` VARCHAR(64)     NULL,
  `parent_external_id`  VARCHAR(64)     NULL,
  `is_reply`            TINYINT         NOT NULL DEFAULT 0,
  `author_id`           BIGINT UNSIGNED NULL,
  `nickname`            VARCHAR(128)    NOT NULL DEFAULT '',
  `text`                TEXT            NOT NULL,
  `likes`               INT             NOT NULL DEFAULT 0,
  `ip_region`           VARCHAR(64)     NULL,
  `published_at`        DATETIME        NULL,
  `dedup_key`           VARCHAR(64)     NOT NULL,
  `dedup_strategy`      VARCHAR(16)     NOT NULL DEFAULT 'CID',
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`           BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_external` (`source_id`, `external_comment_id`),
  UNIQUE KEY `uk_comment_dedup` (`dedup_key`),
  KEY `idx_comment_content` (`content_id`, `published_at`),
  CONSTRAINT `fk_ct_comment_content` FOREIGN KEY (`content_id`) REFERENCES `ct_content` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论';

CREATE TABLE `ct_grant` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `grantee_user_id` BIGINT UNSIGNED NOT NULL,
  `content_id`      BIGINT UNSIGNED NOT NULL,
  `permission`      VARCHAR(16)     NOT NULL DEFAULT 'READ',
  `granted_by`      BIGINT UNSIGNED NOT NULL,
  `expires_at`      DATETIME        NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grant` (`grantee_user_id`, `content_id`, `permission`),
  CONSTRAINT `fk_ct_grant_user` FOREIGN KEY (`grantee_user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ct_grant_content` FOREIGN KEY (`content_id`) REFERENCES `ct_content` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容读授权';
