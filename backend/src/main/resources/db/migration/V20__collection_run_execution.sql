-- V20 · Run / Execution（裁决 §9 层级：Task → Run → Execution → Item）
-- PENDING 只属于 Run；Task.status 为聚合态。

CREATE TABLE `col_run` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`               BIGINT UNSIGNED NOT NULL,
  `run_no`                INT             NOT NULL,
  `trigger_type`          VARCHAR(16)     NOT NULL DEFAULT 'MANUAL',
  `status`                VARCHAR(24)     NOT NULL DEFAULT 'PENDING'
                          COMMENT 'PENDING/RUNNING/COMPLETED/PARTIAL_SUCCESS/FAILED/PAUSED/CANCELLED',
  `config_snapshot`       JSON            NULL,
  `total_execution_count` INT             NOT NULL DEFAULT 0,
  `success_count`         INT             NOT NULL DEFAULT 0,
  `failed_count`          INT             NOT NULL DEFAULT 0,
  `started_at`            DATETIME        NULL,
  `finished_at`           DATETIME        NULL,
  `error_summary`         VARCHAR(512)    NULL,
  `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_no` (`task_id`, `run_no`),
  KEY `idx_run_status` (`status`, `id`),
  CONSTRAINT `fk_run_task` FOREIGN KEY (`task_id`) REFERENCES `collection_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采集运行实例';

CREATE TABLE `col_execution` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `run_id`          BIGINT UNSIGNED NOT NULL,
  `source_id`       BIGINT UNSIGNED NOT NULL,
  `account_id`      BIGINT UNSIGNED NULL COMMENT 'PUBLIC 模式必须为 NULL',
  `auth_mode`       VARCHAR(16)     NOT NULL DEFAULT 'PUBLIC',
  `policy_id`       BIGINT UNSIGNED NULL COMMENT '指向具体发布版本，不指向分组',
  `capability`      VARCHAR(48)     NOT NULL,
  `status`          VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
  `attempt_no`      INT             NOT NULL DEFAULT 0,
  `request_count`   INT             NOT NULL DEFAULT 0,
  `fetched_count`   INT             NOT NULL DEFAULT 0,
  `inserted_count`  INT             NOT NULL DEFAULT 0,
  `duplicate_count` INT             NOT NULL DEFAULT 0,
  `failed_count`    INT             NOT NULL DEFAULT 0,
  `failure_type`    VARCHAR(24)     NULL
                    COMMENT 'AUTHENTICATION/AUTHORIZATION/RATE_LIMIT/NETWORK/PLATFORM/DATA/SYSTEM',
  `error_code`      VARCHAR(64)     NULL,
  `error_message`   VARCHAR(1000)   NULL,
  `adapter_version` VARCHAR(32)     NULL,
  `idem_key`        VARCHAR(96)     NOT NULL,
  `started_at`      DATETIME        NULL,
  `finished_at`     DATETIME        NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_exec_idem` (`idem_key`),
  KEY `idx_exec_run` (`run_id`, `id`),
  KEY `idx_exec_status` (`status`),
  CONSTRAINT `fk_exec_run`     FOREIGN KEY (`run_id`)     REFERENCES `col_run` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_exec_source`  FOREIGN KEY (`source_id`)  REFERENCES `ds_source` (`id`),
  CONSTRAINT `fk_exec_account` FOREIGN KEY (`account_id`) REFERENCES `ds_account` (`id`),
  CONSTRAINT `fk_exec_policy`  FOREIGN KEY (`policy_id`)  REFERENCES `ds_policy` (`id`),
  -- 防「AUTHENTICATED 但未绑账号」这类静默错误
  CONSTRAINT `ck_exec_auth_account` CHECK (
    (`auth_mode` = 'PUBLIC'         AND `account_id` IS NULL)
    OR
    (`auth_mode` = 'AUTHENTICATED'  AND `account_id` IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采集执行单元';
