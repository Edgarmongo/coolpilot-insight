-- =====================================================================
-- V13 · RBAC 基础表（Batch 2 第一步）
--
-- 与 R3-01 的 V1 基线对齐；过渡期外键指向 `user`（`sys_user` 尚未建）。
-- 现有 `user.role`（0/1）**保留不动**：切换期间做双读比对，
-- 确认 RBAC 判定与原逻辑一致后再移除，避免一刀切导致越权或全员失去权限。
-- =====================================================================

CREATE TABLE `sys_role` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(32)     NOT NULL COMMENT 'ADMIN / USER / REVIEWER',
  `name`        VARCHAR(64)     NOT NULL,
  `builtin`     TINYINT         NOT NULL DEFAULT 0 COMMENT '内置角色不可删除',
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'ENABLED',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';

CREATE TABLE `sys_user_role` (
  `id`        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`   BIGINT UNSIGNED NOT NULL,
  `role_id`   BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_ur_role` (`role_id`),
  CONSTRAINT `fk_ur_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ur_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联';

CREATE TABLE `sys_permission` (
  `id`        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`      VARCHAR(64)     NOT NULL COMMENT '如 system:user:list',
  `name`      VARCHAR(64)     NOT NULL,
  `module`    VARCHAR(32)     NOT NULL COMMENT '所属模块',
  `type`      VARCHAR(16)     NOT NULL DEFAULT 'API' COMMENT 'API / MENU / BUTTON',
  `created_at` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`code`),
  KEY `idx_perm_module` (`module`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限点';

CREATE TABLE `sys_role_permission` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `role_id`       BIGINT UNSIGNED NOT NULL,
  `permission_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`),
  CONSTRAINT `fk_rp_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_rp_perm` FOREIGN KEY (`permission_id`) REFERENCES `sys_permission` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联';

-- ---------------- 种子 ----------------

INSERT INTO `sys_role` (`code`, `name`, `builtin`) VALUES
  ('ADMIN',    '管理员',   1),
  ('USER',     '普通用户', 1),
  ('REVIEWER', '审核员',   1);

-- 权限点：覆盖现有 system 接口 + 后续模块的入口
INSERT INTO `sys_permission` (`code`, `name`, `module`, `type`) VALUES
  ('system:user:list',        '用户列表',    'system', 'API'),
  ('system:user:create',      '创建用户',    'system', 'API'),
  ('system:user:update',      '编辑用户',    'system', 'API'),
  ('system:user:delete',      '删除用户',    'system', 'API'),
  ('system:user:assign-role', '分配角色',    'system', 'API'),
  ('system:role:list',        '角色列表',    'system', 'API'),
  ('system:role:manage',      '角色管理',    'system', 'API'),
  ('system:permission:list',  '权限查看',    'system', 'API'),
  ('system:menu:list',        '菜单查看',    'system', 'API'),
  ('system:menu:manage',      '菜单管理',    'system', 'API'),
  ('system:config:list',      '配置查看',    'system', 'API'),
  ('system:config:update',    '配置修改',    'system', 'API'),
  ('system:dict:manage',      '字典管理',    'system', 'API'),
  ('system:audit:list',       '审计查看',    'system', 'API');

-- ADMIN 拥有全部权限
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT r.`id`, p.`id` FROM `sys_role` r, `sys_permission` p WHERE r.`code` = 'ADMIN';

-- USER 只有只读类
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT r.`id`, p.`id` FROM `sys_role` r, `sys_permission` p
WHERE r.`code` = 'USER'
  AND p.`code` IN ('system:user:list', 'system:menu:list', 'system:config:list');

-- REVIEWER 在 USER 基础上增加审计查看（后续加线索审核）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT r.`id`, p.`id` FROM `sys_role` r, `sys_permission` p
WHERE r.`code` = 'REVIEWER'
  AND p.`code` IN ('system:user:list', 'system:menu:list', 'system:config:list', 'system:audit:list');

-- 存量用户按 user.role 映射到新角色（role=1 → ADMIN，其余 → USER）
INSERT INTO `sys_user_role` (`user_id`, `role_id`)
SELECT u.`id`, r.`id` FROM `user` u
JOIN `sys_role` r ON r.`code` = IF(COALESCE(u.`role`, 0) = 1, 'ADMIN', 'USER')
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_user_role` x WHERE x.`user_id` = u.`id` AND x.`role_id` = r.`id`
);
