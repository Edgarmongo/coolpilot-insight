# 03 · 接口清单

来源：`dy-backend/src/main/java/.../controller/`（静态解析注解，未启动服务）
共 **12 个 Controller、59 个端点**。

## 1. 端点全量

### 系统与健康

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查，返回 `db: UP/DOWN` |
| GET | `/api/admin/users` | 用户列表（管理员） |
| DELETE | `/api/admin/user/{id}` | 删除用户（管理员） |

### 用户

| 方法 | 路径 |
|---|---|
| POST | `/api/user/register` |
| POST | `/api/user/login`（**字段是 `phone`，不是 `username`**） |
| GET | `/api/user/info` |
| PUT | `/api/user/password` |
| POST | `/api/user/logout` |

### 关键词与发现

| 方法 | 路径 |
|---|---|
| POST / GET | `/api/keyword` |
| DELETE | `/api/keyword/{id}` |
| POST | `/api/keyword/{id}/discover` |
| GET | `/api/keyword/{id}/videos` |
| GET | `/api/keyword/discovery-tasks` |

### 采集

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/collect/start` | 创建采集任务 |
| GET | `/api/collect/status` | 当前任务状态 |
| GET | `/api/collect/tasks` | 任务列表 |
| GET | `/api/collect/tasks/{id}` | 任务详情 |
| GET | `/api/collect/tasks/{id}/logs` | 执行日志 |
| POST | `/api/collect/tasks/{id}/cancel` | 取消 |
| POST | `/api/collect/tasks/{id}/retry` | 重试 |
| POST | `/api/collect/upload` | 手工上传 CSV |

### 评论与统计

| 方法 | 路径 |
|---|---|
| GET | `/api/comment/list` |
| GET | `/api/comment/tree`（楼中楼树结构） |
| GET | `/api/comment/search` |
| GET | `/api/overview` |
| GET | `/api/overview/video-options` |

### 分析（本地统计，不调 AI）

| 方法 | 路径 |
|---|---|
| GET | `/api/analysis/summary` |
| GET | `/api/analysis/region` |
| GET | `/api/analysis/sentiment` |
| GET | `/api/analysis/trend` |
| GET | `/api/analysis/wordcloud` |
| GET | `/api/analysis/top-comments` |
| GET | `/api/analysis/topic` |
| GET | `/api/analysis/sankey` |
| GET | `/api/analysis/videos` |
| GET | `/api/analysis/compare`（多视频对比） |
| POST | `/api/analysis/refresh`（重算本地统计） |

### AI（调用 Flask → DeepSeek）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/analyze-comments` | 提交评论级分析任务（异步） |
| POST | `/api/ai/video-insight` | 生成视频级洞察（同步，实测 5 秒） |
| GET | `/api/ai/video-insights` | 洞察列表 |
| GET | `/api/ai/comment-summary` | 评论维度汇总 |
| GET | `/api/ai/tasks` | 分析任务列表 |
| GET | `/api/ai/tasks/{id}` | 任务详情（含批次） |
| GET | `/api/ai/usage` | 当日 token / 成本 |

### 复核

| 方法 | 路径 |
|---|---|
| GET | `/api/review/queue` |
| GET | `/api/review/stats` |
| POST | `/api/review/{analysisId}` |
| GET | `/api/review/{analysisId}/history` |

### 报告

| 方法 | 路径 |
|---|---|
| POST / GET | `/api/report` |
| GET | `/api/report/{id}` |
| GET | `/api/report/{id}/versions` |
| GET | `/api/report/{id}/export`（Markdown） |
| GET | `/api/report/{id}/file?format=pdf\|xlsx` |
| DELETE | `/api/report/{id}` |

### 清洗

| 方法 | 路径 |
|---|---|
| GET | `/api/clean/files` |
| POST | `/api/clean/run` |
| POST | `/api/clean/export` |

## 2. 鉴权方式

- JWT，登录后通过 `Authorization` 头传递（**不加 `Bearer` 前缀**，直接传 token）
- `@RequireRole` 注解 + 拦截器做角色校验
- ⚠️ 已知坑：拦截器 `excludePathPatterns` 不能随意扩充。
  `/api/analysis/summary` 曾被错误排除，导致上下文为空且未登录可访问。**新增白名单前必须确认无敏感数据。**

## 3. 目标架构缺口（相对 8 大中心）

| 目标能力 | 现有接口 | 缺口 |
|---|---|---|
| 数据源管理 | 无 | 无 `/api/source/*`，平台写死 |
| 平台账号授权 | 无 | 无 `source_account` 概念 |
| 线索池 | 无 | 无线索相关任何端点 |
| 线索审核/跟进/转化 | 无 | 无 |
| 产品匹配 | 无 | 无产品目录接口 |
| 运营分析（漏斗/渠道/获客成本） | 无 | 只有 `/api/overview` 表层统计 |
| 分析规则管理 | 部分（`analysis_profile` 表存在） | 无 CRUD 接口，无法在界面配置 profile |
| 模型配置 | 无 | provider/model 硬编码在 `AiAnalysisService` |

## 4. 前端路由与页面对应

15 条路由 / 12 个业务页面：`dashboard`、`overview`、`collect`、`tasks`、`clean`、
`comments`、`keywords`、`insight`、`review`、`reports`、`profile`、`admin/users`（+ login/register/根重定向）。

→ 现有菜单是「按功能」组织，目标架构是「按业务中心」组织，改造时需要重排导航但页面大多可复用。
