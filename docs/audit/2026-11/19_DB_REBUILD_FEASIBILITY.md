# 19 · 全新数据库重建可行性评估（只读）

**评估日期**：2026-11-03
**执行模式**：READ ONLY
**前置依据**：`18_DATABASE_BASELINE.md`（数据库基线）+ 01～17 号审计报告
**评估问题**：在「测试数据可弃、不保留历史兼容」的前提下，全新重建数据库是否可行、代价多大、哪些代码会断
**授权状态**：**未授权执行任何清库 / DDL / 迁移**。本文仅为可行性评估。

---

## 零、结论摘要

**技术可行性：高。** 项目已具备「空库自动初始化」机制（`docker-entrypoint-initdb.d`），重建的门槛远低于常规遗留系统。

**但有一个关键认知需要先纠正**：

> 删掉测试数据 ≠ 删掉表结构约束。
> 真正决定重建成本的不是那 14 行评论，而是**代码里对列名的硬绑定**。

| 维度 | 评估 |
|---|---|
| 清空测试数据 | 影响面小，仅「界面无数据」，功能不中断 |
| 重建表结构（保留列名） | **成本低**，只需改 `dy_comment.sql` + 重启 |
| 重建表结构（改列名） | **成本中高**，需同步改 Java 实体 + 5 处 Python SQL + 前端 24 处字段引用 |
| 引入 Flyway | 可行，但与现有初始化机制**存在冲突**，需二选一 |
| 必须保留的真实资产 | **2 个种子管理员**（SQL 内可恢复）、**chrome-data 登录态卷**（独立，不受影响）、**1 个自注册用户**（会丢失） |

---

## 一、当前「清库重建」的技术路径

### 1.1 现有初始化机制（关键事实）

```yaml
# docker-compose.yml:29-32
volumes:
  - mysql-data:/var/lib/mysql
  # 首次启动自动执行建库建表脚本
  - ./dy_comment.sql:/docker-entrypoint-initdb.d/init.sql:ro
```

MySQL 官方镜像的 `docker-entrypoint-initdb.d` 机制：**仅在数据目录为空时执行**。这意味着重建的最小操作是：

```
删除卷 dy-comment_mysql-data → 修改 dy_comment.sql → 重启 mysql 容器
```

**不需要任何迁移工具即可完成全新初始化。** 这是本项目相较典型遗留系统的最大优势。

### 1.2 三种重建路径对比

| 方案 | 做法 | 优点 | 缺点 | 适用 |
|---|---|---|---|---|
| **A. 纯粹替换 SQL** | 删卷 → 重写 `dy_comment.sql` → 重启 | 零新增依赖、改动最小、与现有部署完全一致 | 无版本历史、无回滚、多次演进的表结构无法追溯 | 仍处于架构建设期、变更不频繁 |
| **B. 引入 Flyway** | 删卷 → 移除 initdb 挂载 → `V1__init.sql` → 启动自动执行 | 版本可追溯、可回滚、开发/生产同源 | 需加依赖、需删 `SchemaMigration`、需处理与 initdb 的冲突 | 进入长期迭代、多人协作 |
| **C. 两者并存** | 保留 initdb 建表 + Flyway 管理后续 | — | **必然冲突**：Flyway 启动时发现非空 schema 无历史表 → 需 baseline；且建表入口分裂 | ❌ 不推荐 |

**建议**：若确定长期演进，选 **B**；若只想先跑通新模型，选 **A**，后续再切 B。**不要选 C。**

### 1.3 方案 B 的三个具体坑（已核实）

**坑 1：Spring Boot 2.7.18 + MySQL 8 需要额外依赖**

```xml
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-mysql</artifactId>   <!-- Flyway 8.2+ 起 MySQL 支持独立成模块 -->
</dependency>
```

只加 `flyway-core` 会在启动时报 `Unsupported Database: MySQL 8.0`。
（版本由 Spring Boot 父 POM 管理，实测前需 `mvn dependency:tree` 确认具体版本号。）

**坑 2：`SchemaMigration.java` 必须移除**

它会在启动时无条件 `ADD COLUMN`（幂等），与 Flyway 争夺表结构控制权。若保留，会出现「Flyway 记录显示 V1，实际表结构被 SchemaMigration 偷偷改过」的版本失真。

**坑 3：initdb 挂载必须移除**

否则 MySQL 先建好 3 张表，Flyway 启动时检测到非空 schema 无历史表，直接失败：
```
Found non-empty schema(s) `dy_comment` but no schema history table.
Use baseline() or set baselineOnMigrate to true to initialize...
```
`baselineOnMigrate=true` 只是掩盖问题——历史表会被标为「已基线」，V1 的建表语句永不执行，**后续所有迁移都建立在未受控的表上**。正确的做法是移除挂载，让 Flyway 成为唯一建表入口。

---

## 二、代码依赖全景（清库的真正成本所在）

### 2.1 Java 实体（3 个，与 3 张表一一对应）

| 实体 | `@TableName` | 字段数 | 映射方式 |
|---|---|---|---|
| `User` | `user` | 6 | 驼峰↔下划线自动映射 |
| `Comment` | `comment` | 13 | 同上 |
| `AnalysisResult` | `analysis_result` | 11 | 同上 |

```yaml
# application.yml:15-21
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true   # ← 列名 video_id ↔ 字段 videoId 自动转换
  global-config:
    db-config:
      id-type: auto
```

**含义**：改列名必须同步改实体字段名，二者强绑定。这是「重建自由」的第一道成本。

**但有个好消息**：全项目**无 Mapper XML、无 `@Select/@Insert` 注解 SQL**，Java 侧 100% 走 MyBatis-Plus 的 Lambda 包装器（`Comment::getVideoId`）。改列名时只需改实体字段，**不需要改任何 SQL 字符串**。成本集中在 Python 的 1 处 INSERT 和前端引用。

### 2.2 Python 侧原生 SQL（5 处，全部只碰 `comment` 表）

| 位置 | SQL | 依赖字段 |
|---|---|---|
| `clean_main.py:90` | `SELECT nickname, content FROM comment WHERE video_id = %s` | nickname, content, video_id |
| `clean_main.py:97` | `INSERT INTO comment (user_id, video_id, nickname, douyin_id, douyin_uid, sec_uid, region, publish_time, content, likes, sentiment, created_at)` | **全部 12 个业务字段** |
| `wordcloud_gen.py:32` | `SELECT content FROM comment WHERE video_id = %s` | content, video_id |
| `wordcloud_service.py:179` | `SELECT content FROM comment WHERE video_id = %s AND user_id = %s` | content, video_id, user_id |
| `wordcloud_service.py:181` | `SELECT content FROM comment WHERE video_id = %s` | content, video_id |

**含义**：`comment` 表的 `nickname / content / video_id / user_id / region / publish_time / likes / sentiment / douyin_id / douyin_uid / sec_uid / created_at` 这 12 个列名被 Python 硬编码。改任何一个都要改 `clean_main.py:97` 的 INSERT 列表。

### 2.3 Vue 前端字段引用（关键约束）

实测前端模板中字段名的引用次数：

| 字段 | 引用次数 | 影响 |
|---|---|---|
| `region` | **24** | 地区分布、地图、筛选全部依赖 |
| `videoId` | **18** | 视频选择、所有分析接口入参 |
| `douyinId` | 5 | 高赞排行、评论列表 |
| `sentiment` | 2 | 情感标签 |
| `nickname` | 2 | 评论展示 |
| `totalComments` | 1 | 统计卡片 |

**含义**：重命名 `region` 需改 24 处前端引用 + 后端 API 返回 key + Java 实体 + 数据库列。**建议这三类字段名保持不变，新模型只做「新增」不做「重命名」。**

### 2.4 依赖收敛结论

| 变更类型 | 需要同步修改的位置数 | 风险 |
|---|---|---|
| 新增表 | 0（仅新代码引用） | 低 |
| `comment` 新增列 | 1（`clean_main.py` INSERT）+ 1（实体） | 低 |
| `comment` **改列名** | 1（DDL）+ 1（实体）+ 1（Python INSERT）+ N（前端引用） | **中高** |
| 删表 | 需先清空所有引用（`AnalysisResult` 全套 + `getVideoList`） | 中 |

---

## 三、必须保留 vs 可以丢弃（数据清点）

### 3.1 可以安全丢弃

| 对象 | 现状 | 丢弃后果 |
|---|---|---|
| `comment` 14 行 | 测试数据（视频 7703721523349239951） | 界面显示"暂无数据"（已有空状态提示），无功能断裂 |
| `analysis_result` 1 行 | 且实测其统计字段**无人读取** | 仅视频列表的 `analyzedAt` 为空，无影响 |
| `comment` 的 AUTO_INCREMENT=129 | 历史删除痕迹 | 重建后归 1，反而更干净 |

### 3.2 必须保留（或重建后恢复）

| 对象 | 现状 | 处理方式 |
|---|---|---|
| **2 个种子管理员** | `dy_comment.sql:62-65` 有 `INSERT`，phone=`<REDACTED-ADMIN-PHONE-1>` / `<REDACTED-ADMIN-PHONE-2>`，密码 `<REDACTED-WEAK-PASSWORD-HASH>` | **重建时自动恢复**——只要保留 SQL 里的 INSERT 语句 |
| **1 个自注册用户** | id=3，phone=`15661167949`，role=0，密码 **BCrypt**（60 字符） | ⚠️ **会永久丢失**。如需保留，必须先导出该行 password 哈希并写入新的种子脚本 |
| **`chrome-data` 卷** | `dy-comment_chrome-data`，存抖音登录态 | ✅ **独立卷，删 mysql-data 不影响** |
| **`.env` 配置** | **不存在实体文件**，全部走默认值（`DB_PASSWORD=root` 等） | 无迁移动作 |
| **采集产物 CSV** | `Py_Data/Datas/` 是宿主机 bind mount | ✅ 不受影响 |

**⚠️ 唯一需要人工决策的数据**：id=3 的自注册用户。它是真实的 BCrypt 账号，与 SQL 里两个 MD5 种子账号不同。若这是你在用的账号，重建前必须导出。

### 3.3 密码格式的技术债（重建时值得一并处理）

| 账号 | 哈希长度 | 算法 | 来源 |
|---|---|---|---|
| id=1 管理员 | 32 | **MD5** | `dy_comment.sql` 种子 |
| id=2 管理员 | 32 | **MD5** | `dy_comment.sql` 种子 |
| id=3 普通用户 | 60 | **BCrypt** | 系统注册 |

`UserServiceImpl` 有双格式兼容逻辑。重建时建议**统一改为 BCrypt**（种子脚本改用预生成的 BCrypt 哈希），消除历史债。

---

## 四、清库后的功能影响面（逐功能核查）

| 功能 | 依赖 | 清库后表现 | 是否断裂 |
|---|---|---|---|
| 登录 | `user` 表 | 2 个种子管理员自动恢复可登录；id=3 用户失效 | 部分（仅第 3 个账号） |
| 采集（在线） | 无 DB 依赖 | 正常，采集后写 CSV | 否 |
| 采集（本机导入） | `Datas/` 目录 | 正常 | 否 |
| 清洗入库 | `comment` 表 | 正常，空表直接插入 | 否 |
| 总览大屏 | `comment` + `analysis_result` | 显示"暂无已入库的评论数据"空状态 | 否（已有降级 UI） |
| 评论列表 | `comment` | 空列表 | 否 |
| 地区分布 / 情感 / 词云 | `comment` | 空数组，图表空白 | 否 |
| 核心话题（AI） | `comment` → Flask → DeepSeek | 无数据时不生成 | 否 |
| 视频下拉列表 | `comment` distinct | 空列表 | 否 |
| 管理后台 | `user` | 正常 | 否 |

**结论：清库不会造成任何功能断裂**，只会让界面进入"空数据"状态，采集一次即可恢复完整链路。

---

## 五、Flyway 可行性评估

### 5.1 兼容性

| 项 | 状态 |
|---|---|
| Spring Boot 2.7.18 | ✅ 内置 Flyway 依赖管理 |
| Java 8 | ✅ Flyway 8.x/9.x 均支持 |
| MySQL 8.0.46 | ✅ 需 `flyway-mysql` 模块 |
| MyBatis-Plus 3.5.5 | ✅ 无冲突（Flyway 先于 ORM 初始化） |

### 5.2 建表脚本迁移改造量

现有 `dy_comment.sql`（**实际 64 行**，含 3 条 `CREATE TABLE` + 2 条种子 `INSERT`）→ `V1__init.sql`：

| 改动 | 说明 |
|---|---|
| 删除 `CREATE DATABASE` / `use` | Flyway 连的是已存在的库，不需要建库语句 |
| 保留 3 张 `CREATE TABLE` | 直接迁移 |
| 保留 2 条 `INSERT`（种子管理员） | 建议改为 BCrypt 哈希 |
| **显式补 `COLLATE=utf8mb4_0900_ai_ci`** | 见 5.3 |
| 新增表（video/keyword/task 等） | 拆成 V2～Vn 分版本 |

### 5.3 必须顺带修掉的 collation 陷阱

```yaml
# docker-compose.yml:26-28 —— 当前配置
command:
  - --character-set-server=utf8mb4
  - --collation-server=utf8mb4_general_ci     # ← server 级
```

而实测三张表全部是 **`utf8mb4_0900_ai_ci`**（MySQL 8 的 utf8mb4 默认），说明 `dy_comment.sql` 里只写了 `DEFAULT CHARSET utf8mb4` 未写 COLLATE，建表时走了引擎默认而非 server 配置。

**后果**：server 与表 collation 不一致。当前无跨表 JOIN 未暴露，但新表若继承 server 的 `general_ci`，与旧表 JOIN 会报 `Illegal mix of collations`。

**修复**：V1 脚本中每张表显式声明 `COLLATE=utf8mb4_0900_ai_ci`，并考虑把 compose 的 `--collation-server` 一并对齐。

---

## 六、建议的重建顺序（方案 B，如选 Flyway）

```
阶段 0：数据备份
  ├─ 导出 id=3 用户的 password 哈希
  └─ 确认 2 个种子管理员密码可重置

阶段 1：改造
  ├─ pom.xml 加 flyway-core + flyway-mysql
  ├─ 删除 SchemaMigration.java
  ├─ dy_comment.sql → src/main/resources/db/migration/V1__init.sql
  │    ├─ 移除 CREATE DATABASE / use
  │    ├─ 补 COLLATE=utf8mb4_0900_ai_ci
  │    ├─ 种子管理员改用 BCrypt
  │    └─ comment 表直接含 cid / parent_cid / platform（不再需要 V2 补列）
  └─ 移除 docker-compose 的 initdb 挂载

阶段 2：重建
  ├─ docker compose down
  ├─ docker volume rm dy-comment_mysql-data      ← 仅删 mysql，保留 chrome-data
  └─ docker compose up -d

阶段 3：验收
  ├─ flyway_schema_history 记录 V1 成功
  ├─ 三表（或新模型全部表）结构符合预期
  ├─ 2 个管理员可登录
  ├─ 采集 → 清洗 → 入库 → 大屏 全链路跑通
  └─ 前端无 500 / 无字段缺失
```

**关键**：`docker volume rm` 只删 `dy-comment_mysql-data`，**绝不能碰 `dy-comment_chrome-data`**（登录态）。

---

## 七、验收标准（可量化）

| # | 验收项 | 判定方式 |
|---|---|---|
| 1 | Flyway 历史表存在且 V1 为 success | `SELECT * FROM flyway_schema_history` |
| 2 | 表数量与设计一致 | `information_schema.TABLES` 计数 |
| 3 | 所有表 collation 统一 | `SELECT TABLE_COLLATION ... GROUP BY` 仅 1 个值 |
| 4 | `comment` 含 cid 且唯一索引生效 | 重复插入同 cid 应报错 |
| 5 | 管理员可登录 | POST `/api/user/login` 返回 200 |
| 6 | 采集→入库→大屏链路 | 采集 1 个视频 → 清洗 → 大屏有数据 |
| 7 | 无代码引用已删表 | 全项目 `grep` 无 `analysis_result` 残留（若废弃） |

---

## 八、风险与未验证事项

| 风险 | 级别 | 说明 |
|---|---|---|
| id=3 用户丢失 | 🟡 中 | 需重建前导出哈希，否则该账号永久失效 |
| 删除错卷导致登录态丢失 | 🔴 高 | 只能删 `mysql-data`，`chrome-data` 必须保留 |
| Flyway 版本与 SB 2.7.18 不匹配 | 🟡 中 | 实施前需 `mvn dependency:tree` 确认 |
| 改列名引发连锁断裂 | 🟡 中 | 前端 `region` 引用 24 处，建议不改名只新增 |
| collation 未统一 | 🟡 中 | 新表须显式 `COLLATE`，否则日后 JOIN 报错 |
| 新模型表数量多（12+） | 🟢 低 | 一次性建齐比逐表补列更干净，风险反而更低 |

**未验证事项**（本轮受限于只读，未实测）：

- Flyway 在实际容器中的启动顺序与耗时
- 新模型全表建齐后的启动时间
- 种子管理员改为 BCrypt 后 `UserServiceImpl` 兼容逻辑是否仍正常

---

## 九、执行边界确认

| 项 | 状态 |
|---|---|
| 清库 / 删卷 | **未执行** |
| DDL（CREATE / ALTER / DROP） | **未执行** |
| DML（INSERT / UPDATE / DELETE） | **未执行** |
| Flyway 引入 / 迁移 | **未执行** |
| 代码 / 配置 / 依赖修改 | **未执行** |
| Git 操作 | **未执行** |
| 只读操作 | `SHOW` / `SELECT` / `information_schema` / `grep` / `ls` / `docker volume ls` |
| 新增文件 | 仅本文（`docs/audit/2026-11/19_DB_REBUILD_FEASIBILITY.md`） |

**本文不构成清库或迁移的授权。需人工确认第四节「必须保留的数据」与第一节「路径选型」后，方可进入实施任务卡。**
