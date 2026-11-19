# R0 · 现有工程审计

审计时间：2026-11-15　对象：`backend/`、`frontend/`、`collection-service/`、`ai-service/`、`deploy/`
方式：静态分析 + 实际执行（`mvn test` 17/17、`docker compose up -d` 全栈健康）

## 1. 规模基线

| 维度 | 数量 | 说明 |
|---|---|---|
| Java 源文件 | 100 | controller 12 / service 18 / mapper 28 / entity 25 / dto 5 / config 5 / utils 4 / interceptor 2 / annotation 1 |
| HTTP 端点 | 59 | 12 个 Controller |
| 数据库表 | 25 | Flyway V1~V8 |
| 前端页面 | 12 | 23 个 Vue 组件，15 条路由 |
| Python 脚本 | 7 | 采集 3 + 清洗/AI 4 |
| 集成测试 | 17 | Testcontainers MySQL，全部通过 |

## 2. 现结构与目标结构的差异

**现状是按技术分层，不是按业务模块。**

```
com.coolskyai.coolpilot.insight
├── controller/   12 个   所有业务的 HTTP 层混在一起
├── service/      18 个   业务逻辑按「功能」切，粒度不一
├── entity/       25 个   与表一一对应，无领域概念
├── mapper/       28 个   MyBatis 接口 + XML
├── dto/ config/ utils/ interceptor/ annotation/
```

目标要求是 `system / source / collection / content / analysis / lead / conversion / analytics / integration / common`
十个业务模块，每模块再分 `controller / application / domain / infrastructure` 四层。

**差距**：现有代码没有一个类能直接落到目标模块里——所有类都要重新归属，
`service` 层要拆成 `application`（用例编排）与 `domain`（业务规则），
`entity` 要区分领域模型与持久化对象。

## 3. 违规点（对照架构约束逐条核查）

| 约束 | 现状 | 证据 |
|---|---|---|
| ① Controller 不得直接访问 Mapper | **违反 2 处** | `AdminController` 注入 `UserMapper`；`AiController` 注入 3 个 Mapper |
| ② Controller 不承载复杂业务逻辑 | **违反** | `AdminController.listUsers` 内含分页拼装、手机号脱敏、密码字段清理 |
| ③ Domain 不依赖 Spring MVC / Mapper | 不适用（无 domain 层） | — |
| ④ 外部通信统一走 integration | **违反** | `CollectServiceImpl` 直接 `ProcessBuilder` 调 Python；`AiAnalysisService` 直接 HTTP 调 Flask；`UsageRecord` 计费逻辑散在 AI 服务里 |
| ⑤ 模块间无循环依赖 | 无法判定（无模块） | 但 `service` 之间互相注入已出现（`CollectServiceImpl` ← `CleanService`、`GovernanceService`） |
| ⑥ 不建无用途空类/空目录 | — | 但存在空壳：`file_asset` 表建了但 0 处代码引用 |

## 4. 可复用资产

| 资产 | 复用方式 |
|---|---|
| 抖音评论采集（`comment.py`，实测 cid 19/19、双唯一键幂等） | 包装为 `collection-service` 的 `DouyinAdapter` |
| 采集任务状态机与断点续采（4 张表 + 9 个接口） | 平移到 `collection` 模块，逻辑基本不动 |
| JWT + BCrypt 鉴权链路（已验证） | 拆为 `common`（基础设施）+ `system`（用户/权限） |
| AI 分析链路（评论级 19/19、视频级 26 条证据） | 平移到 `analysis`，Flask 调用改走 `integration` |
| Flyway 迁移机制 + 25 张表的约束设计（42 FK / 48 UK / 8 CHECK） | 作为新库设计参考，不直接复用（重建） |
| 17 个集成测试基础设施（Testcontainers） | 保留，作为 R2/R3 的安全网 |
| 侧边栏信息架构（7 组 / `menu.js` 单一数据源） | 保留，映射到 10 个模块后微调 |

## 5. 废弃资产

| 资产 | 处理 |
|---|---|
| `analysis_result`（历史表，从未被读取，已不存在） | 不再重建 |
| `file_asset`（建表未用，0 处引用） | 新架构中由 system 的文件管理重新设计，不复用旧表 |
| `AnalysisReviewMapperExt`（Ext 后缀的临时补丁类） | 合并进 analysis 的正常 Mapper |
| 5 处 `create_table_test` 之外的临时脚本 | 清理 |

## 6. 外部依赖

| 依赖 | 用途 | 现状 |
|---|---|---|
| Chrome / DrissionPage | 抖音页面采集 | 容器内已装（Chrome 157） |
| Flask（ai-service） | 分词、情感、话题分析、报告渲染 | 5 个端点，`/health` 正常 |
| DeepSeek API | LLM 分析 | Key 未配置时降级 |
| MySQL 8 | 业务库 | 25 表 / 数据量级：3 视频、19 评论 |

## 7. 数据清理安全边界（执行 R3 前必须核验）

- 目标库：`coolpilot_insight`，容器 `cpi-mysql`，宿主端口 **3307**
- 同机其他项目的库：`dy_comment`（同容器内可能存在历史库）、`coolpilot_*` 系列卷
- **R3 执行前将先执行**：`SELECT DATABASE() / SHOW DATABASES` + 卷名核对，确认只重建本项目库，
  不触碰其他项目卷（`coolpilot_mysql_data`、`dtk_postgres-data` 等）

## 8. 结论

现有系统是「一个能跑通的单平台评论分析应用」，功能验证充分（AI 全链路、采集幂等、容器部署都有实测），
但**架构上是技术分层而非业务模块化**，且存在 Controller 越层、外部调用未收敛两类硬伤。

按目标架构重构是合理的。风险不在"能不能改"，而在于：
- 100 个类重新归类需要业务判断（见 `03-module-design.md` 的待确认项）
- 数据库重建会一次性作废 25 张表的现有映射，必须同期改完实体、Mapper、Service
- 现有 17 个测试是唯一的回归保障，重建期间必须保持可执行
