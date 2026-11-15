-- V25 · 固定「数据采集」五项菜单
-- 顺序：数据源管理 / 平台账号 / 关键词管理 / 采集任务 / 采集记录
-- 未实现的页面标 PLANNED（前端置灰不可点），避免点了空白。

-- 1) 任务中心 → 采集记录（路由不变，仅显示名与排序；该页已含任务列表+日志）
UPDATE `sys_menu` SET `name` = '采集记录', `sort` = 5
WHERE `path` = '/tasks';

-- 2) 新增 数据源管理 / 平台账号（PLANNED：后端接口与页面未完成）
INSERT INTO `sys_menu` (`parent_id`, `name`, `path`, `menu_type`, `sort`, `status`)
SELECT p.`id`, '数据源管理', '/sources', 'PAGE', 1, 'PLANNED'
FROM `sys_menu` p WHERE p.`name` = '数据采集' AND p.`parent_id` = 0
  AND NOT EXISTS (SELECT 1 FROM `sys_menu` x WHERE x.`path` = '/sources');

INSERT INTO `sys_menu` (`parent_id`, `name`, `path`, `menu_type`, `sort`, `status`)
SELECT p.`id`, '平台账号', '/accounts', 'PAGE', 2, 'PLANNED'
FROM `sys_menu` p WHERE p.`name` = '数据采集' AND p.`parent_id` = 0
  AND NOT EXISTS (SELECT 1 FROM `sys_menu` x WHERE x.`path` = '/accounts');

-- 3) 关键词管理 → 排序 3；采集任务 → 排序 4
UPDATE `sys_menu` SET `sort` = 3 WHERE `path` = '/keywords';
UPDATE `sys_menu` SET `sort` = 4 WHERE `path` = '/collect';

-- 4) 数据清洗页暂不在五项内：隐藏菜单但保留数据（页面仍可通过路由访问）
UPDATE `sys_menu` SET `visible` = 0 WHERE `path` = '/clean';
