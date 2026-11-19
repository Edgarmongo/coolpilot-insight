# R3-05b-2 · 对齐新旧主键（切读路径的关键前置）

日期：2026-11-15　迁移：`V12__align_legacy_ids.sql`（flyway version=12）

## 1. 发现的问题

V11 迁数据时用自增 id，导致：

| 表 | id |
|---|---|
| `video` | 1, 2, 3 |
| `ct_content` | **7, 8, 9** |
| `comment` | 1–19 |
| `ct_comment` | **63–81** |

根因：V11 之前我在事务里试跑过两次并 `ROLLBACK`，**自增序列不会随回滚复原**，Flyway 正式执行时就跳号了。

## 2. 为什么这是关键问题

`comment.video_id`、`analysis_task.content_id`、`comment_analysis.comment_id`、
`collection_task_item.video_id` 全部引用**旧 id**。

如果 id 不对齐，切换读路径时每一个关联查询都会串数据——而且不会报错，只会显示错的内容。
这类 bug 在测试里很难发现，在生产上极难排查。

## 3. 解决方式

`V12` 清空 ct_* 中由迁移产生的副本（旧表数据完好，可随时重放），按旧 id 显式插入：

```sql
INSERT INTO ct_content (id, ...) SELECT v.id, ... FROM video v;
INSERT INTO ct_comment (id, ...) SELECT cm.id, ... FROM comment cm;
ALTER TABLE ct_content AUTO_INCREMENT = 100000;  -- 新数据从 10 万起，不撞旧 id
```

**不修改已应用的 V11**（Flyway `validate-on-migrate=true`，改了会导致校验失败）。

## 4. 结果（实测）

| 项 | 结果 |
|---|---|
| `ct_content` id | **1, 2, 3**（与 `video` 一致） |
| `ct_comment` id | **1–19**（与 `comment` 一致） |
| `ct_comment` 行数 | 19 |
| flyway | version=12 |
| `mvn test` | 57/57 |
| `/api/comment/list`、`/api/overview` | 200 |

## 5. 对下一步的影响

原本「切读路径」需要做 **id 重映射**（查旧 id → 找新 id → 改写所有关联），
属于高风险改造，且必须整批一次完成。

现在 id 对齐后，切换退化为**换表名 + 换列名**：

| 旧 | 新 |
|---|---|
| `video.id` | `ct_content.id`（同值） |
| `video.aweme_id` | `ct_content.external_content_id` |
| `comment.video_id` | `ct_comment.content_id`（同值） |
| `comment.cid` | `ct_comment.external_comment_id` |
| `comment.content` | `ct_comment.text` |
| `comment.publish_time` | `ct_comment.published_at` |

仍建议整批切换（避免半套代码读旧表、半套读新表），但风险已从"可能串数据"
降为"改漏了会编译失败或查不到行"。

## 6. 教训

**任何一个会写数据的迁移，都不能在它之前对同一张表做试跑并回滚**——
自增序列的消耗是不可回滚的。以后验证迁移脚本应：
1. 在独立临时库验证，或
2. 验证后显式重置 `AUTO_INCREMENT`，或
3. 迁移本身显式指定 id（本次采用的方案）
