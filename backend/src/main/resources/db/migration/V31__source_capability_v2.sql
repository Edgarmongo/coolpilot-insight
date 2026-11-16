-- V31 · ds_source.capability_json：v1 → v2（SOURCE-04-04 §2.2 冻结映射）
--
-- 为什么必须迁移：SOURCE-04-06 之后管理接口只写 v2。若库中旧行继续以 v1 存在，
-- 同一列就同时承载两种结构，"业务声明"这件事就没有唯一表达。
--
-- 可重入：只处理**没有** schemaVersion 的行；重复执行是 no-op。
--
-- 只做整体迁移，不做部分迁移：
--   · sourceCode 不在四个已登记平台内        → 整行保持 v1（不猜 connectorCode）
--   · 任一已出现的 v1 字段不是布尔          → 整行保持 v1（不猜字段含义）
--   · 一个可识别字段都没有                  → 整行保持 v1（不把"读不懂"写成"声明为空"）
-- 保留 v1 的行不会被静默丢弃：运行时 DesiredCapabilityParser 仍按 v1 双读，
-- 读不懂时以 desiredError 暴露具体原因（SOURCE-04-04 §2.6 铁律）。
--
-- 旧状态词 commentStatus / keywordStatus 不迁移（SOURCE-04-04 §2.4）：
-- PENDING 不在 effectiveStatus 八值内，且它本就属于 Observed，不属于 Desired。
--
-- 迁移后人工核对：
--   SELECT id, code, JSON_EXTRACT(capability_json, '$.schemaVersion') FROM ds_source;
-- 期望：每行要么为 "2.0"，要么仍是 v1 但能被 Java 侧明确诊断。

UPDATE `ds_source` s
JOIN (
    SELECT s2.`id`,
           JSON_ARRAYAGG(JSON_OBJECT(
               'sourceCode', s2.`code`,
               'connectorCode', CASE s2.`code`
                   WHEN 'DOUYIN'       THEN 'DOUYIN_PUBLIC'
                   WHEN 'XIAOHONGSHU'  THEN 'XIAOHONGSHU_PUBLIC'
                   WHEN 'KUAISHOU'     THEN 'KUAISHOU_PUBLIC'
                   WHEN 'WEB'          THEN 'WEB_PUBLIC'
               END,
               'code', m.`cap_code`,
               'category', m.`category`,
               'accessModes', CAST(m.`modes` AS JSON),
               'desiredStatus', IF(JSON_UNQUOTE(JSON_EXTRACT(s2.`capability_json`,
                                       CONCAT('$.', m.`v1_field`))) = 'true',
                                   'ENABLED', 'DISABLED')
           )) AS `caps`
    FROM `ds_source` s2
    JOIN (
        SELECT 'comments' AS `v1_field`, 'COMMENT' AS `cap_code`, 'COMMENT' AS `category`,
               '["PUBLIC","AUTHENTICATED"]' AS `modes`
        UNION ALL
        SELECT 'contentDetail', 'CONTENT_DETAIL', 'CONTENT', '["PUBLIC"]'
        UNION ALL
        SELECT 'keywordSearch', 'KEYWORD_SEARCH', 'SEARCH', '["PUBLIC"]'
    ) m
      ON JSON_TYPE(JSON_EXTRACT(s2.`capability_json`, CONCAT('$.', m.`v1_field`))) = 'BOOLEAN'
    WHERE s2.`capability_json` IS NOT NULL
      AND JSON_EXTRACT(s2.`capability_json`, '$.schemaVersion') IS NULL
      AND s2.`code` IN ('DOUYIN', 'XIAOHONGSHU', 'KUAISHOU', 'WEB')
      -- 任何已出现但非布尔的映射字段 → 整行不迁移（fail-closed，不猜字段含义）
      AND (JSON_EXTRACT(s2.`capability_json`, '$.comments') IS NULL
           OR JSON_TYPE(JSON_EXTRACT(s2.`capability_json`, '$.comments')) = 'BOOLEAN')
      AND (JSON_EXTRACT(s2.`capability_json`, '$.contentDetail') IS NULL
           OR JSON_TYPE(JSON_EXTRACT(s2.`capability_json`, '$.contentDetail')) = 'BOOLEAN')
      AND (JSON_EXTRACT(s2.`capability_json`, '$.keywordSearch') IS NULL
           OR JSON_TYPE(JSON_EXTRACT(s2.`capability_json`, '$.keywordSearch')) = 'BOOLEAN')
    GROUP BY s2.`id`, s2.`code`
) x ON x.`id` = s.`id`
SET s.`capability_json` = JSON_OBJECT('schemaVersion', '2.0', 'capabilities', x.`caps`);
