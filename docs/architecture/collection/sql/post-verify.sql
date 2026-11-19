-- 迁移后验证
SELECT '① 新表数(期望4)' k, COUNT(*) v FROM information_schema.TABLES WHERE TABLE_SCHEMA='coolpilot_insight'
  AND TABLE_NAME IN ('ds_keyword_group','ds_policy','col_run','col_execution')
UNION ALL SELECT '② runs(>=tasks)', COUNT(*) FROM col_run
UNION ALL SELECT '③ item 未关联 run(期望0)', COUNT(*) FROM collection_task_item WHERE run_id IS NULL
UNION ALL SELECT '④ source_id 为空(需人工确认)', COUNT(*) FROM collection_task WHERE source_id IS NULL
UNION ALL SELECT '⑤ 非聚合态状态(期望0)', COUNT(*) FROM collection_task
  WHERE status NOT IN ('IDLE','RUNNING','PAUSED','DISABLED')
UNION ALL SELECT '⑥ 状态快照行数(=任务数)', COUNT(*) FROM _bak_collection_task_status;
-- 唯一键与外键存在性
SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
 WHERE TABLE_SCHEMA='coolpilot_insight' AND CONSTRAINT_NAME IN
   ('uk_group_code','uk_policy_version','uk_run_no','uk_exec_idem','fk_item_run','fk_item_execution','ck_exec_auth_account')
 GROUP BY CONSTRAINT_NAME;
