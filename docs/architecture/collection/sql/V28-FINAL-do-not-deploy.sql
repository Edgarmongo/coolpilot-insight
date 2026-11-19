-- ⚠️ A-01 最终 SQL —— 禁止移入 backend/src/main/resources/db/migration
-- 该目录下任何 .sql 会随服务重启被 Flyway 自动执行，等于绕过审批。
-- 待 A-01.1 授权后再移入，并赋予正式版本号。

-- 前置核对结果（2026-11-16 实测）
--   MySQL 8.0.46（>= 8.0.16，CHECK 实际生效）
--   ds_account: collation=utf8mb4_unicode_ci, 0 行
--   status      VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'   ← 注意是 ACTIVE 不是 ENABLED
--   auth_type   VARCHAR(24) NOT NULL DEFAULT 'COOKIE'
--   本迁移新增列沿用 VARCHAR(32)/utf8mb4，与表一致

ALTER TABLE `ds_account`
  ADD COLUMN `auth_status`        VARCHAR(32)  NOT NULL DEFAULT 'UNKNOWN'
      COMMENT 'UNKNOWN/VALID/EXPIRED/INVALID/PENDING_VERIFY，仅由验证结果写入',
  ADD COLUMN `authorized_at`      DATETIME     NULL,
  ADD COLUMN `last_verified_at`   DATETIME     NULL,
  ADD COLUMN `last_error_code`    VARCHAR(64)  NULL,
  ADD COLUMN `last_error_message` VARCHAR(500) NULL,
  ADD COLUMN `remark`             VARCHAR(500) NULL,
  ADD CONSTRAINT `chk_ds_account_auth_status`
      CHECK (`auth_status` IN ('UNKNOWN','VALID','EXPIRED','INVALID','PENDING_VERIFY'));

-- 历史回填：0 行，无需回填；严禁把 UNKNOWN 批量改为 VALID
