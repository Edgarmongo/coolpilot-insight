# 18 · 数据库现状基线（M0 交付物）

**盘点日期**：2026-11-03
**执行模式**：READ ONLY（仅 SHOW / SELECT / grep / ls）
**数据来源**：运行中的 `mysql:8` 容器（库 `dy_comment`）+ 本地源码静态检查
**用途**：为《数据库设计专项评审 V1.0》提供真实 DDL 依据，供其收敛为字段级 ER 模型

> 本文所有结论均为 A 级证据（实际查询或代码读取），无推测。
> 未执行任何 DDL / DML / Flyway / 代码修改 / Git 操作。

---

## 零、先说结论：评审文档有 4 处前提与事实不符

在给出 DDL 之前，必须先纠正以下 4 点，否则后续迁移方案会建立在错误假设上。

| # | 评审 V1.0 的说法 | 基线实测 | 影响 |
|---|---|---|---|
| **1** | 「当前已有 Flyway V1～V13，不能覆盖已有迁移」 | **项目中不存在 Flyway**（依赖、目录、历史表三项全否） | V14～V25 的版本规划无基线可续；且引入 Flyway 到非空库需 baseline 处理 |
| **2** | 「不能把 `comment.user_id` 直接认定为所有权字段」 | 它**已经在承担所有权语义**（Java + Flask 双层过滤） | 直接弃用会让现有非管理员用户看到空数据或越权看到全部 |
| **3** | 「`comment.video_id` 应指向 `video.id`」 | 现为 `varchar(32)`，存的是平台 aweme_id 字符串 | 改 `BIGINT` 是破坏性变更，需双列共存 + 回填 |
| **4** | 「唯一索引 `UNIQUE(platform, cid)`」 | 表无 `platform`、无 `cid`，且无法回填历史值 | 14 条历史数据 cid 永久为 NULL；MySQL 唯一索引对 NULL 不生效 |

---

## 一、数据库实例基线

| 项 | 值 |
|---|---|
| MySQL 版本 | `8.0.46` |
| 数据库 | `dy_comment` |
| server charset / collation | `utf8mb4` / **`utf8mb4_general_ci`** |
| 表实际 collation | **`utf8mb4_0900_ai_ci`** |
| 引擎 | 全部 InnoDB |
| 表数量 | **3 张**（user / comment / analysis_result） |

**⚠️ collation 不一致（新发现）**：server 级为 `utf8mb4_general_ci`，而三张表均为 `utf8mb4_0900_ai_ci`。当前无跨表 JOIN，暂未暴露；一旦新建表沿用 server 默认 collation 并与 `comment` JOIN，会报 `Illegal mix of collations`。**M1 建表时必须显式声明 `COLLATE=utf8mb4_0900_ai_ci`。**

---

## 二、迁移机制与完整性基线：无 Flyway / 无 Liquibase / 零外键

**Flyway 与 Liquibase 均不存在**，五重验证全部为否：

| 验证项 | 命令 | 结果 |
|---|---|---|
| Flyway 依赖 | `grep -i flyway dy-backend/pom.xml` | 无匹配 |
| Liquibase 依赖 | `grep -ri liquibase *.xml *.yml *.properties` | 无匹配 |
| 迁移目录 | `ls db/migration` | 不存在 |
| Flyway 历史表 | `SHOW TABLES LIKE 'flyway%'` | 0 行 |
| Liquibase 历史表 | `SHOW TABLES LIKE 'DATABASECHANGELOG%'` | 0 行 |
| resources 内容 | `ls src/main/resources/` | **仅 `application.yml`** |

### 建表脚本维护：`dy_comment.sql` 与运行库基本一致

根目录 `dy_comment.sql`（3.3 KB，最后修改 2026-11-03 18:52）已包含 `douyin_id` / `douyin_uid` / `sec_uid`，字段顺序与实际库一致。说明：

- 新库首次初始化由该脚本完成（docker-entrypoint-initdb.d 机制）
- **已有库的增量变更**由 `SchemaMigration` 补齐
- 二者目前同步，但**没有任何机制保证今后不再漂移**——这是 M1 要解决的核心问题

### 外键：零外键

```sql
-- information_schema.KEY_COLUMN_USAGE where REFERENCED_TABLE_NAME IS NOT NULL
-- 返回 0 行
```

三张表之间**没有任何外键约束**。`comment.video_id` 与 `analysis_result.video_id` 都只是孤立字符串，删除评论不会级联，删除用户也不会阻断。评审文档第五节所说「外键是否启用需评审」——**现状是完全没有**，引用完整性 100% 靠应用层保证。

### 实际机制：自定义 `SchemaMigration`

`dy-backend/src/main/java/com/dy/comment/config/SchemaMigration.java`：

```java
// L26-33：读 application.yml 里的 datasource 配置
@Value("${spring.datasource.url}") private String jdbcUrl;
// L37：启动时 JDBC 直连
try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
    // L40-44：幂等 ADD COLUMN
    addColumnIfMissing(conn, "comment", "douyin_id",
        "`douyin_id` VARCHAR(64) NULL COMMENT '评论者抖音号(unique_id)'", added);
}
```

### 能力边界（这决定 M1~M5 怎么落地）

| 能力 | 是否支持 | 说明 |
|---|---|---|
| `ADD COLUMN`（幂等） | ✅ | 唯一实际具备的能力 |
| `CREATE TABLE` | ❌ | 需扩展或改用手工脚本 |
| `ADD INDEX` / `UNIQUE` | ❌ | 无法通过现有机制创建 cid 唯一索引 |
| `MODIFY COLUMN`（改类型） | ❌ | 无法把 `video_id` 改为 BIGINT |
| 执行顺序 / 版本记录 | ❌ | 无版本号、无历史表、无回滚 |
| 失败中断保护 | ❌ | 逐条 catch，失败仅 warn 日志 |

**结论**：现有机制只能承担「加列」，无法承担 M2~M5 的建表与改类型。**要么引入 Flyway（需 baseline），要么扩展 SchemaMigration 为带版本表的轻量迁移器**。这是 M0 必须做出的决策，不能留到实施阶段。

### 附带缺陷：中文注释写入后变乱码

`SHOW CREATE TABLE` 实际输出：

```sql
`douyin_id`  varchar(64)  DEFAULT NULL COMMENT '??????(unique_id)',
`douyin_uid` varchar(32)  DEFAULT NULL COMMENT '?????UID',
`sec_uid`    varchar(128) DEFAULT NULL COMMENT '?????UID(?????)',
```

源码里的中文是正确的，说明是 JDBC 连接字符集导致 metadata 通道编码丢失。不影响功能，但**后续迁移必须验证注释是否写入正确**，否则新表同样会出现乱码。

---

## 三、`user` 表 DDL

```sql
CREATE TABLE `user` (
  `id`         bigint       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username`   varchar(50)  NOT NULL                COMMENT '用户名',
  `phone`      varchar(11)  NOT NULL                COMMENT '手机号',
  `password`   varchar(128) NOT NULL                COMMENT 'BCrypt加密密码',
  `role`       tinyint      NOT NULL DEFAULT '0'    COMMENT '角色: 0=普通用户, 1=管理员',
  `created_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_role` (`role`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
```

### 基线缺口

| 缺口 | 现状 | 迁移建议 |
|---|---|---|
| 无 `status` / `enabled` | 无法禁用账号，只能删行 | M1 增加 `status tinyint NOT NULL DEFAULT 1` |
| 无 `updated_at` | 改密码无时间戳 | 追加 |
| 无软删除 | 删用户会导致 `comment.user_id` 悬挂 | 先确认现有删除逻辑，再加 `deleted_at` |
| `password` 注释写 BCrypt | 管理员实际为 **MD5**（`e10adc39...`） | 注释需修正，避免误导 |
| `role` 仅 0/1 | 无角色表 | 评审文档已建议暂不引入 RBAC，同意 |

---

## 四、`comment` 表 DDL（核心事实表）

```sql
CREATE TABLE `comment` (
  `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '评论ID',
  `user_id`      bigint       DEFAULT NULL            COMMENT '用户ID',
  `video_id`     varchar(32)  NOT NULL                COMMENT '视频编号',
  `nickname`     varchar(100) NOT NULL                COMMENT '评论用户昵称',
  `region`       varchar(50)  NOT NULL                COMMENT '用户归属地区',
  `publish_time` datetime     NOT NULL                COMMENT '评论发布时间',
  `content`      text         NOT NULL                COMMENT '评论内容',
  `likes`        int          NOT NULL DEFAULT '0'    COMMENT '点赞数',
  `sentiment`    tinyint      NOT NULL DEFAULT '0'    COMMENT '情感: 1=积极, 0=中性, -1=消极',
  `created_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  `douyin_id`    varchar(64)  DEFAULT NULL            COMMENT '??????(unique_id)',
  `douyin_uid`   varchar(32)  DEFAULT NULL            COMMENT '?????UID',
  `sec_uid`      varchar(128) DEFAULT NULL            COMMENT '?????UID(?????)',
  PRIMARY KEY (`id`),
  KEY `idx_video_id`     (`video_id`),
  KEY `idx_video_region` (`video_id`,`region`),
  KEY `idx_video_time`   (`video_id`,`publish_time`),
  KEY `idx_video_likes`  (`video_id`,`likes`)
) ENGINE=InnoDB AUTO_INCREMENT=129 DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='评论数据表';
```

### 关键缺口（全部已实测确认）

| 缺口 | 事实 | 后果 |
|---|---|---|
| **无 `cid`** | 13 个字段中不存在 | 无法幂等写入、无法增量、并发有竞态 |
| **无 `parent_cid` / `root_cid`** | 不存在 | 回复评论无处挂载 |
| **无 `platform`** | 不存在 | 多平台扩展需改主键策略 |
| **无任何唯一约束** | 4 个索引全部 `NON_UNIQUE=1` | 去重只能靠应用层查库比对 |
| **`user_id` 无索引** | `SHOW INDEX` 无 user_id | 非管理员查询 `video_id+user_id` 需回表过滤 |
| **`video_id` 是 varchar(32)** | 存平台 aweme_id（如 `7703721523349239951` 为 19 位，够用） | 无法直接外键关联 `video.id`(BIGINT) |
| **`AUTO_INCREMENT=129` 但仅 14 行** | 历史删除过 115 行 | 无影响，但说明曾有大量测试数据 |

### 历史 cid 可回填性：确认不可回填

采集脚本 `douyin_comment.py` 只将业务字段写入 CSV，**原始响应 JSON 从未落盘**。因此现有 14 条记录的 cid 无法追溯，只能：

- 方案 A：置 NULL，等下次重采补齐（推荐）
- 方案 B：整体重采覆盖

**注意**：MySQL 唯一索引对 NULL 不生效（多行 NULL 不算重复），所以 `UNIQUE(platform, cid)` 在历史数据上不会报冲突，但也不提供保护。必须靠采集侧保证 cid 非空。

---

## 五、`analysis_result` 表 DDL

```sql
CREATE TABLE `analysis_result` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `user_id`         bigint       DEFAULT NULL,
  `video_id`        varchar(32)  NOT NULL COMMENT '视频编号',
  `total_comments`  int          NOT NULL DEFAULT '0',
  `total_users`     int          NOT NULL DEFAULT '0',
  `total_likes`     bigint       NOT NULL DEFAULT '0',
  `avg_likes`       decimal(10,2) NOT NULL DEFAULT '0.00',
  `region_stats`    text         COMMENT '地区统计JSON',
  `sentiment_stats` text         COMMENT '情感统计JSON',
  `daily_stats`     text         COMMENT '每日统计JSON',
  `top_comments`    text         COMMENT '高赞评论JSON',
  `analyzed_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_video_id` (`video_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci COMMENT='分析结果表';
```

### 基线事实

- **`uk_video_id` 是硬约束**：一视频只能有一行，无法版本化、无法多用户并存
- 4 个 `text` 存 JSON（非 `JSON` 类型），无法用 MySQL JSON 函数查询
- `user_id` 有列但**未参与唯一约束**，也**未用于过滤**（与 `comment` 不同）

### ⚠️ 重大发现：这张表的统计数据从未被读取

`analysisResultMapper` 全项目**只有 3 处调用**：

| 行号 | 用途 |
|---|---|
| `AnalysisServiceImpl:244` | `selectList` — **仅取 `analyzedAt` 用于视频列表时间展示** |
| `AnalysisServiceImpl:746` | `selectOne` — `refreshAnalysis` 内查已有记录 |
| `AnalysisServiceImpl:757-758` | `updateById` / `insert` — `refreshAnalysis` 写入 |

而 6 个分析接口**全部实时查 `comment` 表**，完全不读这张表的统计字段：

```java
// getSummary / getRegionStats / getSentimentStats / getTrendStats / getTopComments
wrapper.eq(Comment::getVideoId, videoId);   // 直查 comment，不走 analysis_result
```

**结论修正**：此前审计记录称它是「持久化缓存」，**这不准确**。它是 **只写不读的冗余数据**——`total_comments`、`region_stats`、`sentiment_stats`、`daily_stats`、`top_comments` 五个字段写入后无人消费。真实作用是「记录该视频最后一次分析时间」。

**对 M4 的直接影响**：`analysis_result` 可**低风险废弃**，只需把 `analyzedAt` 的展示需求转移到 `video_insight.created_at` 或 `collection_task.finished_at`。不需要"退化为缓存"的过渡期。

**代码依赖点**：`AnalysisResultMapper` / `AnalysisResult` 实体 / `AnalysisController.refresh` / `getVideoList` 的 L244。

**与 `video_insight` 的冲突**：`uk_video_id` 使一视频只能有一行，天然无法版本化。既然其统计字段无人读取，M4 直接切换到 `video_insight` 的阻力远小于预期。

---

## 六、【最关键发现】`comment.user_id` 已在承担所有权语义

评审文档判断「不能把它认定为所有权字段」。基线核查后发现情况更微妙——**它已经在被当作所有权字段使用，但写入语义与之不匹配**。

### 写入侧：是「清洗操作者」

```java
// CleanServiceImpl.java:54-55
Long uid = RequestContext.getUserId();          // 当前登录用户
if (uid != null) pb.environment().put("CLEAN_USER_ID", uid.toString());
```
```python
# clean_main.py:79, 125
USER_ID = os.environ.get('CLEAN_USER_ID')
batch.append((USER_ID, video_id, nickname, ...))
```

### 读取侧：被当作「数据所有者」做隔离

**共 5 处独立实现**，散落在 Java 与 Python 两侧，全部基于 `comment.user_id`：

| # | 位置 | 形式 |
|---|---|---|
| 1 | `CommentServiceImpl:22` | `wrapper.eq(Comment::getUserId, userId)` |
| 2 | `AnalysisServiceImpl:74`（getSummary） | 同上 |
| 3 | `AnalysisServiceImpl:231`（getVideoList） | 同上 |
| 4 | `AnalysisServiceImpl:273`（getWordCloud） | 拼进 URL 传给 Flask |
| 5 | `wordcloud_service.py:179` | `WHERE video_id=%s AND user_id=%s` |

这意味着：**修改隔离逻辑必须同步改 5 处，其中 1 处在 Python 服务里**。任何只改 Java 的迁移都会留下 Flask 侧的越权缺口（或反向的数据不可见）。

```java
// 代表性实现 1 —— CommentServiceImpl.java:16-22
private void applyUserFilter(LambdaQueryWrapper<Comment> wrapper) {
    Long userId = RequestContext.getUserId();
    Integer role = RequestContext.getRole();
    if (userId != null && (role == null || role != 1)) {
        wrapper.eq(Comment::getUserId, userId);   // 非管理员只能看自己的
    }
}

// 代表性实现 2 —— AnalysisServiceImpl.java:272-273（跨进程传递）
new URL(flaskBaseUrl + "/wordcloud?video_id=" + videoId
    + "&user_id=" + (非管理员 ? RequestContext.getUserId() : ""));
```
```python
# 代表性实现 3 —— wordcloud_service.py:178-181（Python 侧二次过滤）
if user_id:
    cursor.execute("SELECT content FROM comment WHERE video_id=%s AND user_id=%s", (video_id, user_id))
else:
    cursor.execute("SELECT content FROM comment WHERE video_id=%s", (video_id,))
```

### 由此产生的真实矛盾

| 场景 | 结果 |
|---|---|
| 用户 A 采集并清洗视频 V | `comment.user_id = A`，A 可正常查看 |
| 用户 B 对同一视频 V 再清洗 | 跨批次去重跳过全部 → **B 入库 0 条，且看不到任何数据** |
| B 想查看 A 已采的同一视频 | `WHERE user_id = B` → **0 行，界面显示空** |
| 管理员（role=1） | 绕过过滤，看全库 |

也就是说：**当前系统把「评论事实数据」错误地绑定了「谁执行了清洗」**。这与评审文档 3.3 的判断方向一致，但结论必须更明确：

> `user_id` **不能简单弃用**——一旦弃用，`CommentServiceImpl` 与 `wordcloud_service` 的隔离同时失效（所有非管理员看到空数据，或改为看到全部）。
>
> 正确做法：**保留 `user_id` 作为「清洗操作审计」**，新增独立的所有权/归属维度，并在 Phase 1 先确定业务问题——**评论数据是「用户私有」还是「平台共享 + 按任务授权」**。这个决策不定，任何所有权字段设计都是空中楼阁。

### 两种可选方向

| 方向 | 设计 | 代价 |
|---|---|---|
| **A. 共享数据 + 任务授权**（推荐） | `comment` 不设 owner；可见性由 `video`/`task` 的归属推导。用户能看到自己任务覆盖过的视频的全部评论 | 需改 `applyUserFilter` 逻辑，改为 `video_id IN (自己的任务覆盖的 video)` |
| **B. 严格私有** | 新增 `owner_user_id`，写入用 task.owner；同一视频多人采集产生重复行 | 需放弃跨批次去重（否则 B 采不到数据），存储翻倍 |

---

## 七、数据质量快照（14 行）

| 指标 | 值 |
|---|---|
| 总行数 | 14 |
| 不同 `video_id` | 1（`7703721523349239951`） |
| `content` 空字符串 / NULL | 0 / 0 |
| `content` 重复组 | **0** |
| `content` 冗余行 | **0** |
| `nickname` 空 | 0 |
| `region` 空或"未知" | 0 |
| `user_id` NULL | 0（全部为 `2`） |
| `publish_time` NULL | 0 |

**当前数据是干净的**，但这是「清空重采 + 应用层去重」的结果，不是数据库约束保证的。**没有唯一索引 → 并发写入仍可能产生重复**（审计已实测：3 并发同视频时 CSV 互相覆盖）。

---

## 八、Git 与运行环境基线

### Git 工作区（记录，未修改）

- 远程仓库：`suhuideyu/douyin_opinion_analysis`（与评审文档一致）
- 已修改：19 个文件（均为前几轮用户批准的开发成果）
- 未跟踪：`.dockerignore`、`.env.example`、`docker/`、`docs/`、`AppProperties.java`、`SchemaMigration.java`、`dy-frontend/public/` 等

### 容器状态

| 服务 | 状态 |
|---|---|
| mysql | 运行中（3307 映射） |
| flask / backend / frontend | 运行中 |

---

## 九、对《数据库设计专项评审 V1.0》的 6 条修正建议

| # | 位置 | 修正内容 |
|---|---|---|
| 1 | 第七节 Flyway 规划 | **整节作废**。项目无 Flyway。M0 必须先决策：引入 Flyway（需 `baseline-on-migrate` + `baselineVersion=0`）还是扩展 `SchemaMigration` 为带版本表的迁移器 |
| 2 | 2.2 `comment` 演进 | `user_id` 不是「含义不明确」，而是「写入=操作者 / 读取=所有者」。需在 Phase 1 先定业务归属模型，再决定是否新增字段 |
| 3 | 3.1 `video` 表 | `aweme_id VARCHAR(64)` 合理（现有 19 位），但 `comment.video_id varchar(32)` 改 BIGINT 需**双列共存期**：新增 `video_ref_id BIGINT NULL` → 回填 → 切换 → 删旧列 |
| 4 | 三/五节 索引建议 | 必须显式 `COLLATE=utf8mb4_0900_ai_ci`，否则与现有表 JOIN 会报 collation 冲突 |
| 5 | 3.6 checkpoint | 「先持久化再更新检查点」正确。补充：现有采集**由滚动被动捕获**，Cursor 从未被读取，`checkpoint.cursor` 字段可能长期为空，需 Plan B（`publish_time` 水位 + cid 集合） |
| 6 | 2.3 `analysis_result` | 实测其 5 个统计字段**写入后从未被读取**，真实作用仅是记录 `analyzedAt`。M4 可低风险直接废弃，只需迁移时间展示需求，无需"退化为缓存"的过渡 |

---

## 十、M0 待决策清单（需人工确认，本轮不决策）

1. **迁移器选型**：Flyway（标准、需 baseline）vs 扩展 SchemaMigration（轻量、需自研版本表）
2. **评论数据归属模型**：方向 A（共享+任务授权）vs 方向 B（严格私有）
3. **`comment.video_id` 改造方式**：双列共存 vs 保持字符串不建外键
4. **历史 14 条 cid**：置 NULL 等重采 vs 整体重采
5. **`analysis_result` 去留**：实测统计字段无人读取 → 建议 M4 直接废弃，`analyzedAt` 迁至 `video_insight` 或任务表（已可决策，风险低）
6. **collation 统一策略**：全部对齐 `utf8mb4_0900_ai_ci` vs 降级到 `general_ci`
7. **用户隔离逻辑收敛**：现有 5 处分散实现（4 Java + 1 Python），是否在 M1 一并收敛为单一入口

---

## 十一、执行边界确认

| 项 | 状态 |
|---|---|
| DDL（CREATE / ALTER / DROP） | **未执行** |
| DML（INSERT / UPDATE / DELETE） | **未执行** |
| Flyway / 迁移 | **未执行**（本身不存在） |
| 代码修改 | **未执行** |
| 配置 / 依赖修改 | **未执行** |
| Git 操作（索引 / 提交 / 分支） | **未执行** |
| 只读操作 | `SHOW CREATE TABLE` ×3、`SHOW TABLES LIKE` ×3（flyway / liquibase）、`SHOW INDEX` ×1、`information_schema` 查询 ×4（TABLES / COLUMNS / STATISTICS / KEY_COLUMN_USAGE）、`SELECT COUNT` 聚合 ×1、`grep` / `ls` 若干 |
| 新增文件 | 仅本文（`docs/audit/2026-11/18_DATABASE_BASELINE.md`） |
| 边界复核 | 表数 3、`comment` 字段 13、`comment` 行数 14 —— **与盘点前完全一致** |

**本阶段到此为止，等待 M0 决策确认后再进入 M1 设计。**
