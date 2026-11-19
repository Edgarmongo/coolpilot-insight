-- =====================================================================
-- V15 · 菜单管理（Batch 2 第六步）
--
-- 目的：替代前端 src/config/menu.js 里硬编码的 34 项。
-- 菜单只决定「显示与导航」，权限判定仍由 @RequirePermission 在后端执行——
-- 前端隐藏菜单不等于禁止调用接口。
--
-- parent_id = 0 表示一级菜单；permission_code 为空的菜单所有登录用户可见。
-- =====================================================================

CREATE TABLE `sys_menu` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `parent_id`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `name`            VARCHAR(64)     NOT NULL,
  `path`            VARCHAR(128)    NULL COMMENT '前端路由，分组可为空',
  `icon`            VARCHAR(64)     NULL,
  `menu_type`       VARCHAR(16)     NOT NULL DEFAULT 'PAGE' COMMENT 'GROUP / PAGE / ACTION',
  `permission_code` VARCHAR(64)     NULL COMMENT '有值时需该权限才显示',
  `sort`            INT             NOT NULL DEFAULT 0,
  `visible`         TINYINT         NOT NULL DEFAULT 1,
  `status`          VARCHAR(16)     NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / PLANNED',
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_menu_parent` (`parent_id`, `sort`),
  KEY `idx_menu_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜单';

-- 一级分组（对应现有 7 组信息架构）
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `path`, `icon`, `menu_type`, `sort`) VALUES
  (1, 0, '工作台',     NULL, 'HomeFilled',   'GROUP', 10),
  (2, 0, '数据采集',   NULL, 'Download',     'GROUP', 20),
  (3, 0, '内容洞察',   NULL, 'ChatDotRound', 'GROUP', 30),
  (4, 0, 'AI 分析',    NULL, 'MagicStick',   'GROUP', 40),
  (5, 0, '智能获客',   NULL, 'Aim',          'GROUP', 50),
  (6, 0, '数据分析',   NULL, 'DataLine',     'GROUP', 60),
  (7, 0, '系统管理',   NULL, 'Setting',      'GROUP', 70);

-- 二级页面（已实现的 12 项，status=ENABLED；未实现的标 PLANNED 供前端禁用展示）
INSERT INTO `sys_menu` (`parent_id`, `name`, `path`, `menu_type`, `permission_code`, `sort`, `status`) VALUES
  (1, '获客驾驶舱', '/dashboard',       'PAGE', NULL, 1, 'ENABLED'),
  (1, '待处理事项', '/review',          'PAGE', NULL, 2, 'ENABLED'),
  (2, '关键词管理', '/keywords',        'PAGE', NULL, 1, 'ENABLED'),
  (2, '采集任务',   '/collect',         'PAGE', NULL, 2, 'ENABLED'),
  (2, '任务中心',   '/tasks',           'PAGE', NULL, 3, 'ENABLED'),
  (2, '数据清洗',   '/clean',           'PAGE', NULL, 4, 'ENABLED'),
  (3, '内容库',     '/overview',        'PAGE', NULL, 1, 'ENABLED'),
  (3, '评论库',     '/comments',        'PAGE', NULL, 2, 'ENABLED'),
  (4, '分析任务',   '/insight',         'PAGE', NULL, 1, 'ENABLED'),
  (6, '分析报告',   '/reports',         'PAGE', NULL, 1, 'ENABLED'),
  (7, '用户与权限', '/admin/users',     'PAGE', 'system:user:list', 1, 'ENABLED'),
  (7, '角色管理',   '/system/roles',    'PAGE', 'system:role:list', 2, 'ENABLED'),
  (7, '字典管理',   '/system/dicts',    'PAGE', 'system:dict:manage', 3, 'ENABLED'),
  (7, '操作审计',   '/system/audits',   'PAGE', 'system:audit:list', 4, 'ENABLED'),
  (7, '系统配置',   '/system/configs',  'PAGE', 'system:config:list', 5, 'ENABLED');

-- 规划中（前端显示但置灰）
INSERT INTO `sys_menu` (`parent_id`, `name`, `path`, `menu_type`, `sort`, `status`) VALUES
  (3, '需求信号', '/signals',   'PAGE', 3, 'PLANNED'),
  (4, '线索评分', '/lead-score','PAGE', 2, 'PLANNED'),
  (5, '线索池',   '/leads',     'PAGE', 1, 'PLANNED'),
  (5, '跟进记录', '/followups', 'PAGE', 2, 'PLANNED'),
  (6, '渠道分析', '/channels',  'PAGE', 2, 'PLANNED');
