-- V23 · item 外键（回填完成后创建，裁决 §3）
-- 单独成文件的原因：V22 是纯 DML 可重复执行；把 ALTER 混在里面会导致
-- 迁移中断后重试时因「外键已存在」而失败。

ALTER TABLE `collection_task_item`
  ADD CONSTRAINT `fk_item_run`       FOREIGN KEY (`run_id`)       REFERENCES `col_run` (`id`),
  ADD CONSTRAINT `fk_item_execution` FOREIGN KEY (`execution_id`) REFERENCES `col_execution` (`id`);
