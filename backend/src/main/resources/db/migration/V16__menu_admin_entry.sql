-- =====================================================================
-- V16 · 补「菜单管理」入口
--
-- V15 建菜单表时漏了这一项：页面存在但导航里没有入口，等于不可达。
-- 用 NOT EXISTS 保证重复执行不产生重复行。
-- =====================================================================

INSERT INTO `sys_menu` (`parent_id`, `name`, `path`, `menu_type`, `permission_code`, `sort`, `status`)
SELECT p.`id`, '菜单管理', '/system/menus', 'PAGE', 'system:menu:list', 6, 'ENABLED'
FROM `sys_menu` p
WHERE p.`name` = '系统管理' AND p.`parent_id` = 0
  AND NOT EXISTS (SELECT 1 FROM `sys_menu` x WHERE x.`path` = '/system/menus');
