-- V33 · 「数据源能力」菜单上线（SOURCE-04-07）
--
-- 该菜单项在 V25 就建好了，状态是 PLANNED（页面未实现，前端置灰）。
-- 现在页面与后端接口都已就绪：GET/PUT /api/v1/source/sources/{code}/capabilities。
--
-- 注意路径仍是 /sources（V25 已固定），只改状态，不新建菜单——
-- 新建会出现两条同路径菜单，前端按 path 匹配的行为将不可预期。

UPDATE `sys_menu` SET `status` = 'ENABLED', `name` = '数据源能力' WHERE `path` = '/sources';

-- 与 V30 一致：菜单授权与权限点是两条路径，必须同步授予 ADMIN
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id` FROM `sys_role` r JOIN `sys_menu` m
WHERE r.`code` = 'ADMIN' AND m.`path` = '/sources'
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` x WHERE x.role_id = r.id AND x.menu_id = m.id
  );
