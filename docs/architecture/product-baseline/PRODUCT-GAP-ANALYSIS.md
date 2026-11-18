# 产品目标 ↔ 当前实现 差距分析

> 基线：`17e87e7` · Flyway V33 · 2026-11-18
> 输入：`PRODUCT-VISION` / `PRODUCT-ARCHITECTURE` / `PRODUCT-ROADMAP` 与
> `CURRENT-IMPLEMENTATION-MAPPING` / `PRODUCT-SOURCE-CONSISTENCY-REVIEW`
> 本文只列差距与建议，**不含实施**。

---

## 0. 先看已经成立的（避免把已做的当成缺口）

| 能力 | 证据 |
|---|---|
| 能力三态（Desired/Observed/Effective） | `CapabilityReconciler` + `ADR-008` |
| 执行准入覆盖全部入口 | `AdmissionGatedCollectionClient`，未验证能力零调用 |
| 采集 → 清洗 → 入库闭环 | 真实跑通，`video` 3 / `comment` 21 |
| 跨次去重（第二次 `collected=0`） | `/internal/collection/dedup` + `dedup.py` |
| 证据 ↔ 实现绑定 | `implementationHash` 含函数源码哈希，改实现即失效 |
| 审计写入 JSON 合法 | 修复后 `SUBMIT_COLLECTION` 落库且 `JSON_VALID=1` |
| 分层健康检查 | `/api/system/health/layers` 四层 |
| 报告导出 | PDF / XLSX |

---

## 1. 差距清单

优先级按 `PRODUCT-ROADMAP` 的 P0–P3 口径；"阶段"指它卡住哪个 Phase 的验收。

| ID | 差距 | 阶段 | 优先级 | 证据 | 建议 |
|---|---|---|---|---|---|
| **G-07** | 内容资产双模型：`video/comment` 在跑，`ct_*` 只读、无写入、无前端 | 1 | **P0** | `ct_content` 3 行、`ct_comment` 19 行；`content` 包外零引用 | 先做 **ADR-009** 决策（统一/废弃/并行），再谈"统一数据资产" |
| **G-09** | 清洗结果无结构化摘要；`POST /api/clean/run` 回传脚本 stdout 原文 | 1 | **P0** | `CleanController:31` → `CleanServiceImpl:109` | 改为 `{inputRows, inserted, updated, failed, deletedArtifact}`；原文只进日志 |
| **G-13** | 商业洞察域零实现（无商品/价格/竞品/机会模型） | 3 | **P0（阶段三前）** | 47 张表无相关模型 | 立 **ADR-010** 建模；这是"商业增长"定位的地基 |
| **G-04** | `clean.py` 直写业务库，绕过 Java 校验与审计 | 1 | **P1** | `ai-service/app/clean.py` `pymysql.connect` | 短期记录为已知债务；中期改经 Java 入库接口 |
| **G-05** | `CONTENT_DETAIL` 有实现有证据，但未注册进作业调度 | 1 | **P1** | `executor.py:96-97` 仅 2 个能力 | 注册即可，成本极低 |
| **G-06** | `analysis_profile` 仅 1 条默认；taxonomy/提示词硬编码 | 2 | **P1** | 表 1 行；`ai-service` 内常量 | 外置 taxonomy 与提示词，Profile 可切换（阶段四前置） |
| **G-10** | 报告未显式区分"事实 / 统计 / 推断 / 建议" | 2 | **P1** | 报告分章节但无类型标注 | 在输出契约里加 `claimType` + 证据引用要求 |
| **G-03** | 凭证未加密（只判存在） | 1→4 | **P1（对外交付前）** | `CredentialProvider` 自述 | SOURCE-09；对客户交付前必须 |
| **G-01** | 架构文档称 Redis 用于缓存与协调，实际无 Redis | 1–2 | P2 | compose/pom/代码均无 | 改文档为"按需引入"，或单独立项评审（见 D1） |
| **G-08** | `file_asset` 空转：上传只写磁盘 | 1 | P2 | 全仓库 0 处 Java 引用 | 上传时落资产记录（谁、何时、哪个任务、哈希） |
| **G-11** | `KEYWORD_SEARCH` 不可用（验证码拦截） | 1 | P2 | 搜索页返回"验证码中间页" | 有登录态时重验；在此之前功能保持关闭 |
| **G-14** | 执行状态与数据结果未分离（`SUCCESS` 语义模糊） | 1 | P2 | `runItem` 判定 | U4：区分 `INSERTED / DUPLICATE_ONLY / EMPTY` |
| **G-15** | 单副本硬约束 + Flask dev server 承载生产 | 1 | P2 | `ADR-007 §4.2.1`；`collection.Dockerfile:97` | 保持单副本并在部署文档写死；换 WSGI 服务器另立卡 |
| **G-16** | 缺数据质量与来源追踪视图 | 1 | P2 | 无页面、无统计接口 | 阶段一验收第 1 条的落地形态 |
| **G-02** | 映射表中的 `lead`/`conversion` 模块不存在 | — | P3 | 包结构无 | **从架构映射删除**（与 VISION §7 冲突） |
| **G-12** | 前端仍挂旧品牌：智能获客 / 获客驾驶舱 / 线索评分(planned) | — | P3 | `AppLayout.vue:9,56`；`menu.js:21,70` | 文案对齐新定位，删除 planned 项 |

---

## 2. 按 Phase 汇总

| Phase | 阻塞项 | 可立即做 | 需要决策 |
|---|---|---|---|
| 0 基线 | — | ✅ 本轮完成 | D1/D2/D3（见一致性审查 §5） |
| 1 数据基础 | G-07、G-09 | G-05、G-08 | ADR-009（资产模型） |
| 2 AI 洞察 | G-06、G-10 | — | taxonomy 外置方式 |
| 3 足球场景 | G-13 | — | ADR-010（商品/竞品建模） |
| 4 商业验证 | G-03 | — | 交付与合规材料 |
| 5 多行业 | G-06（Profile） | — | ADR-011 |

---

## 3. 建议的推进顺序（前三步）

### 第一步：收口"看得见的不一致"（1–2 天量级）

1. **G-12 文案对齐**：三处旧品牌文案改掉，删除 `线索评分(planned)`。
   零风险，但它是当前最刺眼的定位矛盾。
2. **G-05 `CONTENT_DETAIL` 接调度**：注册进 `_ADAPTERS`，补一条执行体映射。
   有实现、有证据，只是没接。
3. **G-09 清洗摘要**（与 FIX02 遗留 F6 合并处理）：同时解决信息回显与可观测性。

### 第二步：做两个决策（不写码）

4. **ADR-009 内容资产模型**：`ct_*` 是废弃、是并行、还是迁移目标？
   不定这个，阶段一的"统一数据资产"无法验收。
5. **ADR-010 商业洞察建模**：商品/价格/竞品/机会四类实体怎么建？
   这是阶段三的最大工作量，越晚越贵。

### 第三步：补阶段一的"可追溯"

6. **G-08 文件资产 + G-16 质量视图**：让"从哪个任务、什么时间、哪次清洗"能查。

---

## 4. 三个需要你拍板的点（与一致性审查 §5 同源）

| # | 议题 | 我的建议 |
|---|---|---|
| D1 | Redis 是否进入目标架构 | **从文档移除**，标注"按需引入"。现在没有 Redis 也能跑，加了会引入缓存一致性面 |
| D2 | `lead`/`conversion` 模块 | **从映射表删除**，与"不构建营销名单"的边界直接冲突 |
| D3 | `clean.py` 直写库 | **短期记录债务，中期改造**。改造收益是可审计与可校验，成本是重构清洗链路 |

---

## 5. 不建议现在做的事

| 事项 | 原因 |
|---|---|
| 对话式 AI 助手 | 依赖 taxonomy 外置与证据检索，现在做会返工 |
| 多行业 Profile 铺开 | 一个行业（足球）都没跑通，配置化没有真实约束 |
| 向量库 / RAG | 数据量与结论结构都还没稳定，先做证据标注更划算 |
| 分布式任务协调 | 单副本够用；扩容前先解决 Chrome profile 隔离 |
| 重构 `service` 大包 | 历史分层与新模块并存，但功能正确；重构属于时机问题，不是正确性问题 |
