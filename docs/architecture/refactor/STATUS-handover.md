# 重构状态与交接

最后更新：2026-11-15 19:06　HEAD：`3be0578`　测试：**57/57**（Java）+ 9/9（Python）
容器：四服务 healthy　工作区：干净　回退点：14 个 tag

---

## 一、一句话状态

**地基与内容写入链路完成，读路径尚未切换。**

- 已模块化：`common`、`system`、`source`、`collection`、`content`（新表+写入口）
- 未开始：`analysis`、`analytics`、`lead`、`conversion`
- 数据库：新旧并存，`ct_*` 已有数据且 **id 与旧表对齐**；旧表仍是读路径

---

## 二、已完成批次（12 个）

| 批次 | 内容 | 提交 | tag | 测试 |
|---|---|---|---|---|
| R2-01 | 根包迁移 `com.coolskyai` → `com.coolpilot` | `6f42d92` | `r2-01-root-package` | 21 |
| R2-02 | 拆 `GovernanceService` | `ebd9375` | `r2-02-governance-split` | 29 |
| R2-03 | 配置管理 + 审计查询 | `6e4bed3` | `r2-03-system-config` | 36 |
| R2-04 | 统一异常与错误码 | `1aff0af` | `r2-04-unified-exception` | 36 |
| R3-01 | 37 表新基线（临时库验证，未切） | `760362b` | `r3-01-db-baseline` | 36 |
| R3-02 | `source` 模块 | `b736483` | `r3-02-source` | 45 |
| R3-03a | 外部调用收敛到 `integration` | `8bc1fc0` | `r3-03a-integration` | 50 |
| R3-03b | 采集任务域迁入 `collection` | `6497a0c` | `r3-03b-collection` | 50 |
| R3-04 | 采集服务 HTTP 化（stub） | `97e2915` | `r3-04-collection-http` | 50+P9 |
| R3-05a | `ct_*` 五表 + ingest 服务 | `68be51b` | `r3-05a-content-ingest` | 57 |
| R3-05b-1 | 旧数据迁入新表（V11） | `0cc5d9b` | `r3-05b1-data-migration` | 57 |
| R3-05b-2 | **新旧主键对齐**（V12） | `3be0578` | `r3-05b2-align-ids` | 57 |

R0/R1 的 19 份文档在 `docs/architecture/refactor/`（`6063ecf`）。

---

## 三、模块完成度

| 模块 | 代码 | 表 | 说明 |
|---|---|---|---|
| `common` | ✅ | — | 响应/异常/错误码/认证/分页 |
| `system` | ✅ | 旧名 | 用户/配置/审计可用 |
| `source` | ✅ | `ds_*` 新 | 唯一完全用新表的模块 |
| `collection` | ✅ | 旧名 | 任务域已迁包，表名待改 |
| `content` | ◐ | `ct_*` 新（有数据） | **写入口完成，读路径仍走旧表** |
| `analysis` | ❌ | 旧名 | 8 实体 + `AiController` 1 处 Mapper 直连 |
| `analytics` | ❌ | 旧名 | 报告 2 实体 + 统计查询 |
| `lead` / `conversion` | ❌ | 无 | 全新领域 |
| `integration` | ◐ | — | 契约+子进程客户端；HTTP 实现待接 |

---

## 四、下一步：切 content 读路径（唯一的大块）

**前置条件已满足**：`ct_content.id == video.id`、`ct_comment.id == comment.id`，
所以不需要 id 重映射，只需换表名与列名。

### 必须整批改的类

| 类 | 改动 |
|---|---|
| `service/VideoService` | `video` → `ct_content`；`aweme_id` → `external_content_id` |
| `service/impl/CommentServiceImpl` | `comment` → `ct_comment`；`cid`/`content`/`publish_time` 换列名；楼中楼 `parent_cid` → `parent_external_id` |
| `content/application/ContentAccessService` | `video_grant` → `ct_grant` |
| `service/impl/CleanServiceImpl` | 改为调用 `ContentIngestService`，不再直写旧表 |
| `service/impl/AnalysisServiceImpl`、`service/AiAnalysisService` | 跟随 `VideoService` 的查询变化 |
| `service/ReportService`、`OverviewService`、`CompareService`、`ReviewService` | 同上 |
| `collection` 侧 `CollectServiceImpl` | `comment` 写入改走 ingest |

### 列映射速查

| 旧 | 新 |
|---|---|
| `video.aweme_id` | `ct_content.external_content_id` |
| `video.cover_url` | `ct_content.source_url` |
| `video.publish_time` | `ct_content.published_at` |
| `video.metadata_json` | `ct_content.metrics_json` |
| `comment.video_id` | `ct_comment.content_id` |
| `comment.cid` | `ct_comment.external_comment_id` |
| `comment.parent_cid` | `ct_comment.parent_external_id` |
| `comment.content` | `ct_comment.text` |
| `comment.publish_time` | `ct_comment.published_at` |

### 验收

`mvn test` 62/62 + `/api/comment/list?videoId=1` 返回 19 条 + `/api/overview` 数据不变。

### 已排除的阻塞点

`root_cid`（旧表有、新表没有）经核实**只被查询带出、业务逻辑从未读取**，
楼中楼组装只用 `cid` + `parentCid`。切换时把该字段删掉或返回 null 即可，
**不需要给 `ct_comment` 补列**。详见 `R3-05b3-read-parity.md` §8。

---

## 五、之后再切库（R3-05b-3）

**必须等 content + analysis + analytics 全部迁完**——它们的实体都指向旧表，
提前切会导致启动即失败。

```bash
# 1. 确认目标与备份
docker ps --format '{{.Names}}' | grep cpi-mysql
docker exec cpi-mysql mysqldump -uroot -proot --databases coolpilot_insight \
  --single-transaction > deploy/backup/pre-cutover-$(date +%Y%m%d_%H%M).sql

# 2. 测试必须全绿才允许继续
cd backend && mvn -s ../deploy/docker/maven-settings.xml test

# 3. 切换（归档旧迁移，启用 V1 基线）
git mv backend/src/main/resources/db/migration docs/architecture/refactor/legacy-migrations
git mv backend/src/main/resources/db/migration-next backend/src/main/resources/db/migration
docker exec cpi-mysql mysql -uroot -proot -e \
  "DROP DATABASE coolpilot_insight; CREATE DATABASE coolpilot_insight DEFAULT CHARACTER SET utf8mb4;"
docker compose up -d backend

# 4. 验证 38 张表（37 + flyway_schema_history）
```

回滚：导入 `deploy/backup/*.sql`。

---

## 六、踩坑记录（10 条，都是真踩过的）

| 坑 | 表现 | 对策 |
|---|---|---|
| **Boot 4 模块化** | Flyway 不执行、`ObjectMapper` 无 bean，**均不报编译错** | 依赖自动配置的能力必须看启动日志；已遇 Flyway、Jackson 两次 |
| **通配 import** | 翻包后几十个 cannot find symbol | 迁模块前先查 `import ...*` |
| **`package` 路径** | Windows 下写出 `package backend.src...` | 用 `os.path.relpath(root, java_root)` |
| **唯一索引 + NULL** | `active_flag` 不置 NULL 则唯一键永久占用 | 显式 `SET NULL`，有测试守护 |
| **响应字符集** | `getWriter()` 默认 ISO-8859-1，中文变 `????` | `writeJson()` 显式 UTF-8 |
| **404 变 500** | `NoResourceFoundException` 被兜底吞成 INTERNAL | 单独处理，返回 40400 |
| **collation 不一致** | 旧表 `0900_ai_ci` vs 新表 `unicode_ci`，跨表比较报 1267 | 每处比较显式 `COLLATE` |
| **自增不回滚** | 迁移前试跑并回滚 → 正式执行 id 跳号 → 关联串数据 | 迁移显式指定 id，或独立库验证 |
| **测试用真外键** | 硬编码 userId 违反 FK | 测试内注册真实用户 |
| **python 启动路径** | `No module named 'app'`，test_client 发现不了 | server.py 顶部加 `sys.path` 引导 |

---

## 七、如何继续

1. 读本文件 + `R1-09-implementation-roadmap.md` 对应任务
2. 改动 → `mvn test` → 容器重建 → curl 验证真实接口
3. 写批次报告 → 提交 → 打 tag

**不要在测试未绿时切库；不要只改一半读路径就提交。**

---

## 八、诚实结论

- 交付的是可运行、可回退、测试全绿的阶段成果，**不是重构完成**
- 剩余最大一块是「切 content 读路径」（约 12 个类），之后是 analysis / analytics
- lead / conversion 是全新开发，不属于"重构"
- 唯一不可逆动作是切库，风险与回滚已备好，**尚未执行**
