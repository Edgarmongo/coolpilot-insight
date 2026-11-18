# CoolPilot Insight · 现状差距审计（只读）

> 审计日期：2026-11-18
> 审计对象：`coolpilot-insight` 仓库（当前运行版本，Flyway V33）
> 方法：**只读**——代码检索、数据库 `information_schema`、运行中容器的接口探测、前端路由与页面。
> 约束：本次未修改任何代码、数据或配置。
> 判定口径：`✅ 可用`（有代码+有数据+有入口）/ `⚠️ 部分`（有实现但覆盖不全或不可用）/ `🧩 骨架`（表/接口在，无业务闭环）/ `❌ 缺失`

---

## 一、总览

| 业务域 | 现状 | 一句话结论 |
|---|---|---|
| 数据源管理 | ✅ 可用 | 治理链完整，缺凭证加密与账号池 |
| 数据采集 | ✅ 可用 | 主链路闭环，仅 2 个能力接入调度 |
| 数据中心 | ⚠️ 部分 | **两套数据模型并存，第二套未接线** |
| 数据分析 | ⚠️ 部分 | 统计与 TopN 有，聚类/关联无 |
| 舆情洞察 | ⚠️ 部分 | 情感与热点有，观点/风险/传播无 |
| 需求洞察 | ⚠️ 部分 | 有痛点抽取，无分类体系 |
| 商业洞察 | ❌ 缺失 | 商品/价格/竞品/机会**完全没有模型** |
| AI 分析助手 | ⚠️ 部分 | 批处理+报告，无对话、无检索 |

---

## 二、逐域明细

### 2.1 数据源管理 ✅

**已实现**

- `ds_source` / `ds_account` / `ds_policy`；`capability_json` 已迁 v2（Desired）。
- 三态模型：Desired / Observed / Effective，推导在 `CapabilityReconciler`，拒绝原因码见 `ADR-008`。
- 接口：`GET/PUT /api/v1/source/sources/{code}/capabilities`；账号管理 `…/accounts`。
- 页面：`SourceManageView.vue`（能力三态）、`AccountManageView.vue`。

**缺口**

| 项 | 证据 |
|---|---|
| 凭证**未加密** | `CredentialProvider` 类注释自述"加密存储尚未实现" |
| 无账号池 / 配额 | `SourceAccountService:108` "未实现配额与账号池" |

**数据量**：`ds_source` 1 行、`ds_keyword` 1 行。

---

### 2.2 数据采集 ✅

**已实现**

- 任务链：`collection_task` → `_item` → `_log` / `_checkpoint`，运行态由 `col_run` 承载（Task 不被 Run 结果污染）。
- 执行：Java 提交 `POST /v1/jobs` → 采集服务队列 → 单 worker 起 Chrome。
- 去重：2026-11-17 起改为从 `GET /internal/collection/dedup` 取历史（不直连库），**第二次采集 `collected=0` 已验证**。
- 能力准入：`AdmissionGatedCollectionClient` 覆盖全部执行入口。
- 页面：`CollectView.vue`、`TaskCenterView.vue`、`CleanView.vue`。

**缺口**

| 项 | 证据 |
|---|---|
| 只 2 个能力接作业调度 | `executor.py:96-97` 仅 `COMMENT` / `COMMENT_REPLY`；`CONTENT_DETAIL` 有实现却不可调度 |
| 关键词搜索不可用 | `KEYWORD_SEARCH` = `UNVERIFIED`；实测搜索页返回"验证码中间页" |
| 单副本硬约束 | `ADR-007 §4.2.1`；多副本会抢同一 Chrome profile |
| 清洗结果未结构化 | `POST /api/clean/run` 回传脚本 stdout 原文 |

**数据量**：任务 12 条，评论 21 条，视频 3 条。

---

### 2.3 数据中心 ⚠️（最需要决策的一域）

**线上一套模型**：`video` → `comment` → `comment_analysis`（+ `analysis_evidence` 77 条）。

**库里还有第二套**：

| 表 | 行数 | 写入方 | 读取方 | 前端 |
|---|---|---|---|---|
| `ct_content` | 3 | **无**（`content` 包外无引用） | `ContentController` 3 个 GET | 无引用 |
| `ct_comment` | 19 | 无 | 同上 | 无 |
| `ct_author` | 0 | 无 | — | 无 |
| `ct_grant` / `ct_raw_archive` | 0 | 无 | — | 无 |
| `file_asset` | 0 | **无**（全仓库 0 处 Java 引用） | 无 | 无 |

**结论**：`ct_*` 是"下一代内容模型"的骨架，但**没有写入闭环、没有页面**；
`file_asset` 完全空转——上传只把文件写进 `datas/`，不产生资产记录。

**影响**：现在说"形成统一数据资产"是不成立的。数据资产事实上只有 `video/comment` 一套，
且它绑定在抖音视频这一种内容形态上。

---

### 2.4 数据分析 ⚠️

**已实现**

- 概览统计：`/api/overview`（视频/评论/用户/待复核 + 14 天趋势 + 高频痛点 Top12 + 评论量 Top10）。
- 维度聚合：情感（`sentimentStats`）、地区、桑基图、评论树。
- 多视频对比：`AnalysisController` "/compare"（并排需求/痛点/情感）。

**缺口**

| 项 | 说明 |
|---|---|
| 聚类 | 无（主题只有 LLM 抽的 `topics_json`，没有聚类结果） |
| 关联分析 | 无（如"地区 × 情感 × 点赞"交叉） |
| 趋势建模 | 只有 14 天折线，无突增检测、无同比/环比 |
| 任意维度下钻 | 统计接口是固定组合，没有通用查询层 |

---

### 2.5 舆情洞察 ⚠️

**已实现**：情感基线（规则，入库时写 `comment_analysis`）、AI 评论级分析、视频级洞察、
热点（按点赞/TopN）、证据表 `analysis_evidence`（77 条）、人工复核 `ReviewView`。

**缺口**：观点抽取、风险识别、传播路径/节点分析、跨视频话题聚合，均无。

---

### 2.6 需求洞察 ⚠️

**已实现**：`comment_analysis.pain_points_json` / `user_need` / `topics_json`；
概览页"高频痛点 Top12"；多视频对比。

**缺口**：无需求分类体系（taxonomy 硬编码在提示词里）、无消费意向识别、无服务需求结构化。

---

### 2.7 商业洞察 ❌

**现状**：数据库中没有商品、价格、竞品、机会任何一类表；没有对应接口与页面。

这一域**不是"做得少"，是"从零开始"**。阶段三的最大工作量在这里。

---

### 2.8 AI 分析助手 ⚠️

**已实现**

- AI 服务：`/analyze/comments`、`/analyze/insight`、`/analyze_topic`、`/wordcloud`、`/render/report`。
- 报告：生成 + 版本（`report` 2 条 / `report_version` 2 条）+ 导出 PDF / XLSX。
- `analysis_profile`：1 条 `ap_default_v1`（deepseek-chat / prompt v1 / schema v1 / taxonomy v1）。
- 用量记账：`usage_record`。

**缺口**

| 项 | 说明 |
|---|---|
| 无对话式分析 | 无 chat/assistant 接口，只有批处理 |
| 无检索增强 | 全仓库无 embedding/向量库 |
| Profile 不可配置 | 只有 1 条默认，无 UI、无多 Profile 切换 |
| 导出格式有限 | 仅 PDF / XLSX（无 DOCX） |

---

## 三、与 SOURCE-04 冻结契约的一致性检查

| 检查项 | 结论 |
|---|---|
| 能力三态（Desired/Observed/Effective） | 未被产品规划触碰；新域不得绕过 |
| Effective 枚举（ADR-008） | 未改动 |
| 执行准入唯一入口 | 保持（`AdmissionGatedCollectionClient`） |
| 证据 ↔ 实现绑定 | 保持；改采集实现必须重验 |
| Task/Run 状态机 | 未改动；新语义应走 `finishedReason`/`errorCode` |

**产品规划与契约的冲突点：无。**
但有三处**张力**，需要按契约口径设计，不能反过来改契约：

1. "统一数据资产"会想改 `video/comment` → 必须走双写灰度，且不得改 `comment` 的去重键；
2. "商业洞察"需要新表 → 新表可以加（Flyway 新版本），但**不得改已有表语义**；
3. "实时/趋势"会想加状态与调度 → 不得往 Task 状态机里塞新状态。

---

## 四、五个关键发现

1. **数据中心是分裂的**：`video/comment` 在跑，`ct_*` 是空转骨架，
   `file_asset` 零引用。规划里的"统一数据资产"目前**不存在**。
2. **商业洞察完全空白**：没有任何商品/价格/竞品模型，而这是"商业增长平台"的核心卖点。
3. **能力接入不全**：4 个已声明能力里，2 个能调度（COMMENT / COMMENT_REPLY），
   `CONTENT_DETAIL` 有实现无入口，`KEYWORD_SEARCH` 被风控卡死。
4. **AI 能力是"批处理 + 报告"，不是"助手"**：没有对话、没有检索、
   Profile 只有一条默认且不可配置 —— 与"AI 分析助手"这个域名的期望有距离。
5. **可配置性只有一半**：能力层可配置（Desired v2），**分析层不可配置**（taxonomy/prompt 硬编码），
   这直接决定了阶段四"换行业靠配置"能否成立。

---

## 五、建议的下一步（按投入产出比排序）

| 优先级 | 事项 | 理由 | 预估 |
|---|---|---|---|
| P0 | 清洗结果结构化（U2/F6） | 阶段一交付标准第 1 条直接卡在这；且现在有信息泄露面 | 小 |
| P0 | 内容资产模型决策（ADR-009） | 不做决策，后续所有"资产/检索/报告"都在沙上 | 决策（不写码） |
| P1 | `CONTENT_DETAIL` 接入作业调度 | 实现与证据都在，接入成本最低，直接补一个能力 | 小 |
| P1 | 商业洞察域建模（ADR-010） | 阶段三的地基，越晚做越贵 | 中 |
| P2 | taxonomy / Profile 外置化 | 决定阶段四能否"配置化扩行业" | 中 |
| P2 | 执行状态与数据结果分离（U4） | 让报告能说清"没有新增"和"采集失败" | 中 |
| P3 | 对话式分析 / 检索增强 | 价值高但依赖前四项，过早做会返工 | 大 |

---

## 六、需要纠正的几个"想当然"

| 说法 | 事实 |
|---|---|
| "已有统一数据资产" | ❌ 两套模型，第二套只读且无数据闭环 |
| "AI 分析助手" | ⚠️ 是批处理分析与报告生成，不是对话式助手 |
| "商业洞察" | ❌ 零实现 |
| "关键词搜索可用" | ❌ 被平台验证码拦截，能力 UNVERIFIED |
| "上传文件有资产管理" | ❌ `file_asset` 空转 |

---

## 七、审计边界

- 未审计前端视觉与交互质量（只核对了路由与页面存在性）；
- 未做性能与并发压测；
- 未评估第三方模型成本与配额；
- 未审计历史数据的清洗质量（仅统计行数）。
