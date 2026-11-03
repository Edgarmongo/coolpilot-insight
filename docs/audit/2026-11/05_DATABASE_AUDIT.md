# 05 · DATABASE_AUDIT

> 数据库结构专项审计（全部结论基于 `SHOW CREATE TABLE` 只读查询，未执行任何 DDL/DML）

---

## 1. 表清单（实测 `SHOW TABLES`）

```
Tables_in_dy_comment
analysis_result
comment
user
```

**共 3 张表。** 任务卡要求核查的以下表，**均不存在**：

| 任务卡要求核查的表 | 存在 | 说明 |
|-------------------|------|------|
| 视频表 `video` | ❌ | 视频仅作为 `comment.video_id` 字符串列存在 |
| 评论表 `comment` | ✅ | 见下 |
| 用户表 `user` | ✅ | 系统用户（非抖音用户） |
| 关键词表 `keyword` | ❌ | 无关键词功能 |
| 采集任务表 `collect_task` | ❌ | 任务状态仅在内存 |
| 情感分析表 | ❌ | `sentiment` 是 comment 的列，非独立表 |
| 话题分析表 | ❌ | 无话题持久化，仅在 `analysis_result` 的 JSON 列 |
| 地区统计表 | ❌ | 同上 |
| 系统用户及权限表 | ⚠️ `user` 表兼之，`role` 字段区分 | — |

**另有 1 个"分析结果缓存表"**：`analysis_result`，兼具统计快照与话题数据存储职责。

---

## 2. 表结构详述

### 2.1 `user` — 系统用户表

```sql
CREATE TABLE `user` (
  `id`         bigint       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username`   varchar(50)  NOT NULL COMMENT '用户名',
  `phone`      varchar(11)  NOT NULL COMMENT '手机号',
  `password`   varchar(128) NOT NULL COMMENT 'BCrypt加密密码',
  `role`       tinyint      NOT NULL DEFAULT '0' COMMENT '角色: 0=普通用户, 1=管理员',
  `created_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_role` (`role`)
) ENGINE=InnoDB AUTO_INCREMENT=4
```

| 字段 | 类型 | 空 | 默认 | 说明 |
|------|------|-----|------|------|
| id | bigint | NOT | AUTO_INCREMENT | 主键 |
| username | varchar(50) | NOT | — | 用户名（注册时自动生成为掩码手机号） |
| phone | varchar(11) | NOT | — | 手机号，唯一 |
| password | varchar(128) | NOT | — | BCrypt 哈希（**兼容历史 MD5**） |
| role | tinyint | NOT | 0 | 0=普通用户 1=管理员 |
| created_at | datetime | NOT | CURRENT_TIMESTAMP | 注册时间 |

**索引**：PK(id) / UNIQUE(phone) / INDEX(role)
**外键**：无（应用层保证）
**现状数据**：4 行（2 管理员 + 1 普通用户 + 1 已删）

⚠️ **无 `deleted` 软删除字段**——`AdminController.deleteUser` 是物理删除。

---

### 2.2 `comment` — 评论数据表（核心）

```sql
CREATE TABLE `comment` (
  `id`            bigint       NOT NULL AUTO_INCREMENT,
  `user_id`       bigint       DEFAULT NULL COMMENT '用户ID',
  `video_id`      varchar(32)  NOT NULL COMMENT '视频编号',
  `nickname`      varchar(100) NOT NULL COMMENT '评论用户昵称',
  `region`        varchar(50)  NOT NULL COMMENT '用户归属地区',
  `publish_time`  datetime     NOT NULL COMMENT '评论发布时间',
  `content`       text         NOT NULL COMMENT '评论内容',
  `likes`         int          NOT NULL DEFAULT '0' COMMENT '点赞数',
  `sentiment`     tinyint      NOT NULL DEFAULT '0' COMMENT '情感: 1=积极, 0=中性, -1=消极',
  `created_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `douyin_id`     varchar(64)  DEFAULT NULL COMMENT '评论者抖音号(unique_id)',
  `douyin_uid`    varchar(32)  DEFAULT NULL COMMENT '评论者抖音UID',
  `sec_uid`       varchar(128) DEFAULT NULL COMMENT '评论者安全UID(主页链接用)',
  PRIMARY KEY (`id`),
  KEY `idx_video_id`     (`video_id`),
  KEY `idx_video_region` (`video_id`,`region`),
  KEY `idx_video_time`   (`video_id`,`publish_time`),
  KEY `idx_video_likes`  (`video_id`,`likes`)
) ENGINE=InnoDB AUTO_INCREMENT=129
```

**索引**：PK(id) / 4 个二级索引
**唯一约束**：❌ **无任何唯一索引**
**外键**：无

#### 字段级问题

| 字段 | 问题 | 严重度 |
|------|------|--------|
| **缺 `cid`** | 抖音评论唯一 ID 未存储 → **无法精确去重、无法识别评论更新** | **P0** |
| **缺 `reply_id` / `parent_cid`** | 无回复层级 → 无法采集/分析二级评论 | P1 |
| **缺 `reply_count`** | 接口有此字段，未存 | P1 |
| **缺 `video_id` 外键** | 视频无独立表，无法存视频元数据（标题、作者、点赞、时长） | P1 |
| `region` NOT NULL | 抖音不返回时用"未知"填充（应用层约定，非约束） | 低 |
| `content` TEXT | 无长度限制，长文本会占用行外存储 | 低 |
| `likes` int | 抖音点赞可超 int？实际远小于 2^31，无风险 | 无 |
| 无 `updated_at` | 评论被编辑/点赞变化无法感知 | P2 |

#### 索引使用分析

| 索引 | 服务的查询 |
|------|-----------|
| `idx_video_id` | `AnalysisServiceImpl` 全部按 `video_id` 过滤 ✅ 命中 |
| `idx_video_region` | 地区分布统计 ✅ 命中 |
| `idx_video_time` | 时间趋势统计 ✅ 命中 |
| `idx_video_likes` | 高赞排序（`ORDER BY likes DESC` + `video_id=`）✅ 命中 |
| `idx_user_id`（隐式） | ⚠️ **缺失**：`applyUserFilter` 用 `user_id` 过滤，非管理员查询会全表扫描 |

---

### 2.3 `analysis_result` — 分析结果缓存表

```sql
CREATE TABLE `analysis_result` (
  `id`             bigint      NOT NULL AUTO_INCREMENT,
  `user_id`        bigint      DEFAULT NULL,
  `video_id`       varchar(32) NOT NULL,
  `total_comments` int         NOT NULL DEFAULT '0',
  `total_users`    int         NOT NULL DEFAULT '0',
  `total_likes`    bigint      NOT NULL DEFAULT '0',
  `avg_likes`      decimal(10,2) NOT NULL DEFAULT '0.00',
  `region_stats`   text        COMMENT '地区统计JSON',
  `sentiment_stats` text       COMMENT '情感统计JSON',
  `daily_stats`    text        COMMENT '每日统计JSON',
  `top_comments`   text        COMMENT '高赞评论JSON',
  `analyzed_at`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_video_id` (`video_id`)
)
```

| 特性 | 说明 |
|------|------|
| 唯一键 | `uk_video_id` — **全局唯一，不含 user_id** |
| ⚠️ **设计冲突** | 表有 `user_id` 列，但唯一键不含它 → **同一视频不同用户无法各存一份分析结果**。当前因 `getSummary` 等实时查 `comment` 表，影响有限，但 `analysis_result` 的统计是**全量口径**（不限用户），与 API 的用户隔离口径不一致 |
| JSON 列 | 4 个 TEXT 存 JSON，无结构约束，无法用 SQL 查询明细 |
| 冗余 | `total_comments` 等可从 `comment` 表实时算出，属缓存冗余 |

---

## 3. 数据唯一性（6.2）

### 3.1 视频唯一标识

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 视频是否有稳定唯一标识 | ✅ 有 `video_id`（抖音 aweme_id，19 位数字） | A |
| 是否有数据库唯一索引 | ❌ 无（视频不是独立表） | D |
| 实际存储形态 | `comment.video_id` varchar(32) | A |

**问题**：无 `video` 表 → 无法存储视频元数据（标题/作者/点赞/发布时间/封面），
无法做视频去重管理，无法做"已发现视频"台账。

### 3.2 评论唯一标识

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 抖音评论唯一 ID（cid）是否可得 | ✅ **接口返回 `cid` 字段，但未存储** | A |
| 表中是否有 cid | ❌ 无 | D |
| 是否有 (platform + comment_id) 唯一索引 | ❌ 无 cid 字段，无法建 | D |
| 是否仅在业务代码判断重复 | ✅ 是（`clean_main.py` 两层去重） | A |
| 并发重复写入风险 | ⚠️ **存在**（见 3.4） | C |
| 重复采集是否产生重复 | ✅ 实测不产生 | A |

**结论：缺少 `cid` 是数据模型最关键的缺陷。**
抖音 `cid` 是评论的天然主键，有了它：
- 去重可用数据库唯一索引兜底，彻底消除并发竞态
- 可精确识别"新增评论"（增量采集的基础）
- 可支持回复层级（`reply_id` → `parent_cid`）

### 3.3 实际去重机制评估

```python
# clean_main.py:89-93  跨批次去重
cursor.execute("SELECT nickname, content FROM comment WHERE video_id = %s", (video_id,))
existing = set(cursor.fetchall())

# clean_main.py:115-118  插入前判重
if (nickname, content) in existing:
    skipped += 1; continue
```

| 特性 | 评价 |
|------|------|
| 去重键 | `(nickname, content)` — **不含抖音号**，理论上会把"同昵称同内容"的不同用户误判为重复 |
| 作用域 | 仅当前 video_id 内 |
| 并发安全 | ❌ 「先查后插」非原子，两进程并发清洗同一视频可能插入重复 |
| 缺失唯一索引兜底 | ❌ 无 |

### 3.4 并发重复写入风险（详细）

**场景**：两个清洗进程同时处理同一视频

```
时刻 T1: 进程A SELECT ... → existing = {(n1,c1)}
时刻 T2: 进程B SELECT ... → existing = {(n1,c1)}
时刻 T3: 进程A INSERT (n1,c1) → 成功
时刻 T4: 进程B INSERT (n1,c1) → 成功   ← 重复！
```

**缓解方案**（推荐）：新增 `cid` 列 + `UNIQUE KEY uk_cid (cid)`，
用 `INSERT IGNORE` 或 `ON DUPLICATE KEY UPDATE` 让数据库兜底。

---

## 4. 增量采集能力（6.3）

| 检查项 | 结论 | 等级 | 证据 |
|--------|------|------|------|
| 是否保存最后采集时间 | ❌ 无 | D | `comment` 表无此列 |
| 是否保存最后采集游标 | ❌ 无 | D | 代码中未使用抖音 `cursor` |
| 是否记录已采集评论 ID | ❌ 无 | D | 无 cid 列 |
| 是否支持只获取新增评论 | ❌ | D | — |
| 是否支持跳过已有评论 | ⚠️ **入库层跳过，采集层仍全量拉** | C | `clean_main.py:115` |
| 是否支持中断后恢复 | ❌ | D | 见 03 号文档 5.2 |

### 4.1 当前实际采用的增量策略

**无增量策略。** 当前流程：

```
每次采集 → 从第 1 页重新拉取（3 页 15 条）
        → 清洗时在入库层丢弃已存在的
        → 实际入库的只有新增部分
```

即：**「全量采集 + 入库去重」**，而非「增量采集」。

**代价**：
- 每次都触发 3 次抖音请求，风控暴露高
- 抖音按热度排序，新评论未必出现在前 3 页 → **增量实际不完整**
- 无法知道"上次采到哪"

### 4.2 设计建议（未实施）

| 方案 | 必要性 | 成本 | 风险 |
|------|--------|------|------|
| **加 `cid` 列 + 唯一索引** | 高（去重与增量共同基础） | 低（1 列 + 1 索引） | 低 |
| **加 `reply_id`/`parent_cid`** | 高（回复分析） | 中 | 低 |
| **记录抖音 `cursor` 到任务表** | 高（断点续采） | 中 | 中（游标可能过期） |
| **按 `publish_time` 增量筛选** | 中 | 低 | 中（时间排序非严格） |
| **建 `video` 表 + `discovered_at`** | 中高（视频管理） | 中 | 低 |

---

## 5. 缺失的数据库能力总表

| 缺失项 | 影响 | 建议优先级 |
|-------|------|-----------|
| `video` 表 | 无法存视频元数据、无视频台账、无关键词-视频关系 | P1 |
| `collect_task` 表 | 任务状态与日志不可持久化 | P0 |
| `comment.cid` | 无自然主键，去重靠内容比对，并发有竞态 | **P0** |
| `comment.reply_id` / `parent_cid` | 无法采集与分析回复 | P1 |
| `comment.reply_count` | 回复数缺失 | P1 |
| `comment.updated_at` | 无法感知评论更新 | P2 |
| `comment` 的 `user_id` 索引 | 非管理员查询全表扫描 | P2 |
| `analysis_result` 唯一键含 user_id | 多用户场景下分析结果口径冲突 | P2 |
| 软删除 | 误删不可恢复，无审计 | P3 |

---

## 6. 证据附录

### 6.1 只读查询语句（本次审计使用）

```sql
SHOW TABLES;
SHOW CREATE TABLE user\G
SHOW CREATE TABLE comment\G
SHOW CREATE TABLE analysis_result\G
SHOW COLUMNS FROM comment;
SELECT COUNT(*), COUNT(DISTINCT video_id), COUNT(DISTINCT douyin_id) FROM comment;
SELECT video_id, COUNT(*) FROM comment GROUP BY video_id;
SELECT COUNT(*) FROM (SELECT content FROM comment GROUP BY content HAVING COUNT(*)>1) t;
```

### 6.2 字段注释编码问题（观察）

`comment` 表的 `douyin_id` / `douyin_uid` / `sec_uid` 三个字段的 COMMENT
在 `SHOW CREATE TABLE` 中显示为 `??????`，而 `dy_comment.sql` 中为中文。

**成因**：这三列由 `SchemaMigration`（Java 执行 `ALTER TABLE ... COMMENT '中文'`）添加，
而 JDBC 连接未指定 `characterEncoding`，导致中文字符被写成 `?`。
（`dy_comment.sql` 里的中文注释正常，因为 MySQL 客户端以 utf8mb4 读取该文件。）

**影响**：仅注释乱码，不影响功能与数据。修复方式为在 JDBC URL 加 `characterEncoding=utf8`
（当前 URL 已有 `characterEncoding=utf8`，但 `ALTER` 的字面量仍走 `connection` 的编码设置，
需在迁移类中显式设置 `conn` 编码或用英文注释）。

**等级**：P3（文档可读性问题，不影响运行）
