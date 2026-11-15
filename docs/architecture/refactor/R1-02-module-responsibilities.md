# R1-02 · 模块职责

每个模块说明：定位 / 核心职责 / 不负责 / 子领域 / 暴露的应用服务 / 表 / API。

## common

| 项 | 内容 |
|---|---|
| 定位 | 公共基础，**零业务逻辑** |
| 职责 | 统一响应、统一异常、认证基础设施（JWT 签发校验、Token 存储）、请求上下文、分页、校验、序列化、注解、工具 |
| 不负责 | 任何业务规则（用户注册规则、线索评分、采集策略） |
| 子领域 | `web`（Result/PageResult/异常处理/MVC）、`security`（Jwt/上下文/鉴权注解）、`util`（缓存/字符串/时间） |
| 暴露 | 仅工具与基础类型，不暴露应用服务 |
| 表 | 无 |
| 判据 | 这段代码能否脱离"获客"独立存在？能 → common |

## system

| 项 | 内容 |
|---|---|
| 定位 | 账号、权限、配置、审计、文件 |
| 核心职责 | 注册/登录/改密/JWT 生命周期；角色与权限判定；系统配置读取；操作审计落库；文件上传下载 |
| 不负责 | 内容可见性（归 content）、线索审核（归 lead） |
| 子领域 | `user`、`role`、`permission`、`config`、`audit`、`file` |
| 暴露应用服务 | `UserService`（认证）、`UserAdminService`（管理）、`PermissionService`（鉴权查询）、`ConfigService`、`AuditService` |
| 表 | `sys_user`、`sys_role`、`sys_user_role`、`sys_config`、`sys_audit_log`、`sys_file` |
| API | `/api/v1/system/auth/*`、`/api/v1/system/users/*`、`/api/v1/system/roles`、`/api/v1/system/configs`、`/api/v1/system/audit-logs` |

## source

| 项 | 内容 |
|---|---|
| 定位 | "去哪儿采"的定义与管理 |
| 核心职责 | 数据源注册（抖音/小红书/快手/Web）、账号授权与登录态、能力状态（Available/Limited/Manual/Disabled/Pending）、健康检查、关键词与发现策略 |
| 不负责 | 任务执行与调度（归 collection）、内容解析（归 collection-service） |
| 子领域 | `source`、`account`、`capability`、`keyword` |
| 暴露应用服务 | `SourceQueryService`、`SourceCapabilityService`、`KeywordService` |
| 表 | `ds_source`、`ds_account`、`ds_keyword` |
| API | `/api/v1/source/sources`、`/api/v1/source/accounts`、`/api/v1/source/keywords` |

## collection

| 项 | 内容 |
|---|---|
| 定位 | "怎么采、采到没有" |
| 核心职责 | 采集任务创建、调度与领取、执行记录、失败重试、断点续采、限流；发现任务的执行 |
| 不负责 | 内容标准化与入库（归 content）、平台解析（归 collection-service） |
| 子领域 | `task`、`run`、`dispatch`、`retry`、`checkpoint`、`discovery` |
| 暴露应用服务 | `CollectTaskService`、`CollectDispatchService`、`CollectionQueryService` |
| 表 | `col_task`、`col_task_item`、`col_task_log`、`col_checkpoint` |
| API | `/api/v1/collection/tasks`、`/api/v1/collection/tasks/{id}/{cancel,retry,logs}`、`/api/v1/collection/discoveries` |

## content

| 项 | 内容 |
|---|---|
| 定位 | 内容与评论的落库、清洗、去重、检索、使用授权 |
| 核心职责 | 原始响应归档、标准化内容/评论/作者、去重（CID + 指纹）、清洗状态流转、检索、按视频的内容读授权 |
| 不负责 | 分析结论（归 analysis）、采集执行（归 collection） |
| 子领域 | `content`、`comment`、`author`、`normalization`、`dedup`、`grant` |
| 暴露应用服务 | `ContentQueryService`、`CommentQueryService`、`ContentIngestService`、`ContentAccessService`（可见性判定） |
| 表 | `ct_content`、`ct_comment`、`ct_author`、`ct_grant` |
| API | `/api/v1/content/contents`、`/api/v1/content/comments`、`/api/v1/content/authors`、`/api/v1/content/grants` |

## analysis

| 项 | 内容 |
|---|---|
| 定位 | AI 分析编排与结果沉淀 |
| 核心职责 | 分析档案（Profile）版本管理、分析任务与批次、评论级结果、视频级洞察、证据链、人工复核、AI 用量 |
| 不负责 | 线索与销售判断（归 lead）、模型调用实现（归 AI Service） |
| 子领域 | `profile`、`task`、`demand`、`intent`、`evidence`、`review`、`usage` |
| 暴露应用服务 | `AnalysisTaskService`、`AnalysisResultQueryService`、`ProfileService`、`ReviewService`、`UsageService` |
| 表 | `an_profile`、`an_task`、`an_task_item`、`an_result`、`an_insight`、`an_evidence`、`an_review`、`an_usage` |
| API | `/api/v1/analysis/tasks`、`/api/v1/analysis/results`、`/api/v1/analysis/insights`、`/api/v1/analysis/profiles`、`/api/v1/analysis/reviews` |

## lead

| 项 | 内容 |
|---|---|
| 定位 | 从需求信号到可跟进线索 |
| 核心职责 | 需求信号沉淀、线索生成与去重、证据关联、线索评分（五维加权）、人工审核、状态流转、跟进记录 |
| 不负责 | 分析结论生成（归 analysis）、成交与订单（归 conversion） |
| 子领域 | `signal`、`lead`、`evidence`、`scoring`、`review`、`followup`、`lifecycle` |
| 暴露应用服务 | `LeadService`、`LeadQueryService`、`LeadReviewService`、`FollowupService` |
| 表 | `ld_signal`、`ld_lead`、`ld_evidence`、`ld_score_history`、`ld_review`、`ld_followup` |
| API | `/api/v1/lead/leads`、`/api/v1/lead/leads/{id}/{review,followups,evidence,score-history}` |
| 状态机 | `NEW → PENDING_REVIEW → QUALIFIED → FOLLOWING → {CONVERTED, NURTURE, CLOSED}`，`PENDING_REVIEW → REJECTED` |

## conversion

| 项 | 内容 |
|---|---|
| 定位 | 需求到成交的闭环 |
| 核心职责 | 产品目录镜像（来自 CoolSkyAI）、线索-产品匹配、成交记录、渠道归因 |
| 不负责 | 产品价格与订单账本的权威数据（CoolSkyAI 为准） |
| 子领域 | `product`、`match`、`conversion`、`attribution` |
| 暴露应用服务 | `ProductMatchService`、`ConversionService` |
| 表 | `cv_product`、`cv_match`、`cv_conversion` |
| API | `/api/v1/conversion/products`、`/api/v1/conversion/matches`、`/api/v1/conversion/records` |

## analytics

| 项 | 内容 |
|---|---|
| 定位 | 只读聚合与输出 |
| 核心职责 | 采集/内容/需求/线索/转化统计、获客驾驶舱、报告生成与导出 |
| 不负责 | 任何写操作（**本模块不得有写库的 Mapper**） |
| 子领域 | `dashboard`、`metrics`、`report`、`aggregation` |
| 暴露应用服务 | `DashboardQueryService`、`StatsQueryService`、`ReportService` |
| 表 | `ag_report`、`ag_report_version`（仅报告持久化；其余数据只读其他模块） |
| API | `/api/v1/analytics/dashboard`、`/api/v1/analytics/{contents,demands,leads,conversions}`、`/api/v1/analytics/reports` |

> 报告表归属说明：报告是 analytics 的输出物且需持久化。**决策：报告表归 analytics（`ag_` 前缀），
> 仅由 `ReportService` 写入**；"只读"约束特指「不写其他模块的表、不修改业务事实」。

## integration

| 项 | 内容 |
|---|---|
| 定位 | 外部世界的唯一出口 |
| 核心职责 | Collection Service 客户端、AI Service 客户端、CoolSkyAI 客户端；超时/重试/幂等/traceId/异常翻译 |
| 不负责 | 业务规则（不做线索评分、不做采集调度决策） |
| 子领域 | `collection.client`、`ai.client`、`coolskyai.client`、`contract`（DTO）、`config` |
| 暴露 | 客户端接口（被 collection/analysis/conversion 调用） |
| 表 | 无 |
| 约束 | 只出站，不回指任何业务模块 |

## 模块职责重叠检查

| 潜在重叠 | 裁决 |
|---|---|
| 清洗属于 content 还是 collection | **content**（产出是标准化内容）；collection 只负责把原始数据取回来 |
| 关键词属于 source 还是 collection | **source**（定义去哪找）；发现任务的执行归 collection |
| 复核（analysis_review）与线索审核（lead_review） | **分开**：前者审 AI 结果正确性，后者审线索是否值得跟进 |
| 报告属于 analytics 还是 system 文件 | **analytics** 生成，文件实体存 `sys_file` |
| 授权属于 content 还是 system | **content**（内容读权限，已核实 `video_grant` 语义） |
