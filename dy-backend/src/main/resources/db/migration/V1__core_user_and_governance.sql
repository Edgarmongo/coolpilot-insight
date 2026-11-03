-- =====================================================================
-- V1 · 用户与治理域
-- 依据：#24 / #25 数据库冻结候选版 V1.1
-- 约定：InnoDB / utf8mb4 / utf8mb4_0900_ai_ci（显式声明，避免与 server 默认 general_ci 混用）
-- =====================================================================

CREATE TABLE `user` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username`        VARCHAR(50)     NOT NULL COMMENT '显示名',
  `phone`           VARCHAR(11)     NOT NULL COMMENT '登录账号',
  `password_hash`   VARCHAR(100)    NOT NULL COMMENT '仅 BCrypt（ADR-005）',
  `role`            TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=普通 1=管理员',
  `status`          TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
  `must_change_pwd` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '强制改密标记',
  `last_login_at`   DATETIME        NULL DEFAULT NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at`      DATETIME        NULL DEFAULT NULL COMMENT '软删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_role` (`role`),
  KEY `idx_status` (`status`),
  KEY `idx_deleted` (`deleted_at`),
  CONSTRAINT `ck_user_role` CHECK (`role` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统用户';

CREATE TABLE `system_config` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key`   VARCHAR(64)     NOT NULL,
  `config_value` TEXT            NULL,
  `value_type`   VARCHAR(16)     NOT NULL DEFAULT 'STRING' COMMENT 'STRING/INT/DECIMAL/BOOL/JSON',
  `is_secret`    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '仅控制 API 脱敏，非加密',
  `description`  VARCHAR(255)    NULL,
  `updated_by`   BIGINT UNSIGNED NULL,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`),
  KEY `idx_updated_by` (`updated_by`),
  CONSTRAINT `fk_config_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `user` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置';

-- 审计表刻意不建 user 外键：审计必须独立于业务表生命周期
CREATE TABLE `audit_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT UNSIGNED NULL,
  `action`        VARCHAR(48)     NOT NULL COMMENT 'LOGIN/DELETE_VIDEO/GRANT...',
  `resource_type` VARCHAR(32)     NULL,
  `resource_id`   BIGINT UNSIGNED NULL,
  `detail_json`   JSON            NULL COMMENT '禁止写密码/Token/完整手机号',
  `client_ip`     VARCHAR(45)     NULL,
  `user_agent`    VARCHAR(255)    NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`, `created_at`),
  KEY `idx_action` (`action`),
  KEY `idx_resource` (`resource_type`, `resource_id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计';
