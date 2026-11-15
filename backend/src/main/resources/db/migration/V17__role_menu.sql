-- =====================================================================
-- V17 · 角色-菜单授权（B 方案第 1 步）
--
-- 目的：支撑「角色分配菜单树」交互（若依形态）。
-- 权限来源合一规则：
--   用户权限集合 = role_menu 所授菜单的 permission_code
--                ∪ role_permission 里不属于任何菜单的权限
--
-- 回填：按现有 sys_role_permission 反查 permission_code 对应的菜单行，
-- 保证切换前后权限完全一致（不新增也不减少任何授权）。
--
-- permission_code 为空的菜单表示「登录可见」，不写入 role_menu——
-- 它们对所有登录用户可见，写进去反而会让新角色默认拿不到。
-- =====================================================================

CREATE TABLE `sys_role_menu` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_id`    BIGINT UNSIGNED NOT NULL,
  `menu_id`    BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`),
  KEY `idx_rm_menu` (`menu_id`),
  CONSTRAINT `fk_rm_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_rm_menu` FOREIGN KEY (`menu_id`) REFERENCES `sys_menu` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色菜单授权';

-- 回填：角色已有权限 → 对应菜单
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT DISTINCT rp.`role_id`, m.`id`
FROM `sys_role_permission` rp
JOIN `sys_permission` p ON p.`id` = rp.`permission_id`
JOIN `sys_menu` m ON m.`permission_code` = p.`code`
WHERE m.`permission_code` IS NOT NULL AND m.`permission_code` <> ''
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` x WHERE x.`role_id` = rp.`role_id` AND x.`menu_id` = m.`id`
  );
