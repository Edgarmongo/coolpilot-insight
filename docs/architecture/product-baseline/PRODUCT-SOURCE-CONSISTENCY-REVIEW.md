# 产品架构 ↔ SOURCE-04 契约一致性审查

> 审查日期：2026-11-18
> 代码基线：`17e87e7` · Flyway **V33**
> 方法：只读（代码检索 / Flyway / `information_schema` / 运行容器探测 / 架构文档比对）
> 结论性质：**设计层兼容性检查**，不等于 SOURCE-04 验收通过（见 §4）

---

## 0. 本轮证据基线（先解决你提出的证据缺口）

你提到的两个证据问题，这里给出当前仓库的实际情况：

| 项 | 事实 |
|---|---|
| 最新 HEAD | `17e87e7`（2026-11-18） |
| 你引用的两份报告 | `3e9024f` = `SOURCE-04-FOLLOWUP-REVIEW-02.md`；`00cda61` = `SOURCE-04-FOLLOWUP-FIX02-REVIEW.md` |
| 这两份之后的提交 | **14 个**，其中**改代码的 5 个**（见下表）——**均未经独立复核** |
| 你提到的 4f1a9bd 能力映射报告 | 早于 SOURCE-04 后续评审，只能作历史审计材料，本文未采用其结论 |

两份报告之后改代码的提交：

| 提交 | 内容 | 是否触碰 SOURCE 契约 |
|---|---|---|
| `6453b60` | A-API 去重查询接口（Java）+ Python 客户端 | 新增内部接口，未改契约 |
| `aea03e6` | 给 `collect_comments` 加 `seen_cids/seen_fps`，**重签证据** | **触碰**：实现哈希变化，已按要求真实重验 |
| `29c3b37` | 为 `KEYWORD_SEARCH` 补验证器 | 工具层新增，未改契约 |
| `62d76d0` | SOURCE-05 分层健康检查 | 新增只读接口，未改契约 |
| `60c6791` | 清洗保留"空内容但有 cid"的行 | 改清洗语义，不改能力契约 |

> 这意味着：你现在引用的"P1/P2 均 PASS WITH FINDINGS"针对的是 `00cda61`，
> **不覆盖**上述 5 个代码提交。基线审查必须把这条写进去，否则会误以为"当前 HEAD 已复核"。

---

## 1. 一致性矩阵

| 检查项 | 产品架构要求 | SOURCE 架构约束 | 代码证据 | 结论 |
|---|---|---|---|---|
| 数据源业务事实 | 统一数据源管理 | Java `source`/`ds_source` 为业务事实 | `source` 包 29 文件；`ds_source` 1 行 | ✅ 一致 |
| 能力声明 | 记录数据源能力 | `capability_json` 保存 Desired | V31 迁移到 v2；`SourceCapabilityService` 唯一写入口 | ✅ 一致 |
| 能力发现 | 获取适配器真实能力 | Python Registry 提供 Observed | `GET /v1/registry/snapshot` 统一快照 | ✅ 一致 |
| 能力核对 | 确定实际可用能力 | Desired + Observed → Effective | `CapabilityReconciler`；`ADR-008` 原因码 | ✅ 一致 |
| 采集执行 | 统一任务协调与执行 | Java 控制、Python 执行 | `AdmissionGatedCollectionClient` → `POST /v1/jobs` | ✅ 一致 |
| 数据持久化 | 统一业务数据资产 | Python 不直接写业务 MySQL | 采集侧已改为 `/internal/collection/dedup`；**但 `clean.py` 仍直写库** | ⚠️ **部分一致**（见 G-04） |
| 失败处理 | 统一错误分类 | 沿用七类 `failure_type` | `registry/errors.py` 实测 7 类：AUTHENTICATION / AUTHORIZATION / RATE_LIMIT / NETWORK / PLATFORM / DATA / SYSTEM | ✅ 定义一致；⚠️ Java 侧未逐项映射 |
| 认证模式 | 支持公开与授权数据 | PUBLIC / AUTHENTICATED 区分 | 能力声明的 `accessModes`；作业 `accessMode` 字段 | ✅ 一致 |
| 凭证安全 | 凭证引用与执行分离 | 不暴露凭证明文 | `CredentialProvider` 只判存在；**加密未实现** | ⚠️ 方向一致，能力缺失 |
| 行业研究 Profile | 配置研究对象与分析规则 | 不改采集核心契约 | `analysis_profile` 仅 1 条默认，无 UI | ⚠️ 设计兼容，实现为种子 |
| 用户需求洞察 | 从内容提取需求 | 依赖 content/analysis | `comment_analysis.pain_points_json` / `user_need` | ⚠️ 实现待核（taxonomy 硬编码） |
| 商业机会分析 | 生成商业洞察与报告 | 不属 SOURCE 核心职责 | 无商品/价格/竞品模型 | ❌ 未建设 |

**初步结论**：产品架构方向与 SOURCE-04 核心设计**没有结构性冲突**；
三处偏差（数据持久化、凭证加密、Profile 落地）都属"实现未到位"，不是"设计打架"。

> 这张表只证明设计兼容，**不能**解释为 SOURCE-04 全部验收通过。

---

## 2. 必须防止的五种架构偏离 —— 逐条体检

| # | 偏离 | 当前是否已发生 | 证据 |
|---|---|---|---|
| 1 | 为足球行业单独建采集系统 | **未发生** | 全仓库仅一套 `collection-service`；无俱乐部专属目录/表 |
| 2 | 每个行业建一套数据源表 | **未发生** | 只有一张 `ds_source`，按 `code` 区分 |
| 3 | Python 自己维护业务任务 | **未发生** | 作业状态只描述执行；任务/权限在 Java（`collection_task`） |
| 4 | 每个行业复制 AI 分析模块 | **未发生** | 只有一套 `ai-service`；但 taxonomy 硬编码，未来有复制风险 |
| 5 | 把 AI 推断当成业务事实 | **部分存在** | 报告有证据链（`analysis_evidence` 77 条），但**未显式标注**"事实/推断/建议" |

第 5 条需要单独说：现在的报告结构是分章节的，但读者无法从文本上区分
"这条是数据算出来的"还是"模型推断的"。这不是代码 bug，是**输出契约缺失**。

---

## 3. 新发现的偏差（本轮审计新增）

### 3.1 架构文档写了 Redis，仓库里没有（G-01）

`PRODUCT-ARCHITECTURE` 的目标态写"Redis 用于缓存与任务协调"，但：

- `docker-compose.yml` 无 redis 服务；
- `backend/pom.xml` 无 Redis 依赖；
- 代码无 Redis 客户端。

实际替代：进程内 `MemoryCache`（缓存）+ 快照 TTL；任务协调靠 MySQL 行状态与租约。
**结论**：Redis 是目标态。引入前需单独立项（会带来缓存一致性面）。

### 3.2 规划映射表里的 `lead` / `conversion` 模块不存在，且不应新建（G-02）

`com.coolpilot.insight` 下实际只有 `source / collection / content / integration / system / service / controller…`，
**没有 `lead`、`conversion`、`analytics`**。

`lead`（线索）与 `conversion`（转化）来自已废止的"智能获客"定位，
与 VISION §7"不将公开社交账号直接转化为营销客户名单"**直接冲突**。
建议从架构映射表中移除，不再作为目标模块。

### 3.3 `clean.py` 直写业务库，违反"Python 不直接写 MySQL"（G-04）

`ai-service/app/clean.py` 用 `pymysql` 直接写 `comment` / `comment_analysis` / `video`，
由 Java 以子进程方式在 backend 容器内启动，**不经过 Java 的业务校验与审计**。

它与"采集服务不直连库"是两件不同的事：采集侧已改正（走 dedup API），清洗侧没有。
这是当前最明确的一处**架构原则与实现不一致**。

### 3.4 能力可调度性与声明不一致（G-05）

4 个已声明能力，只有 2 个注册进作业调度（`executor.py` 的 `_ADAPTERS`）：

- `CONTENT_DETAIL`：VERIFIED、有实现，但**未注册**，请求返回 `CAPABILITY_NOT_DISPATCHABLE`；
- `KEYWORD_SEARCH`：`UNVERIFIED`（搜索页返回验证码中间页），且**未注册**。

按"不把未验证能力标记为可用"的要求，`KEYWORD_SEARCH` 现状是**正确**的（被闸门拒绝）；
但 `CONTENT_DETAIL` 属于"有货不卖"，是接入缺口而非安全问题。

### 3.5 前端仍挂旧品牌文案（G-12）

`AppLayout.vue:9` "内容洞察 · 智能获客"、`menu.js:21` "获客驾驶舱"、
`menu.js:70` "线索评分（planned）" —— 与 §7 的品牌边界冲突。
代码无 lead scoring 实现，只有文案与一个 planned 菜单项。

---

## 4. 对 SOURCE-04 最新评审状态的处理

### 4.1 已知状态

| 报告 | 提交 | 结论 |
|---|---|---|
| `SOURCE-04-FOLLOWUP-REVIEW-02` | `3e9024f` | A-1 通过；B-1 未通过（B1-1/B1-3）；C-4 通过；A-2 通过；U1 不通过 |
| `SOURCE-04-FOLLOWUP-FIX02-REVIEW` | `00cda61` | P1/P2 均 **PASS WITH FINDINGS**，无 FAIL |

### 4.2 两条必须写死的判断

1. **这两份都是自评审**，不等于独立第三方验证。任何"已通过"的表述都要带这个前提。
2. 二者对应的基线是 `00cda61`，**之后有 5 个代码提交未复核**（见 §0 表）。
   所以现在不能说"当前 HEAD 已通过 P1/P2 复核"。

### 4.3 仍挂着的遗留项（不因产品基线而关闭）

| 来源 | 遗留 |
|---|---|
| REVIEW-02 | B1-1 清洗失败原文经 `error_message` 入（已修）；B1-3 关键词经子进程绕过准入（已修，但现在该能力被验证卡住） |
| FIX02-REVIEW | F1 拒绝仍有内部 HTTP/DB 读（表述问题）；F2 `executor=process` 也过闸，Registry 故障时回滚失效；F6 `POST /api/clean/run` 回传 stdout |
| SOURCE-04-03 | FIX-04 M1/L1 仍待修复与独立复核 |

---

## 5. 结论

1. **设计层兼容**：产品架构与 SOURCE-04 的核心契约（三态、准入、七类失败、PUBLIC/AUTHENTICATED、
   凭证分离、不规避平台安全机制）**没有冲突**。
2. **实现层有 5 处偏差**：Python 直写库（G-04）、凭证未加密（G-03）、Profile 只有种子（G-06）、
   能力调度不全（G-05）、AI 推断未显式标注（G-10）。
3. **文档层有 2 处需要修正**：Redis 未落地（G-01）、`lead/conversion` 模块不存在且不应新建（G-02）。
4. **评审层有 1 个空洞**：`00cda61` 之后 5 个代码提交未复核。

### 需要你裁决的三件事

| # | 议题 | 选项 |
|---|---|---|
| D1 | Redis 是否进入目标架构 | ①从架构文档移除，改为"按需引入"；②保留并单独立项评审 |
| D2 | `lead` / `conversion` 模块 | ①从映射表删除（推荐，与定位冲突）；②保留为"未来可选能力" |
| D3 | `clean.py` 直写库 | ①维持现状并记录为已知债务；②改造为经 Java 接口入库（成本高，收益是可审计） |

在这三项定下来之前，`PRODUCT-ARCHITECTURE.md` 的目标态会一直带着一个"说了但没做"的尾巴。
