# 02 · 数据库清单

> ⚠️ **本文档记录的是 2026-11-15 升级前的基线**（Spring Boot 2.7.18 / JDK 8 / MyBatis-Plus）。
> 当日已完成迁移到 Spring Boot 4.1.2 + JDK 21 + 原生 MyBatis，
> 变更详情与验证记录见 [`docs/migration/2026-11-15-boot4-jdk21-mybatis.md`](../migration/2026-11-15-boot4-jdk21-mybatis.md)。
> 下文保留原样以作为前后对比依据；涉及技术栈与 ORM 的描述均以迁移文档为准。

审计方式：静态解析 `dy-backend/src/main/resources/db/migration/`（未连接数据库、未执行 DDL）

## 1. 迁移文件

| 版本 | 文件 | 内容 |
|---|---|---|
| V1 | `V1__core_user_and_governance.sql` | 用户、系统配置、审计日志 |
| V2 | `V2__keyword_and_video.sql` | 关键词、视频、发现任务 |
| V3 | `V3__collection_and_comment.sql` | 采集任务、评论 |
| V4 | `V4__ai_analysis.sql` | AI 分析全链路表 |
| V5 | `V5__grant_file_report_usage.sql` | 授权、文件、报告、用量 |
| V6 | `V6__seed_data.sql` | 种子数据 |
| V7 | `V7__analysis_review.sql` | 人工复核 |
| V8 | `V8__analysis_evidence_source_level.sql` | 补 `analysis_evidence.source_level` |

> V8 是修复性迁移：实体早已声明 `sourceLevel` 但 V4 建表遗漏，导致评论级分析写证据报
> `Unknown column 'source_level'`，整批评论 FAILED。**迁移与实体的一致性此前没有检查手段**，
> 建议把「实体字段 vs 库表列」比对纳入 CI（已有脚本 `tools/check_schema_drift.py` 可复用）。

## 2. 表清单（25 张）

### 用户与治理
`user`、`system_config`、`audit_log`

### 关键词与视频
`keyword`、`video`、`keyword_video`、`discovery_task`、`discovery_task_item`

### 采集
`collection_task`、`collection_task_item`、`collection_task_log`、`collection_checkpoint`

### 评论
`comment`

### AI 分析
`analysis_profile`、`analysis_task`、`analysis_task_batch`、`analysis_task_item`、
`comment_analysis`、`video_insight`、`analysis_evidence`、`analysis_review`

### 授权 / 文件 / 报告 / 用量
`video_grant`、`file_asset`、`report`、`report_version`、`usage_record`

## 3. 约束统计

| 类型 | 数量 |
|---|---|
| FOREIGN KEY | 42 |
| UNIQUE KEY | 48 |
| CHECK 约束 | 8 |

约束密度较高，说明设计期对完整性有考虑，不是裸表堆砌。

## 4. 三个关键设计（含实测结论）

**1. 视频独立建模**
`video.id` 是内部主键，`aweme_id` 是平台标识；`comment.video_id` 指向内部 ID。
→ 好处：平台 ID 变化不影响内部关联。→ 局限：`aweme_id` 命名已绑死抖音。

**2. 评论双唯一键**
`uk_platform_cid (platform, cid)` + `uk_platform_dedup (platform, dedup_key)`。
`cid` 允许 NULL（历史数据），`dedup_key` 恒非空（无 cid 时用本地指纹兜底）。
→ **实测**：抖音评论接口确实返回 cid，19/19 命中，策略落 `CID`；二次采集靠双键幂等，不产生重复。

**3. 活动任务唯一**
`collection_task_item.uk_video_active (video_id, active_flag)`，终态把 `active_flag` 置 NULL
（MySQL 唯一索引对 NULL 不生效）。
→ 注意：MyBatis-Plus 默认 `NOT_NULL` 更新策略，**置 null 必须用 `LambdaUpdateWrapper.set(f, null)`**，
否则写不进库，会造成 `active_flag` 永久占用。这是已知踩坑点。

## 5. 缺失的关键表（相对目标架构）

| 目标表 | 现状 |
|---|---|
| `data_source` / `source_account` | 不存在，平台写死 |
| `raw_content` / `content_comment` / `content_author` | 不存在，等价物是 `video`/`comment`（抖音专用） |
| `lead` / `lead_evidence` / `lead_score_history` / `lead_review` | 不存在 |
| `lead_followup` / `conversion_record` | 不存在 |
| `demand_signal` | 不存在（最接近的是 `comment_analysis`） |
| `analysis_result` | **不存在**，且历史上从未被读取（已废弃） |

## 6. 版本化机制现状

`analysis_profile` 表存在，但：

- 仅被 **3 个 Java 文件**引用（`AnalysisProfile.java`、`AnalysisProfileMapper.java`、`AiAnalysisService.java`）
- `profileId` 全项目仅 **3 处**使用

→ 结论：**版本化机制有骨架、未贯通**。当前只能保证「同一视频用某个 profile 跑一次」，
做不到「同一内容用不同 profile 重跑并保留历史对比」，也没有 `demand_taxonomy_version` /
`prompt_version` / `model_config_id` 这些字段。

## 7. 空壳与死表

| 表 | 状态 | 证据 |
|---|---|---|
| `file_asset` | **建表未用** | 全项目 **0 个 Java 文件**引用 `file_asset` / `FileAsset` |
| `analysis_result` | 已废弃 | 表不存在，代码中无引用 |

## 8. 数据量现状（来自同 commit 运行实例）

| 表 | 行数 |
|---|---|
| `video` | 3 |
| `comment` | 19 |
| `comment_analysis` | 19 |
| `analysis_evidence` | 45（19 评论级 + 26 洞察级） |
| `video_insight` | 1 |
| `report` | 1 |
| `analysis_task` | 2 |

数据量极小，说明系统仍处于**功能验证阶段，未进入真实业务运行**。
迁移风险低，但也不能据此判断大数据量下的性能表现。
