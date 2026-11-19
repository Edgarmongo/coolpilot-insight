# R1-01 · 目标架构

依据：R0 审计（`R0-01`~`R0-09`）。本文档只描述 TARGET。

## 1. 架构形态

**Java 模块化单体 + 两个 Python 服务**。

```
┌──────────────────────────────────────────────────────────┐
│  前端 Vue 3（Nginx 托管，反代 /api）                       │
└───────────────────────────┬──────────────────────────────┘
                            │ /api/v1/{module}/...
┌───────────────────────────▼──────────────────────────────┐
│  Java 模块化单体（com.coolpilot.insight）                  │
│  system · source · collection · content · analysis        │
│  lead · conversion · analytics · integration · common     │
└──────┬──────────────────┬──────────────────┬─────────────┘
       │ JDBC             │ HTTP             │ HTTP（预留）
┌──────▼──────┐   ┌───────▼────────┐  ┌──────▼──────────┐
│   MySQL 8   │   │ Collection Svc │  │  AI Service     │
│             │   │ (Python)       │  │  (Python)       │
└─────────────┘   └───────┬────────┘  └──────┬──────────┘
                          │ Chrome            │ LLM API
                    抖音/小红书/快手/Web    DeepSeek/Ollama
                                                 │
                                        ┌────────▼────────┐
                                        │ CoolSkyAI（产品/  │
                                        │ 订单权威系统）    │
                                        └─────────────────┘
```

## 2. Java 根包与模块

根包：`com.coolpilot.insight`（**从 `com.coolskyai.coolpilot.insight` 迁移**）

```
com.coolpilot.insight
├── common/          公共基础，零业务
├── system/          用户·角色·权限·配置·审计·文件
├── source/          数据源·账号·关键词·能力
├── collection/      采集任务·执行·重试·断点
├── content/         内容·评论·作者·去重·授权·清洗
├── analysis/        AI 任务·档案·结果·证据·复核·用量
├── lead/            需求信号·线索·证据·评分·审核·跟进
├── conversion/      产品匹配·转化·归因
├── analytics/       统计·驾驶舱·报告（只读）
└── integration/     Python 采集/AI 客户端·CoolSkyAI 客户端
```

每个业务模块内固定四层：

```
<module>/
├── controller/       HTTP 出入参、校验、权限注解（禁止访问 Mapper、禁止业务编排）
├── application/      用例编排、事务边界、跨模块协调
├── domain/           实体、值对象、状态机、领域规则（禁止依赖 MVC/Mapper/外部 API）
└── infrastructure/   Mapper、XML、缓存、外部实现（禁止业务规则）
```

## 3. 与现有工程的关系

| 项 | CURRENT | TARGET | 处理 |
|---|---|---|---|
| 根包 | `com.coolskyai.coolpilot.insight` | `com.coolpilot.insight` | R2 各批次迁移时逐模块改，最后统一删旧包 |
| 已模块化 | `common`、`system` | 同左，但需改根包 | 改包名 + 更新 XML |
| 未模块化 | 8 个技术分层包 | 8 个业务模块 | R2 后续批次 |
| API 前缀 | `/api/...` | `/api/v1/...` | R4 统一 |
| Python | 脚本 + 单 Flask | 两个独立服务 | R3/R4 逐步 |
| 数据库 | 26 表无前缀 | 36 表带前缀 | R3 重建 |

> 根包改名影响全部 104 个类 + 25 个 XML 的 namespace/type。建议**在 R2 最后一个批次一次性完成**，
> 避免每批次都改一遍 import。

## 4. 模块与业务链路对应

获客链路 `采集 → 需求识别 → 用户画像 → 线索评分 → 客户转化` 落到模块：

| 链路环节 | 承担模块 |
|---|---|
| 数据采集 | `source`（去哪采）+ `collection`（怎么采、采到没） |
| 需求识别 | `analysis`（识别意图/需求/阶段）+ `content`（数据来源） |
| 用户画像 | `content`（作者公开标识）+ `lead`（线索画像与标签） |
| 线索评分 | `lead`（信号→线索→评分→审核） |
| 客户转化 | `conversion`（产品匹配、成交、归因）+ `analytics`（效果度量） |

## 5. 分层职责的判定标准

给实现者的可操作判据（避免"什么都往 service 里塞"）：

| 问题 | 放哪层 |
|---|---|
| 这段代码能否脱离"获客"独立存在？（如 Result、JWT、分页） | `common` |
| 它是否只负责把 HTTP 入参转成用例调用？ | `controller` |
| 它是否编排多个领域对象并决定事务边界？ | `application` |
| 它是否是"什么状态下允许做什么"的规则？（状态机、评分公式、去重策略） | `domain` |
| 它是否是 SQL、HTTP、文件、缓存的具体实现？ | `infrastructure` |
| 它是不是在调另一个模块？ | 经对方的 `application` 或只读 `QueryService` |

## 6. 明确不做（首期）

- 不拆微服务、不引入 Kafka/RabbitMQ
- 不引入 Milvus（无 Embedding 能力前无需求）
- 不做多租户 SaaS 计费（仅预留 `tenant_id`）
- 不重复建设产品价格与订单账本（以 CoolSkyAI 为准）
- 不为"目录完整"创建无业务支撑的空包
