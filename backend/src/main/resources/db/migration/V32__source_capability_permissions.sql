-- V32 · 数据源能力配置权限码（SOURCE-04-06）
--
-- 读写分离：只读三态明细与写 Desired 是两个权限点。
-- 三态明细含 Observed（运行时实际加载了什么）与 Effective（准入结论），
-- 属于排障信息，普通运营可读；写 Desired 会直接改变"业务声明"，
-- 必须单独授权，否则任何能看页面的人都能把能力关掉。

INSERT INTO `sys_permission` (`code`, `name`, `module`, `type`)
SELECT 'source:capability:view', '能力三态查看', 'source', 'API' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `code` = 'source:capability:view');

INSERT INTO `sys_permission` (`code`, `name`, `module`, `type`)
SELECT 'source:capability:manage', '能力声明配置', 'source', 'API' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `code` = 'source:capability:manage');

-- 授予 ADMIN（内置管理员角色）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT r.`id`, p.`id` FROM `sys_role` r JOIN `sys_permission` p
WHERE r.`code` = 'ADMIN'
  AND p.`code` IN ('source:capability:view', 'source:capability:manage')
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_permission` x WHERE x.role_id = r.id AND x.permission_id = p.id
  );
