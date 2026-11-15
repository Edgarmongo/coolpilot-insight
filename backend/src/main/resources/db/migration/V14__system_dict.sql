-- =====================================================================
-- V14 · 字典管理（Batch 2 第五步）
--
-- 用途：业务状态/分类的单一来源。但**不是所有枚举都进字典**——
-- 代码里参与逻辑判断的固定枚举（如任务状态机）仍用 Java enum，
-- 字典只承载「运营可改、后端不做分支判断」的值（如行业分类、来源渠道）。
-- =====================================================================

CREATE TABLE `sys_dict_type` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `type_code`   VARCHAR(64)     NOT NULL COMMENT '如 industry / source_channel',
  `type_name`   VARCHAR(64)     NOT NULL,
  `remark`      VARCHAR(255)    NULL,
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict_type_code` (`type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典类型';

CREATE TABLE `sys_dict_item` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `type_id`     BIGINT UNSIGNED NOT NULL,
  `item_value`  VARCHAR(64)     NOT NULL,
  `item_label`  VARCHAR(128)    NOT NULL,
  `sort`        INT             NOT NULL DEFAULT 0,
  `status`      VARCHAR(16)     NOT NULL DEFAULT 'ENABLED',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict_item` (`type_id`, `item_value`),
  KEY `idx_dict_item_sort` (`type_id`, `sort`),
  CONSTRAINT `fk_dict_item_type` FOREIGN KEY (`type_id`) REFERENCES `sys_dict_type` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典项';

-- 种子：仅放运营会调整、且代码不做分支判断的分类
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `remark`) VALUES
  ('industry',       '行业分类', '获客分析用的行业标签'),
  ('source_channel', '来源渠道', '线索归因用的渠道');

INSERT INTO `sys_dict_item` (`type_id`, `item_value`, `item_label`, `sort`)
SELECT id, 'it',      'IT / 互联网', 10 FROM `sys_dict_type` WHERE `type_code` = 'industry'
UNION ALL SELECT id, 'fintech',   '金融',      20 FROM `sys_dict_type` WHERE `type_code` = 'industry'
UNION ALL SELECT id, 'manufacture','制造业',    30 FROM `sys_dict_type` WHERE `type_code` = 'industry'
UNION ALL SELECT id, 'education', '教育培训',   40 FROM `sys_dict_type` WHERE `type_code` = 'industry'
UNION ALL SELECT id, 'douyin',    '抖音',      10 FROM `sys_dict_type` WHERE `type_code` = 'source_channel'
UNION ALL SELECT id, 'xhs',       '小红书',    20 FROM `sys_dict_type` WHERE `type_code` = 'source_channel'
UNION ALL SELECT id, 'manual',    '手动录入',  90 FROM `sys_dict_type` WHERE `type_code` = 'source_channel';
