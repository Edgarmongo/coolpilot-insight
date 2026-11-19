-- =====================================================================
-- CoolPilot Insight · 新架构基线（R3-01）
-- 37 张表，按模块前缀组织：
--   sys_  system · ds_  source · col_ collection · ct_  content
--   an_   analysis · ld_ lead · cv_  conversion · ag_  analytics
--
-- 说明：
-- 1. 本文件对应 R1-04 目标模型，是 R3 之后的唯一结构基线。
-- 2. 当前放在 db/migration-next/ 下，尚未生效；R3-05 代码迁移完成后
--    才会切换 spring.flyway.locations 并归档 V1~V8。
-- 3. 公共字段：id / created_at / updated_at / created_by / updated_by / tenant_id
-- 4. 状态字段统一 VARCHAR(24) + 应用枚举，不用 MySQL ENUM。
-- 5. 时间统一 DATETIME，应用层按 Asia/Shanghai 解释。
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =====================================================================
-- 01 · system
-- =====================================================================

CREATE TABLE `sys_user` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username`         VARCHAR(64)     NOT NULL DEFAULT '',
  `phone`            VARCHAR(20)     NOT NULL COMMENT '登录名',
  `password_hash`    VARCHAR(100)    NOT NULL COMMENT 'BCrypt；!SENTINEL_NO_LOGIN! 表示未初始化',
  `status`           TINYINT         NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用',
  `must_change_pwd`  TINYINT         NOT NULL DEFAULT 1,
  `last_login_at`    DATETIME        NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`       BIGINT UNSIGNED NULL,
  `updated_by`       BIGINT UNSIGNED NULL,
  `tenant_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `deleted_at`       DATETIME        NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_phone` (`phone`),
  KEY `idx_user_status` (`status`),
  KEY `idx_user_deleted` (`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户';

CREATE TABLE `sys_role` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`             VARCHAR(32)     NOT NULL COMMENT 'ADMIN / USER',
  `name`             VARCHAR(64)     NOT NULL,
  `builtin`          TINYINT         NOT NULL DEFAULT 0 COMMENT '内置角色不可删除',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';

CREATE TABLE `sys_user_role` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`          BIGINT UNSIGNED NOT NULL,
  `role_id`          BIGINT UNSIGNED NOT NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_ur_role` (`role_id`),
  CONSTRAINT `fk_ur_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ur_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联';

CREATE TABLE `sys_config` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key`       VARCHAR(128)    NOT NULL,
  `config_value`     VARCHAR(1024)   NULL,
  `value_type`       VARCHAR(16)     NOT NULL DEFAULT 'STRING' COMMENT 'STRING/INT/DECIMAL/BOOLEAN',
  `is_secret`        TINYINT         NOT NULL DEFAULT 0 COMMENT '1=密钥，接口不回显',
  `description`      VARCHAR(255)    NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `updated_by`       BIGINT UNSIGNED NULL,
  `tenant_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置';

CREATE TABLE `sys_audit_log` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`          BIGINT UNSIGNED NULL,
  `action`           VARCHAR(64)     NOT NULL COMMENT 'LOGIN / USER_DELETE / CONFIG_UPDATE ...',
  `resource_type`    VARCHAR(64)     NULL,
  `resource_id`      BIGINT UNSIGNED NULL,
  `detail_json`      TEXT            NULL,
  `client_ip`        VARCHAR(64)     NULL,
  `user_agent`       VARCHAR(255)    NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_audit_user` (`user_id`),
  KEY `idx_audit_action` (`action`),
  KEY `idx_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计';

CREATE TABLE `sys_file` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `file_name`        VARCHAR(255)    NOT NULL,
  `storage_type`     VARCHAR(16)     NOT NULL DEFAULT 'LOCAL' COMMENT 'LOCAL / OSS',
  `storage_path`     VARCHAR(512)    NOT NULL,
  `content_type`     VARCHAR(128)    NULL,
  `size_bytes`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `checksum`         VARCHAR(64)     NULL,
  `uploaded_by`      BIGINT UNSIGNED NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `tenant_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `deleted_at`       DATETIME        NULL,
  PRIMARY KEY (`id`),
  KEY `idx_file_uploader` (`uploaded_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件资产';

-- =====================================================================
-- 02 · source
-- =====================================================================

CREATE TABLE `ds_source` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`             VARCHAR(32)     NOT NULL COMMENT 'DOUYIN / XHS / KUAISHOU / WEB',
  `name`             VARCHAR(64)     NOT NULL,
  `source_type`      VARCHAR(24)     NOT NULL COMMENT 'SHORT_VIDEO / NOTE / FORUM / BLOG',
  `adapter_code`     VARCHAR(64)     NOT NULL COMMENT '对应 collection-service 的适配器',
  `status`           VARCHAR(24)     NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  `capability_json`  JSON            NULL COMMENT '{"comments":true,"keywordSearch":false,"status":"LIMITED"}',
  `remark`           VARCHAR(255)    NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据源定义';

CREATE TABLE `ds_account` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`             BIGINT UNSIGNED NOT NULL,
  `account_name`          VARCHAR(128)    NULL,
  `external_account_id`   VARCHAR(128)    NOT NULL,
  `auth_type`             VARCHAR(24)     NOT NULL DEFAULT 'COOKIE' COMMENT 'COOKIE / TOKEN / NONE',
  `credential_ref`        VARCHAR(255)    NULL COMMENT '凭据引用（不存明文）',
  `status`                VARCHAR(24)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EXPIRED/DISABLED',
  `expires_at`            DATETIME        NULL,
  `last_used_at`          DATETIME        NULL,
  `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`             BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_external` (`source_id`, `external_account_id`),
  CONSTRAINT `fk_account_source` FOREIGN KEY (`source_id`) REFERENCES `ds_source` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台账号';

CREATE TABLE `ds_keyword` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`        BIGINT UNSIGNED NOT NULL,
  `keyword`          VARCHAR(128)    NOT NULL,
  `normalized`       VARCHAR(128)    NOT NULL COMMENT '小写去空格，用于去重',
  `owner_user_id`    BIGINT UNSIGNED NOT NULL,
  `status`           VARCHAR(24)     NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  `discover_policy`  VARCHAR(24)     NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / DAILY',
  `last_discovered_at` DATETIME      NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_keyword_owner` (`source_id`, `owner_user_id`, `normalized`),
  KEY `idx_keyword_status` (`status`),
  CONSTRAINT `fk_keyword_source` FOREIGN KEY (`source_id`) REFERENCES `ds_source` (`id`),
  CONSTRAINT `fk_keyword_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关键词与发现策略';

-- =====================================================================
-- 03 · collection
-- =====================================================================

CREATE TABLE `col_task` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no`          VARCHAR(40)     NOT NULL,
  `task_type`        VARCHAR(24)     NOT NULL DEFAULT 'COLLECT' COMMENT 'COLLECT / DISCOVERY / REFRESH',
  `source_id`        BIGINT UNSIGNED NOT NULL,
  `status`           VARCHAR(24)     NOT NULL DEFAULT 'PENDING'
                     COMMENT 'PENDING/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED',
  `priority`         INT             NOT NULL DEFAULT 0,
  `owner_user_id`    BIGINT UNSIGNED NOT NULL,
  `keyword_id`       BIGINT UNSIGNED NULL COMMENT '发现任务关联的关键词',
  `total_items`      INT             NOT NULL DEFAULT 0,
  `success_items`    INT             NOT NULL DEFAULT 0,
  `failed_items`     INT             NOT NULL DEFAULT 0,
  `started_at`       DATETIME        NULL,
  `finished_at`      DATETIME        NULL,
  `error_message`    VARCHAR(512)    NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_task_status` (`status`, `id`),
  KEY `idx_task_owner` (`owner_user_id`),
  CONSTRAINT `fk_task_source` FOREIGN KEY (`source_id`) REFERENCES `ds_source` (`id`),
  CONSTRAINT `fk_task_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采集任务';

CREATE TABLE `col_task_item` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`              BIGINT UNSIGNED NOT NULL,
  `source_id`            BIGINT UNSIGNED NOT NULL,
  `external_content_id`  VARCHAR(64)     NOT NULL COMMENT '平台侧内容 id，不 FK 到内容表',
  `content_type`         VARCHAR(24)     NOT NULL DEFAULT 'VIDEO',
  `status`               VARCHAR(24)     NOT NULL DEFAULT 'PENDING'
                         COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED',
  `retry_count`          INT             NOT NULL DEFAULT 0,
  `max_retry`            INT             NOT NULL DEFAULT 3,
  `requested_max`        INT             NOT NULL DEFAULT 300,
  `collected_count`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `active_flag`          TINYINT         NULL COMMENT '1=进行中；终态显式置 NULL 释放唯一键',
  `error_code`           VARCHAR(64)     NULL,
  `error_message`        VARCHAR(512)    NULL,
  `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item_active` (`source_id`, `external_content_id`, `active_flag`),
  KEY `idx_item_task` (`task_id`, `id`),
  KEY `idx_item_status` (`status`),
  CONSTRAINT `fk_item_task` FOREIGN KEY (`task_id`) REFERENCES `col_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采集任务项';

CREATE TABLE `col_task_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`       BIGINT UNSIGNED NOT NULL,
  `item_id`       BIGINT UNSIGNED NULL,
  `log_type`      VARCHAR(32)     NOT NULL DEFAULT 'INFO' COMMENT 'STATUS_CHANGE/ERROR/RETRY/INFO',
  `level`         VARCHAR(16)     NOT NULL DEFAULT 'INFO',
  `message`       VARCHAR(2000)   NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_log_task` (`task_id`, `id`),
  CONSTRAINT `fk_log_task` FOREIGN KEY (`task_id`) REFERENCES `col_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采集执行日志';

CREATE TABLE `col_checkpoint` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_item_id`      BIGINT UNSIGNED NOT NULL,
  `persisted_count`   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `cursor_json`       JSON            NULL COMMENT '翻页游标，用于断点续采',
  `last_success_at`   DATETIME        NULL,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_checkpoint_item` (`task_item_id`),
  CONSTRAINT `fk_ckpt_item` FOREIGN KEY (`task_item_id`) REFERENCES `col_task_item` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采集断点';

-- =====================================================================
-- 04 · content（原始与标准化分离）
-- =====================================================================

CREATE TABLE `ct_raw_archive` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`        BIGINT UNSIGNED NOT NULL,
  `external_id`      VARCHAR(64)     NOT NULL COMMENT '平台内容 id',
  `content_type`     VARCHAR(24)     NOT NULL DEFAULT 'VIDEO',
  `payload`          MEDIUMTEXT      NULL COMMENT '平台原始响应',
  `payload_hash`     VARCHAR(64)     NOT NULL COMMENT '内容哈希，用于重放幂等',
  `collected_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_raw_source_ext_hash` (`source_id`, `external_id`, `payload_hash`),
  KEY `idx_raw_ext` (`external_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台原始响应归档';

CREATE TABLE `ct_author` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`              BIGINT UNSIGNED NOT NULL,
  `external_author_id`     VARCHAR(128)    NOT NULL COMMENT '平台作者标识（如 sec_uid）',
  `display_name`           VARCHAR(128)    NULL,
  `profile_url`            VARCHAR(512)    NULL,
  `public_profile_json`    JSON            NULL COMMENT '仅存平台公开字段，不含隐私推断',
  `first_seen_at`          DATETIME        NULL,
  `last_seen_at`           DATETIME        NULL,
  `created_at`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`              BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_author_external` (`source_id`, `external_author_id`),
  CONSTRAINT `fk_author_source` FOREIGN KEY (`source_id`) REFERENCES `ds_source` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作者公开标识';

CREATE TABLE `ct_content` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`             BIGINT UNSIGNED NOT NULL,
  `external_content_id`   VARCHAR(64)     NOT NULL,
  `content_type`          VARCHAR(24)     NOT NULL DEFAULT 'VIDEO',
  `title`                 VARCHAR(512)    NULL,
  `content_text`          MEDIUMTEXT      NULL,
  `source_url`            VARCHAR(512)    NULL,
  `author_id`             BIGINT UNSIGNED NULL,
  `raw_archive_id`        BIGINT UNSIGNED NULL,
  `content_hash`          VARCHAR(64)     NULL,
  `normalize_status`      VARCHAR(24)     NOT NULL DEFAULT 'RAW' COMMENT 'RAW/NORMALIZED/FAILED',
  `published_at`          DATETIME        NULL,
  `collected_at`          DATETIME        NULL,
  `metrics_json`          JSON            NULL COMMENT '点赞/评论/分享等平台指标',
  `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`             BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_external` (`source_id`, `external_content_id`),
  KEY `idx_content_published` (`published_at`),
  KEY `idx_content_author` (`author_id`),
  CONSTRAINT `fk_content_source` FOREIGN KEY (`source_id`) REFERENCES `ds_source` (`id`),
  CONSTRAINT `fk_content_author` FOREIGN KEY (`author_id`) REFERENCES `ct_author` (`id`),
  CONSTRAINT `fk_content_raw` FOREIGN KEY (`raw_archive_id`) REFERENCES `ct_raw_archive` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标准化内容';

CREATE TABLE `ct_comment` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `content_id`           BIGINT UNSIGNED NOT NULL,
  `source_id`            BIGINT UNSIGNED NOT NULL,
  `external_comment_id`  VARCHAR(64)     NULL COMMENT '平台评论 id，可能缺失',
  `parent_external_id`   VARCHAR(64)     NULL,
  `root_external_id`     VARCHAR(64)     NULL,
  `is_reply`             TINYINT         NOT NULL DEFAULT 0,
  `reply_count`          INT             NOT NULL DEFAULT 0,
  `author_id`            BIGINT UNSIGNED NULL,
  `nickname`             VARCHAR(128)    NOT NULL DEFAULT '',
  `text`                 TEXT            NOT NULL,
  `likes`                INT             NOT NULL DEFAULT 0,
  `ip_region`            VARCHAR(64)     NULL,
  `published_at`         DATETIME        NULL,
  `dedup_key`            VARCHAR(64)     NOT NULL COMMENT '无 cid 时用指纹；恒非空',
  `dedup_strategy`       VARCHAR(16)     NOT NULL DEFAULT 'CID' COMMENT 'CID / FINGERPRINT',
  `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`            BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_external` (`source_id`, `external_comment_id`),
  UNIQUE KEY `uk_comment_dedup` (`dedup_key`),
  KEY `idx_comment_content` (`content_id`, `published_at`),
  KEY `idx_comment_author` (`author_id`),
  CONSTRAINT `fk_comment_content` FOREIGN KEY (`content_id`) REFERENCES `ct_content` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_comment_author` FOREIGN KEY (`author_id`) REFERENCES `ct_author` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论';

CREATE TABLE `ct_grant` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `grantee_user_id` BIGINT UNSIGNED NOT NULL,
  `content_id`      BIGINT UNSIGNED NOT NULL,
  `permission`      VARCHAR(16)     NOT NULL DEFAULT 'READ',
  `granted_by`      BIGINT UNSIGNED NOT NULL,
  `expires_at`      DATETIME        NULL COMMENT 'NULL=永久',
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grant` (`grantee_user_id`, `content_id`, `permission`),
  KEY `idx_grant_expires` (`expires_at`),
  CONSTRAINT `fk_grant_user` FOREIGN KEY (`grantee_user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_grant_content` FOREIGN KEY (`content_id`) REFERENCES `ct_content` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容读授权';

-- =====================================================================
-- 05 · analysis
-- =====================================================================

CREATE TABLE `an_profile` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `profile_code`       VARCHAR(64)     NOT NULL COMMENT '逻辑名，如 java-ai',
  `profile_version`    INT             NOT NULL DEFAULT 1 COMMENT '单调递增，不回改旧行',
  `profile_name`       VARCHAR(128)    NOT NULL DEFAULT '',
  `taxonomy_version`   VARCHAR(32)     NOT NULL DEFAULT 'v1' COMMENT '需求分类体系版本',
  `prompt_version`     VARCHAR(32)     NOT NULL DEFAULT 'p1',
  `model_config`       VARCHAR(128)    NOT NULL DEFAULT 'deepseek-chat',
  `score_config_json`  JSON            NULL COMMENT '五维权重',
  `status`             VARCHAR(24)     NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  `remark`             VARCHAR(255)    NULL,
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_profile_code_version` (`profile_code`, `profile_version`),
  KEY `idx_profile_status` (`profile_code`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分析档案（版本化）';

CREATE TABLE `an_task` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no`          VARCHAR(40)     NOT NULL,
  `task_type`        VARCHAR(24)     NOT NULL DEFAULT 'COMMENT' COMMENT 'COMMENT / INSIGHT',
  `profile_id`       BIGINT UNSIGNED NOT NULL COMMENT '锁定具体版本，保证可追溯',
  `status`           VARCHAR(24)     NOT NULL DEFAULT 'PENDING'
                     COMMENT 'PENDING/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED',
  `owner_user_id`    BIGINT UNSIGNED NOT NULL,
  `content_id`       BIGINT UNSIGNED NULL,
  `total_items`      INT             NOT NULL DEFAULT 0,
  `success_items`    INT             NOT NULL DEFAULT 0,
  `failed_items`     INT             NOT NULL DEFAULT 0,
  `started_at`       DATETIME        NULL,
  `finished_at`      DATETIME        NULL,
  `error_message`    VARCHAR(512)    NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`        BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_an_task_no` (`task_no`),
  KEY `idx_an_task_status` (`status`, `id`),
  CONSTRAINT `fk_an_task_profile` FOREIGN KEY (`profile_id`) REFERENCES `an_profile` (`id`),
  CONSTRAINT `fk_an_task_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `sys_user` (`id`),
  CONSTRAINT `fk_an_task_content` FOREIGN KEY (`content_id`) REFERENCES `ct_content` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分析任务';

CREATE TABLE `an_task_item` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`        BIGINT UNSIGNED NOT NULL,
  `comment_id`     BIGINT UNSIGNED NULL,
  `status`         VARCHAR(24)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
  `retry_count`    INT             NOT NULL DEFAULT 0,
  `error_message`  VARCHAR(512)    NULL,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_an_item_task` (`task_id`, `status`),
  KEY `idx_an_item_comment` (`comment_id`),
  CONSTRAINT `fk_an_item_task` FOREIGN KEY (`task_id`) REFERENCES `an_task` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_an_item_comment` FOREIGN KEY (`comment_id`) REFERENCES `ct_comment` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分析任务项';

CREATE TABLE `an_result` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `comment_id`          BIGINT UNSIGNED NOT NULL,
  `content_id`          BIGINT UNSIGNED NOT NULL,
  `profile_id`          BIGINT UNSIGNED NOT NULL,
  `demand_category`     VARCHAR(64)     NULL COMMENT '需求分类，如 TECH_LEARNING',
  `technology_tags_json` JSON           NULL,
  `intent_type`         VARCHAR(64)     NULL,
  `buying_stage`        VARCHAR(32)     NULL COMMENT 'AWARENESS/CONSIDERATION/DECISION',
  `intent_score`        INT             NULL COMMENT '0-100',
  `confidence`          DECIMAL(4,3)    NULL COMMENT '0.000-1.000',
  `result_json`         JSON            NULL COMMENT '完整结构化结果',
  `model_name`          VARCHAR(64)     NULL COMMENT '快照，便于追溯',
  `prompt_version`      VARCHAR(32)     NULL COMMENT '快照',
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `tenant_id`           BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_result_comment_profile` (`comment_id`, `profile_id`),
  KEY `idx_result_category` (`demand_category`),
  KEY `idx_result_created` (`created_at`),
  CONSTRAINT `fk_result_comment` FOREIGN KEY (`comment_id`) REFERENCES `ct_comment` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_result_content` FOREIGN KEY (`content_id`) REFERENCES `ct_content` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_result_profile` FOREIGN KEY (`profile_id`) REFERENCES `an_profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论级分析结果';

CREATE TABLE `an_insight` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `content_id`     BIGINT UNSIGNED NOT NULL,
  `profile_id`     BIGINT UNSIGNED NOT NULL,
  `result_json`    JSON            NULL,
  `model_name`     VARCHAR(64)     NULL,
  `prompt_version` VARCHAR(32)     NULL,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `tenant_id`      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_insight_content` (`content_id`, `id`),
  CONSTRAINT `fk_insight_content` FOREIGN KEY (`content_id`) REFERENCES `ct_content` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_insight_profile` FOREIGN KEY (`profile_id`) REFERENCES `an_profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频级洞察';

CREATE TABLE `an_evidence` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `result_id`           BIGINT UNSIGNED NULL,
  `insight_id`          BIGINT UNSIGNED NULL,
  `source_level`        VARCHAR(24)     NOT NULL COMMENT 'COMMENT / INSIGHT',
  `evidence_text`       TEXT            NOT NULL,
  `reason`              VARCHAR(512)    NULL,
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_evi_result` (`result_id`),
  KEY `idx_evi_insight` (`insight_id`),
  CONSTRAINT `fk_evi_result` FOREIGN KEY (`result_id`) REFERENCES `an_result` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_evi_insight` FOREIGN KEY (`insight_id`) REFERENCES `an_insight` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_evi_owner` CHECK (`result_id` IS NOT NULL OR `insight_id` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分析证据链';

CREATE TABLE `an_review` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `result_id`         BIGINT UNSIGNED NOT NULL,
  `reviewer_user_id`  BIGINT UNSIGNED NOT NULL,
  `status`            VARCHAR(24)     NOT NULL COMMENT 'APPROVED/REJECTED/CORRECTED',
  `note`              VARCHAR(512)    NULL,
  `corrected_json`    JSON            NULL COMMENT '纠正后的字段',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_review_result` (`result_id`, `created_at`),
  KEY `idx_review_status` (`status`),
  CONSTRAINT `fk_review_result` FOREIGN KEY (`result_id`) REFERENCES `an_result` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_review_user` FOREIGN KEY (`reviewer_user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 结果人工复核';

CREATE TABLE `an_usage` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`           BIGINT UNSIGNED NULL,
  `provider`          VARCHAR(32)     NOT NULL DEFAULT 'deepseek',
  `model_name`        VARCHAR(64)     NULL,
  `prompt_tokens`     INT             NOT NULL DEFAULT 0,
  `completion_tokens` INT             NOT NULL DEFAULT 0,
  `cost_amount`       DECIMAL(12,4)   NOT NULL DEFAULT 0,
  `trace_id`          VARCHAR(64)     NULL,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_usage_task` (`task_id`),
  KEY `idx_usage_created` (`created_at`),
  CONSTRAINT `fk_usage_task` FOREIGN KEY (`task_id`) REFERENCES `an_task` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 用量与成本';

-- =====================================================================
-- 06 · lead
-- =====================================================================

CREATE TABLE `ld_signal` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `result_id`            BIGINT UNSIGNED NOT NULL,
  `signal_type`          VARCHAR(32)     NOT NULL COMMENT 'DEMAND / QUESTION / COMPLAINT',
  `demand_description`   VARCHAR(512)    NULL,
  `product_category`     VARCHAR(64)     NULL,
  `intent_level`         VARCHAR(16)     NULL COMMENT 'LOW/MEDIUM/HIGH',
  `status`               VARCHAR(24)     NOT NULL DEFAULT 'NEW' COMMENT 'NEW/CONSUMED/IGNORED',
  `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `tenant_id`            BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_signal_result_type` (`result_id`, `signal_type`),
  KEY `idx_signal_status` (`status`),
  CONSTRAINT `fk_signal_result` FOREIGN KEY (`result_id`) REFERENCES `an_result` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求信号';

CREATE TABLE `ld_lead` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lead_code`           VARCHAR(64)     NOT NULL COMMENT '去重键：作者+需求类别+时间窗',
  `lead_type`           VARCHAR(32)     NOT NULL DEFAULT 'DEMAND',
  `source_author_id`    BIGINT UNSIGNED NULL,
  `demand_category`     VARCHAR(64)     NULL,
  `technology_tags_json` JSON           NULL,
  `buying_stage`        VARCHAR(32)     NULL,
  `lead_score`          INT             NULL COMMENT '0-100',
  `confidence`          DECIMAL(4,3)    NULL,
  `status`              VARCHAR(24)     NOT NULL DEFAULT 'NEW'
                        COMMENT 'NEW/PENDING_REVIEW/QUALIFIED/FOLLOWING/CONVERTED/NURTURE/REJECTED/CLOSED',
  `review_status`       VARCHAR(24)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  `assigned_user_id`    BIGINT UNSIGNED NULL,
  `first_seen_at`       DATETIME        NULL,
  `last_seen_at`        DATETIME        NULL,
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`           BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `deleted_at`          DATETIME        NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lead_code` (`lead_code`),
  KEY `idx_lead_status_score` (`status`, `lead_score`),
  KEY `idx_lead_author` (`source_author_id`),
  CONSTRAINT `fk_lead_author` FOREIGN KEY (`source_author_id`) REFERENCES `ct_author` (`id`),
  CONSTRAINT `fk_lead_assignee` FOREIGN KEY (`assigned_user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='潜在线索';

CREATE TABLE `ld_evidence` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lead_id`        BIGINT UNSIGNED NOT NULL,
  `signal_id`      BIGINT UNSIGNED NULL,
  `result_id`      BIGINT UNSIGNED NULL,
  `evidence_text`  TEXT            NOT NULL,
  `source_url`     VARCHAR(512)    NULL,
  `collected_at`   DATETIME        NULL,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ld_evi_lead` (`lead_id`),
  KEY `idx_ld_evi_signal` (`signal_id`),
  CONSTRAINT `fk_ld_evi_lead` FOREIGN KEY (`lead_id`) REFERENCES `ld_lead` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ld_evi_signal` FOREIGN KEY (`signal_id`) REFERENCES `ld_signal` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线索证据';

CREATE TABLE `ld_score_history` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lead_id`           BIGINT UNSIGNED NOT NULL,
  `profile_id`        BIGINT UNSIGNED NULL,
  `old_score`         INT             NULL,
  `new_score`         INT             NULL,
  `score_detail_json` JSON            NULL COMMENT '五维明细',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_score_lead` (`lead_id`, `created_at`),
  CONSTRAINT `fk_score_lead` FOREIGN KEY (`lead_id`) REFERENCES `ld_lead` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线索评分历史';

CREATE TABLE `ld_review` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lead_id`           BIGINT UNSIGNED NOT NULL,
  `reviewer_user_id`  BIGINT UNSIGNED NOT NULL,
  `review_result`     VARCHAR(24)     NOT NULL COMMENT 'APPROVED / REJECTED',
  `review_reason`     VARCHAR(512)    NULL,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ld_review_lead` (`lead_id`, `created_at`),
  CONSTRAINT `fk_ld_review_lead` FOREIGN KEY (`lead_id`) REFERENCES `ld_lead` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ld_review_user` FOREIGN KEY (`reviewer_user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线索人工审核';

CREATE TABLE `ld_followup` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lead_id`          BIGINT UNSIGNED NOT NULL,
  `followup_type`    VARCHAR(32)     NOT NULL DEFAULT 'NOTE' COMMENT 'NOTE/CALL/MESSAGE/EMAIL',
  `content`          VARCHAR(2000)   NULL,
  `contact_channel`  VARCHAR(32)     NULL,
  `consent_status`   VARCHAR(24)     NOT NULL DEFAULT 'UNKNOWN' COMMENT 'GRANTED/DENIED/UNKNOWN',
  `result`           VARCHAR(32)     NULL,
  `next_followup_at` DATETIME        NULL,
  `created_by`       BIGINT UNSIGNED NULL,
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_followup_lead` (`lead_id`, `created_at`),
  CONSTRAINT `fk_followup_lead` FOREIGN KEY (`lead_id`) REFERENCES `ld_lead` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='跟进记录';

-- =====================================================================
-- 07 · conversion
-- =====================================================================

CREATE TABLE `cv_product` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `external_product_id`  VARCHAR(128)    NOT NULL COMMENT 'CoolSkyAI 产品主键（权威）',
  `product_name`         VARCHAR(255)    NOT NULL,
  `product_type`         VARCHAR(32)     NULL COMMENT 'COURSE/RESOURCE/SERVICE',
  `product_url`          VARCHAR(512)    NULL,
  `status`               VARCHAR(24)     NOT NULL DEFAULT 'ACTIVE',
  `synced_at`            DATETIME        NULL,
  `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`            BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_external` (`external_product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='产品镜像（不存价格/库存）';

CREATE TABLE `cv_match` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lead_id`      BIGINT UNSIGNED NOT NULL,
  `product_id`   BIGINT UNSIGNED NOT NULL,
  `match_score`  INT             NULL,
  `reason`       VARCHAR(512)    NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_match_lead_product` (`lead_id`, `product_id`),
  KEY `idx_match_product` (`product_id`),
  CONSTRAINT `fk_match_lead` FOREIGN KEY (`lead_id`) REFERENCES `ld_lead` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_match_product` FOREIGN KEY (`product_id`) REFERENCES `cv_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线索-产品匹配';

CREATE TABLE `cv_conversion` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lead_id`             BIGINT UNSIGNED NOT NULL,
  `product_id`          BIGINT UNSIGNED NULL,
  `external_order_id`   VARCHAR(128)    NULL COMMENT 'CoolSkyAI 订单号（不在本库建账本）',
  `amount`              DECIMAL(12,4)   NOT NULL DEFAULT 0 COMMENT '成交金额快照',
  `currency`            VARCHAR(8)      NOT NULL DEFAULT 'CNY',
  `converted_at`        DATETIME        NULL,
  `attribution_source`  VARCHAR(64)     NULL COMMENT '渠道归因：来源平台 + 关键词',
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `tenant_id`           BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversion_lead` (`lead_id`),
  KEY `idx_conversion_order` (`external_order_id`),
  CONSTRAINT `fk_conversion_lead` FOREIGN KEY (`lead_id`) REFERENCES `ld_lead` (`id`),
  CONSTRAINT `fk_conversion_product` FOREIGN KEY (`product_id`) REFERENCES `cv_product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='转化记录';

-- =====================================================================
-- 08 · analytics
-- =====================================================================

CREATE TABLE `ag_report` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `report_no`         VARCHAR(40)     NOT NULL,
  `title`             VARCHAR(255)    NOT NULL DEFAULT '',
  `report_type`       VARCHAR(32)     NOT NULL DEFAULT 'CONTENT' COMMENT 'CONTENT/VIDEO/LEAD/PERIOD',
  `content_id`        BIGINT UNSIGNED NULL,
  `status`            VARCHAR(24)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
  `owner_user_id`     BIGINT UNSIGNED NOT NULL,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `deleted_at`        DATETIME        NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_no` (`report_no`),
  KEY `idx_report_owner` (`owner_user_id`),
  CONSTRAINT `fk_report_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='报告';

CREATE TABLE `ag_report_version` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `report_id`     BIGINT UNSIGNED NOT NULL,
  `version_no`    INT             NOT NULL DEFAULT 1,
  `content_md`    MEDIUMTEXT      NULL,
  `file_id`       BIGINT UNSIGNED NULL COMMENT '导出的 PDF/XLSX',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_version` (`report_id`, `version_no`),
  CONSTRAINT `fk_rv_report` FOREIGN KEY (`report_id`) REFERENCES `ag_report` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_rv_file` FOREIGN KEY (`file_id`) REFERENCES `sys_file` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='报告版本';

-- =====================================================================
-- 09 · 种子数据
-- =====================================================================

INSERT INTO `sys_role` (`code`, `name`, `builtin`) VALUES
  ('ADMIN', '管理员', 1),
  ('USER',  '普通用户', 1);

-- 管理员使用哨兵密码：库泄露也无法直接登录，真实 BCrypt 由 AdminBootstrap 在配置了
-- ADMIN_INIT_PASSWORD 时写入
INSERT INTO `sys_user` (`username`, `phone`, `password_hash`, `status`, `must_change_pwd`) VALUES
  ('管理员', '00000000001', '!SENTINEL_NO_LOGIN!', 1, 1);

INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT u.`id`, r.`id` FROM `sys_user` u, `sys_role` r
WHERE u.`phone` = '00000000001' AND r.`code` = 'ADMIN';

INSERT INTO `sys_config` (`config_key`, `config_value`, `value_type`, `is_secret`, `description`) VALUES
  ('ai.daily_token_budget',        '2000000', 'INT',     0, '单日 Token 总预算'),
  ('ai.single_task_token_budget',  '200000',  'INT',     0, '单次任务 Token 上限'),
  ('ai.max_concurrency',           '3',       'INT',     0, 'AI 分析最大并发'),
  ('ai.input_price_per_m',         '2.0',     'DECIMAL', 0, '输入价格（每百万 token）'),
  ('ai.output_price_per_m',        '8.0',     'DECIMAL', 0, '输出价格（每百万 token）'),
  ('ai.price_version',             'deepseek-2026-01', 'STRING', 0, '计费价格版本'),
  ('collect.default_max_comments', '300',     'INT',     0, '默认采集条数'),
  ('collect.max_comments_per_video','1000',   'INT',     0, '单视频采集上限'),
  ('security.max_upload_mb',       '20',      'INT',     0, '单文件上传上限（MB）');

INSERT INTO `ds_source` (`code`, `name`, `source_type`, `adapter_code`, `status`, `capability_json`, `remark`) VALUES
  ('DOUYIN', '抖音', 'SHORT_VIDEO', 'douyin', 'ENABLED',
   '{"contentDetail":true,"comments":true,"keywordSearch":false,"commentStatus":"AVAILABLE","keywordStatus":"PENDING"}',
   '评论采集已实测；关键词搜索未验证');

INSERT INTO `an_profile` (`profile_code`, `profile_version`, `profile_name`,
                          `taxonomy_version`, `prompt_version`, `model_config`, `score_config_json`, `status`) VALUES
  ('java-ai', 1, '默认需求分析档案', 'v1', 'p1', 'deepseek-chat',
   '{"demandClarity":0.3,"productFit":0.3,"intentStrength":0.2,"recency":0.1,"authorQuality":0.1}',
   'ENABLED');

SET FOREIGN_KEY_CHECKS = 1;
