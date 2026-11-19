# R3-05b-1 · 旧内容数据迁入新表

日期：2026-11-15
迁移：`V11__migrate_legacy_content.sql`（已在业务库执行，`flyway version=11 success=1`）

## 1. 为什么先迁数据再切读路径

content 的读路径一旦切到 `ct_*`，而新表是空的，页面会瞬间变空——
看起来像"重构把数据弄丢了"。所以顺序必须是：先复制数据 → 再切读路径。

## 2. 迁移结果（实测）

| 目标表 | 行数 | 说明 |
|---|---:|---|
| `ct_content` | 3 | 来自 `video` |
| `ct_comment` | 19 | 来自 `comment`，沿用原 `dedup_key` |
| `ct_author` | 0 | 旧表 `video.author_id` 为空，无法建立作者 |
| `ct_grant` | 0 | 旧表 `video_grant` 本身为空 |

旧表未被修改：`comment` 仍 19 行。

## 3. 字段映射

| 旧 | 新 |
|---|---|
| `video.aweme_id` | `ct_content.external_content_id` |
| `video.title` | `ct_content.title` |
| `video.cover_url` | `ct_content.source_url`（暂用封面，语义待 R3-05b 修正） |
| `video.metadata_json` | `ct_content.metrics_json` |
| `video.publish_time` | `ct_content.published_at` |
| `comment.cid` / `parent_cid` / `nickname` / `content` / `likes` / `ip_region` / `publish_time` | `ct_comment` 同义字段 |
| `comment.dedup_key` | `ct_comment.dedup_key`（直接沿用，保证幂等一致） |

**未迁移**：`comment.douyin_id`/`douyin_uid`/`sec_uid`（平台专有字段），
目标模型里由 `ct_author.external_author_id` 承载，但旧数据缺作者映射，故留空。

原始响应归档 `ct_raw_archive` 为空——旧库从未保存原始响应，这是历史欠账，不是迁移遗漏。

## 4. 踩到的坑：collation 不一致

旧表是 `utf8mb4_0900_ai_ci`，新表（V9/V10 建）是 `utf8mb4_unicode_ci`。
跨表字符串比较直接报：

```
ERROR 1267 (HY000): Illegal mix of collations
(utf8mb4_unicode_ci,IMPLICIT) and (utf8mb4_0900_ai_ci,IMPLICIT) for operation '='
```

需要在**每一处**旧表列与 new 表列的比较上显式 `COLLATE utf8mb4_unicode_ci`。
本次共 6 处：`platform`、`author_id`、`aweme_id`（2 处）、`dedup_key`、`permission`。

> 这个问题在写 SQL 时完全看不出来，只有真跑才暴露。
> 切库后旧表消失，此类比较自然不存在——但过渡期的所有 SQL 都要留意。

## 5. 幂等性

每步都带 `NOT EXISTS`，重复执行不产生重复行。
验证方式：先在事务里跑一遍并 `ROLLBACK`，再让 Flyway 正式执行，两次计数一致。

## 6. 验证

| 项 | 结果 |
|---|---|
| `flyway_schema_history` | version=11, success=1 |
| `ct_content` / `ct_comment` | 3 / 19 |
| 旧表 `comment` | 19（未改动） |
| `mvn test` | 57/57 |
| `/api/comment/list?videoId=1` | 200（旧读路径未受影响） |
| `/api/overview` | 200 |

## 7. 下一步：切换读路径

数据已就位，但**读路径仍走旧表**。切换需要改约 12 个 Service：

| 类 | 改动 |
|---|---|
| `VideoService` | `video` → `ct_content`；`resolveInternalId` 语义改为 external → content id |
| `CommentServiceImpl` | `comment` → `ct_comment`；楼中楼查询键 `parent_cid` → `parent_external_id` |
| `AnalysisServiceImpl` / `AiAnalysisService` / `ReportService` / `OverviewService` / `CompareService` | 跟随 VideoService 的 id 语义变化 |
| `ContentAccessService` | `video_grant` → `ct_grant` |
| `CleanServiceImpl` | 清洗入库改为调用 `ContentIngestService`，不再直写旧表 |

**风险**：`video.id`（内部 id）与 `ct_content.id` 不同，所有以 videoId 为外键的表
（`comment.video_id`、`collection_task_item.video_id`、`analysis_*`）都会受影响。
切换必须整批做，不能逐个 Service 改。
