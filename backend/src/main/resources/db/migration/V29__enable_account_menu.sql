-- V29 · 「平台账号」菜单上线（页面已实现）
-- 后端 6 个接口 + 前端页面均已完成，PLANNED → ENABLED。

UPDATE `sys_menu` SET `status` = 'ENABLED' WHERE `path` = '/accounts';
