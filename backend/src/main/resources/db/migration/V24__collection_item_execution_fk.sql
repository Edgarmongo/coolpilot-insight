-- V24 · item.execution_id 外键（回填后创建）
-- 见 V23 注释：一个文件只加一个约束，保证中断后可重试。

ALTER TABLE `collection_task_item`
  ADD CONSTRAINT `fk_item_execution` FOREIGN KEY (`execution_id`) REFERENCES `col_execution` (`id`);
