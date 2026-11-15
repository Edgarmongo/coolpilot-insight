-- V26 · 固定完整信息架构（六大分组 + 二级菜单）
-- 只重建二级菜单（parent_id<>0），分组与角色授权不受影响。
-- 已实现页面 ENABLED，未实现 PLANNED（前端置灰）。

DELETE FROM `sys_menu` WHERE `parent_id` <> 0;

-- 工作台
INSERT INTO `sys_menu` (parent_id,name,path,menu_type,permission_code,sort,status)
SELECT p.id,'获客驾驶舱','/dashboard','PAGE',NULL,1,'ENABLED' FROM sys_menu p WHERE p.name='工作台' AND p.parent_id=0
UNION ALL SELECT p.id,'今日需求','/today','PAGE',NULL,2,'PLANNED' FROM sys_menu p WHERE p.name='工作台' AND p.parent_id=0
UNION ALL SELECT p.id,'待处理事项','/review','PAGE',NULL,3,'ENABLED' FROM sys_menu p WHERE p.name='工作台' AND p.parent_id=0;

-- 数据采集
INSERT INTO `sys_menu` (parent_id,name,path,menu_type,permission_code,sort,status)
SELECT p.id,'数据源管理','/sources','PAGE',NULL,1,'PLANNED' FROM sys_menu p WHERE p.name='数据采集' AND p.parent_id=0
UNION ALL SELECT p.id,'平台账号','/accounts','PAGE',NULL,2,'PLANNED' FROM sys_menu p WHERE p.name='数据采集' AND p.parent_id=0
UNION ALL SELECT p.id,'关键词管理','/keywords','PAGE',NULL,3,'ENABLED' FROM sys_menu p WHERE p.name='数据采集' AND p.parent_id=0
UNION ALL SELECT p.id,'采集任务','/collect','PAGE',NULL,4,'ENABLED' FROM sys_menu p WHERE p.name='数据采集' AND p.parent_id=0
UNION ALL SELECT p.id,'采集记录','/tasks','PAGE',NULL,5,'ENABLED' FROM sys_menu p WHERE p.name='数据采集' AND p.parent_id=0;

-- 内容洞察
INSERT INTO `sys_menu` (parent_id,name,path,menu_type,permission_code,sort,status)
SELECT p.id,'内容库','/overview','PAGE',NULL,1,'ENABLED' FROM sys_menu p WHERE p.name='内容洞察' AND p.parent_id=0
UNION ALL SELECT p.id,'评论库','/comments','PAGE',NULL,2,'ENABLED' FROM sys_menu p WHERE p.name='内容洞察' AND p.parent_id=0
UNION ALL SELECT p.id,'用户公开资料','/authors','PAGE',NULL,3,'PLANNED' FROM sys_menu p WHERE p.name='内容洞察' AND p.parent_id=0
UNION ALL SELECT p.id,'需求信号','/signals','PAGE',NULL,4,'PLANNED' FROM sys_menu p WHERE p.name='内容洞察' AND p.parent_id=0
UNION ALL SELECT p.id,'热点趋势','/hot-trends','PAGE',NULL,5,'PLANNED' FROM sys_menu p WHERE p.name='内容洞察' AND p.parent_id=0;

-- AI 分析
INSERT INTO `sys_menu` (parent_id,name,path,menu_type,permission_code,sort,status)
SELECT p.id,'分析任务','/insight','PAGE',NULL,1,'ENABLED' FROM sys_menu p WHERE p.name='AI 分析' AND p.parent_id=0
UNION ALL SELECT p.id,'需求分类','/analysis/categories','PAGE',NULL,2,'PLANNED' FROM sys_menu p WHERE p.name='AI 分析' AND p.parent_id=0
UNION ALL SELECT p.id,'意向分析','/analysis/intent','PAGE',NULL,3,'PLANNED' FROM sys_menu p WHERE p.name='AI 分析' AND p.parent_id=0
UNION ALL SELECT p.id,'线索评分','/analysis/lead-score','PAGE',NULL,4,'PLANNED' FROM sys_menu p WHERE p.name='AI 分析' AND p.parent_id=0
UNION ALL SELECT p.id,'产品匹配','/analysis/product-match','PAGE',NULL,5,'PLANNED' FROM sys_menu p WHERE p.name='AI 分析' AND p.parent_id=0
UNION ALL SELECT p.id,'分析规则','/analysis/rules','PAGE',NULL,6,'PLANNED' FROM sys_menu p WHERE p.name='AI 分析' AND p.parent_id=0;

-- 智能获客
INSERT INTO `sys_menu` (parent_id,name,path,menu_type,permission_code,sort,status)
SELECT p.id,'线索池','/leads','PAGE',NULL,1,'PLANNED' FROM sys_menu p WHERE p.name='智能获客' AND p.parent_id=0
UNION ALL SELECT p.id,'线索审核','/lead-review','PAGE',NULL,2,'PLANNED' FROM sys_menu p WHERE p.name='智能获客' AND p.parent_id=0
UNION ALL SELECT p.id,'跟进记录','/followups','PAGE',NULL,3,'PLANNED' FROM sys_menu p WHERE p.name='智能获客' AND p.parent_id=0
UNION ALL SELECT p.id,'转化记录','/conversions','PAGE',NULL,4,'PLANNED' FROM sys_menu p WHERE p.name='智能获客' AND p.parent_id=0;

-- 数据分析
INSERT INTO `sys_menu` (parent_id,name,path,menu_type,permission_code,sort,status)
SELECT p.id,'分析报告','/reports','PAGE',NULL,1,'ENABLED' FROM sys_menu p WHERE p.name='数据分析' AND p.parent_id=0
UNION ALL SELECT p.id,'渠道分析','/channels','PAGE',NULL,2,'PLANNED' FROM sys_menu p WHERE p.name='数据分析' AND p.parent_id=0
UNION ALL SELECT p.id,'需求趋势','/demand-trends','PAGE',NULL,3,'PLANNED' FROM sys_menu p WHERE p.name='数据分析' AND p.parent_id=0
UNION ALL SELECT p.id,'产品机会','/opportunities','PAGE',NULL,4,'PLANNED' FROM sys_menu p WHERE p.name='数据分析' AND p.parent_id=0
UNION ALL SELECT p.id,'获客效果','/acquisition','PAGE',NULL,5,'PLANNED' FROM sys_menu p WHERE p.name='数据分析' AND p.parent_id=0;

-- 系统管理（保留原五项 + 数据清洗仍隐藏：不在新 IA 中）
UPDATE `sys_menu` SET `visible` = 0 WHERE `path` = '/clean';
