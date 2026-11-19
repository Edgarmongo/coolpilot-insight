-- =============================================================
-- V8: analysis_evidence 补 source_level 列
-- 背景：AnalysisEvidence 实体已声明 sourceLevel（INSIGHT / COMMENT_ANALYSIS），
--       V4 建表时遗漏该列，导致评论级分析写入证据时抛
--       "Unknown column 'source_level' in 'field list'"，整条评论被标记为 FAILED。
-- 幂等：Flyway 保证只执行一次；此处仍用 IF NOT EXISTS 风格的保护性写法。
-- =============================================================

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'analysis_evidence'
      AND COLUMN_NAME = 'source_level'
);

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE analysis_evidence
       ADD COLUMN source_level VARCHAR(32) NOT NULL DEFAULT ''INSIGHT'' COMMENT ''证据来源层级：INSIGHT / COMMENT_ANALYSIS''
       AFTER explanation',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 便于按「来源层级 + 业务对象」检索证据
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'analysis_evidence'
      AND INDEX_NAME = 'idx_evidence_source_level'
);

SET @ddl2 := IF(@idx_exists = 0,
    'ALTER TABLE analysis_evidence ADD INDEX idx_evidence_source_level (source_level, comment_id)',
    'SELECT 1');

PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
