-- V30 · 补齐平台账号权限码
--
-- 前端按钮已使用 source:account:* 判定，但 sys_permission 里没有这两条，
-- 导致非管理员永远看不到按钮（只能靠管理员兜底）。此处补齐并授予 ADMIN。

INSERT INTO `sys_permission` (`code`, `name`, `module`, `type`)
SELECT 'source:account:list', '账号列表', 'source', 'API' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `code` = 'source:account:list');

INSERT INTO `sys_permission` (`code`, `name`, `module`, `type`)
SELECT 'source:account:manage', '账号管理', 'source', 'API' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `code` = 'source:account:manage');

-- 授予 ADMIN（内置管理员角色）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT r.`id`, p.`id` FROM `sys_role` r JOIN `sys_permission` p
WHERE r.`code` = 'ADMIN'
  AND p.`code` IN ('source:account:list', 'source:account:manage')
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_permission` x WHERE x.role_id = r.id AND x.permission_id = p.id
  );

-- 同步角色-菜单授权：菜单授权与权限点两条路径保持一致
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`id`, m.`id` FROM `sys_role` r JOIN `sys_menu` m
WHERE r.`code` = 'ADMIN' AND m.`path` = '/accounts'
  AND NOT EXISTS (SELECT 1 FROM `sys_role_menu` x WHERE x.role_id = r.id AND x.menu_id = m.id);
