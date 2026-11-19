-- =====================================================================
-- V9 · source 模块（过渡迁移）
--
-- 背景：R3-01 的 V1__init.sql 是新架构的最终基线，但当前代码仍有 22 个实体
-- 指向旧表结构，不能一次性切换。本迁移只**新增** source 模块所需的表，
-- 不动任何既有表，保证测试与现有功能全绿。
--
-- 与 V1 的差异（过渡期特有，R3-05 切库时由 V1 重建）：
-- 1. 外键指向 `user` 而非 `sys_user`（后者尚未建）
-- 2. 不建 sys_role / sys_user_role
-- 3. 不改动 keyword 表——旧关键词功能仍走旧表，R7 前端切换后再清理
--
-- R3-05 切库时本文件与 V1~V8 一起归档，其内容不会进入最终库。
-- =====================================================================

CREATE TABLE `ds_source` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`             VARCHAR(32)     NOT NULL COMMENT 'DOUYIN / XHS / KUAISHOU / WEB',
  `name`             VARCHAR(64)     NOT NULL,
  `source_type`      VARCHAR(24)     NOT NULL COMMENT 'SHORT_VIDEO / NOTE / FORUM / BLOG',
  `adapter_code`     VARCHAR(64)     NOT NULL COMMENT '对应 collection-service 的适配器',
  `status`           VARCHAR(24)     NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  `capability_json`  JSON            NULL,
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
  `auth_type`             VARCHAR(24)     NOT NULL DEFAULT 'COOKIE',
  `credential_ref`        VARCHAR(255)    NULL COMMENT '凭据引用（不存明文）',
  `status`                VARCHAR(24)     NOT NULL DEFAULT 'ACTIVE',
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
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`          BIGINT UNSIGNED NOT NULL,
  `keyword`            VARCHAR(128)    NOT NULL,
  `normalized`         VARCHAR(128)    NOT NULL,
  `owner_user_id`      BIGINT UNSIGNED NOT NULL,
  `status`             VARCHAR(24)     NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  `discover_policy`    VARCHAR(24)     NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / DAILY',
  `last_discovered_at` DATETIME        NULL,
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id`          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_keyword_owner` (`source_id`, `owner_user_id`, `normalized`),
  KEY `idx_keyword_status` (`status`),
  CONSTRAINT `fk_keyword_source` FOREIGN KEY (`source_id`) REFERENCES `ds_source` (`id`),
  CONSTRAINT `fk_keyword_owner2` FOREIGN KEY (`owner_user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关键词与发现策略';

-- 预置抖音数据源；关键词搜索能力标为 PENDING，避免前端误以为可用
INSERT INTO `ds_source` (`code`, `name`, `source_type`, `adapter_code`, `status`, `capability_json`, `remark`)
VALUES ('DOUYIN', '抖音', 'SHORT_VIDEO', 'douyin', 'ENABLED',
        '{"contentDetail":true,"comments":true,"keywordSearch":false,"commentStatus":"AVAILABLE","keywordStatus":"PENDING"}',
        '评论采集已实测；关键词搜索未验证');
