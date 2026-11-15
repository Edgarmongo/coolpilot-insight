# 重构状态与交接（截至 R3-04）

最后更新：2026-11-15 19:00
HEAD：R3-05a 提交后　测试基线：**57/57 通过**　容器：四服务 healthy　工作区：干净

---

## 一、一句话状态

**地基完成，业务模块迁移到一半。**

- 已落地：`common`、`system`、`source`、`collection`（代码边界）、`integration`（契约 + 子进程客户端）
- 未开始：`content`、`analysis`、`lead`、`conversion`、`analytics`
- 数据库：新基线脚本已写好并在临时库验证，**业务库仍是旧结构，尚未切换**

所以：**系统可以正常运行，但还没有变成"新架构"。**

---

## 二、已完成批次（8 个，均已提交并可回退）

| 批次 | 内容 | 提交 | 回退 tag | 测试 |
|---|---|---|---|---|
| R2-01 | 根包迁移 `com.coolskyai` → `com.coolpilot` | `6f42d92` | `r2-01-root-package` | 21 |
| R2-02 | 拆 `GovernanceService` 为权限/审计/配置/可见性 | `ebd9375` | `r2-02-governance-split` | 29 |
| R2-03 | 配置管理 + 审计查询接口 | `6e4bed3` | `r2-03-system-config` | 36 |
| R2-04 | 统一 `BizException` + 错误码 + HTTP 状态 | `1aff0af` | `r2-04-unified-exception` | 36 |
| R3-01 | 新库基线 37 表（临时库验证通过，未切库） | `760362b` | `r3-01-db-baseline` | 36 |
| R3-02 | `source` 模块（数据源/能力/关键词） | `b736483` | `r3-02-source` | 45 |
| R3-03a | 外部调用收敛到 `integration` | `8bc1fc0` | `r3-03a-integration` | 50 |
| R3-03b | 采集任务域迁入 `collection` | `6497a0c` | `r3-03b-collection` | 50 |
| R3-04 | 采集服务 HTTP 化（stub 适配器） | `97e2915` | `r3-04-collection-http` | 50 + Python 9 |
| R3-05a | content 表 + ingest 服务（未切库） | 见 git log | `r3-05a-content-ingest` | 57 |

R0/R1 的 19 份审计与架构文档在 `docs/architecture/refactor/`，提交 `6063ecf`。

---

## 三、当前模块真实完成度

| 模块 | 代码边界 | 对应表 | 说明 |
|---|---|---|---|
| `common` | ✅ | 无 | 响应/异常/错误码/认证/分页就绪 |
| `system` | ✅ | `user`/`system_config`/`audit_log`（旧名） | 用户/配置/审计可用，`sys_role` 表待切库 |
| `source` | ✅ | `ds_source`/`ds_account`/`ds_keyword`（新） | 唯一已用新表的模块 |
| `collection` | ✅ 代码 / ❌ 表名 | `collection_task*`（旧名） | 任务域已迁包，表未改名 |
| `content` | ◐ 新表+ingest 就位 | `ct_*`（新，V10）+ 旧表并存 | 写入口已建；读路径仍走旧表 |
| `analysis` | ❌ | `analysis_*`/`comment_analysis` 等（旧名） | 仍全在旧 `service`/`entity` 包 |
| `analytics` | ❌ | `report`/`report_version`（旧名） | 同上 |
| `lead` | ❌ | 无 | 全新领域 |
| `conversion` | ❌ | 无 | 全新领域 |
| `integration` | ◐ | 无 | `CollectionClient`/`AiClient` 已就位，HTTP 实现待接 |

**旧包现状**：`controller/`（8 个）、`service/`（14 个）、`entity/`（18 个）、`mapper/`（21 个）仍在技术分层结构下。

---

## 四、剩余工作（按依赖顺序）

### R3-05a · content 表与 ingest（✅ 已完成）

| 项 | 内容 |
|---|---|
| 目标 | `video`/`comment`/`video_grant` → `ct_content`/`ct_comment`/`ct_grant`，新增 `ct_raw_archive`、`ct_author` |
| 前置 | R3-01（V1 脚本已就绪） |
| 方式 | 新增 V10 建 `ct_*` 表 → 迁实体/Mapper → 改 Service 引用，**不动旧表** |
| 涉及文件 | 实体 5、Mapper 5 + XML 5、Service 约 12（`VideoService`/`CommentServiceImpl`/`ContentAccessService`/`AnalysisServiceImpl`/`AiAnalysisService`/`ReportService`/`OverviewService`/`CompareService`/`CleanServiceImpl`/`ReviewService`/`CollectServiceImpl`） |
| 风险 | **中高**：`video` 表字段要拆到 `ct_content`（标准化）+ `ct_raw_archive`（原始），字段映射不是一一对应 |
| 验收 | `mvn test` 50/50；`/api/comments`、`/api/overview` 数据不变 |

### R3-05b · 切库（最危险，不可逆）

| 项 | 内容 |
|---|---|
| 目标 | 归档 V1~V10，启用新 `V1__init.sql`，删旧表 |
| 前置 | R3-05a 全绿 + `analysis`/`analytics` 也已迁完（否则旧实体无处安放） |
| 风险 | **高**：37 表与旧 26 表结构不兼容，一步错则全站不可用 |
| 回滚 | `deploy/backup/coolpilot_insight_20261115_182706.sql` |

> 关键判断：**R3-05b 不能只等 R3-05a**。`analysis`、`analytics` 的实体也指向旧表，
> 切库会一起废掉。合理顺序是先把 content/analysis/analytics 全部迁到新表名，最后统一切。

### R4 · analysis 模块

- 迁 `AiController`/`AiAnalysisService`/`ReviewController`/`ReviewService` 与 8 个实体
- 消除 `AiController` 最后一处 Mapper 直连（`UsageRecordMapper`）
- `Profile` 版本化落地：`an_task.profile_id` 锁定版本，结果存 model/prompt 快照
- 验收：同一评论用两个 Profile 版本分析，结果并存可区分

### R5 · lead 模块（全新）

- 6 张 `ld_*` 表已有 DDL，需要：实体、Mapper、`LeadService`（信号→线索幂等）、`LeadScoringService`（五维）、审核、跟进
- 验收：一条评论 → 信号 → 线索 → 证据链完整；同作者可产生多条线索

### R6 · conversion + analytics

- conversion：产品镜像同步（CoolSkyAI）、匹配、成交登记；**不建订单账本**
- analytics：驾驶舱、内容统计、需求趋势、报告（迁 `ReportService`）
- 验收：analytics 无写其他模块表的 Mapper

### R7 · 前端适配 + 全链路验收

- 58 个 API 方法改 `/api/v1/{module}/...`，菜单按 10 模块重排
- 补 lead/conversion/source 页面（6 个）
- 端到端用例：登录 → 采集（mock）→ ingest → 分析（mock）→ 线索 → 审核 → 报告
- Docker 干净环境部署验证

---

## 五、切库检查清单（R3-05b 执行前逐项确认）

```bash
# 1. 确认目标（同机有 dtk_* 与其他 coolpilot_* 卷，绝不能错）
docker ps --format '{{.Names}}' | grep cpi-mysql
docker exec cpi-mysql mysql -uroot -proot -N -e "SHOW DATABASES"

# 2. 重新备份（备份要新于最后一次数据变更）
docker exec cpi-mysql mysqldump -uroot -proot --databases coolpilot_insight \
  --single-transaction > deploy/backup/pre-cutover-$(date +%Y%m%d_%H%M).sql

# 3. 确认测试全绿（不绿不许切）
cd backend && mvn -s ../deploy/docker/maven-settings.xml test

# 4. 切换
git mv backend/src/main/resources/db/migration docs/architecture/refactor/legacy-migrations
git mv backend/src/main/resources/db/migration-next backend/src/main/resources/db/migration
docker exec cpi-mysql mysql -uroot -proot -e \
  "DROP DATABASE coolpilot_insight; CREATE DATABASE coolpilot_insight DEFAULT CHARACTER SET utf8mb4;"
docker compose up -d backend

# 5. 验证
docker exec cpi-mysql mysql -uroot -proot -N -e \
  "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='coolpilot_insight'"  # 应为 38（37+flyway）
curl -s localhost:8080/api/health
```

---

## 六、踩过的坑（后续别再踩）

| 坑 | 表现 | 对策 |
|---|---|---|
| **Boot 4 模块化** | Flyway 不执行迁移、`ObjectMapper` 无 bean，都**不报编译错** | 依赖自动配置的能力必须实测启动日志；已遇到 Flyway、Jackson 两次 |
| **通配 import** | 移动类后编译报几十个 cannot find symbol | 迁模块前先查 `import ...*`；本次 R3-03b 因此返工 |
| **`package` 路径计算** | Windows 下用 cwd 拼包名，写出 `package backend.src...` | 一律用 `os.path.relpath(root, java_root)` |
| **唯一索引 + NULL** | `active_flag` 终态必须显式 `SET NULL`，否则唯一键永远占着 | `MapperCrudSemanticsTest` 有专门用例守着 |
| **响应字符集** | `getWriter()` 默认 ISO-8859-1，中文变 `????` | 已修：`writeJson()` 显式 UTF-8 |
| **测试用真实外键** | 硬编码 userId 违反 FK，4 条用例失败 | 测试内注册真实用户，不要塞假 id |
| **`python app/server.py`** | `No module named 'app'`（test_client 发现不了） | server.py 顶部加 `sys.path` 引导 |
| **两份项目共用 compose name** | 与 `douyin_opinion_analysis` 副本互斥 | 操作前确认容器归属 |

---

## 七、如何继续

每个批次固定流程：

1. 读本文件 + `docs/architecture/refactor/R1-09-implementation-roadmap.md` 对应任务
2. 改动 → `mvn test` → 容器重建 → curl 验证真实接口
3. 写批次报告（`R*-*.md`）→ 提交 → 打 tag

**不要跳过 `mvn test`，也不要在测试未绿时切库。**

---

## 八、诚实结论

- 本次交付的是**可运行、可回退、测试全绿**的阶段性成果，不是"重构完成"
- 剩余工作量估计：content/analysis/analytics 迁移约等于已完成部分的工作量；lead/conversion 是全新开发
- 唯一不可逆的动作是切库，已把风险与回滚准备好，**但没有执行**——因为前置条件还不满足
