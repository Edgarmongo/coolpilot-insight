# R0-08 · 代码复用与废弃清单

依据：R0-02（Java）、R0-03（数据库）、R0-04（前端）、R0-05（Python）、R0-07（测试）

## 1. 可以直接复用（不动逻辑，仅调整位置/依赖）

| 文件/资产 | 当前职责 | 目标模块 | 目标分层 | 证据 |
|---|---|---|---|---|
| `mapper/BaseCrudMapper.java` | 单表 CRUD 契约（替代 MyBatis-Plus） | common | infrastructure 契约 | 21 个测试依赖其语义 |
| `resources/mapper/*.xml`（25 个） | 单表 CRUD + 部分查询 | 各模块 | infrastructure | 由生成器产出，语义与 MP 对齐并已验证 |
| `system/application/UserAdminService` 等 system 全套 | 用户/审计/配置 | system | 全层 | R2 批次一已完成，21 测试通过 |
| `common/**`（web/security/util） | 响应/异常/认证/工具 | common | — | 同上 |
| `service/impl/CollectServiceImpl` 的**状态机部分** | 任务领取、状态流转、断点 | collection | application/domain | `CollectionTaskSchedulingTest` 3 例 |
| `comment.py`（312 行） | 抖音评论采集 | collection-service | adapter | 实测 cid 19/19、幂等 |
| `clean.py`（291 行） | CSV 清洗入库 | ai-service | pipeline | 实测写入 19 条评论 |
| `server.py` 的 `/analyze/comments`、`/analyze/insight`、`/render/report` | LLM 分析、报告渲染 | ai-service | api | 实测 19/19 + 77 条证据 + PDF/XLSX |
| `analysis_engine.py` + `sentiment_dict/` | 词典情感 | ai-service | domain | 被 `clean.py` 调用 |
| 前端全部 13 个页面与 58 个 API 方法 | 界面 | frontend | — | 登录后逐页可访问 |
| `docs/audit/`、`docs/migration/`、`docs/product/` | 历史与产品文档 | docs | — | 与源码一致 |

## 2. 调整后复用（逻辑保留，依赖需改）

| 文件 | 问题 | 处理方式 | 目标模块 |
|---|---|---|---|
| `service/AiAnalysisService.java` | 内嵌 HTTP 调 Flask | HTTP 调用抽到 `integration`，业务编排留 application | analysis |
| `service/impl/CleanServiceImpl.java` | `ProcessBuilder` 调 `clean.py` | 改为 `integration.CollectionClient/AiClient` | content |
| `service/impl/CollectServiceImpl.java` | 同文件含子进程 + CSV 落盘 + 状态机 | 拆三份：状态机（application/domain）、脚本调用（integration）、落盘（infrastructure） | collection |
| `service/DiscoveryService.java` | `ProcessBuilder` 调 `search.py` | 同上 | collection |
| `service/GovernanceService.java` | 一个类同时做「权限判定」与「内容可见性」 | 拆为 `system` 权限基础 + `content` 可见性判定 | system + content |
| `service/ReportService.java` | HTTP 调 Flask 渲染 | 抽到 integration | analytics |
| `controller/AiController.java` | 直接注入 `UsageRecordMapper` | 交给 analysis 的 application 层 | analysis |
| `utils/TokenStore.java` | 内存 Token 存储，属认证基础设施 | 迁 `common/security`，并评估多实例失效问题 | common |
| `utils/CollectUtils.java` | 抖音链接/口令解析 | 迁 `collection` 的 Douyin Adapter | collection |
| `dto/CollectRequest.java` | 位置未迁 | 迁 `collection/dto` | collection |
| `frontend/src/api/index.js` | 无版本前缀、按模块未分组 | 按 10 模块重组，路径改 `/api/v1/...` | frontend |
| `frontend/src/config/menu.js` | 7 组菜单，22 项规划中 | 按 10 模块重排，逐步启用 | frontend |

## 3. 需要拆分

| 对象 | 拆分依据 | 证据 |
|---|---|---|
| `CollectServiceImpl`（约 650 行） | 三类职责混杂 | 同文件出现 `ProcessBuilder`（376 行）、`clearActiveFlag`、CSV 落盘、任务状态机 |
| `GovernanceService` | 横切 8 个服务，且横跨 content/system | R0-02 §4 依赖表 |
| `AnalysisReviewMapperExt` | `Ext` 补丁类，同时承担 CRUD 与 3 条自定义查询 | 类内既有 `extends BaseCrudMapper` 又有 3 个 `@Select` |
| `ai-service/app/server.py`（600 行） | 分词 / 话题 / 评论分析 / 洞察 / 报告渲染全在一个文件 | 6 个路由同文件 |

## 4. 需要重写

**无。** 现有业务逻辑经真实数据验证可用，不推倒重写。
唯一近似"重写"的是：`integration` 层目前**不存在**（只有 `AppProperties` 配置），需要新建通信客户端——这属于新增，不是重写旧代码。

## 5. 可以废弃

| 对象 | 原因 | 风险 |
|---|---|---|
| `coolpilot_insight.sql`（仓库根） | 与 Flyway 重复，且已过期 | 低（保留会误导新环境建库方式） |
| `file_asset` 表 | 建表未用，0 处引用 | 低 |
| `AnalysisReviewMapperExt` 的 Ext 形态 | 命名与职责不规范 | 低（逻辑合并进正常 Mapper） |
| `tools/codegen/generate_mybatis_crud.py` | 一次性迁移工具，实体注解已移除，现 dry-run 全部跳过 | 低（保留追溯价值，可在文档标注"仅历史用途"） |
| `docs/audit/01、02` 的旧数字（25 表/2.7.18） | 已被本次审计更正（26 表 / Boot 4.1.2） | 低（已有时效横幅） |

## 6. 暂时无法确认

| 对象 | 为什么无法确认 | 建议 |
|---|---|---|
| 关键词发现链路（`KeywordController`/`DiscoveryService`/`search.py`） | 库中 `keyword`、`discovery_task` 均为 0 行；`search.py` 从未跑通 | R3 前先做一次可行性验证，再决定是否保留该能力 |
| `video_grant` 授权模型 | 表 0 行、无任何写入路径，仅在 `GovernanceService` 读 | 确认是否保留该能力（当前无界面可授权） |
| `analysis_review` 复核功能 | 代码完整、表 0 行，未在业务中启用 | 与 lead 的"线索审核"是否合并，需产品确认 |
| `UsageRecord` 的计费口径 | 3 行数据，仅 AI 侧写入，未见采集侧 | 目标是否需要对采集也计费，需确认 |

## 7. 汇总统计

| 类别 | 数量 |
|---|---:|
| 直接复用（文件/资产级） | 12 |
| 调整后复用 | 12 |
| 需要拆分 | 4 |
| 需要重写 | 0 |
| 可以废弃 | 5 |
| 无法确认 | 4 |
