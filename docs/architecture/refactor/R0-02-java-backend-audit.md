# R0-02 · Java 后端审计

审计基线：commit `3ef5ea3`（含 R2 批次一：common + system 已模块化）
方法：静态源码分析 + 调用关系 grep + 容器运行验证

## 1. 类清单与目标归属（104 个 .java）

### 1.1 已模块化（R2 批次一）

| 包 | 类数 | 代表类 | 目标分层 |
|---|---|---|---|
| `common/web` | 6 | `Result`、`PageResult`、`GlobalExceptionHandler`、`WebMvcConfig`、`OpenApiConfig`、`HealthController` | controller/基础设施 |
| `common/security` | 4 | `JwtUtil`、`JwtInterceptor`、`RequestContext`、`RequireRole` | 基础设施 |
| `common/util` | 1 | `MemoryCache` | 基础设施 |
| `system/controller` | 2 | `UserController`、`AdminController` | controller |
| `system/application` | 3 | `UserService`、`UserAdminService`、`impl/UserServiceImpl` | application |
| `system/domain` | 3 | `User`、`AuditLog`、`SystemConfig` | domain |
| `system/dto` | 4 | `LoginRequest`、`LoginResponse`、`RegisterRequest`、`UserView` | dto |
| `system/infrastructure/persistence` | 3 | `UserMapper`、`AuditLogMapper`、`SystemConfigMapper` | infrastructure |
| `system/config` | 1 | `AdminBootstrap` | 配置 |

### 1.2 未模块化（仍是技术分层）

| 包 | 类数 | 内容 | 目标模块（建议） |
|---|---|---|---|
| `controller` | 9 | `Ai/Analysis/Clean/Collect/Comment/Keyword/Overview/Report/Review` Controller | analysis / analytics / content / collection / source |
| `service` + `service/impl` | 16 | 4 个接口 + 4 个实现 + 8 个无接口 Service | 各归宿见 §8 |
| `entity` | 22 | 与表一一对应的 POJO（无 MyBatis 注解，映射在 XML） | 各模块 `domain` |
| `mapper` | 25 | MyBatis 接口（含 `BaseCrudMapper`、`AnalysisReviewMapperExt`） | 各模块 `infrastructure/persistence` |
| `dto` | 1 | `CollectRequest` | collection |
| `utils` | 2 | `TokenStore`、`CollectUtils` | common.security / collection |
| `integration/config` | 1 | `AppProperties`（Python 路径、Flask 地址） | integration |
| 根 | 1 | `CoolPilotInsightApplication` | — |

## 2. 当前业务能力清单（CURRENT，已确认）

| 能力 | 状态 | 证据 |
|---|---|---|
| 注册/登录/JWT/改密 | 已确认 | `UserController`、`UserServiceImpl`、`JwtInterceptor`；测试 `UserAuthFlowTest` 6 例通过 |
| 用户管理（分页/脱敏/删除） | 已确认 | `UserAdminService`；`UserAdminServiceTest` 4 例通过 |
| 抖音评论采集（链接 → CSV） | 已确认 | `CollectServiceImpl` + `comment.py`；实测 cid 19/19 |
| 采集任务状态机与断点续采 | 已确认 | `collection_task/_item/_log/_checkpoint`；`CollectionTaskSchedulingTest` 3 例 |
| CSV 清洗入库（去重 + 情感） | 已确认 | `CleanServiceImpl` + `ai-service/app/clean.py` |
| 关键词与发现任务 | **部分确认** | 代码存在（`KeywordController`/`DiscoveryService`），库中 `keyword`/`discovery_task` 行数为 0，**未曾真实跑通** |
| AI 评论级分析 | 已确认 | `AiAnalysisService` + Flask `/analyze/comments`；库中 `comment_analysis` 19 行 |
| AI 视频级洞察 | 已确认 | `/analyze/insight`；`video_insight` 1 行、`analysis_evidence` 77 行 |
| 人工复核 | 已确认（未使用） | `ReviewService`；`analysis_review` 0 行 |
| 报告生成与导出 | 已确认 | `ReportService` + Flask `/render/report`；`report` 2 行 |
| 统计（地区/情感/趋势/词云/对比） | 已确认 | `AnalysisServiceImpl`、`CompareService`、`OverviewService` |
| 视频级访问授权 | **仅迁移存在** | `video_grant` 表 0 行，无任何业务写入路径 |

## 3. Controller 与 API 清单（59 个端点）

| Controller | 端点数 | 主要路径 |
|---|---|---|
| `UserController` | 5 | `/api/user/{register,login,info,password,logout}` |
| `AdminController` | 2 | `GET /api/admin/users`、`DELETE /api/admin/user/{id}` |
| `HealthController` | 1 | `GET /api/health` |
| `AiController` | 7 | `/api/ai/{analyze-comments,video-insight,video-insights,comment-summary,tasks,usage}` |
| `AnalysisController` | 12 | `/api/analysis/{summary,region,sentiment,trend,wordcloud,top-comments,topic,sankey,videos,compare,refresh}` |
| `CollectController` | 9 | `/api/collect/{start,status,tasks,...}` |
| `CommentController` | 3 | `/api/comment/{list,tree,search}` |
| `CleanController` | 3 | `/api/clean/{files,run,export}` |
| `KeywordController` | 6 | `/api/keyword`、`/api/keyword/{id}/discover` 等 |
| `OverviewController` | 2 | `/api/overview`、`/api/overview/video-options` |
| `ReportController` | 7 | `/api/report`、`/api/report/{id}/{versions,export,file}` |
| `ReviewController` | 4 | `/api/review/{queue,stats,{id},{id}/history}` |

> 全部端点均无版本前缀，目标规范为 `/api/v1/{module}/...`（见 R1-06）。

## 4. Service 依赖关系（实测注入）

| 调用方 | 被调方 |
|---|---|
| `AiAnalysisService` | `VideoService`、`GovernanceService` |
| `CompareService` | `VideoService`、`GovernanceService` |
| `DiscoveryService` | `VideoService`、`GovernanceService` |
| `CommentServiceImpl` | `VideoService`、`GovernanceService` |
| `OverviewService` | `VideoService`、`GovernanceService` |
| `ReportService` | `VideoService`、`GovernanceService` |
| `ReviewService` | `VideoService`、`GovernanceService` |
| `VideoService` | `GovernanceService` |
| `CollectServiceImpl` | `CleanService`、`ExecutorService`（线程池，非 Spring Bean） |

**结论**：`GovernanceService` 被 8 个服务依赖，是事实上的横切组件；`VideoService` 被 7 个依赖。
**未发现 Spring Bean 之间的循环依赖**（依赖是单向的：业务 → Governance/Video）。

但存在**隐式循环风险**：`GovernanceService` 直接使用 4 个 Mapper（grant/task/item/user），
若按目标架构拆入 `content` 与 `system` 两个模块，将产生 `content ↔ system` 双向引用，
必须在 R2 中把它拆为「内容可见性判定（content）」+「权限基础（system/common）」。

## 5. Mapper 使用关系

- 25 个 Mapper 接口，全部 `extends BaseCrudMapper<T>` 或声明注解 SQL
- **11 个 Service 直接使用 Mapper**（跨层访问）
- `BaseCrudMapper`（`mapper/BaseCrudMapper.java`）是自建契约，替代 MyBatis-Plus 的 `BaseMapper`
- `AnalysisReviewMapperExt` 是 `Ext` 后缀的补丁类，同时承担 `CommentAnalysis` 的 CRUD 与 3 条自定义查询

## 6. 业务逻辑越层情况（实测）

| 违规 | 位置 | 证据 |
|---|---|---|
| Controller 直接使用 Mapper | `AiController` | `service/../controller/AiController.java:19 private final UsageRecordMapper usageMapper;` |
| ~~Controller 直接使用 Mapper~~ | ~~`AdminController`~~ | **已消除**（R2 批次一，现 0 处 Mapper 引用） |
| Service 直接起 Python 子进程 | `CollectServiceImpl:376`、`DiscoveryService:246`、`CleanServiceImpl:101`、`CoolPilotInsightApplication:41` | `new ProcessBuilder(...)` |
| Service 直接 HTTP 调 Flask | `AiAnalysisService`、`AnalysisServiceImpl`、`ReportService` | 出现 `HttpClient/RestTemplate/URL(` |
| 业务与基础设施混合 | `CollectServiceImpl` | 同文件内既有任务状态机，又有子进程管理与 CSV 落盘 |

## 7. 重点问题复检（对照历史结论）

| 历史问题 | 当前是否仍存在 | 证据 |
|---|---|---|
| `AdminController` 直连 `UserMapper` | **否**（已修） | 该类现无 Mapper 引用 |
| `AiController` 直连多个 Mapper | **是，已缩减为 1 个** | 仅 `UsageRecordMapper` |
| `CollectServiceImpl` 用 `ProcessBuilder` 调 Python | **是** | 第 376 行 |
| `AiAnalysisService` 直接 HTTP 调 Flask | **是** | 3 个文件出现 HTTP 客户端 |
| `UsageRecord` 混合计费与采集用量 | **部分** | `UsageRecord` 仅被 `AiAnalysisService` 与 `AiController` 读写，未见采集侧写入 |
| `CollectUtils` 含抖音专属解析 | **是** | 类名与用途均为抖音链接/口令解析 |

## 8. 类归属建议（CURRENT → TARGET）

| 类 | 目标模块 | 目标分层 | 处理建议 |
|---|---|---|---|
| `AiController`、`AiAnalysisService` | analysis | controller / application | 拆分：编排留 application，HTTP 调用抽到 integration |
| `ReviewController`、`ReviewService` | analysis | controller / application | 复用（复核对象是分析结果） |
| `AnalysisController`、`AnalysisServiceImpl` | analytics | controller / application | 复用（纯统计，只读） |
| `OverviewController`、`OverviewService` | analytics | controller / application | 复用 |
| `CompareService` | analytics | application | 复用 |
| `ReportController`、`ReportService`、`Report`、`ReportVersion` | analytics | controller / application / domain | 复用，导出渲染改走 integration |
| `CommentController`、`CommentServiceImpl`、`VideoService`、`GovernanceService`、`Video`、`Comment`、`VideoGrant` | content | 全层 | **拆分**：Governance 的权限部分上移 common/system |
| `CleanController`、`CleanServiceImpl` | content | controller / application | 复用（产出为标准化内容），脚本调用走 integration |
| `CollectController`、`CollectServiceImpl`、`CollectService`、4 张 collection 表实体 | collection | 全层 | 复用，抽离子进程调用 |
| `KeywordController`、`Keyword`、`KeywordVideo` | source | controller / domain | 复用（关键词配置与发现策略） |
| `DiscoveryService`、`DiscoveryTask`、`DiscoveryTaskItem` | collection | application / domain | 复用（发现任务执行） |
| `UsageRecord`、`AnalysisProfile/Task/Item/Batch`、`CommentAnalysis`、`VideoInsight`、`AnalysisEvidence`、`AnalysisReview` | analysis | domain | 复用，补 Profile 版本语义 |
| `CollectRequest` | collection | dto | 迁移位置 |
| `TokenStore` | common | security | 迁移位置 |
| `CollectUtils` | collection | domain（Douyin Adapter） | 迁移并限定为抖音专用 |
| `AnalysisReviewMapperExt` | analysis | infrastructure | **合并**进正常 Mapper，取消 Ext 后缀 |

## 9. 处理结论汇总

- **直接复用**：采集任务链路、内容/评论链路、AI 分析链路、统计与报告、鉴权（共 11 项能力）
- **调整后复用**：`GovernanceService`（拆权限）、`AiAnalysisService`（抽 HTTP）、`CleanServiceImpl`（抽脚本调用）
- **需要拆分**：`CollectServiceImpl`（状态机 / 子进程 / 落盘三职责）、`AnalysisReviewMapperExt`（CRUD 与查询混合）
- **需要重写**：无。现有业务逻辑经实测可用，不推倒
- **可以废弃**：`AnalysisReviewMapperExt` 的 Ext 形态、`coolpilot_insight.sql`（历史脚本）
- **无法确认**：关键词发现链路的真实可用性（库中 0 行数据，无跑通证据）
