# R1-06 · API 规范与接口清单

## 1. URL 规范

```
/api/v1/{module}/{resource}[/{id}][/{sub-resource}]
```

- 版本号在路径中固定为 `v1`，破坏性变更才升 `v2`
- 资源名用**复数**、kebab-case（`audit-logs`、`score-history`）
- 不使用动词路径，动作用子资源或特定端点表达：`POST /tasks/{id}/cancel`、`POST /leads/{id}/review`

## 2. 请求与响应

### 统一响应

```json
{ "code": 200, "message": "success", "data": { }, "traceId": "a1b2c3d4" }
```

| 字段 | 说明 |
|---|---|
| `code` | 200 成功；非 200 为业务错误码（HTTP 状态码同时表达类别） |
| `message` | 人类可读信息（中文），不暴露堆栈 |
| `data` | 业务数据；无返回时为 `null` |
| `traceId` | 贯穿 Java → Python 的请求标识，用于排障 |

### 错误码

| 区间 | 含义 | 示例 |
|---|---|---|
| 400xx | 参数校验失败 | `40001` 手机号格式错误 |
| 401xx | 未认证/Token 失效 | `40101` 未登录 |
| 403xx | 无权限 | `40301` 非管理员；`40302` 无权访问该内容 |
| 404xx | 资源不存在 | `40401` 任务不存在 |
| 409xx | 状态冲突 | `40901` 任务已取消；`40902` 线索状态不允许该操作 |
| 429xx | 限流 | `42901` 采集过于频繁 |
| 500xx | 服务内部/依赖失败 | `50001` AI 服务不可用；`50002` 采集服务超时 |

### 分页

请求：`page`（从 1 开始）、`size`（默认 20，上限 100）、`sort`（如 `-createdAt`）。
响应 `data` 为：

```json
{ "records": [], "total": 0, "current": 1, "size": 20, "pages": 0 }
```

（与前端现有 `records/total` 约定一致，复用 `common.web.PageResult`）

### 其它约定

| 项 | 规定 |
|---|---|
| 时间 | ISO-8601 带时区：`2026-11-15T18:00:00+08:00` |
| 空值 | 字段值为空时**保留键**并置 `null`，不省略键 |
| 命名 | JSON 用 camelCase；数据库用 snake_case |
| 批量 | 单次批量上限 100 条，超出返回 `40003` |
| 幂等 | 会重复提交的写接口需带 `Idempotency-Key` 头（见 §4） |

## 3. 安全规范

| 项 | 规定 |
|---|---|
| 认证 | JWT，`Authorization: <token>`（沿用现行，无 `Bearer` 前缀，R4 评估是否改为标准 `Bearer`） |
| Token 有效期 | 24h，退出后服务端置黑名单（`TokenStore` 迁 `common.security`） |
| 权限 | `@RequireRole` 注解 + 资源级校验（如 `ContentAccessService`） |
| 数据所有者 | 非管理员只能读写自己 `owner_user_id` 的资源 |
| 脱敏 | 手机号、邮箱在**视图 DTO** 中脱敏，实体不出 Controller |
| 审计 | 登录、删号、审批、状态流转写 `sys_audit_log` |
| 敏感字段 | 任何响应不得包含 `password_hash`（已由 `UserView` 保证） |

## 4. 幂等要求

| 接口 | 幂等键 |
|---|---|
| `POST /collection/tasks` | `Idempotency-Key` + `(owner, source, external_content_id, active_flag)` 唯一 |
| `POST /analysis/tasks` | `(comment_id, profile_id)` 唯一 |
| `POST /lead/leads`（由分析生成） | `(result_id, signal_type)` 唯一 |
| `POST /conversion/records` | `lead_id` 唯一 |

## 5. 接口清单

> 状态：`新增` / `复用`（现有接口语义不变仅改路径）/ `重写`（语义或结构变化）/ `废弃`
> 权限：`公开` / `登录` / `管理员` / `所有者`

### system（10）

| 方法 | URL | 功能 | 权限 | 幂等 | 优先级 | 状态 |
|---|---|---|---|---|---|---|
| POST | `/api/v1/system/auth/register` | 注册 | 公开 | 是 | P0 | 复用 |
| POST | `/api/v1/system/auth/login` | 登录 | 公开 | — | P0 | 复用 |
| POST | `/api/v1/system/auth/logout` | 退出 | 登录 | 是 | P0 | 复用 |
| GET | `/api/v1/system/auth/me` | 当前用户 | 登录 | — | P0 | 复用 |
| PUT | `/api/v1/system/auth/password` | 改密 | 登录 | — | P0 | 复用 |
| GET | `/api/v1/system/users` | 用户分页 | 管理员 | — | P0 | 复用 |
| DELETE | `/api/v1/system/users/{id}` | 删除用户 | 管理员 | 是 | P0 | 复用 |
| GET | `/api/v1/system/configs` | 配置列表 | 管理员 | — | P1 | 新增 |
| PUT | `/api/v1/system/configs/{key}` | 更新配置 | 管理员 | 是 | P1 | 新增 |
| GET | `/api/v1/system/audit-logs` | 审计日志 | 管理员 | — | P1 | 新增 |

### source（8）

| 方法 | URL | 功能 | 权限 | 优先级 | 状态 |
|---|---|---|---|---|---|
| GET | `/api/v1/source/sources` | 数据源列表（含能力状态） | 登录 | P0 | 新增 |
| PUT | `/api/v1/source/sources/{code}/status` | 启用/停用 | 管理员 | P1 | 新增 |
| GET | `/api/v1/source/accounts` | 平台账号列表 | 管理员 | P1 | 新增 |
| POST | `/api/v1/source/accounts` | 绑定账号 | 管理员 | P1 | 新增 |
| GET | `/api/v1/source/keywords` | 关键词列表 | 登录 | P0 | 复用 |
| POST | `/api/v1/source/keywords` | 新建关键词 | 登录 | P0 | 复用 |
| DELETE | `/api/v1/source/keywords/{id}` | 删除关键词 | 所有者 | P0 | 复用 |
| POST | `/api/v1/source/keywords/{id}/discovery` | 触发发现 | 所有者 | P1 | 复用 |

### collection（8）

| 方法 | URL | 功能 | 权限 | 优先级 | 状态 |
|---|---|---|---|---|---|
| POST | `/api/v1/collection/tasks` | 创建采集任务 | 登录 | P0 | 复用 |
| GET | `/api/v1/collection/tasks` | 任务分页 | 登录 | P0 | 复用 |
| GET | `/api/v1/collection/tasks/{id}` | 任务详情 | 所有者/管理员 | P0 | 复用 |
| GET | `/api/v1/collection/tasks/{id}/logs` | 执行日志 | 所有者/管理员 | P0 | 复用 |
| POST | `/api/v1/collection/tasks/{id}/cancel` | 取消 | 所有者 | P0 | 复用 |
| POST | `/api/v1/collection/tasks/{id}/retry` | 重试失败项 | 所有者 | P0 | 复用 |
| POST | `/api/v1/collection/imports` | 手工导入 CSV | 登录 | P2 | 复用 |
| GET | `/api/v1/collection/discoveries` | 发现任务列表 | 登录 | P1 | 复用 |

### content（8）

| 方法 | URL | 功能 | 权限 | 优先级 | 状态 |
|---|---|---|---|---|---|
| GET | `/api/v1/content/contents` | 内容列表 | 登录（按可见性过滤） | P0 | 重写 |
| GET | `/api/v1/content/contents/{id}` | 内容详情 | 可见 | P0 | 重写 |
| GET | `/api/v1/content/comments` | 评论分页 | 可见 | P0 | 重写 |
| GET | `/api/v1/content/comments/tree` | 楼中楼 | 可见 | P0 | 重写 |
| GET | `/api/v1/content/comments/search` | 评论检索 | 可见 | P0 | 重写 |
| POST | `/api/v1/content/normalize` | 触发清洗入库 | 登录 | P1 | 重写 |
| GET | `/api/v1/content/grants` | 授权列表 | 管理员 | P1 | 新增 |
| POST | `/api/v1/content/grants` | 授予读权限 | 管理员 | P1 | 新增 |

### analysis（12）

| 方法 | URL | 功能 | 权限 | 优先级 | 状态 |
|---|---|---|---|---|---|
| GET | `/api/v1/analysis/profiles` | 档案列表（含版本） | 登录 | P0 | 重写 |
| POST | `/api/v1/analysis/profiles` | 新建档案版本 | 管理员 | P1 | 新增 |
| POST | `/api/v1/analysis/tasks` | 提交分析任务 | 登录 | P0 | 复用 |
| GET | `/api/v1/analysis/tasks` | 任务分页 | 登录 | P0 | 复用 |
| GET | `/api/v1/analysis/tasks/{id}` | 任务详情 | 所有者/管理员 | P0 | 复用 |
| GET | `/api/v1/analysis/results` | 评论级结果 | 可见 | P0 | 复用 |
| GET | `/api/v1/analysis/insights` | 视频级洞察 | 可见 | P0 | 复用 |
| POST | `/api/v1/analysis/insights` | 生成洞察 | 登录 | P0 | 复用 |
| GET | `/api/v1/analysis/reviews` | 复核队列 | 登录 | P0 | 复用 |
| GET | `/api/v1/analysis/reviews/stats` | 复核统计 | 登录 | P1 | 复用 |
| POST | `/api/v1/analysis/reviews/{resultId}` | 提交复核 | 登录 | P0 | 复用 |
| GET | `/api/v1/analysis/usage` | AI 用量 | 管理员 | P1 | 复用 |

### lead（9）

| 方法 | URL | 功能 | 权限 | 优先级 | 状态 |
|---|---|---|---|---|---|
| GET | `/api/v1/lead/leads` | 线索池分页（按状态/等级/领域筛） | 登录 | P0 | 新增 |
| GET | `/api/v1/lead/leads/{id}` | 线索详情 | 所有者/管理员 | P0 | 新增 |
| POST | `/api/v1/lead/leads` | 手工建线索 | 登录 | P1 | 新增 |
| GET | `/api/v1/lead/leads/{id}/evidence` | 证据链 | 可见 | P0 | 新增 |
| POST | `/api/v1/lead/leads/{id}/review` | 审核（通过/拒绝） | 管理员 | P0 | 新增 |
| GET | `/api/v1/lead/leads/{id}/score-history` | 评分历史 | 登录 | P1 | 新增 |
| POST | `/api/v1/lead/leads/{id}/followups` | 新增跟进 | 所有者 | P1 | 新增 |
| GET | `/api/v1/lead/leads/{id}/followups` | 跟进记录 | 所有者 | P1 | 新增 |
| GET | `/api/v1/lead/signals` | 需求信号列表 | 登录 | P1 | 新增 |

### conversion（6）

| 方法 | URL | 功能 | 权限 | 优先级 | 状态 |
|---|---|---|---|---|---|
| GET | `/api/v1/conversion/products` | 产品镜像 | 登录 | P1 | 新增 |
| POST | `/api/v1/conversion/products/sync` | 从 CoolSkyAI 同步 | 管理员 | P1 | 新增 |
| GET | `/api/v1/conversion/matches` | 匹配结果 | 登录 | P1 | 新增 |
| POST | `/api/v1/conversion/matches` | 建立匹配 | 登录 | P1 | 新增 |
| GET | `/api/v1/conversion/records` | 成交记录 | 登录 | P1 | 新增 |
| POST | `/api/v1/conversion/records` | 登记成交（关联外部订单） | 登录 | P1 | 新增 |

### analytics（8）

| 方法 | URL | 功能 | 权限 | 优先级 | 状态 |
|---|---|---|---|---|---|
| GET | `/api/v1/analytics/dashboard` | 获客驾驶舱 | 登录 | P0 | 重写（聚合现有 overview/analysis） |
| GET | `/api/v1/analytics/contents` | 内容统计（地区/情感/趋势/词云/对比） | 登录 | P0 | 重写 |
| GET | `/api/v1/analytics/demands` | 需求趋势 | 登录 | P1 | 新增 |
| GET | `/api/v1/analytics/leads` | 线索漏斗与分布 | 登录 | P1 | 新增 |
| GET | `/api/v1/analytics/conversions` | 渠道效果与获客成本 | 登录 | P2 | 新增 |
| POST | `/api/v1/analytics/reports` | 创建报告 | 登录 | P0 | 复用 |
| GET | `/api/v1/analytics/reports` | 报告列表 | 登录 | P0 | 复用 |
| GET | `/api/v1/analytics/reports/{id}/{file,versions}` | 导出/版本 | 登录 | P0 | 复用 |

### common（1）

| 方法 | URL | 功能 | 权限 |
|---|---|---|---|
| GET | `/api/v1/health` | 健康检查（含 DB） | 公开 |

## 6. 与现有接口的差异

| 变化 | 影响 |
|---|---|
| 全部加 `/v1` 前缀 | 前端 `api/index.js` 58 个方法需统一调整 |
| `/api/analysis/*` 统计类迁到 `/api/v1/analytics/contents` | `DashboardView`、`OverviewView` 需改调用 |
| `/api/keyword/*` 迁到 `/api/v1/source/keywords` | `KeywordView` |
| `/api/collect/*` 迁到 `/api/v1/collection/tasks` | `CollectView`、`TaskCenterView` |
| 新增 `/api/v1/lead/*` | 需新页面 |
| 废弃 | 无（现有端点全部有对应目标） |
