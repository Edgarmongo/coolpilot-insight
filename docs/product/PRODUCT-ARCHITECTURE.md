# CoolPilot Insight — Product Architecture

| 项 | 内容 |
|---|---|
| 产品 | CoolPilot Insight |
| 版本 | 1.0 |
| 日期 | 2026-11-18 |
| 代码基线 | `17e87e7` / Flyway V33 |
| 架构原则 | 通用核心能力 + 可配置行业研究场景 |

> §2 描述**职责应该是什么**；§3 描述**当前实际有什么**。两者必须分开读。

---

## 1. 总体架构

CoolPilot Insight 沿用现有架构：**Java 业务控制中心 + Python Collection Service 执行服务**。

| 层 | 负责 |
|---|---|
| Java 业务控制中心 | 业务管理、任务协调、数据管理、业务 API、权限、审计 |
| Python Collection Service | 平台采集适配器、Registry、采集执行、平台差异处理 |
| Python AI 服务 | 分词、情感、洞察分析、报告渲染（由 Java 以子进程/HTTP 调用） |
| MySQL | 业务事实（唯一权威） |
| Redis | 缓存与任务协调（**目标态，当前未落地**，见 §3.4） |

**硬约束**：不得在产品扩展中重复建设第二套数据库、账号体系或任务体系。

---

## 2. 产品业务域（职责定义）

### 2.1 数据源管理

管理数据平台与数据源；管理数据源状态；声明数据源能力；管理授权模式与凭证引用；
管理数据源健康状态。

### 2.2 数据采集

采集任务管理；采集执行协调；进度与错误管理；采集结果接收；采集数据质量控制。

### 2.3 数据资产管理

统一内容数据管理；数据去重；数据来源追踪；数据结构化；数据检索和关联。

### 2.4 数据分析

内容分类；主题提取；观点与情绪分析；趋势分析；关联分析；统计分析。

### 2.5 需求洞察

识别用户关注主题；归纳需求和痛点；识别消费及服务需求信号；统计需求变化；提供证据支持。

### 2.6 商业洞察

商品市场研究；竞品分析；价格与市场趋势分析；内容渠道表现分析；商业机会识别。

### 2.7 AI 分析助手

自然语言查询；分析任务执行；证据检索；洞察摘要生成；报告生成；分析结论解释。

> **AI 不应直接把模型生成内容作为已验证事实**——推断必须带证据锚点与置信说明。

### 2.8 报告与交付

研究报告生成；报告版本管理；数据证据关联；报告导出；研究结果追踪。

---

## 3. 现有技术模块映射（**以代码为准**）

> 本节是审计结果，不是设计意图。统计口径：`backend/src/main/java` 包结构、
> `collection-service/app`、`ai-service/app`、Flyway V1–V33、前端路由。

### 3.1 后端模块（`com.coolpilot.insight.*`）

| 包 | 职责 | 规模 | 产品域对应 |
|---|---|---|---|
| `source` | 数据源、账号、能力三态（Desired/Observed/Effective）、凭证判定 | controller/service/domain/infrastructure 完整 | 2.1 |
| `collection` | 采集任务与执行协调、准入闸门、去重查询、清洗编排 | 完整 | 2.2 / 2.3（部分） |
| `content` | 内容资产（`ct_*`）——**只读，无写入闭环** | 3 个 GET | 2.3（骨架） |
| `integration` | 采集服务客户端、AI 客户端、进程执行器、配置 | 完整 | 基础设施 |
| `system` | 用户/角色/菜单/字典/配置/审计/健康检查 | 完整 | 支撑 |
| `service` | 清洗、AI 分析、报告、复核、发现、概览（历史分层） | 完整 | 2.4–2.8 |
| `controller` / `mapper` / `entity` / `dto` | 传统分层，与上并存 | — | — |

**规模**：23 个 Controller、104 个端点映射、21 个前端页面、47 张业务表。

### 3.2 产品规划中的模块 vs 实际

| 规划模块 | 实际状态 |
|---|---|
| `source` | ✅ 存在 |
| `collection` | ✅ 存在 |
| `content` | ⚠️ 存在但只读（`ct_*` 无写入方、前端零引用） |
| `analysis` | ⚠️ 无独立包；分析能力散在 `service/` + `ai-service` |
| `lead`（线索管理） | ❌ **不存在**（且与 VISION §7 的定位冲突，不应新建） |
| `conversion`（转化分析） | ❌ **不存在**（同上） |
| `analytics`（运营统计） | ⚠️ 无独立包；统计在 `controller/OverviewController` + `CommentMapper` |
| `integration` | ✅ 存在 |
| `system` | ✅ 存在 |

> `lead` / `conversion` 来自已废止的"智能获客"定位，**不建议实现**；
> 若未来需要"业务线索"，必须先在产品基线里重新定义其合规依据。

### 3.3 执行侧模块

| 目录 | 内容 |
|---|---|
| `collection-service/app/adapters/douyin` | 采集适配器（collector / search / browser） |
| `collection-service/app/jobs` | 作业队列、执行体、dispatch、dedup 客户端 |
| `collection-service/app/registry` | 能力声明、证据、快照、错误类型 |
| `ai-service/app` | clean（清洗）、analysis_engine、server（分析/报告渲染） |

### 3.4 基础设施现状（与规划不符的一处）

| 规划 | 实际 |
|---|---|
| Redis 用于缓存与任务协调 | **仓库中不存在 Redis**：无依赖、无 compose 服务、无客户端代码 |

现状替代实现：

- 缓存：进程内 `MemoryCache` + 采集服务侧 `SnapshotCache`（TTL）；
- 任务协调：MySQL 行状态 + 租约（`col_run` / `lease_until`）+ 采集服务进程内队列（单副本）。

**结论**：Redis 是**目标态**。在引入前，必须先把"多副本下的任务协调"（ADR-007 §4.2.1）
与"缓存一致性"作为独立议题评审，不能因为架构图画了就直接加依赖。

---

## 4. Java 与 Python 的职责边界

### 4.1 Java 业务控制中心

业务 API；数据源管理；业务任务管理；任务协调；数据持久化；数据资产管理；权限与业务规则；
报告及业务流程。

### 4.2 Python Collection Service

平台 Adapter；Adapter Registry；采集能力声明；采集执行；平台差异处理；采集结果结构化返回；
约定范围内的 AI 分析执行。

**Python 不直接写入业务 MySQL，不自行建立第二套业务事实。**

> ⚠️ **现状偏差（必须记录）**：清洗脚本 `ai-service/app/clean.py` 通过 `pymysql`
> **直接写入** `comment` / `comment_analysis` / `video`。
> 它由 Java 以子进程方式在 backend 容器内启动，凭据与 backend 同域，
> 且不经过 Java 的业务校验。
> 严格说这违反 §4.2 的原则；它是历史遗留，**不属于采集服务**，但属于同一类"Python 写库"风险。
> 处置建议见 `PRODUCT-GAP-ANALYSIS.md`（G-04）。

### 4.3 一条已被代码强制的边界

采集侧**不直连业务库**：去重记忆通过 `GET /internal/collection/dedup` 由 Java 提供
（2026-11-17 落地）。这是目前唯一严格成立的"Python 不碰业务库"链路。

---

## 5. SOURCE 能力契约约束

必须遵循已冻结的 SOURCE 架构契约：

1. Java `source` / `ds_source` 是数据源业务事实。
2. `ds_source.capability_json` 保存 Desired Capability。
3. Python Registry 提供 Observed Capability。
4. Desired 与 Observed 经过核对形成 Effective Capability。
5. 能力核对结果必须区分声明、实现、验证和实际可用状态。
6. 采集失败统一沿用已确定的七类 `failure_type`。
7. `PUBLIC` 与 `AUTHENTICATED` 必须明确区分。
8. 凭证引用与凭证明文严格分离。
9. 不采用签名复刻、指纹伪装、身份铸造等规避平台安全机制的方案。

**不得因新增行业研究场景而绕过 SOURCE 契约。**

七类 `failure_type`（代码实测，`registry/errors.py`）：
`AUTHENTICATION` / `AUTHORIZATION` / `RATE_LIMIT` / `NETWORK` / `PLATFORM` / `DATA` / `SYSTEM`。

### 5.1 一条容易被忽略的派生约束

证据文件 `verification_evidence.json` 的 `implementationHash` = 能力声明 + **目标函数源码** 哈希。
**改采集实现 = 旧证据失效 = 该能力被准入拒绝**。新增/修改采集能力必须走
`tools/verify_capability.py` 真实重验，不能补录。

---

## 6. 行业 Profile 设计原则

行业研究场景属于**配置层**，不属于核心采集系统。Profile 表达：

- 行业类型；研究对象；研究主题
- 关键词和过滤规则
- 分析维度；指标定义
- 报告模板

首个 Profile：足球俱乐部运营与商业增长研究。

**Profile 不得复制数据源、采集任务或账号管理能力。**

现状：`analysis_profile` 表存在但仅 1 条默认（`ap_default_v1`，deepseek-chat / prompt v1 /
schema v1 / taxonomy v1），无 UI 管理、无多 Profile 切换，且 taxonomy 与提示词硬编码在
`ai-service` 内。→ Profile 机制目前是**种子**，不是能力。

---

## 7. 数据分层

| 层 | 内容 | 现状 |
|---|---|---|
| 原始数据 | 来源与采集元数据 | ⚠️ 部分（`ct_raw_archive` 存在但 0 行） |
| 标准化数据 | 统一字段、去重、关联 | ✅ `video` / `comment`（含双唯一键） |
| 分析数据 | 分析结果、模型版本、分析时间 | ✅ `comment_analysis` / `video_insight`（含 profile 与版本） |
| 洞察数据 | 结论、证据引用、置信、适用范围 | ⚠️ `analysis_evidence`（77 条）有证据，置信/适用范围字段不全 |
| 报告数据 | 报告内容及版本 | ✅ `report` / `report_version` |

具体表结构必须复用现有 Schema；是否需要扩展由独立数据模型审计决定，不在此文顺手改。

---

## 8. 安全与治理

- 采集前检查数据源能力和授权模式（已落地：`AdmissionGatedCollectionClient`）。
- 不在普通日志中记录凭证明文（已落地：日志脱敏与 `redact.py`）。
- 用户数据按权限隔离（已落地：`GovernanceService` 三层授权）。
- 重要操作保留审计记录（`audit_log`，2026-11-17 修复了 JSON 写入缺陷）。
- AI 结论关联证据来源（`analysis_evidence`）。
- 对高风险外部操作设置人工确认（`ReviewView` 复核流程）。
- 遵守数据来源平台的访问规则。

---

## 9. 缓存层约束（ADR-REDIS-001 拟定，待评审生效）

1. **缓存不能成为业务事实来源**——任何业务数据必须能从 MySQL 或合法原始数据重建；
2. **Redis 不承担 Python ↔ Java 的数据交换**——首期仅 Java 业务服务使用；
3. **Redis 不承担分布式任务协调**——任务状态与租约仍由 MySQL 行状态 + `lease_until` 承担。

## 9.1 架构演进约束

- 优先复用现有模块。
- 不重复建设任务、账号和数据源体系。
- 不随意改变已冻结的 SOURCE 契约。
- **不以产品规划替代实现证据。**
- 数据库和 Migration 变更必须单独评审与授权。
- 行业场景不得侵入通用核心领域模型。

---

## 10. 待定架构决策（ADR 候选）

| # | 议题 | 影响 |
|---|---|---|
| ADR-009 | 内容资产模型统一（`video/comment` vs `ct_*`） | 决定"统一数据资产"能否成立 |
| ADR-010 | 商品/价格/竞品资产建模 | 商业洞察域的地基（当前为零） |
| ADR-011 | 行业 Profile 的配置载体（库表 / 文件 / 混合） | 决定换行业的成本 |
| ADR-012 | 检索增强（向量/关键词混合）与证据引用格式 | 决定"可解释"能落到什么程度 |
| ADR-013 | Redis 引入的必要性与切分点（缓存 / 协调） | 已起草 `ADR-REDIS-001`（Proposed），见 `docs/architecture/redis/` |
