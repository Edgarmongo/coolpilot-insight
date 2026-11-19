-- V19 · 关键词分组与策略版本（裁决 DECISION-02 §8）
-- 发布后的策略不可原地修改：由应用层拦截，DDL 不强制（MySQL 无法表达"禁止 UPDATE 某列"）。

CREATE TABLE `ds_keyword_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`   BIGINT UNSIGNED NOT NULL,
  `group_code`  VARCHAR(64)  NOT NULL,
  `group_name`  VARCHAR(128) NOT NULL,
  `description` VARCHAR(255) NULL,
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_code` (`source_id`, `group_code`),
  CONSTRAINT `fk_group_source` FOREIGN KEY (`source_id`) REFERENCES `ds_source` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关键词分组';

CREATE TABLE `ds_policy` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `group_id`     BIGINT UNSIGNED NOT NULL,
  `version`      INT             NOT NULL,
  `policy_json`  JSON            NOT NULL,
  `status`       VARCHAR(16)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
  `change_note`  VARCHAR(255)    NULL,
  `published_at` DATETIME        NULL,
  `published_by` BIGINT UNSIGNED NULL,
  `created_by`   BIGINT UNSIGNED NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_version` (`group_id`, `version`),
  KEY `idx_policy_status` (`group_id`, `status`),
  CONSTRAINT `fk_policy_group` FOREIGN KEY (`group_id`) REFERENCES `ds_keyword_group` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关键词策略版本';

-- group_id 允许 NULL（历史关键词未分组）；外键 RESTRICT：删除分组前必须先解绑关键词
ALTER TABLE `ds_keyword`
  ADD COLUMN `group_id` BIGINT UNSIGNED NULL AFTER `source_id`,
  ADD KEY `idx_kw_group` (`group_id`),
  ADD CONSTRAINT `fk_kw_group` FOREIGN KEY (`group_id`) REFERENCES `ds_keyword_group` (`id`);
