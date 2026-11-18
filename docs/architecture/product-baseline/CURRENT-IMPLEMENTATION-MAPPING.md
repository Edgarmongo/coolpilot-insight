# 当前实现映射（CURRENT-IMPLEMENTATION-MAPPING）

> 证据基线：`coolpilot-insight` @ `17e87e7` · Flyway **V33** · 审计日期 2026-11-18
> 方法：只读——包结构、Flyway 迁移、`information_schema`、运行中容器探测、前端路由。
> 本文只记录**已存在**的东西；缺失项统一记在 `PRODUCT-GAP-ANALYSIS.md`。

---

## 1. 运行形态

| 容器 | 角色 | 端口 | 状态 |
|---|---|---|---|
| `cpi-mysql` | MySQL 8（唯一业务库） | 3307→3306 | healthy |
| `cpi-backend` | Java 控制中心 + 内置 Python(清洗) + Chrome | 8080 | healthy |
| `cpi-collection` | 采集服务（适配器 + Registry + 作业队列） | 5001 | healthy |
| `cpi-flask` | AI/分词/报告渲染 | 仅内网 5000 | healthy |
| `cpi-frontend` | Nginx + Vue 静态资源 | 5173→80 | Up |

**部署硬约束**：`collection` 只能单副本（进程内队列 + 共享 Chrome profile，见 ADR-007 §4.2.1）。

---

## 2. Java 后端模块

| 包 | Java 文件数 | 职责 | 成熟度 |
|---|---|---|---|
| `source` | 29 | 数据源、账号、Desired/Observed/Effective、凭证判定、快照缓存 | 完整 |
| `system` | 45 | 用户/角色/菜单/字典/配置/审计/分层健康 | 完整 |
| `collection` | 22 | 采集任务、准入闸门、去重查询、清洗编排、内部接口 | 完整 |
| `integration` | 22 | 采集服务客户端、AI 客户端、进程执行器、配置 | 完整 |
| `content` | 14 | `ct_*` 内容资产（**仅 3 个 GET**） | 骨架 |
| `service` | 13 | 清洗、AI 分析、报告、复核、发现、概览 | 完整（历史分层） |
| `controller` | 8 | 传统控制器层 | 完整 |
| `mapper` | 21 | MyBatis 接口（含手写 XML） | 完整 |
| `entity` | 18 | 实体 | 完整 |
| `dto` | 1 | 传输对象 | 偏薄 |

**接口规模**：23 个 Controller、**104** 个端点映射。

### 2.1 关键接口（按业务域）

| 域 | 端点 |
|---|---|
| 认证/用户 | `/api/user/{login,logout,register,current,mine}` |
| 数据源 | `/api/v1/source/sources`、`/sources/{code}/capabilities`、`/v1/source/accounts/**` |
| 采集 | `/api/collect/{start,tasks,tasks/{id}/{trigger,cancel,retry},upload}` |
| 清洗 | `/api/clean/{run,export,files}` |
| 评论/分析 | `/api/comment/**`、`/api/analysis/{compare,summary,stats}` |
| 关键词 | `/api/keyword/**`、`/discovery-tasks` |
| 报告 | `/api/report/**`（生成/导出 pdf,xlsx） |
| 系统 | `/api/v1/system/{users,roles,menus,dicts,permissions,configs,audit-logs}`、`/api/system/health/layers` |
| 内部 | `/internal/collection/dedup`（`X-Internal-Token`） |
| 健康 | `/api/health`（免鉴权，容器探活用） |

---

## 3. Python 执行侧

### 3.1 采集服务（`collection-service/app`，24 个 py 文件）

| 目录 | 内容 |
|---|---|
| `adapters/douyin/` | `collector.py`（评论采集）、`search.py`（关键词搜索）、`browser.py`（Chrome 选项） |
| `jobs/` | `queue.py`（进程内队列 + Job）、`executor.py`（能力→执行体映射）、`dispatch.py`（准入判定）、`dedup.py`（去重记忆客户端） |
| `registry/` | 能力声明、证据校验、快照生成、七类 `failure_type`、bootstrap |
| `server.py` | Flask 服务：`/v1/jobs*`、`/v1/registry/snapshot`、`/v1/adapters*`、`/health` |

**能力声明（4 个）**

| 能力 | 实现状态 | 可调度 |
|---|---|---|
| `COMMENT` | VERIFIED（真实平台，cid 19/19） | ✅ |
| `COMMENT_REPLY` | VERIFIED（6 条楼中楼） | ✅ |
| `CONTENT_DETAIL` | VERIFIED | ❌ 未注册进 `_ADAPTERS` |
| `KEYWORD_SEARCH` | **UNVERIFIED**（验证码拦截） | ❌ 未注册 |

### 3.2 AI 服务（`ai-service/app`，7 个 py 文件）

`clean.py`（CSV→DB 清洗）、`analysis_engine.py`（分词/情感）、`server.py`
（`/analyze/comments`、`/analyze/insight`、`/analyze_topic`、`/wordcloud`、`/render/report`）、
`redact.py`（异常脱敏）、`rows.py`（行过滤规则）。

---

## 4. 数据库（Flyway V1–V33，47 张业务表）

| 域 | 表 |
|---|---|
| 数据源与能力 | `ds_source`、`ds_account`、`ds_policy`、`ds_keyword`、`ds_keyword_group` |
| 采集 | `collection_task`、`collection_task_item`、`collection_task_log`、`collection_checkpoint`、`col_run`、`col_execution` |
| 数据资产（主线） | `video`、`comment`、`comment_analysis`、`video_insight`、`video_grant` |
| 数据资产（第二模型） | `ct_content`、`ct_comment`、`ct_author`、`ct_grant`、`ct_raw_archive` |
| 分析 | `analysis_profile`、`analysis_task`、`analysis_task_item`、`analysis_task_batch`、`analysis_evidence`、`analysis_review`、`analysis_result`(已废弃) |
| 发现/关键词 | `discovery_task`、`discovery_task_item`、`keyword`、`keyword_video` |
| 报告 | `report`、`report_version` |
| 系统 | `user`、`sys_role`、`sys_user_role`、`sys_menu`、`sys_role_menu`、`sys_permission`、`sys_role_permission`、`sys_dict_type`、`sys_dict_item`、`system_config`、`audit_log`、`usage_record` |
| 文件 | `file_asset`（0 行，无写入方） |

**实测数据量**：video 3、comment 21、comment_analysis 38、analysis_evidence 77、
collection_task 12、report 2、report_version 2、ds_source 1、ds_keyword 1、ct_content 3、ct_comment 19。

---

## 5. 前端（21 个页面）

| 域 | 页面 |
|---|---|
| 总览/驾驶舱 | `OverviewView`、`DashboardView` |
| 采集/任务/清洗 | `CollectView`、`TaskCenterView`、`CleanView` |
| 评论/洞察/复核 | `CommentListView`、`InsightView`、`ReviewView` |
| 数据源/账号 | `source/SourceManageView`、`source/AccountManageView` |
| 关键词/报告 | `KeywordView`、`ReportView` |
| 个人/认证 | `ProfileView`、`LoginView`、`RegisterView` |
| 管理 | `admin/{UserManageView,RoleManageView,DictManageView,MenuManageView,ConfigManageView,AuditLogView}` |

技术栈：Vue3 + Element Plus + ECharts；Nginx 做 SPA fallback 与 `/api` 反代。

---

## 6. 外部依赖

| 依赖 | 用途 | 配置 |
|---|---|---|
| DeepSeek API | 评论级分析、视频级洞察、报告文案 | `DEEPSEEK_API_KEY`；缺失时优雅降级 |
| Chrome / DrissionPage | 抖音页面采集 | 容器内置 Google Chrome |
| MySQL | 唯一业务事实 | 容器 `cpi-mysql` |

**无**：Redis、消息队列、向量库/embedding、对象存储。

---

## 7. 已成立的关键链路（可复现）

| 链路 | 证据 |
|---|---|
| 采集 → CSV → 清洗 → DB | 真实跑通；`collected=0` 收敛（2026-11-17） |
| 能力准入 → 执行 | `AdmissionGatedCollectionClient`；未验证能力零调用 |
| 去重记忆（跨次） | `/internal/collection/dedup` + `dedup.py`，第二次 `collected=0` |
| AI 分析 → 证据 → 报告 | 77 条证据；PDF/XLSX 导出 |
| 分层健康 | `/api/system/health/layers`：backend/db/collection/ai 四层 |

---

## 8. 明确不成立的"想当然"

| 说法 | 事实 |
|---|---|
| 有 Redis 缓存与任务协调 | ❌ 无 Redis，缓存是进程内 `MemoryCache` + TTL 快照，协调靠 DB 租约 |
| 统一内容资产模型 | ❌ `ct_*` 只读、无写入、无前端；主线仍是 `video/comment` |
| 有 `lead` / `conversion` 模块 | ❌ 不存在（且与当前定位冲突，不应新建） |
| 上传文件有资产管理 | ❌ `file_asset` 零引用 |
| 关键词搜索可用 | ❌ `UNVERIFIED`，搜索页返回验证码中间页 |
| Python 完全不写业务库 | ❌ `clean.py` 通过 pymysql 直写 `comment`/`comment_analysis` |
