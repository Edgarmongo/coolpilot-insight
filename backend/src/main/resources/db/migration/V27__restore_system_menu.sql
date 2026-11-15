-- V27 · 恢复系统管理二级菜单
-- V26 用 DELETE ... WHERE parent_id <> 0 重建业务菜单时，误删了系统管理下的六项
-- （它们同样是二级节点）。此处按原定义补回。

INSERT INTO `sys_menu` (parent_id,name,path,menu_type,permission_code,sort,status)
SELECT p.id,'用户与权限','/admin/users','PAGE','system:user:list',1,'ENABLED' FROM sys_menu p WHERE p.name='系统管理' AND p.parent_id=0
UNION ALL SELECT p.id,'角色管理','/system/roles','PAGE','system:role:list',2,'ENABLED' FROM sys_menu p WHERE p.name='系统管理' AND p.parent_id=0
UNION ALL SELECT p.id,'字典管理','/system/dicts','PAGE','system:dict:manage',3,'ENABLED' FROM sys_menu p WHERE p.name='系统管理' AND p.parent_id=0
UNION ALL SELECT p.id,'操作审计','/system/audits','PAGE','system:audit:list',4,'ENABLED' FROM sys_menu p WHERE p.name='系统管理' AND p.parent_id=0
UNION ALL SELECT p.id,'系统配置','/system/configs','PAGE','system:config:list',5,'ENABLED' FROM sys_menu p WHERE p.name='系统管理' AND p.parent_id=0
UNION ALL SELECT p.id,'菜单管理','/system/menus','PAGE','system:menu:list',6,'ENABLED' FROM sys_menu p WHERE p.name='系统管理' AND p.parent_id=0;
