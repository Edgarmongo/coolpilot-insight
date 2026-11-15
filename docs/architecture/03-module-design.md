# R1 · 架构定稿

日期：2026-11-15　状态：**待确认**（§7 有 6 个需要业务判断的归属问题）

## 1. 模块与分层

模块化单体。10 个顶层模块，每模块四层：

```
com.coolskyai.coolpilot.insight
├── common/          公共基础（异常、响应、认证基础设施、上下文、分页、工具）
├── system/          系统管理（用户、角色、权限、配置、日志、审计、文件）
├── source/          数据源管理（平台定义、账号授权、能力状态、健康检查）
├── collection/      数据采集（任务、调度、执行记录、重试、限流）
├── content/         内容管理（内容、评论、作者、去重、检索、溯源）
├── analysis/        AI 分析（需求识别、标签、意图、评分、Prompt 版本、评估）
├── lead/            潜在客户（需求信号、线索、证据、评分、审核、跟进）
├── conversion/      转化（产品匹配、订单关联、成交、归因）
├── analytics/       经营分析（采集/内容/需求/线索/转化统计、驾驶舱）
└── integration/     外部集成（Python 采集、Python AI、CoolSkyAI 的统一适配）
```

每个业务模块内部固定四层：

| 层 | 职责 | 禁止 |
|---|---|---|
| `controller` | HTTP 入参出参、参数校验、权限注解 | 禁止访问 Mapper；禁止业务编排 |
| `application` | 用例编排、事务边界、跨模块协调 | 禁止写 SQL；禁止依赖 MVC |
| `domain` | 领域模型、状态机、业务规则、领域服务 | 禁止依赖 Spring MVC / Mapper / 外部 API |
| `infrastructure` | Mapper、PO、外部实现、缓存 | 禁止承载业务规则 |

## 2. 依赖规则

允许的依赖方向（箭头 = 可依赖）：

```
common  ←  所有模块
system  ←  所有模块（认证与权限是横切能力）
source  ←  collection
collection  ←  content
content  ←  analysis
analysis  ←  lead
lead  ←  conversion
analytics  →  依赖上述模块的只读查询接口（单向，不被任何模块依赖）
integration  →  被 collection / analysis / conversion 调用（只出站，不回指业务）
```

硬性约束：

1. **禁止循环依赖**。跨模块调用只允许"下游依赖上游"，反向需求走事件或查询接口。
2. `analytics` 只读，不被依赖；它通过各模块暴露的 `*QueryService` 取数，不直连他人 Mapper。
3. `integration` 不含业务规则，只做协议适配（超时、重试、异常翻译、幂等键）。
4. `common` 不放业务逻辑——判断标准：这段代码能否脱离"获客"这个业务独立存在。

## 3. 现有 100 个类的归属

| 目标模块 | 现有类 | 说明 |
|---|---|---|
| **common** | `Result`、`GlobalExceptionHandler`、`JwtUtil`、`JwtInterceptor`、`RequestContext`、`RequireRole`、`MemoryCache`、`CollectUtils` | 响应/异常/认证基础设施/上下文/工具。`CollectUtils`（抖音链接解析）归属待确认 Q6 |
| **system** | `User`、`AuditLog`、`SystemConfig`、`UserService(/Impl)`、`UserController`、`AdminController`、`AdminBootstrap`、`WebMvcConfig`、`OpenApiConfig`、`AppProperties` | 用户/配置/审计；`AdminController` 的分页与脱敏逻辑要下沉到 application |
| **source** | `Keyword`、`KeywordVideo`、`DiscoveryTask`、`DiscoveryTaskItem`、`KeywordController`、`DiscoveryService` | 关键词与发现任务属"数据源能力" |
| **collection** | `CollectionTask`、`CollectionTaskItem`、`CollectionTaskLog`、`CollectionCheckpoint`、`CollectController`、`CollectService(/Impl)` | 任务状态机、断点续采、原子领取 |
| **content** | `Video`、`Comment`、`VideoGrant`、`VideoService`、`CommentService(/Impl)`、`CommentController`、`GovernanceService` | 内容与评论；`GovernanceService` 是数据访问控制，属内容域 |
| **analysis** | `CommentAnalysis`、`VideoInsight`、`AnalysisEvidence`、`AnalysisTask`、`AnalysisTaskBatch`、`AnalysisTaskItem`、`AnalysisProfile`、`AnalysisReview`、`UsageRecord`、`AiController`、`AiAnalysisService`、`ReviewController`、`ReviewService` | AI 分析主链路 + 人工复核（对分析结果的复核） |
| **lead** | *（现有代码中无对应类）* | 全新领域：线索、证据、评分、审核、跟进 |
| **conversion** | *（无）* | 全新领域：产品匹配、成交、归因 |
| **analytics** | `OverviewController`、`OverviewService`、`AnalysisController`、`AnalysisService(/Impl)`、`CompareService`、`ReportController`、`ReportService`、`Report`、`ReportVersion` | 统计与报告（只读聚合） |
| **integration** | *（逻辑散落在各 Service 中，需抽出）* | `CollectServiceImpl` 的 `ProcessBuilder`、`AiAnalysisService` 的 Flask HTTP、`CleanServiceImpl` 的脚本调用 |

**需要抽取而非移动的部分**（这是重构的真正工作量）：

- `CollectServiceImpl` 里 4 处 `ProcessBuilder` 调 Python → `integration.python.CollectionClient`
- `AiAnalysisService` 里的 Flask HTTP 调用 → `integration.python.AnalysisClient`
- `UsageRecord` 的计费与 token 汇总 → `analysis`（业务）与 `integration`（用量采集）分离
- `AdminController` 的业务逻辑 → `system.application.UserAdminService`

## 4. 数据库域设计

重建。表名带模块前缀，便于识别归属与后续分库。

| 模块 | 表 | 关键约束 |
|---|---|---|
| system | `sys_user`、`sys_role`、`sys_user_role`、`sys_config`、`sys_audit_log`、`sys_file` | `uk_user_phone(phone)`、`uk_role_code(code)`、`uk_user_role(user_id, role_id)` |
| source | `ds_source`、`ds_account`、`ds_capability`（或并入 source 的 JSON 列） | `uk_source_code(code)`、`uk_account(source_id, external_account_id)` |
| collection | `col_task`、`col_task_item`、`col_task_log`、`col_checkpoint` | `uk_task_no(task_no)`、`uk_item_active(video_id, active_flag)` |
| content | `ct_content`、`ct_comment`、`ct_author`、`ct_grant` | `uk_content(source_id, external_id)`、`uk_comment(source_id, external_cid)`、`uk_comment_dedup(dedup_key)` |
| analysis | `an_profile`、`an_task`、`an_task_item`、`an_result`、`an_insight`、`an_evidence`、`an_review`、`an_usage` | `uk_profile_code(code, version)`、`uk_result(content_id, comment_id, profile_id)` |
| lead | `ld_signal`、`ld_lead`、`ld_lead_evidence`、`ld_score_history`、`ld_review`、`ld_followup` | `uk_lead_code(code)`、`uk_signal(result_id, signal_type)` |
| conversion | `cv_product`、`cv_match`、`cv_conversion` | `uk_product_external(external_product_id)`、`uk_conversion_lead(lead_id)` |
| analytics | *无表* | 读模型；必要时后续加物化汇总表 |

公共字段（所有业务表）：

- 主键 `id BIGINT UNSIGNED AUTO_INCREMENT`
- 审计：`created_at`、`updated_at`、`created_by`、`updated_by`
- 软删：`deleted_at`（仅对需要保留痕迹的表）
- 预留：`tenant_id BIGINT DEFAULT 0`（首期单租户）

状态字段一律用 `VARCHAR(24)` + 应用层枚举，不用 MySQL ENUM（改起来要 DDL）。

## 5. Python 服务通信协议

| 调用方 | 被调方 | 协议 | 约束 |
|---|---|---|---|
| collection.application | collection-service | 子进程（现状）→ 后期 HTTP | 超时按任务类型分级；失败重试受 `col_task_item.max_retry` 控制；幂等键 `task_item_id` |
| analysis.application | ai-service | HTTP JSON | 超时 30s/任务；失败不丢原始数据；返回必须过 Schema 校验 |
| conversion.application | CoolSkyAI | HTTP | 只读产品与订单，不写对方账本 |

统一要求（R5 落地）：

- 每次调用记录 traceId、耗时、结果状态到 `an_usage` / `col_task_log`
- 异常统一翻译为 `IntegrationException`，不让 Python 的堆栈泄漏到控制层
- 重试只对幂等操作开启

## 6. 执行顺序（R2 → R7）

| 阶段 | 内容 | 可验证产出 |
|---|---|---|
| R2 | 建 `common` + `system` 骨架，迁认证与用户；其余模块按域逐个迁 | `mvn test` 通过 + 登录可用 |
| R3 | 新 Flyway V1（空库可建全表），删除旧测试库 | 空库启动自动建表 |
| R4 | Controller 去 Mapper 化、统一 DTO/分页/异常 | 接口回归脚本全绿 |
| R5 | Python 调用抽到 integration，统一超时/重试/追踪 | 采集与 AI 各跑通一次 |
| R6 | 前端按 10 模块重排菜单与 API | 页面可访问 |
| R7 | 单元 + 集成 + 迁移 + Docker 部署测试 | 全量报告 |

## 7. 待确认（不自行编造）

以下 6 处归属有歧义，请确认。括号内是我的建议：

1. **`CleanController` / `CleanService`（清洗）** → collection 还是 content？
   （建议 **content**：清洗的产出是标准化内容，属于内容域的入库规则；但由采集触发）
2. **`Keyword` / `DiscoveryTask`（关键词发现）** → source 还是 collection？
   （建议 **source**：关键词定义的是"去哪找"，属数据源能力；任务执行仍归 collection）
3. **`VideoGrant`（视频授权）** → content 还是 system？
   （建议 **content**：它约束的是内容数据的可见范围）
4. **`Report`（报告）** → analytics 还是独立模块？
   （建议 **analytics**：报告是只读聚合的输出物）
5. **`AnalysisController` 的统计接口**（region / sentiment / trend / wordcloud）→ analysis 还是 analytics？
   （建议 **analytics**：它们是统计维度，不产生分析结论）
6. **`CollectUtils`（抖音链接解析）** → common 还是 collection 还是 integration？
   （建议 **collection.domain**：这是抖音专属的输入解析，不属于通用工具，也不属于 HTTP 适配）

确认后我按 R2 开始动代码：**先做 `common` + `system` 两个模块**（认证链路是其他所有模块的前置），
每完成一个模块跑一次 `mvn test`，17 个集成测试作为回归网。
