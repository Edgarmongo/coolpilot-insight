# 数据采集模块 · 需求梳理（对齐现有实现）

日期：2026-11-16　状态：**待确认**（§5 有 3 个决策、§6 有 1 处建议调整）

---

## 一、先说结论：提案中约 60% 已有实现

你给的设计是完整的目标形态。但直接照单开发会重复造轮子——下表是**代码里已经有的**：

| 提案能力 | 现状 | 证据 |
|---|---|---|
| 数据源列表 + 能力模型 | ✅ 有 | `ds_source.capability_json`（DOUYIN 已标 `keywordSearch:false`） |
| 能力"待验证"语义 | ✅ 有 | `keywordStatus: PENDING` / `commentStatus: AVAILABLE` |
| 关键词 CRUD + 归属校验 | ✅ 有 | `ds_keyword` + `KeywordService`（去重键 `(source,owner,normalized)`） |
| 任务配置快照 | ✅ 有 | `collection_task.params_json`——**创建时固化，天然满足"改配置不影响历史任务"** |
| 任务状态机 | ✅ 有 | PENDING/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED |
| 取消 / 重试 | ✅ 有 | 有状态校验 + 4 条测试（`CollectStateMachineTest`） |
| 断点续采 | ✅ 有表 | `collection_checkpoint`（游标 + 已采集数，按 task_item） |
| 执行日志 | ✅ 有 | `collection_task_log`（日志类型/级别/消息） |
| 原子领取 | ✅ 有 | `active_flag` + `UK(source, external_id, active_flag)` |
| 内容标准化 + 去重 | ✅ 有 | `ct_content`/`ct_comment`（双唯一键）/`ct_raw_archive` |

**所以本模块真正缺的不是"五个菜单"，是四块能力。**

---

## 二、真正的四个缺口

### 缺口 1：没有"运行记录"这一层（最严重）

提案要求区分**任务 / 运行 / 子任务**三层。现在只有两层：

```
collection_task       ← 任务主表，status/started_at/finished_at 是"最后一次"覆盖式
collection_task_item  ← 执行对象
```

后果：一个任务跑了 3 次，只能看到最后一次的开始时间、耗时、成功数。
**无法回答"这个任务历史上每次跑了多少、为什么失败"。**

这正是提案里"不能只在任务主表记录最后一次运行结果"指出的问题——**它是对的，我们确实缺**。

建议新增 `collection_run`：`task_id / run_no / status / started_at / finished_at / requested / fetched / inserted / duplicated / failed / trigger_type(MANUAL\|SCHEDULE) / error_summary`。

### 缺口 2：任务不记录数据源与账号

`collection_task` 现有列：`task_no, task_type, status, owner_user_id, params_json, total/success/failed_items, 时间戳`。

**没有 `source_id`、没有 `account_id`。**

后果：
- 无法按数据源统计（"抖音采了多少、Web 采了多少"）
- 无法实现"账号授权过期时禁止创建任务"——因为任务根本不知道用哪个账号
- 提案中"账号授权过期不能继续采集"这条**现在无法落地**

### 缺口 3：失败原因没有分类

现在只有 `error_code` + `error_message` 自由文本。

提案明确要求区分：**权限不足 / 数据不可访问 / 达到限制 / 平台能力不支持 / 网络失败**。

这是刚性需求——否则后面分析时无法区分"没发现需求"和"根本没采到数据"。建议加 `failure_type` 枚举。

### 缺口 4：`ds_account` 是空壳

表已建（含 `credential_ref` 只存引用、不存明文），但**零接口、零页面、零数据**。

提案里整个"平台账号"菜单，实际要从零做。

---

## 三、对提案数据库模型的调整建议

提案列了 19 张候选表。**我不建议全建**，理由是按现有实现只需要 3 张新表。

| 提案候选表 | 建议 | 理由 |
|---|---|---|
| `source_platform` + `source_capability` | **合并为现有 `ds_source`**（JSON 能力列） | 能力是平台的一个属性，拆表后每次读平台能力都要 join；JSON 已能表达"是否支持+状态+备注" |
| `source_account` | ✅ 用现有 `ds_account` | 已建，缺接口 |
| `source_credential` | **不建**，保留 `credential_ref` 字符串 | 引用外部密文（环境变量/密管服务），凭证绝不进业务库 |
| `source_health_check` | **降级为字段**，不建表 | 只需 `last_checked_at` + `last_check_result`，建表等于每次检查写一行 |
| `source_keyword_group` + `source_keyword` + `source_collection_policy` | **合并进 `ds_keyword`**，加 3 列 | 见下 |
| `collection_task` / `_item` / `_checkpoint` / `_log` | ✅ 用现有 | 已实现 |
| **`collection_run`** | ✅ **新增** | 缺口 1 |
| `collection_execution` / `collection_retry` | **不建** | 与 `task_item` 重复；重试信息在 item 的 `retry_count` + log 里已够 |
| `content_*` 五张 | ✅ 用现有 `ct_*` | 已实现（含原始归档分离） |
| `content_source_relation` | **不建** | 提案要求"一个内容可被多次采集"，用 `ct_raw_archive` 按 `(source,external_id,payload_hash)` 天然支持；溯源查 archive 即可 |

**关键词表的调整**（对应提案的三级结构）：

现有 `ds_keyword`：`source_id, keyword, normalized, owner_user_id, status, discover_policy, last_discovered_at`

建议加 3 列而非拆 3 表：

```sql
group_code      VARCHAR(32)   -- 一级：分组（如 ai_dev）
match_type      VARCHAR(16)   -- 精确/模糊
exclude_words   VARCHAR(255)  -- 逗号分隔的排除词
```

`discover_policy` 已存在，可承载"手动/每日"；若要更细的"频率+时间窗"，再启 `policy_json`。

> 判断依据：分组、匹配方式、排除词都是**关键词的从属属性**，不存在独立生命周期。
> 拆成 4 张表后，"查一个关键词的完整配置"要 join 三次——这是过度建模。

---

## 四、五个菜单 → 现有模块的映射

| 菜单 | 后端归属 | 现状 | 要补的工作 |
|---|---|---|---|
| 数据源管理 | `source` | 表有、接口只有 `GET /sources` | 编辑/启停/能力编辑/健康检查 |
| 平台账号 | `source` | **只有表** | 全部（接口+页面+凭证校验） |
| 关键词管理 | `source` | CRUD 完整，页面已有 | 分组/匹配方式/排除词 + 页面调整 |
| 采集任务 | `collection` | 后端完整、页面已有 | 加 `source_id`/`account_id`、运行记录 |
| 采集记录 | `collection` | **只有 task_log** | `collection_run` + 记录列表/详情页 |

---

## 五、三个决策（我的建议）

| # | 决策 | 建议 | 理由 |
|---|---|---|---|
| 1 | 账号使用方式 | **两种都支持**，按能力矩阵控制 | 抖音匿名采集实测只到 3 页 15~20 条；要完整数据必须登录态。但 Web 抓取无需登录，不该被账号体系卡住 |
| 2 | 关键词组织 | **分组 + 关键词 + 策略（3 列扩展）** | 不做"策略版本表"。版本化属于分析档案（`an_profile` 已有），关键词改动只需审计留痕 |
| 3 | 执行方式 | **手动 + 定时** | 手动已有；定时可用现有 `@Scheduled` 做"扫描待执行任务"，不做瑞 Quartz 式调度器 |

## 六、需要调整的一处（提案与现状冲突）

提案建议 `collection_task` 与 `collection_run` 分表，同时又说"任务可暂停/恢复"。

**问题**：暂停/恢复是**任务级**语义，但执行是 run 级。若 run 正在跑，暂停请求要作用到正在执行的 run 上——需要 `run.status = PAUSED` 且调度器跳过该任务的后续 run。

建议明确：**`PENDING` 只存在于 run**，任务主表的 status 改为聚合态（IDLE / RUNNING / PAUSED / DISABLED）。否则两个地方都有 PENDING，判定必然分叉。

---

## 七、分阶段（对齐现有代码，不重做）

| 阶段 | 内容 | 新增表/列 | 预计 |
|---|---|---|---|
| **P1** | 数据源管理补全（编辑/启停/能力/健康检查）+ 平台账号 CRUD | `ds_source` +2 列，`ds_account` 零新增 | 1 批 |
| **P2** | 关键词扩展（分组/匹配/排除词） | `ds_keyword` +3 列 | 1 批 |
| **P3** | 采集任务补 `source_id`/`account_id` + `collection_run` | `collection_task` +2 列，**新增 1 表** | 2 批（含页面改造） |
| **P4** | 采集记录（列表 + 四 Tab 详情） | 无（复用 run） | 1~2 批 |

提案说"数据库模型要先定稿"，我同意——**但定稿的是这 4 处变更，不是 19 张表**。

---

## 八、明确不做（及理由）

| 项 | 理由 |
|---|---|
| `source_credential` 表 | 凭证不进业务库，只存引用 |
| `source_health_check` 表 | 降级为 `last_checked_at` 字段 |
| `collection_execution` / `collection_retry` 表 | 与 `task_item` 重复 |
| `content_source_relation` 表 | 原始归档已支撑溯源 |
| 关键词策略版本表 | 审计留痕足够，版本化成本高收益低 |
| 账号轮换 / 反限制机制 | 提案自己写明"不应设计绕过平台限制的机制" |
| 通用调度平台 | `@Scheduled` + 待执行扫描够用 |

---

## 九、验收标准（每阶段通用）

1. `mvn test` 全绿（当前 109 条），新增用例覆盖状态流转与权限
2. 新增接口有 `@RequirePermission`，越权返回 403
3. 页面能被非管理员账号按权限正确隐藏
4. 失败原因必须落库到 `failure_type`，不能只有自由文本
5. 每阶段独立提交 + tag，可单独回退

---

## 十、需要你确认的三件事

1. **§5 三个决策**是否按建议执行
2. **§3 数据库调整**是否接受（19 表 → 3 张新表 + 8 个新列）
3. **§6** 任务与运行的状态划分是否认可（`PENDING` 只存在于 run）

确认后我出 `CP-INSIGHT-R3-COLLECT` 任务卡，按 P1→P4 逐批开发。
