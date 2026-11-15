# 现有采集能力 → 目标架构 · 映射表

日期：2026-11-16　方法：逐文件核对 + 数据库实查，不依据设计文档推断
HEAD：`4f1a9bd`　测试基线：109（Java）+ 9（Python）

---

## 一、结论概览

| 结论 | 数量 | 含义 |
|---|---:|---|
| **保留** | 7 项 | 已实现且符合目标，直接复用 |
| **改造** | 6 项 | 有基础但语义/结构不符，需调整 |
| **新建** | 5 项 | 完全没有 |
| **不建议建** | 4 项 | 任务卡要求，但实测不必要（§5） |

---

## 二、逐能力映射

### 2.1 平台与能力

| 能力 | 现状（证据） | 目标落点 | 结论 |
|---|---|---|---|
| 平台定义 | `ds_source`（code/name/source_type/adapter_code/status） | `ds_platform` | **保留**（表名不改，避免无收益迁移） |
| 能力声明 | `ds_source.capability_json` = `{"contentDetail":true,"comments":true,"keywordSearch":false,"commentStatus":"AVAILABLE","keywordStatus":"PENDING"}` | `ds_capability` 8 字段 | **改造**：现有 JSON 已含"支持与否 + 状态"，但缺 `verified_at` / `adapter_version` / `constraints_json`。建议**保持 JSON 列**，补这 3 个键 |
| 能力状态枚举 | 现用 `AVAILABLE/PENDING` | `IMPLEMENTED/VERIFIED/DEGRADED/DISABLED/UNSUPPORTED` | **改造**：需加 `IMPLEMENTED`（代码写完但未验证）与 `DEGRADED` |
| 健康检查 | **无** | `ds_health_check` | **新建**，但建议只用字段（`last_checked_at`/`last_check_result`），不建表 |

### 2.2 账号

| 能力 | 现状 | 目标 | 结论 |
|---|---|---|---|
| 账号表 | `ds_account`（source_id/external_account_id/auth_type/credential_ref/status/expires_at/last_used_at）**0 行、0 接口、0 页面** | `ds_account` | **表保留**，接口/页面**新建** |
| 凭证 | `credential_ref` 只存引用，无明文 | `ds_credential`（密文） | **保留现有设计**。凭证放外部密管/环境变量，比入库加密更安全 |
| 双账号模式 | **无概念** | PUBLIC / AUTHENTICATED | **新建**：`ds_account` 加 `auth_mode`，任务侧引用 |
| 实测背景 | 匿名态采集上限约 3 页 15~20 条（R0-05 记录） | — | 这条是"必须支持 AUTHENTICATED"的事实依据 |

### 2.3 关键词与策略

| 能力 | 现状 | 目标 | 结论 |
|---|---|---|---|
| 关键词 CRUD | `ds_keyword` + `KeywordService`（去重键 `(source, owner, normalized)`，9 条测试） | `ds_keyword` | **保留** |
| 分组 | **无** | `ds_keyword_group` | **改造**：加 `group_code` 列，不建表 |
| 组合规则 AND/OR/NOT/EXACT | **无**，当前只有单个关键词字符串 | `ds_policy.policy_json` | **新建**（策略表 + 结构化表达式） |
| 排除词 | **无** | policy 内 | 随策略一起**新建** |
| 策略版本 | **无**；现有 `discover_policy` 只是 `MANUAL/DAILY` 开关 | `ds_policy`（`UK(group_id, version)`，发布后不可改） | **新建** |
| 任务绑定策略版本 | **无** | `col_task_target.policy_id` | **新建**（依赖上面两项） |

> 任务卡把"策略版本"列为三大风险之一，我同意。现状确实是零。

### 2.4 任务模型（最关键的差距）

| 能力 | 现状 | 目标 | 结论 |
|---|---|---|---|
| 任务定义 | `collection_task`：`task_no/task_type/status/owner_user_id/params_json/total_items/success_items/failed_items/时间戳`（4 行真实数据） | `col_task` | **改造**：补 `source_id`、`account_id`、`execution_mode`、`schedule_expression`、`next_run_at` |
| 配置快照 | ✅ `params_json`（创建时固化） | `config_snapshot` | **保留**——任务卡要求的"快照"已满足 |
| 运行实例 Run | **无**。`status/started_at/finished_at` 是覆盖式（最后一次） | `col_run` | **新建**（本模块最大缺口） |
| 执行单元 Execution | 有 `collection_task_item`（按内容粒度） | `col_execution` | **改造语义**：现有 item 是"一个视频一个 item"，目标 execution 是"一次 run 内一个执行单元"。可复用表，加 `run_id`/`attempt_no`/`request_count` |
| 任务目标 Target | **无**（任务只有一个隐式目标） | `col_task_target` | **新建**（多平台多目标才需要；若首期单平台可后置） |
| 断点 | `collection_checkpoint`（per item：`persisted_count` + `cursor_json`） | `col_checkpoint` | **改造**：见 §3 事实 1，`cursor_json` 实际为空 |
| 重试 | `item.retry_count` + `max_retry` + log | `col_retry` | **保留现有**，不建独立重试表 |
| 日志/事件 | `collection_task_log`（`event_type/event_level/message`） | `col_event` | **保留**，加 `run_id`/`execution_id` 外键即可 |

### 2.5 采集执行（Python）

| 能力 | 现状（`collection-service/app/adapters/douyin/`） | 目标 | 结论 |
|---|---|---|---|
| 评论采集 | `comment.py` 312 行：`preflight_check` → `scroll_comment_panel` → CSV 追加 | 适配器 `collect_comments` | **保留**，包一层 `PlatformAdapter` |
| CID 采集 | ✅ 文件级证据：`load_existing_cids()` 读 CSV 已有 cid；R0-05 实测 19/19 返回 cid | 内容唯一键 | **保留** |
| 去重 | 双层：`comment.py` 的 `fingerprint(nickname, dt, content)` + `clean.py` 的双唯一键（`cid` 可空 + `dedup_key` 恒非空） | — | **保留**，这是已验证的正确设计 |
| 楼中楼 | `comment.py` 含 `reply` 处理（`reply_comment`） | — | **保留** |
| 分页 | **DOM 滚动**（`scroll_comment_panel`），非 cursor/API 翻页 | 适配器分页契约 | **改造**：见 §3 事实 1 |
| 搜索发现 | `search.py` 146 行，**从未验证成功**（能力标记 `keywordSearch:false`） | `discover()` | **未验证，不能计入能力矩阵** |
| HTTP 服务 | `app/server.py`（R3-04 新建）：`/health`、`/capabilities`、`/v1/jobs` 等 5 端点，9 条 Python 测试 | `/internal/v1/*` | **改造**：路径与 DTO 按任务卡对齐，逻辑可复用 |

### 2.6 Java ↔ Python 通信

| 项 | 现状 | 目标 | 结论 |
|---|---|---|---|
| 调用方式 | 子进程（`ProcessRunner`，已有超时/强杀/异常翻译） | HTTP | **改造**：已有 `CollectionClient` 接口作为切换点 |
| HTTP 客户端 | `collection-service` 已能跑，但 Java 侧无 `HttpCollectionClient` | `CollectionServiceClient` | **新建**（接口已就绪） |
| 数据流 | 脚本写 CSV → `clean.py` 解析入库 | Python 返回 JSON → Java 入库 | **改造**：需 `ContentIngestService`（已建）承接 |
| Trace ID | Java 侧已有（`X-Trace-Id`） | 跨服务贯穿 | **改造**：HTTP 头透传即可 |

---

## 三、三个必须先说清的事实

### 事实 1：分页不是 cursor，是 DOM 滚动

`comment.py` 里没有 `cursor` / `has_more` / `max_cursor`。它靠 `scroll_comment_panel()` 触发懒加载，边滚边收集。

**后果**：`collection_checkpoint.cursor_json` 这一列**存不了有用的游标**。Java 侧 `saveCheckpoint()` 只写了 `persisted_count`。

所以任务卡要求的"断点续采"目前是**半成品**：能做到"已存 N 条不重复"，做不到"从第 200 条之后继续"。

**建议**：首期把断点定义为「按 cid 集合续采」——重新打开页面，跳过已存 cid，直到累计够数。这与现有 `load_existing_cids()` 一致，是能真正工作的方案。要真正的游标续采，必须改用 API 接口（抖音评论 API 是 cursor 制），那是另一件事。

### 事实 2：任务不知道自己属于哪个数据源

`collection_task` 没有 `source_id`。所以：

- 无法按源统计
- 无法校验"该源是否支持此任务类型"
- **无法实现任务卡的 9 条创建前校验**（第 1、2、3、5 条都依赖 `source_id`）

这是 Batch C 的第一个动作，不是可选项。

### 事实 3：`collection_task_item` 与目标 `col_execution` 语义不同

| | 现在 | 目标 |
|---|---|---|
| 粒度 | 一个视频 = 一个 item | 一次 run 内一个执行单元（可能覆盖多个视频） |
| 状态 | 自身状态机 | 由 run 派生 + attempt_no |
| 重试 | `retry_count` 字段 | 独立 retry 记录 |

**结论**：不能简单改名。要么给 item 补 `run_id` 当成 execution 用（推荐，改动小），要么新建表并与 item 并存（会两套数据，不推荐）。

---

## 四、与任务卡的冲突（需你裁决）

| # | 任务卡要求 | 现状/我的意见 |
|---|---|---|
| 1 | `ds_capability` 独立 8 字段表 | 建议保留 `capability_json` + 补 3 键。理由：拆表后每次读能力都要 join，而能力是平台的属性集合，没有独立生命周期 |
| 2 | `ds_credential` 密文表 | 建议不建。`credential_ref` 指向外部密管，**凭证不进业务库**比"入库后加密"更安全，也符合任务卡自己的"凭证不混入账号表" |
| 3 | `ds_health_check` 表 | 建议降级为字段。健康检查是"最近状态"，不是流水 |
| 4 | `col_retry` 独立表 | 建议不建。`item.retry_count` + log 已能追溯；独立表需要额外维护一致性 |
| 5 | 运行状态含 `PENDING` | `PENDING` 应**只存在于 Run**，Task 用聚合态（IDLE/RUNNING/PAUSED/DISABLED）。两处都有会分叉 |
| 6 | 8 张 Source 表 + 7 张 Collection 表 | 建议 **3 张新表**（`ds_policy`、`col_run`、可选 `col_task_target`）+ 8 个新列 |

---

## 五、Batch A 最小可交付（我的建议）

任务卡要求 Batch A 做"数据源 + 账号 + 凭证 + 健康检查 + 页面 + 测试"。按上面结论，实际范围是：

| 项 | 动作 |
|---|---|
| `ds_source` | 补 3 个 JSON 键（`verifiedAt`/`adapterVersion`/`constraints`）+ 2 列（`last_checked_at`/`last_check_result`） |
| `ds_account` | 加 `auth_mode`（PUBLIC/AUTHENTICATED）+ 接口 6 个 + 页面 1 个 |
| 能力枚举 | `AVAILABLE/PENDING` → 五态 |
| 健康检查 | 复用 `GET /api/v1/source/sources` + 新增 `POST /{code}/health-check` |
| 凭证 | **不动**（保持引用式） |
| 页面 | 数据源管理、平台账号 |

**不做**：`ds_credential`、`ds_health_check` 表、9 张 Source 表。

---

## 六、对任务卡三大风险的现实评估

| 任务卡列的风险 | 现状 | 我的判断 |
|---|---|---|
| **1. Task/Run/Execution 混乱** | 已混：`task.status` 承担了 run 的语义 | **确实存在**，且已经在影响可观测性（看不到历史运行）。`col_run` 是必做项 |
| **2. 策略不能版本化** | 零实现，只有 `discover_policy` 开关 | **确实存在**，但优先级低于 §3 事实 2（任务无 source_id）。没有 source_id，策略绑定了也没处用 |
| **3. Java/Python 边界模糊** | 子进程 + CSV 中转；Python 写 CSV，Java 读 | **确实存在**。好消息是 `CollectionClient` 接口已抽出，切换成本比想象低 |

第 3 点补充：现在 **Python 不接触数据库**（写 CSV），这其实已经满足"Python 不直连业务库"的要求。改 HTTP 后仍保持这个边界即可，不需要反转。

---

## 七、下一步

1. 你裁决 §4 的 6 处冲突（尤其第 1、2 条：能力要不要拆表、凭证要不要入库）
2. 确认 §3 事实 1 的处理方式（断点按 cid 集合续采 vs 改用 API 游标）
3. 我按 §5 出 Batch A 的字段字典 + ER 图，再动 DDL

**在裁决前不建议动 Batch A 的建表**——能力表和凭证表的选择会影响后续所有代码。
