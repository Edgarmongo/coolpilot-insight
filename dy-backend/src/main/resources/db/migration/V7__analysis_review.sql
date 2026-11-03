-- =====================================================================
-- V7 · 人工复核（移植自 DYP 的 ai_review 能力）
--
-- 设计取舍：
-- - AI 结果本身不可篡改，复核只做「状态 + 备注 + 纠正快照」，写在独立表里
-- - comment_analysis 上冗余一列 review_status，用于列表筛选与统计，避免每次 JOIN
-- =====================================================================

ALTER TABLE `comment_analysis`
  ADD COLUMN `review_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING'
      COMMENT 'PENDING/APPROVED/REJECTED/CORRECTED' AFTER `is_uncertain`,
  ADD COLUMN `reviewed_by` BIGINT UNSIGNED NULL AFTER `review_status`,
  ADD COLUMN `reviewed_at` DATETIME NULL AFTER `reviewed_by`,
  ADD KEY `idx_review_status` (`review_status`),
  ADD CONSTRAINT `ck_review_status`
      CHECK (`review_status` IN ('PENDING', 'APPROVED', 'REJECTED', 'CORRECTED'));

CREATE TABLE `analysis_review` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `comment_analysis_id` BIGINT UNSIGNED NOT NULL,
  `reviewer_user_id`    BIGINT UNSIGNED NULL COMMENT '复核人；用户删除后置空但记录保留',
  `status`              VARCHAR(24)     NOT NULL COMMENT 'APPROVED/REJECTED/CORRECTED',
  `note`                VARCHAR(512)    NULL,
  `corrected_json`      JSON            NULL COMMENT '人工纠正后的结构化字段快照',
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_analysis_created` (`comment_analysis_id`, `created_at`),
  KEY `idx_reviewer` (`reviewer_user_id`),
  CONSTRAINT `ck_analysis_review_status`
      CHECK (`status` IN ('APPROVED', 'REJECTED', 'CORRECTED')),
  CONSTRAINT `fk_ar_analysis` FOREIGN KEY (`comment_analysis_id`)
      REFERENCES `comment_analysis` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `fk_ar_reviewer` FOREIGN KEY (`reviewer_user_id`)
      REFERENCES `user` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 结果人工复核记录';
