-- =====================================================================
-- V6 · 种子数据
-- 管理员账户使用哨兵密码：库泄露也无法登录（ADR-005 / U7 方案 A）
-- 真实 BCrypt 由应用启动引导写入（AdminBootstrap），需 ADMIN_INIT_PASSWORD 环境变量
-- =====================================================================

INSERT INTO `user` (`username`, `phone`, `password_hash`, `role`, `status`, `must_change_pwd`)
VALUES
  ('管理员', '00000000001', '!SENTINEL_NO_LOGIN!', 1, 1, 1),
  ('管理员', '00000000002', '!SENTINEL_NO_LOGIN!', 1, 1, 1);

-- 默认 AI 版本档案：prompt/schema/taxonomy 三版本号共同定义一次分析口径
INSERT INTO `analysis_profile`
  (`profile_key`, `provider`, `model_name`, `prompt_version`, `schema_version`, `taxonomy_version`,
   `params_json`, `params_hash`, `norm_version`, `status`, `description`)
VALUES (
  'ap_default_v1',
  'deepseek',
  'deepseek-chat',
  'v1',
  'v1',
  'v1',
  JSON_OBJECT('temperature', 0.2, 'max_tokens', 2048),
  SHA2('{"max_tokens":2048,"temperature":0.2}', 256),
  'norm-v1',
  1,
  '默认评论级结构化分析档案'
);

-- 规则情感基线档案：清洗入库时写入，作为 AI 分析之前的默认情感标签
INSERT INTO `analysis_profile`
  (`profile_key`, `provider`, `model_name`, `prompt_version`, `schema_version`, `taxonomy_version`,
   `params_json`, `params_hash`, `norm_version`, `status`, `description`)
VALUES (
  'ap_ruleset_v1',
  'local',
  'snownlp',
  'ruleset-v1',
  'v1',
  'v1',
  JSON_OBJECT('threshold_positive', 0.6, 'threshold_negative', 0.4),
  SHA2('{"threshold_negative":0.4,"threshold_positive":0.6}', 256),
  'norm-v1',
  1,
  '清洗阶段的规则情感基线（非 LLM）'
);

-- 系统配置（成本护栏与采集上限）
INSERT INTO `system_config` (`config_key`, `config_value`, `value_type`, `is_secret`, `description`) VALUES
  ('ai.daily_token_budget', '2000000', 'INT', 0, '单日 Token 总预算'),
  ('ai.single_task_token_budget', '200000', 'INT', 0, '单任务 Token 上限'),
  ('ai.max_concurrency', '3', 'INT', 0, 'AI 并发上限（进程内信号量）'),
  ('ai.price_version', 'deepseek-2026-01', 'STRING', 0, '计价版本标识'),
  ('ai.input_price_per_m', '2.0', 'DECIMAL', 0, '输入价格 元/百万 token'),
  ('ai.output_price_per_m', '8.0', 'DECIMAL', 0, '输出价格 元/百万 token'),
  ('collect.max_comments_per_video', '1000', 'INT', 0, '单视频评论采集上限'),
  ('collect.default_max_comments', '300', 'INT', 0, '默认采集条数'),
  ('security.max_upload_mb', '20', 'INT', 0, '单文件上传上限');
