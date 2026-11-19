-- 回滚脚本（按序执行）
-- 前提：未删除 _bak_collection_task_status
START TRANSACTION;
UPDATE collection_task t JOIN _bak_collection_task_status b ON b.task_id = t.id
  SET t.status = b.old_status, t.started_at = b.old_started, t.finished_at = b.old_finished;
COMMIT;

ALTER TABLE collection_task_item DROP FOREIGN KEY fk_item_run;
ALTER TABLE collection_task_item DROP FOREIGN KEY fk_item_execution;
ALTER TABLE collection_task_item DROP KEY idx_item_run, DROP COLUMN execution_id, DROP COLUMN run_id;
ALTER TABLE collection_task_log  DROP KEY idx_log_run, DROP COLUMN execution_id, DROP COLUMN run_id;
ALTER TABLE collection_task DROP KEY idx_task_source, DROP COLUMN next_run_at,
  DROP COLUMN schedule_expression, DROP COLUMN execution_mode, DROP COLUMN source_id;
ALTER TABLE ds_source DROP COLUMN last_check_result, DROP COLUMN last_checked_at;
ALTER TABLE ds_keyword DROP FOREIGN KEY fk_kw_group, DROP KEY idx_kw_group, DROP COLUMN group_id;
DROP TABLE IF EXISTS col_execution, col_run, ds_policy, ds_keyword_group;
-- 快照表保留（可作为回滚凭证），确认无误后再手工删除：
-- DROP TABLE _bak_collection_task_status;
