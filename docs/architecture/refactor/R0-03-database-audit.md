# R0-03 · 数据库审计

审计方式：Flyway 脚本静态解析 + 运行库元数据/行数查询（只读，无 DDL/DML）
数据库：MySQL **8.0.46**，库 `coolpilot_insight`，容器 `cpi-mysql`，宿主端口 3307

## 1. 迁移机制

| 项 | 值 | 证据 |
|---|---|---|
| 工具 | Flyway（`spring-boot-flyway` + `flyway-core` + `flyway-mysql`） | `backend/pom.xml` |
| 脚本位置 | `backend/src/main/resources/db/migration/` | — |
| 版本数 | 8（V1~V8） | 文件列表 |
| 已应用版本 | **8** | `flyway_schema_history` 8 行；启动日志 `Current version of schema ...: 8` |
| `baseline-on-migrate` | false | `application.yml` |
| `validate-on-migrate` | true | 同上 |
| `out-of-order` | false | 同上 |

> 因 `validate-on-migrate: true` 且 `out-of-order: false`，**已应用的 V1~V8 不可修改**，新增结构只能追加 V9+。
> R3 重建数据库时需清空 `flyway_schema_history` 或新建库。

## 2. 表清单（**26 张业务表** + 1 张 Flyway 历史表）

> 更正：此前文档（`docs/audit/02-database-inventory.md`）记为 25 张，实测为 **26 张**。
> 差异来自当时漏计 `analysis_profile` 之外的一处重复计数。

| # | 表名 | 当前用途 | 行数 | 代码引用 | 使用状态 | 目标模块 |
|---|---|---|---:|---|---|---|
| 1 | `user` | 账号 | 2 | `UserMapper` | 已使用 | system |
| 2 | `system_config` | 配置项 | 9 | `SystemConfigMapper` | 已使用 | system |
| 3 | `audit_log` | 操作审计 | 61 | `AuditLogMapper` | 已使用（登录/删号写日志） | system |
| 4 | `keyword` | 关键词 | **0** | `KeywordMapper` | 仅迁移存在（有代码，无数据） | source |
| 5 | `keyword_video` | 关键词-视频关联 | **0** | `KeywordVideoMapper` | 仅迁移存在 | source |
| 6 | `video` | 视频（抖音） | 3 | `VideoMapper` | 已使用 | content |
| 7 | `comment` | 评论 | 19 | `CommentMapper` | 已使用 | content |
| 8 | `video_grant` | 视频读授权 | **0** | `VideoGrantMapper` | **仅迁移存在**（无写入路径） | content |
| 9 | `discovery_task` | 发现任务 | **0** | `DiscoveryTaskMapper` | 仅迁移存在 | collection |
| 10 | `discovery_task_item` | 发现任务项 | **0** | `DiscoveryTaskItemMapper` | 仅迁移存在 | collection |
| 11 | `collection_task` | 采集任务 | 4 | `CollectionTaskMapper` | 已使用 | collection |
| 12 | `collection_task_item` | 采集任务项 | 5 | `CollectionTaskItemMapper` | 已使用 | collection |
| 13 | `collection_task_log` | 任务日志 | 22 | `CollectionTaskLogMapper` | 已使用 | collection |
| 14 | `collection_checkpoint` | 断点 | 2 | `CollectionCheckpointMapper` | 已使用 | collection |
| 15 | `analysis_profile` | 分析档案 | 2 | `AnalysisProfileMapper` | 已使用（`ap_default_v1`） | analysis |
| 16 | `analysis_task` | 分析任务 | 3 | `AnalysisTaskMapper` | 已使用 | analysis |
| 17 | `analysis_task_batch` | 批次 | 1 | `AnalysisTaskBatchMapper` | 已使用 | analysis |
| 18 | `analysis_task_item` | 任务项 | 21 | `AnalysisTaskItemMapper` | 已使用 | analysis |
| 19 | `comment_analysis` | 评论级结果 | 19 | `CommentAnalysisMapper` | 已使用 | analysis |
| 20 | `video_insight` | 视频级洞察 | 2 | `VideoInsightMapper` | 已使用 | analysis |
| 21 | `analysis_evidence` | 证据链 | 77 | `AnalysisEvidenceMapper` | 已使用 | analysis |
| 22 | `analysis_review` | 人工复核 | **0** | `AnalysisReviewMapper(Ext)` | 已实现未使用 | analysis |
| 23 | `usage_record` | AI 用量 | 3 | `UsageRecordMapper` | 已使用（仅 AI 侧写入） | analysis |
| 24 | `report` | 报告 | 2 | `ReportMapper` | 已使用 | analytics |
| 25 | `report_version` | 报告版本 | 2 | `ReportVersionMapper` | 已使用 | analytics |
| 26 | `file_asset` | 文件资产 | **0** | **0 处引用** | **仅迁移存在（空壳）** | system（待重设计） |
| — | `flyway_schema_history` | 迁移历史 | 8 | Flyway | 系统表 | — |

## 3. 约束与索引概览（来自 V1~V8）

| 类型 | 数量 |
|---|---|
| 主键 | 26 |
| 外键 | 42 |
| 唯一键 | 48 |
| CHECK 约束 | 8 |

关键复合唯一键（去重与幂等的基础，必须保留设计意图）：

| 表 | 唯一键 | 作用 |
|---|---|---|
| `comment` | `uk_platform_cid(platform, cid)`、`uk_platform_dedup(platform, dedup_key)` | 双键幂等；`cid` 允许 NULL，`dedup_key` 恒非空 |
| `video` | `uk_platform_aweme(platform, aweme_id)` | 同一平台视频唯一 |
| `collection_task_item` | `uk_video_active(video_id, active_flag)` | 同一视频仅一个活动采集（终态置 NULL 释放） |
| `video_grant` | `uk_grant(grantee_user_id, video_id, permission)` | 同一授权对象不重复 |
| `analysis_evidence` | 无复合唯一 | 允许同一分析多条证据 |

> `uk_video_active` 依赖 MySQL「唯一索引对 NULL 不生效」的特性。
> 应用侧必须用显式 `SET active_flag = NULL` 更新（已实现 `CollectionTaskItemMapper.clearActiveFlag`），
> 不能用「实体置 null + updateById」，否则永不释放。

## 4. 字段设计特征

- **状态字段**：全部为 `VARCHAR(24)`（如 `PENDING/RUNNING/SUCCESS/FAILED`）或 `TINYINT`（`status 0/1`），
  无 MySQL `ENUM`——便于应用层扩展枚举值而不改 DDL
- **审计字段**：多数表有 `created_at`/`updated_at`（DB 默认值 `CURRENT_TIMESTAMP` 与 `ON UPDATE`），
  但**无 `created_by`/`updated_by`**
- **软删除**：仅 `user.deleted_at`、`report.deleted_at` 两处
- **租户字段**：**不存在**（无 `tenant_id`），当前为单用户/小团队模式
- **金额**：`usage_record.cost_amount`、`report` 相关金额为 `DECIMAL`，未见订单/支付表

## 5. 与业务代码的对应关系

- 26 张表中 **22 张有对应 Mapper** 且被 Service 实际调用
- `file_asset` 无 Mapper、无引用
- `analysis_review` 有 Mapper 与 Service（`ReviewService`），但 0 行数据 → 功能已实现但从未使用
- 无「只在测试中使用」的表（测试使用 Testcontainers 独立库，结构与主库一致）

## 6. 已废弃/缺失

| 项 | 判定 | 证据 |
|---|---|---|
| `analysis_result` | **已废弃且不存在** | 历史文档提及，迁移脚本中无此表，代码无引用 |
| `file_asset` | **空壳** | 建表于 V5，全项目 0 处引用 |
| `coolpilot_insight.sql`（仓库根） | 历史脚本 | 与 Flyway 重复，正式入口为 Flyway |

## 7. 数据现状（用于 R3 风险评估）

总行数约 **290 行**，集中在 `analysis_evidence`(77)、`audit_log`(61)、`collection_task_log`(22)、
`analysis_task_item`(21)、`comment`(19)、`comment_analysis`(19)。

结论：数据量极小、无外部依赖、允许删除——R3 重建风险低。
**但重建前必须确认目标库为 `coolpilot_insight`，且不触碰 `dtk_*` 与 `coolpilot_*` 其他卷。**

## 8. 与目标模型的差距（详见 R1-04）

| 差距 | 说明 |
|---|---|
| 无模块前缀 | 目标按 `sys_/ds_/col_/ct_/an_/ld_/cv_` 命名 |
| 缺 3 个领域 | `source`（数据源/账号）、`lead`（线索/证据/评分/审核/跟进）、`conversion`（产品/成交/归因） |
| 无审计人字段 | 目标补齐 `created_by`/`updated_by` |
| 无租户字段 | 目标预留 `tenant_id DEFAULT 0` |
| 原始数据与标准化数据未分离 | 现 `video`/`comment` 同时承担原始与标准化职责 |
| `analysis_profile` 无版本语义 | 现有 2 行，无 `prompt_version`/`model_config` 字段 |
| 无产品/订单引用 | CoolSkyAI 集成尚未开始 |
