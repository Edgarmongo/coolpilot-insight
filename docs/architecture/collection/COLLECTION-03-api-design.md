# COLLECTION-03 · API 设计

版本：v2　仅设计，未实现。统一前缀 `/api/v1`，统一 `Result`/`PageResult`/错误码，权限用 `@RequirePermission`。


## 裁决记录（DECISION-02，2026-11-16）

| # | 事项 | 裁决 |
|---|---|---|
| 1 | `task_item` 唯一键 | 保留 `UK(task_id,video_id)` + `UK(video_id,active_flag)`，**不加 run_id**；唯一性跨 Run 生效 |
| 2 | Task↔Source | 首期一任务一源，保留 `collection_task.source_id`，**不建 `col_task_target`**；跨平台编排留待未来上层 Collection Plan |
| 3 | `failure_type` | 七类固定：AUTHENTICATION / AUTHORIZATION / RATE_LIMIT / NETWORK / PLATFORM / DATA / SYSTEM；细节走 `error_code`；用户取消不算 failure |
| 4 | checkpoint | 不加字段；`last_cid/page_no/cursor_value` 保留；首期仅 CID_SET；修复 Java `saveCheckpoint` 写 `last_cid`/`page_no` |

**实测修正**：`collection_task_item` 真实唯一键为 `uk_task_video(task_id,video_id)` +
`uk_video_active(video_id,active_flag)`；历史 4 个任务为 2 FAILED + 2 SUCCESS，**无 PENDING**。


## 1. 数据源（改造现有 `/api/v1/source/sources`）

| 方法 | 路径 | 权限 | 现状 |
|---|---|---|---|
| GET | `/source/sources` | 登录 | ✅ 已有 |
| PUT | `/source/sources/{code}` | `source:source:update` | 新增（改 name/remark/capability） |
| PATCH | `/source/sources/{code}/status` | `source:source:enable` | ✅ 已有（改 status） |
| GET | `/source/sources/{code}/capabilities` | 登录 | 新增（返回结构化能力，含五态） |
| POST | `/source/sources/{code}/health-check` | `source:source:check` | 新增（触发检查，写 `last_checked_at`） |

## 2. 平台账号（表已有，接口全新增）

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/source/accounts` | `source:account:list` |
| POST | `/source/accounts` | `source:account:manage` |
| PUT | `/source/accounts/{id}` | `source:account:manage` |
| PATCH | `/source/accounts/{id}/status` | `source:account:manage` |
| POST | `/source/accounts/{id}/verify` | `source:account:manage` |
| GET | `/source/accounts/{id}/usage` | `source:account:list` |

**凭证**：不提供 `POST .../credentials` 上传接口（裁决 §2：凭证由外部机制管）。只暴露 `credential_ref` 的**是否已配置**布尔值。

## 3. 关键词与策略

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/source/keyword-groups` | 分组列表/新建 |
| PUT/DELETE | `/source/keyword-groups/{id}` | 有策略引用时禁止删 |
| GET | `/source/keyword-groups/{id}/keywords` | 分组下关键词 |
| POST | `/source/keywords` | 新建（可带 groupId） |
| PUT/DELETE | `/source/keywords/{id}` | 改/删 |
| GET | `/source/keyword-groups/{id}/policies` | 版本列表 |
| POST | `/source/keyword-groups/{id}/policies` | 建草稿（version 自动 +1） |
| POST | `/source/policies/{id}/validate` | 校验 JSON schema |
| POST | `/source/policies/{id}/publish` | 发布（置 PUBLISHED，此后不可改） |
| GET | `/source/policies/{id}` | 版本详情 |

兼容性：现有 `/api/v1/source/keywords` 的 `list/create/disable/delete` 保留，`create` 增加可选 `groupId`，**不破坏现有前端**。

## 4. 采集任务

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/collection/tasks` | 列表（现有 `CollectionQueryService`） |
| GET | `/collection/tasks/{id}` | 详情 |
| POST | `/collection/tasks` | 创建（含 9 条预校验） |
| PUT | `/collection/tasks/{id}` | 仅 IDLE 可改 |
| POST | `/collection/tasks/{id}/trigger` | 手动触发 → 建 Run |
| POST | `/collection/tasks/{id}/pause` / `resume` | 仅作用于 RUNNING 的 run |
| POST | `/collection/tasks/{id}/cancel` | 取消当前 run（现有 `cancel` 语义调整） |
| PATCH | `/collection/tasks/{id}/status` | ENABLED/DISABLED（停用不删） |
| POST | `/collection/tasks/{id}/schedule` | 设置 cron |
| DELETE | `/collection/tasks/{id}/schedule` | 取消调度 |
| POST | `/collection/tasks/validate` | 创建前预检（不落库） |

### 创建任务的 9 条预校验（对应任务卡 §4.4）

1. `source_id` 存在且 `status=ENABLED`
2. 该源支持 `capability`（读 `capability_json`）
3. 该能力 `status=VERIFIED`（`IMPLEMENTED` 不足以创建）
4. `auth_mode=PUBLIC` 时该能力允许公开访问
5. `auth_mode=AUTHENTICATED` 时 `account_id` 非空且属于该源
6. 账号 `status=ACTIVE` 且 `expires_at` 未过期
7. `policy_id` 指向的策略 `status=PUBLISHED`
8. 策略中平台不支持的条件可通过 `LOCAL_FILTER` 处理（否则拒绝）
9. `maxItems` ≤ 该平台 `constraints.maxItems`

任一条失败返回 400 + 具体原因，**不创建注定失败的任务**。

## 5. 采集记录

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/collection/runs` | 运行记录列表（按 task/status/时间筛选） |
| GET | `/collection/runs/{id}` | 运行详情（含 config_snapshot） |
| GET | `/collection/runs/{id}/executions` | 子执行 |
| GET | `/collection/executions/{id}` | 执行详情（含 failure_type） |
| GET | `/collection/executions/{id}/events` | 事件（复用 `collection_task_log`） |
| GET | `/collection/executions/{id}/results` | 结果摘要（关联 `ct_raw_archive` 计数） |
| POST | `/collection/executions/{id}/retry` | 重试（阈值内） |

## 6. 现有 API 兼容处理

| 现有 | 处理 |
|---|---|
| `GET /api/collect/tasks` | 保留，内部改读新模型 |
| `GET /api/collect/tasks/{id}` | 保留，加 `runs` 字段 |
| `POST /api/collect/tasks/{id}/cancel` | 语义从"取消任务"改为"取消当前 run" |
| `GET /api/collect/status` | 保留（前端轮询用） |
| `/api/keyword`（旧） | 保留到 R7 前端切换后删除 |

## 7. 权限码（需新增）

```
source:source:update   source:source:enable   source:source:check
source:account:list    source:account:manage
source:keyword:manage  source:policy:manage
collection:task:create collection:task:control collection:run:view
```
