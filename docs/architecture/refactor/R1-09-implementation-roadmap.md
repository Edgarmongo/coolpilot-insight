# R1-09 · R2–R7 实施路线图

前置说明：R2 的 `common` + `system` 已完成（commit `3ef5ea3`，21 个测试通过），
下表把已完成部分拆成可验收的小项，便于核对。

## 阶段总览

| 阶段 | 内容 | 前置 | 出口标准 |
|---|---|---|---|
| R2 | common + system + 根包迁移 | — | 认证可用、21 测试绿、无 Controller 直连 Mapper |
| R3 | 数据库重建 + source/collection/content | R2 | 空库 V1 一次性建全表；采集→入库跑通 |
| R4 | analysis + AI Service 契约 | R3 | 评论分析落到 `an_result`，Profile 版本可追溯 |
| R5 | lead | R4 | 线索可从信号生成、带证据、可审核 |
| R6 | conversion + analytics | R5 | 匹配/成交可记录，驾驶舱出数 |
| R7 | 前端适配 + 全链路验收 | R2–R6 | 19 份文档的验收项全部满足 |

---

## R2 · 公共基础与系统管理

### R2-01 根包迁移（`com.coolskyai.coolpilot.insight` → `com.coolpilot.insight`）

| 项 | 内容 |
|---|---|
| 目标 | 统一根包，消除 "coolskyai" 历史痕迹 |
| 前置 | 无 |
| 修改目录 | `backend/src/main/java`、`backend/src/test/java`、`resources/mapper/*.xml`、`application.yml` |
| 新增 | 无 |
| 修改 | 104 个 Java 的 package/import；25 个 XML 的 namespace/type；`@MapperScan`；`type-aliases-package` |
| 删除 | 旧包目录 |
| 数据库变化 | 无 |
| API 变化 | 无 |
| 测试 | `mvn test` 21/21 |
| 验收 | 编译通过；测试全绿；`grep -r "com.coolskyai" backend/src` 为 0 |
| 风险 | 一次性大范围重命名，若有遗漏会编译失败（**可控**，编译器会全部报出来） |

### R2-02 governance 拆分

| 项 | 内容 |
|---|---|
| 目标 | 切除 content ↔ system 的潜在循环 |
| 修改目录 | `service/GovernanceService`、`system/`、`content/` |
| 新增 | `system.application.PermissionService`（角色判定）、`content.application.ContentAccessService`（可见性规则） |
| 修改 | 8 个调用方改为依赖新接口 |
| 测试 | 现有 21 个 + 新增 3 个（管理员可见全部 / 所有者可见自己任务 / 无授权不可见） |
| 验收 | `GovernanceService` 删除；无跨模块 Mapper 访问 |
| 风险 | 权限判定回归会导致越权或看不到数据，**必须有测试覆盖** |

### R2-03 system 完善

| 项 | 内容 |
|---|---|
| 目标 | 补角色表、配置管理、审计查询 |
| 数据库变化 | `sys_role`、`sys_user_role` 启用（V1 中已建）；`sys_config` 加审计列 |
| API | 新增 `GET /system/configs`、`GET /system/audit-logs` |
| 测试 | 新增角色鉴权 2 例、配置读写 1 例 |
| 验收 | 非管理员访问管理端返回 40301 |

### R2-04 统一异常与响应

| 项 | 内容 |
|---|---|
| 目标 | 业务代码不再抛 `IllegalArgumentException` 当业务异常 |
| 新增 | `common.exception.BizException(code, message)`、错误码枚举 |
| 修改 | `GlobalExceptionHandler` 映射到统一 `Result`；各 Service 逐步替换 |
| API 变化 | 错误响应体增加 `code`、`traceId` |
| 测试 | 新增 2 例（参数错误 → 40001；无权限 → 40301） |
| 验收 | 前端能按 `code` 区分错误类型 |

---

## R3 · 数据库重建 + 数据源 / 采集 / 内容

### R3-01 Flyway V1 重建

| 项 | 内容 |
|---|---|
| 目标 | 37 张表一次建全，旧库清空 |
| 前置 | R2 完成 |
| 修改目录 | `backend/src/main/resources/db/migration/` |
| 新增 | `V1__init.sql`（37 表 + 索引 + 外键 + 种子：1 个管理员、1 个 DOUYIN 数据源、1 个默认 Profile） |
| 删除 | V1~V8（旧脚本归档到 `docs/architecture/refactor/legacy-migrations/`） |
| 数据库变化 | **整库重建**：删除 `coolpilot_insight` 并新建 |
| 测试 | 空库启动 → Flyway 自动建表 → `SHOW TABLES` = 37 |
| 验收 | 干净 MySQL 容器上 `docker compose up` 后 `/api/v1/health` 返回 db UP |
| 风险 | **删库不可逆**。执行前必须：确认容器为 `cpi-mysql`、库名为 `coolpilot_insight`、导出备份到 `deploy/backup/`（即使允许删除，也保留 7 天） |

### R3-02 source 模块

| 项 | 内容 |
|---|---|
| 目标 | 数据源与关键词从代码写死变为可配置 |
| 新增目录 | `source/{controller,application,domain,infrastructure}` |
| 新增 | `SourceQueryService`、`SourceCapabilityService`、`KeywordService`；`ds_source`/`ds_account`/`ds_keyword` 实体与 Mapper |
| 修改 | `KeywordController` → `source.controller.KeywordController` |
| 迁移 | `entity.Keyword`、`entity.KeywordVideo`、`mapper.Keyword*` |
| API | `/api/v1/source/*`（8 个） |
| 测试 | 新增 3 例（列数据源、能力状态、关键词 CRUD） |
| 验收 | 前端「数据源管理」可列出 DOUYIN 及其能力状态 |

### R3-03 collection 模块

| 项 | 内容 |
|---|---|
| 目标 | 采集任务与子进程解耦 |
| 新增目录 | `collection/{controller,application,domain,infrastructure}` |
| 新增 | `CollectTaskService`、`CollectDispatchService`、`CollectionQueryService`；`integration.collection.CollectionClient` |
| 迁移 | `CollectController`、`CollectServiceImpl`、4 个 collection 实体与 Mapper、`DiscoveryService`（发现任务部分） |
| 删除 | `CollectServiceImpl` 中的 `ProcessBuilder`（迁到 client） |
| API | `/api/v1/collection/*`（8 个） |
| 测试 | 迁移现有 3 例 + 新增 2 例（client 超时、幂等 jobId） |
| 验收 | 创建任务 → 状态机流转 → 断点续采可用 |

### R3-04 collection-service 服务化

| 项 | 内容 |
|---|---|
| 目标 | 脚本 → HTTP 服务 |
| 修改目录 | `collection-service/` |
| 新增 | `app/server.py`（`/health`、`/v1/capabilities`、`/v1/jobs*`）、`app/adapters/douyin/adapter.py`（实现 `PlatformAdapter`） |
| 修改 | `comment.py` 抽出可被调用的函数；`deploy/docker/collection-service.Dockerfile`（含 Chrome） |
| 数据库变化 | 无（不再直写库） |
| 测试 | 新增 `collection-service/tests/test_adapter.py`（normalize、去重键） |
| 验收 | `curl :5001/health` OK；Java 提交作业能拿到结果 |

### R3-05 content 模块

| 项 | 内容 |
|---|---|
| 目标 | 原始与标准化分离，清洗入库归 content |
| 新增目录 | `content/{controller,application,domain,infrastructure}` |
| 新增 | `ContentIngestService`（唯一写入口）、`ContentQueryService`、`CommentQueryService`、`ContentAccessService` |
| 迁移 | `Video`/`Comment`/`VideoGrant` 实体、`CommentServiceImpl`、`VideoService`、`CleanServiceImpl`、`CleanController` |
| 删除 | Python 侧 `pymysql` 直写逻辑（`clean.py` 改为返回结果） |
| API | `/api/v1/content/*`（8 个） |
| 测试 | 迁移现有 8 例（含双唯一键）+ 新增 3 例（ingest 幂等、原始归档、可见性） |
| 验收 | 采集 → ingest → 评论可查，重复 ingest 不产生重复行 |

---

## R4 · AI 分析

### R4-01 analysis 模块

| 项 | 内容 |
|---|---|
| 新增目录 | `analysis/{controller,application,domain,infrastructure}` |
| 新增 | `AnalysisTaskService`、`AnalysisResultQueryService`、`ProfileService`、`ReviewService`、`UsageService` |
| 迁移 | `AiController`、`AiAnalysisService`、`ReviewController`、`ReviewService`、8 个 analysis 实体与 Mapper |
| 删除 | `AnalysisReviewMapperExt`（合并） |
| API | `/api/v1/analysis/*`（12 个） |
| 测试 | 新增 4 例（Profile 版本切换、结果按版本隔离、用量汇总、复核流转） |
| 验收 | 同一评论用两个 Profile 版本分析，结果并存且可区分 |
| 风险 | `AiController` 的 Mapper 直连必须消除 |

### R4-02 ai-service 服务化与 Model Gateway

| 项 | 内容 |
|---|---|
| 修改目录 | `ai-service/` |
| 新增 | `app/api/`（路由拆分）、`app/model_gateway/{base,registry,router,adapters/deepseek,ollama}`、`app/schemas.py`（Pydantic） |
| 修改 | 路由加 `/v1` 前缀；Prompt 抽到 `app/prompts/` 并带版本号 |
| 数据库变化 | 无 |
| 测试 | 新增 `ai-service/tests/`（schema 校验、降级路径、DeepSeek mock） |
| 验收 | 切换 `model=ollama` 不改调用方代码；输出通过 Pydantic 校验 |

---

## R5 · 线索管理

### R5-01 lead 模块

| 项 | 内容 |
|---|---|
| 新增目录 | `lead/{controller,application,domain,infrastructure}` |
| 新增 | `LeadService`（生成/去重）、`LeadScoringService`（五维）、`LeadReviewService`、`LeadQueryService`、`FollowupService`；6 张 `ld_*` 表实体与 Mapper |
| API | `/api/v1/lead/*`（9 个） |
| 测试 | 新增 6 例（信号→线索幂等、五维评分、状态机非法流转、证据关联、审核、跟进） |
| 验收 | 一条评论 → 信号 → 线索 → 证据链可完整展示；同一作者可产生多条线索 |
| 风险 | 线索质量依赖分析准确率，需保留人工审核为必经环节 |

### R5-02 integration 统一客户端

| 项 | 内容 |
|---|---|
| 新增目录 | `integration/{collection,ai,coolskyai}/` |
| 新增 | `CollectionClient`、`AnalysisClient`、`CoolSkyAiClient`；统一超时/重试/异常翻译/traceId |
| 修改 | R3/R4 中临时的调用改为依赖接口 |
| 测试 | 新增 3 例（超时重试、4xx 不重试、traceId 透传） |
| 验收 | 业务模块内 `grep -r "HttpClient\|ProcessBuilder"` 结果为 0 |

---

## R6 · 转化与经营分析

### R6-01 conversion 模块

| 项 | 内容 |
|---|---|
| 新增目录 | `conversion/{controller,application,domain,infrastructure}` |
| 新增 | `ProductMatchService`、`ConversionService`、`CoolSkyAiClient` 对接；3 张 `cv_*` 表 |
| API | `/api/v1/conversion/*`（6 个） |
| 测试 | 新增 3 例（产品同步幂等、匹配、成交登记不重复） |
| 验收 | 不出现订单账本表；成交金额以 CoolSkyAI 为准 |

### R6-02 analytics 模块

| 项 | 内容 |
|---|---|
| 新增目录 | `analytics/{controller,application,domain,infrastructure}` |
| 新增 | `DashboardQueryService`、`StatsQueryService`、`ReportService`；`ag_report`/`ag_report_version` |
| 迁移 | `AnalysisController`（统计部分）、`OverviewController`、`CompareService`、`ReportController` |
| API | `/api/v1/analytics/*`（8 个） |
| 测试 | 新增 3 例（驾驶舱指标口径、空数据返回 0 而非 null、报告版本） |
| 验收 | analytics 无写其他模块表的 Mapper；报告可导出 |

---

## R7 · 前端适配与整体验收

### R7-01 前端 API 与菜单适配

| 项 | 内容 |
|---|---|
| 修改目录 | `frontend/src/{api,config,router,views}` |
| 修改 | 58 个 API 方法按模块分组并改 `/api/v1` 前缀；菜单按 10 模块重排；补齐 lead/conversion/source 页面 |
| 测试 | 无自动化（现状），人工逐页回归 |
| 验收 | 13 个现有页面全部可用；新增 6 个页面可访问 |

### R7-02 全链路集成测试

| 项 | 内容 |
|---|---|
| 新增 | `backend/src/test/.../e2e/`：登录 → 建采集任务（mock collection）→ ingest → 分析（mock AI）→ 生成线索 → 审核 → 报告 |
| 测试 | 至少 1 条端到端用例，覆盖 5 个模块 |
| 验收 | `mvn verify` 全绿；报告归档 |

### R7-03 Docker 与验收

| 项 | 内容 |
|---|---|
| 验收 | 干净环境 `docker compose up -d` → 5 容器 healthy → 前端可登录 → 全链路可用 |
| 检查 | 镜像体积（backend ≤ 400 MB）；无 `com.coolskyai` 残留；无 Controller 直连 Mapper |
| 输出 | 架构图、模块说明、ER 图、API 文档、部署文档、测试报告 |

---

## 架构一致性检查（R1 自检）

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 10 模块职责清晰 | 通过（R1-02 含"不负责"与重叠裁决） |
| 2 | common 完全独立 | 通过（依赖矩阵第 1 行为空） |
| 3 | 无循环依赖 | 通过（矩阵为上三角；`lead → content` 仅只读） |
| 4 | 无跨模块 Mapper 访问设计 | 通过（`analytics` 仅 `Q`；其余经 application） |
| 5 | 模块职责重叠 | 已裁决 5 处（清洗/关键词/复核/报告/授权） |
| 6 | Controller 承载业务逻辑 | 目标禁止；现状 `AiController` 待 R4 消除 |
| 7 | 表有明确模块归属 | 通过（37 表均带前缀且列出归属） |
| 8 | 覆盖首期核心业务 | 通过（采集→内容→分析→线索→转化→统计全链） |
| 9 | 冗余表 | 无（`file_asset` 由 `sys_file` 重设计；`analysis_result` 不重建） |
| 10 | 不重复 CoolSkyAI | 通过（仅存外部引用与金额快照） |
| 11 | 可追溯内容→线索 | 通过（R1-05 §7 追溯链） |
| 12 | 同作者多信号 | 通过（`ld_evidence` 多对多） |
| 13 | Profile 版本可追溯 | 通过（`UK(code,version)` + 结果存快照） |
| 14 | Java/Python 契约一致 | 通过（R1-07 Schema 与 `an_result` 字段对齐） |
| 15 | API 覆盖前端首期页面 | 通过（R1-06 §6 给出迁移映射） |
| 16 | Docker 依赖合理 | 通过（backend 去 Chrome/Python） |
| 17 | 无不必要新基础设施 | 通过（无 MQ、无 Milvus、无 Redis） |
| 18 | R2–R7 可执行 | 通过（每个任务含验收与风险） |

**未解决的冲突**：无阻塞项。有 4 项"无法确认"（R0-08 §6）需业务决策，不阻塞 R2/R3 开工，
但会影响 R4（关键词发现）、R5（复核与线索审核是否合并）。

## 风险清单

| 风险 | 影响 | 是否阻塞 R2 | 建议 |
|---|---|---|---|
| 关键词发现链路从未跑通 | source 模块可能建了无用功能 | 否 | R3 前做一次可行性验证 |
| `review` 与 lead 审核边界 | 可能重复建设 | 否 | R5 前确认 |
| 根包改名影响面大 | 一次性大 diff | 否 | R2-01 单独提交，便于回退 |
| 数据库删除 | 不可逆 | 否 | R3-01 前强制导出备份 |
| Python 零测试 | R3/R4 改动无保护 | 否 | R3-04/R4-02 各补基础测试 |
