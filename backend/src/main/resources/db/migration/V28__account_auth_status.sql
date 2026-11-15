-- V28 · 平台账号授权状态（A-01.1 已授权移入；执行待 A-01.2 单独审批）
--
-- 前置事实（2026-11-16 实测）：
--   MySQL 8.0.46（>= 8.0.16，CHECK 真实生效）
--   ds_account collation=utf8mb4_unicode_ci，当前 0 行
--   status    VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'  ← 启用态是 ACTIVE，不是 ENABLED
--   auth_type VARCHAR(24) NOT NULL DEFAULT 'COOKIE'
--
-- 设计要点：
-- 1. auth_status 与 status 严格分离：前者是授权有效性，后者是管理员启停意图；
-- 2. CHECK 只限制合法值集合，不表达「启用+有效+未过期+Source 归属」的组合规则
--    （那由 AccountService 统一校验，塞进 CHECK 会让业务规则散落两处）；
-- 3. PENDING_VERIFY 纳入合法集合，首期可不实现对应流程；
-- 4. 严禁把 UNKNOWN 批量回填为 VALID —— 那等于凭空断言授权有效。

ALTER TABLE `ds_account`
  ADD COLUMN `auth_status`        VARCHAR(32)  NOT NULL DEFAULT 'UNKNOWN'
      COMMENT 'UNKNOWN/VALID/EXPIRED/INVALID/PENDING_VERIFY，仅由验证结果写入',
  ADD COLUMN `authorized_at`      DATETIME     NULL COMMENT '最近授权时间',
  ADD COLUMN `last_verified_at`   DATETIME     NULL COMMENT '最近验证时间；为空表示从未验证',
  ADD COLUMN `last_error_code`    VARCHAR(64)  NULL COMMENT '最近授权错误码',
  ADD COLUMN `last_error_message` VARCHAR(500) NULL COMMENT '最近错误摘要',
  ADD COLUMN `remark`             VARCHAR(500) NULL COMMENT '备注',
  ADD CONSTRAINT `chk_ds_account_auth_status`
      CHECK (`auth_status` IN ('UNKNOWN','VALID','EXPIRED','INVALID','PENDING_VERIFY'));
