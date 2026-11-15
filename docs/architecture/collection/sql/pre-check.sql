-- 迁移前检查（只读）
SELECT '① 基线表数' k, COUNT(*) v FROM information_schema.TABLES WHERE TABLE_SCHEMA='coolpilot_insight'
UNION ALL SELECT '② 任务数', COUNT(*) FROM collection_task
UNION ALL SELECT '③ item 数', COUNT(*) FROM collection_task_item
UNION ALL SELECT '④ Task 状态种类', COUNT(DISTINCT status) FROM collection_task
UNION ALL SELECT '⑤ 新表是否已存在(应0)', COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA='coolpilot_insight' AND TABLE_NAME IN ('ds_keyword_group','ds_policy','col_run','col_execution')
UNION ALL SELECT '⑥ flyway 版本', MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;
-- 双唯一键须存在
SELECT INDEX_NAME FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA='coolpilot_insight' AND TABLE_NAME='collection_task_item'
   AND INDEX_NAME IN ('uk_task_video','uk_video_active') GROUP BY INDEX_NAME;
