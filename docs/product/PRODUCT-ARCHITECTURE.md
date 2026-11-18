# CoolPilot Insight · 产品架构（V1.0）

> 状态：**产品基线**，与 `PRODUCT-VISION.md` 配套
> 本文所有"现有实现"部分均取自当前仓库代码与数据库 Schema；
> 未实现的部分明确标注，**不得据本文推断功能已存在**。
> 与 SOURCE-04 已冻结契约冲突时，**以契约为准**（见 §4）。

---

## 一、分层架构

```
┌──────────────────────────────────────────────────────────────┐
│  产品层（Web）  Vue3 + Element Plus + ECharts                  │
│  总览 / 采集 / 任务 / 清洗 / 评论 / 数据源 / 账号 / 关键词       │
│  洞察 / 复核 / 报告 / 个人 / 管理（用户·角色·字典·审计·配置）      │
└───────────────────────────┬──────────────────────────────────┘
                            │ HTTP /api/**
┌───────────────────────────▼──────────────────────────────────┐
│  Java 业务控制中心（Spring Boot 2.7 / JDK8）                   │
│  · 业务编排与状态机    · 权限与审计（RBAC + audit_log）          │
│  · 能力准入（Effective Capability 单一闸门）                     │
│  · 报告与导出          · 对外 API                                │
└──────────┬──────────────────────────────┬────────────────────┘
           │ HTTP（内部令牌）               │ 子进程（清洗）
┌──────────▼──────────────┐   ┌───────────▼───────────────────┐
│ Python 执行服务（独立容器）│   │ Python AI 服务（flask 容器）    │
│ 采集适配器 + 作业队列      │   │ 分词 / 情感 / 洞察 / 报告渲染   │
│ Registry（能力与证据）     │   │                               │
└──────────┬──────────────┘   └───────────┬───────────────────┘
           │                              │
┌──────────▼──────────────────────────────▼───────────────────┐
│  基础设施：MySQL 8（唯一权威库）· 共享数据目录（CSV）· 模型服务  │
└──────────────────────────────────────────────────────────────┘
```

**一条职责分界**（已在代码中落地，不要打破）：

- **控制面在 Java**：谁能执行、能不能执行、执行结果算成功还是失败、状态怎么变；
- **执行面在 Python**：怎么打开页面、怎么翻页、怎么解析、怎么清洗；
- Python **不自己做授权与准入判断**（`AdmissionGatedCollectionClient` 是唯一入口），也不拥有业务状态。

---

## 二、八大业务域 → 现有落点

> 现状列：`✅ 可用` / `⚠️ 部分` / `🧩 骨架` / `❌ 缺失`。判定依据见 `PRODUCT-GAP-AUDIT.md`。

| 业务域 | 现有代码/表 | 前端 | 现状 |
|---|---|---|---|
| 数据源管理 | `source/*`、`ds_source`/`ds_account`/`ds_policy`、`capability_json`(v2)、`CapabilityReconciler` | SourceManageView / AccountManageView | ✅ 可用（凭证未加密、无账号池） |
| 数据采集 | `collection/*`、`collection_task/_item/_log/_checkpoint`、`col_run`、`app/jobs/*` | CollectView / TaskCenterView | ✅ 可用（仅 2 个能力接入调度） |
| 数据中心 | `video`/`comment`（主线）、`ct_content`/`ct_comment`/`ct_author`（第二模型，只读）、`file_asset`（空转） | CommentListView | ⚠️ 分裂 |
| 数据分析 | `/api/overview/*`、`CommentMapper` 统计聚合、`analysis_*` | OverviewView / InsightView | ⚠️ 统计有、聚类关联无 |
| 舆情洞察 | `comment_analysis`（情感/主题/痛点）、`video_insight`、`analysis_evidence` | InsightView / ReviewView | ⚠️ 有情感与热点，缺观点/风险/传播路径 |
| 需求洞察 | `comment_analysis.pain_points_json` / `user_need` | InsightView（Top12 痛点） | ⚠️ 有抽取，缺分类体系与消费意向 |
| 商业洞察 | — | — | ❌ 无商品/价格/竞品/机会模型 |
| AI 分析助手 | `AiAnalysisService`、`integration/ai/*`、`analysis_profile`、`report`/`report_version` | InsightView / ReportView | ⚠️ 批处理+报告，无对话式、无检索增强 |

---

## 三、数据资产模型（当前的真实状态）

存在**两套并行模型**，这是架构层面最需要正视的问题：

### 3.1 主线模型（在跑）

```
video  ──< comment ──< comment_analysis
  │            │
  │            └── analysis_evidence（结论 → 证据）
  └──< video_insight ──< analysis_task / analysis_task_item / analysis_batch
```

- 采集 → CSV → 清洗入库到这里；
- 所有页面、统计、洞察、报告都基于它；
- 去重键：`uk_platform_cid(platform,cid)` + `uk_platform_dedup(platform,dedup_key)`。

### 3.2 第二模型（只读、未接线）

```
ct_content ──< ct_comment
     └── ct_author / ct_grant / ct_raw_archive
```

- `ContentController` 只有 `GET /api/v1/content/contents{,…}`（**无写入接口**）；
- `content` 包外无任何引用，采集与清洗都不写它；
- 前端零引用；当前 `ct_content` 3 行、`ct_comment` 19 行、`ct_author` 0 行。

**结论**：`ct_*` 是一次"下一代内容模型"的预留，但**尚未成为资产主模型**。

### 3.3 规划方向（不在本轮实施）

统一为"内容 / 作者 / 评论 / 事件"四类资产，`video` 是"内容"在视频平台上的特化。
迁移必须满足：**灰度双写 → 一致性校验 → 读切换 → 写切换**，不得直接替换。

---

## 四、不可违反的契约（SOURCE-02 / SOURCE-04 冻结项）

产品规划可以新增能力，但**下列三样不得改动**，否则整条治理链失效：

| 冻结项 | 内容 | 为什么不能动 |
|---|---|---|
| **能力三态模型** | Desired（业务声明）/ Observed（实测）/ Effective（判定） | 页面展示、执行准入、报告口径全部依赖它 |
| **Effective 状态与原因枚举** | `ADR-008` 的 status × reason 合法组合 | 改了会让历史数据与诊断结果无法解释 |
| **执行准入唯一入口** | `AdmissionGatedCollectionClient` → `CollectionAdmissionGuard` | 它保证"未验证能力不得执行"；绕过一次就等于没有 |

**证据与实现的绑定**：`verification_evidence.json` 的 `implementationHash`
= 能力声明 + 目标函数源码哈希。**改采集实现 = 旧证据失效 = 该能力被准入拒绝**。
新增/修改采集能力必须走 `tools/verify_capability.py` 真实重验，不能补录。

**状态机**：Task / Run 的状态集合（`C-01-state-machine.md`）不得为产品需求随意增加状态；
新语义优先用 `finishedReason` / `errorCode` 这类旁路字段表达。

---

## 五、扩展机制（行业可配置的关键）

目标：**新增一个行业 = 新增一份 Profile，而不是开一个分支。**

现有可复用基础：

| 机制 | 现状 | 缺口 |
|---|---|---|
| `analysis_profile` | 1 条 `ap_default_v1`（deepseek-chat / prompt v1 / taxonomy v1 / 参数哈希） | 无 UI 管理、无多 Profile 切换、Profile 与任务无强绑定 |
| 能力声明（Capability Spec） | 每个能力有 input/output schema、accessModes、contractVersion | — |
| Desired 文档（v2） | 可按数据源声明能力开关与访问模式 | 只到"能力"粒度，未到"研究主题"粒度 |
| 分类体系 / 提示词 | 在 ai-service 内硬编码（taxonomy_version 标记） | 未外置为可配置资产 |

**规划中的三层配置**（阶段三前落地）：

```
行业 Profile
 ├── 数据源与能力选择（复用 Desired v2）
 ├── 研究主题（球迷需求 / 商品市场 / 内容传播 …）
 ├── 分类体系与词表（taxonomy_version）
 └── 指标与报告模板（prompt_version / schema_version）
```

---

## 六、模块边界与依赖规则

| 规则 | 说明 |
|---|---|
| 依赖方向 | `controller → application → domain/integration`；反向依赖禁止 |
| Python 不拥有业务状态 | 作业状态只描述"执行到哪一步"，任务/权限/审计在 Java |
| 共享数据只走契约 | Java↔Python 之间只有 HTTP 契约 + 共享数据目录，禁止直连对方内部结构 |
| 数据库唯一权威 | 采集侧**不直连业务库**（去重通过 `/internal/collection/dedup` 拉取） |
| 页面不猜状态 | 前端只渲染后端给出的 Effective/状态，不自行推导 |

---

## 七、待定的架构决策（ADR 候选）

| # | 议题 | 为什么需要决策 |
|---|---|---|
| ADR-009 | 内容资产模型统一（`video/comment` vs `ct_*`） | 决定"数据中心"能否成立 |
| ADR-010 | 商品/价格/竞品的资产建模 | 商业洞察域的地基，现在完全没有 |
| ADR-011 | 行业 Profile 的配置形式（库表 vs 文件 vs 混合） | 决定行业扩展成本 |
| ADR-012 | 检索增强（向量/关键词混合）与证据引用格式 | 决定"分析可解释"能落到什么程度 |
| ADR-013 | 报告版本与证据快照的绑定方式 | 决定结论能否被复核与追溯 |

---

## 八、与现有 SOURCE-04 体系的关系

- SOURCE-04 解决的是**"数据能不能被采、能不能被执行"**（能力治理与准入）；
- 本架构文档解决的是**"采到的数据如何变成资产、资产如何变成洞察"**；
- 两者是**垂直关系**：SOURCE-04 是地基，产品架构在其上分层，**不替换、不绕过**。

任何新增业务域，都必须先回答三个问题：

1. 它依赖哪些数据源能力？这些能力是否已通过准入（Effective = AVAILABLE）？
2. 它产出什么资产？资产的可追溯性锚点是什么（cid / contentId / 时间窗口）？
3. 它的结论如何回到证据？没有证据锚点的结论，不作为产品输出。
