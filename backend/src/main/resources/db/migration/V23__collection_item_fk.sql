-- V23 · item.run_id 外键（回填后创建）
--
-- 拆分原则：**一个 Migration 只加一个约束**。
-- 若两个约束写在同一个文件里，第一条成功第二条失败时，Flyway 标记该版本失败，
-- repair 后重跑会因第一条已存在而再次失败——无法自动恢复。
-- 一个文件一个约束后，失败的那条必然尚未生效，重跑即可。

ALTER TABLE `collection_task_item`
  ADD CONSTRAINT `fk_item_run` FOREIGN KEY (`run_id`) REFERENCES `col_run` (`id`);
