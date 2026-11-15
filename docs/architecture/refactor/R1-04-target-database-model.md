# R1-04 · 目标数据库模型

设计依据：R0-02 能力清单、R0-03 现状结构、R1-02 模块职责、R1-03 依赖矩阵。
**命名规约**：`sys_` system · `ds_` source · `col_` collection · `ct_` content · `an_` analysis · `ld_` lead · `cv_` conversion · `ag_` analytics

> 本文档给出**结构级设计**（表 / 主键 / 关键列 / 唯一键 / 索引 / 外键 / 归属模块）。
> 逐列类型、默认值与完整 DDL 在 R3 的 Flyway `V1__init.sql` 中定稿，并回填本节。
> 之所以不在此处写死类型，是为了避免与 R3 实际脚本产生两份互相矛盾的"真相"。

## 1. 表清单（37 张）

| 模块 | 表 | 用途 | 首期 |
|---|---|---|---|
| system | `sys_user` | 账号 | 必须 |
| system | `sys_role` | 角色 | 必须 |
| system | `sys_user_role` | 用户-角色 | 必须 |
| system | `sys_config` | 系统配置 | 必须 |
| system | `sys_audit_log` | 操作审计 | 必须 |
| system | `sys_file` | 文件资产 | 必须（报告/原始归档） |
| source | `ds_source` | 数据源定义 | 必须 |
| source | `ds_account` | 平台账号/授权 | P1（首期仅抖音单账号可预置一行） |
| source | `ds_keyword` | 关键词与发现策略 | 必须 |
| collection | `col_task` | 采集任务 | 必须 |
| collection | `col_task_item` | 任务项 | 必须 |
| collection | `col_task_log` | 执行日志 | 必须 |
| collection | `col_checkpoint` | 断点 | 必须 |
| content | `ct_raw_archive` | **原始响应归档** | 必须 |
| content | `ct_content` | **标准化内容** | 必须 |
| content | `ct_comment` | 评论 | 必须 |
| content | `ct_author` | 作者公开标识 | 必须 |
| content | `ct_grant` | 内容读授权 | P1（原 `video_grant`） |
| analysis | `an_profile` | 分析档案（版本化） | 必须 |
| analysis | `an_task` | 分析任务 | 必须 |
| analysis | `an_task_item` | 任务项 | 必须 |
| analysis | `an_result` | 评论级结果 | 必须 |
| analysis | `an_insight` | 视频级洞察 | 必须 |
| analysis | `an_evidence` | 证据链 | 必须 |
| analysis | `an_review` | AI 结果人工复核 | 必须 |
| analysis | `an_usage` | AI 用量与成本 | 必须 |
| lead | `ld_signal` | 需求信号 | 必须 |
| lead | `ld_lead` | 线索 | 必须 |
| lead | `ld_evidence` | 线索证据 | 必须 |
| lead | `ld_score_history` | 评分历史 | 必须 |
| lead | `ld_review` | 线索审核 | 必须 |
| lead | `ld_followup` | 跟进记录 | P1 |
| conversion | `cv_product` | 产品镜像（CoolSkyAI） | P1 |
| conversion | `cv_match` | 线索-产品匹配 | P1 |
| conversion | `cv_conversion` | 成交与归因 | P1 |
| analytics | `ag_report` | 报告 | 必须 |
| analytics | `ag_report_version` | 报告版本 | 必须 |

## 2. 公共字段规范

所有业务表包含：

| 列 | 说明 |
|---|---|
| `id` | `BIGINT UNSIGNED AUTO_INCREMENT` 主键 |
| `created_at` | 创建时间，`DEFAULT CURRENT_TIMESTAMP` |
| `updated_at` | 更新时间，`ON UPDATE CURRENT_TIMESTAMP` |
| `created_by` / `updated_by` | 操作人 user_id，可空（系统任务为空） |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 0`，首期恒 0 |
| `deleted_at` | 仅需要保留痕迹的表（`sys_user`、`ld_lead`、`ag_report`） |

状态字段统一 `VARCHAR(24)` + 应用枚举（不用 MySQL ENUM）。金额 `DECIMAL(12,4)`。

## 3. 核心表设计

### 3.1 `sys_user`

| 列 | 类型 | 约束 |
|---|---|---|
| id | BIGINT UNSIGNED | PK |
| username | VARCHAR(64) | NOT NULL |
| phone | VARCHAR(20) | NOT NULL, **UK** |
| password_hash | VARCHAR(100) | NOT NULL（BCrypt；哨兵值 `!SENTINEL_NO_LOGIN!` 表示未初始化） |
| status | TINYINT | NOT NULL DEFAULT 1（1 正常 / 0 禁用） |
| must_change_pwd | TINYINT | NOT NULL DEFAULT 1 |
| last_login_at | DATETIME | NULL |
| 公共字段 | — | 含 `deleted_at` |

### 3.2 `ds_source`

| 列 | 约束 |
|---|---|
| id | PK |
| code | VARCHAR(32) NOT NULL **UK**（DOUYIN/XHS/KUAISHOU/WEB） |
| name / source_type | NOT NULL |
| adapter_code | VARCHAR(64)，对应 collection-service 的适配器 |
| status | `ENABLED/DISABLED` |
| capability_json | JSON：`{comments:true, keyword_search:false, status:"LIMITED"}` |

**设计意图**：能力状态入库而非写死在代码，前端据此禁用不可用功能。

### 3.3 `col_task` / `col_task_item`

| 表 | 关键列 | 约束 |
|---|---|---|
| `col_task` | task_no, task_type, source_id, status, priority, owner_user_id, total/success/failed_items, start_at, end_at | UK(task_no)；idx(status, id)；FK(source_id) |
| `col_task_item` | task_id, source_id, external_content_id, content_type, status, retry_count, max_retry, requested_max, collected_count, **active_flag**, error_code, error_message | FK(task_id)；**UK(source_id, external_content_id, active_flag)**；idx(status) |

**关键决策**：`col_task_item` **不 FK 到 `ct_content`**。
采集先于内容入库，且 content 依赖 collection（反向禁止），故任务项只存平台侧标识，
由 `content` 的 ingest 负责解析/创建内容记录。`active_flag` 沿用「终态置 NULL 释放唯一键」的既有机制。

### 3.4 `ct_raw_archive` 与 `ct_content`（原始 vs 标准化分离）

| 表 | 职责 | 关键列 |
|---|---|---|
| `ct_raw_archive` | 保存平台**原始响应**，用于溯源与重放 | source_id, external_id, content_type, payload(JSON/MEDIUMTEXT), payload_hash, collected_at, **UK(source_id, external_id, payload_hash)** |
| `ct_content` | **标准化后**的统一内容 | source_id, external_content_id, content_type, title, content_text, url, author_id, published_at, normalize_status, raw_archive_id, content_hash |

**为什么分开**：现有 `video` 表同时承担原始与标准化职责，导致"平台字段变更"与"业务字段变更"耦合；
分开后 `ct_raw_archive` 可无限追加（幂等哈希去重），`ct_content` 只保留业务需要的字段。
`normalize_status`：`RAW / NORMALIZED / FAILED`。

### 3.5 `ct_comment`（去重策略）

| 列 | 约束 |
|---|---|
| content_id | FK → ct_content，NOT NULL |
| source_id | NOT NULL |
| external_comment_id | NULL 允许（平台可能不返回） |
| parent_external_id | NULL |
| dedup_key | VARCHAR(64) **NOT NULL**（无 cid 时用指纹） |
| dedup_strategy | `CID / FINGERPRINT` |
| text, nickname, likes, ip_region, published_at | NOT NULL 视含义 |

**唯一键**：`UK(source_id, external_comment_id)` + `UK(dedup_key)`（与现状双键设计一致，已实测幂等）。
**索引**：`idx(content_id, published_at)`。

### 3.6 `an_profile`（版本化，重点）

| 列 | 约束 | 说明 |
|---|---|---|
| profile_code | VARCHAR(64) | 逻辑名，如 `java-ai` |
| profile_version | INT | 单调递增 |
| taxonomy_version | VARCHAR(32) | 需求分类体系版本 |
| prompt_version | VARCHAR(32) | Prompt 模板版本 |
| model_config | VARCHAR(128) | 模型标识，如 `deepseek-chat` |
| score_config_json | JSON | 五维权重 |
| status | `ENABLED / DISABLED` | 同一 code 仅一个 ENABLED |

**唯一键**：`UK(profile_code, profile_version)`。

**生命周期**：新建 version+1（不回改旧行）→ 置新行 ENABLED、旧行 DISABLED → 新任务引用新 id。
**可追溯**：`an_result`/`an_insight` 均存 `profile_id`（具体版本），并可冗余 `prompt_version`/`model_name` 快照，
保证"这条结论是用哪套规则算出来的"永远可查。

### 3.7 `an_result` / `an_insight` / `an_evidence`

| 表 | 关键列 | 约束 |
|---|---|---|
| `an_result` | comment_id, content_id, profile_id, demand_category, technology_tags_json, intent_type, buying_stage, intent_score, confidence, result_json, model_name, prompt_version | **UK(comment_id, profile_id)**；FK(comment_id)、FK(profile_id) |
| `an_insight` | content_id, profile_id, result_json, model_name, prompt_version | idx(content_id, id) |
| `an_evidence` | result_id(NULL), insight_id(NULL), source_level(COMMENT/INSIGHT), evidence_text, reason | idx(result_id)、idx(insight_id)；**CHECK：两 id 至少一个非空** |

### 3.8 `ld_signal` → `ld_lead` → `ld_evidence`

| 表 | 关键列 | 约束 |
|---|---|---|
| `ld_signal` | result_id, signal_type, demand_description, product_category, intent_level, status | **UK(result_id, signal_type)**（幂等） |
| `ld_lead` | lead_code, lead_type, source_author_id, demand_category, technology_tags_json, buying_stage, lead_score, confidence, status, review_status, first_seen_at, last_seen_at, assigned_user_id | **UK(lead_code)**；idx(status, lead_score)；含 `deleted_at` |
| `ld_evidence` | lead_id, signal_id, result_id, evidence_text, source_url, collected_at | FK(lead_id)；idx(signal_id) |
| `ld_score_history` | lead_id, profile_id, old_score, new_score, score_detail_json(五维), created_at | idx(lead_id, created_at) |
| `ld_review` | lead_id, reviewer_user_id, review_result, review_reason, reviewed_at | idx(lead_id) |
| `ld_followup` | lead_id, followup_type, content, contact_channel, consent_status, result, next_followup_at | idx(lead_id, created_at) |

**支持"同一作者多条需求信号"**：`ld_lead` 与 `ld_signal` 通过 `ld_evidence` 多对多关联；
一条线索可聚合多条信号与多条证据，一个作者可产生多条线索（不同需求类别/时间窗）。

### 3.9 `cv_product` / `cv_conversion`（CoolSkyAI 边界）

| 表 | 只存什么 | 明确不存 |
|---|---|---|
| `cv_product` | external_product_id(**UK**)、名称、类型、URL、状态、synced_at | 价格、库存、上下架规则（以 CoolSkyAI 为准） |
| `cv_match` | lead_id、product_id、match_score、reason | — |
| `cv_conversion` | lead_id(**UK**)、product_id、external_order_id、amount(快照)、currency、converted_at、attribution_source | 订单明细、支付流水、退款账本 |

**归因**：`attribution_source` 记录线索最初来源（source_code + keyword + 内容 id），用于渠道效果统计。

## 4. 租户与数据所有者

- 首期**单租户**：所有业务表 `tenant_id DEFAULT 0`，唯一键暂不含 `tenant_id`
- 数据所有者：`sys_user`（账号）、`ds_keyword.owner_user_id`、`col_task.owner_user_id`、
  `an_task.owner_user_id`、`ld_lead.assigned_user_id`
- 演进方式：商业化时把 `tenant_id` 加入唯一键与所有查询条件，不改表名与语义

## 5. 与现有库的映射

| 现有表 | 目标表 | 变化 |
|---|---|---|
| `user` | `sys_user` | 加审计人、tenant_id |
| `system_config` | `sys_config` | 加审计字段 |
| `audit_log` | `sys_audit_log` | 对齐列名 |
| `keyword` / `keyword_video` | `ds_keyword`（`keyword_video` 并入 `ct_content` 关联表或 `ds_keyword_content`） | 拆分与合并 |
| `video` / `comment` | `ct_content` / `ct_comment`（+`ct_raw_archive`） | **原始与标准化分离** |
| `video_grant` | `ct_grant` | 改名，语义不变 |
| `discovery_task` / `_item` | `col_task`（task_type=DISCOVERY） / `col_task_item` | 合并，减少两类任务表 |
| `collection_*` 4 表 | `col_*` 4 表 | 改名 + 去掉对 content 的 FK |
| `analysis_profile` | `an_profile` | **加版本、taxonomy/prompt/model 字段** |
| `analysis_task*` | `an_task*` | 改名 |
| `comment_analysis` / `video_insight` / `analysis_evidence` / `analysis_review` / `usage_record` | `an_result` / `an_insight` / `an_evidence` / `an_review` / `an_usage` | 改名 + 补版本快照 |
| `report` / `report_version` | `ag_report` / `ag_report_version` | 改名 |
| `file_asset` | `sys_file` | 重新设计（当前空壳） |
| — | `ld_*`、`cv_*`、`ds_source`、`ds_account` | **全新** |

## 6. 演进规范

1. 所有 DDL 只经 Flyway，`V1__init.sql` 为全新基线（R3 生成）
2. 禁止修改已应用的迁移文件（`validate-on-migrate=true`）
3. 改名/删列必须新增迁移 + 应用层双写过渡，不允许直接 DROP
4. 每张表必须有明确的模块归属，跨模块表（如关联表）归属"发起方"模块
5. 状态值只增不删；废弃状态保留在枚举中并标注 `DEPRECATED`
