-- ============================================================
-- DRAFT · 已被 backend/.../db/migration/V28__account_auth_status.sql 取代
-- 保留本文件作为评审历史，请勿执行、勿移入 migration 目录。
-- ============================================================
-- ⚠️ 草案，禁止放入 backend/src/main/resources/db/migration
-- 该目录下任何 .sql 都会随服务重启被 Flyway 自动执行。
-- 评审通过后再移入 migration 并赋予正式版本号。

ALTER TABLE `ds_account`
  ADD COLUMN `auth_status`        VARCHAR(32)  NOT NULL DEFAULT 'UNKNOWN'
      COMMENT 'VALID/EXPIRED/INVALID/REVOKED/UNKNOWN，仅由验证结果写入',
  ADD COLUMN `authorized_at`      DATETIME     NULL,
  ADD COLUMN `last_verified_at`   DATETIME     NULL,
  ADD COLUMN `last_error_code`    VARCHAR(64)  NULL,
  ADD COLUMN `last_error_message` VARCHAR(500) NULL,
  ADD COLUMN `remark`             VARCHAR(500) NULL;

-- 历史回填：现有 0 行，无需回填。
-- 若将来有存量账号：新增列默认 UNKNOWN（不假设已授权），
-- 不允许把 UNKNOWN 批量改成 VALID。
