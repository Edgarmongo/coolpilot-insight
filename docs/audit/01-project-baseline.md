# 01 · 项目基线审计

审计时间：2026-11-15　审计对象：`coolpilot-platform/code/coolpilot-insight`
审计方式：**只读**（未修改任何业务代码、未执行 DDL、未改动数据）

## 1. 仓库状态

| 项 | 值 |
|---|---|
| 当前分支 | `main` |
| HEAD commit | `415027f` · 2026-11-04 · `fix(ai): 打通 AI 全链路并修复成本汇总/证据落库缺陷` |
| 远端 | `origin` = `git@github.com:Edgarmongo/coolpilot-insight.git` |
| upstream | **无**（旧仓库 `upstream` 已移除） |
| 工作区 | 干净，无未提交改动 |

> 结论：本仓库与 `AIProjects/DataAnalysis/douyin_opinion_analysis` 处于**同一 commit**，
> 是同一套代码的另一个工作副本。两处 `docker-compose.yml` 都声明 `name: coolpilot-insight`，
> 因此共用容器名（`cpi-*`）、网络与数据卷，**不能同时启动**。

## 2. 技术栈基线

### 后端 `dy-backend/`

| 项 | 值 |
|---|---|
| 框架 | Spring Boot **2.7.18** |
| JDK | **1.8**（`java.version=1.8`） |
| ORM | MyBatis-Plus |
| 迁移 | Flyway 8.5.13（`flyway-core` + `flyway-mysql`） |
| 连接池 | Druid |
| 鉴权 | java-jwt + spring-security-crypto（BCrypt） |
| 文档 | springdoc-openapi-ui |
| JSON | fastjson2 |

关键配置（`dy-backend/src/main/resources/application.yml`）：
`baseline-on-migrate: false`、`validate-on-migrate: true`、`out-of-order: false`。
这意味着**任何已应用但本地缺失的迁移都会导致启动失败**，迁移文件不可随意删除。

### 前端 `dy-frontend/`

| 项 | 值 |
|---|---|
| 框架 | Vue **3.3** + Vite **5** |
| UI | Element Plus 2.4 |
| 图表 | ECharts 5.4 |
| 状态 | Pinia |
| 路由 | vue-router 4，**15 条路由** |

### 数据层 `Py_Data/`

Python + Flask + jieba + DrissionPage。根目录 4 个脚本 + `clean/` 4 个模块。
仅 `Py_Data/Datas` 是 bind mount，其余文件**打进镜像**——改脚本需重建 backend 镜像。

## 3. 代码规模

| 维度 | 数量 |
|---|---|
| Java 源文件 | 100 |
| Controller | 12 |
| HTTP 映射注解 | 59 |
| Vue 组件 | 23（含 12 个业务页面） |
| 前端路由 | 15 |
| Flyway 迁移 | 8（V1~V8） |
| 数据库表 | 25 |
| Python 脚本/模块 | 8 |
| **单元测试** | **0**（无 `src/test` 目录，无任何 `*Test.java`） |

## 4. 模块划分（现有）

```
controller/   12 个   Admin Ai Analysis Clean Collect Comment Health Keyword Overview Report Review User
service/      impl 5 + AiAnalysisService / ReportService 等
entity/       25 个   与 25 张表一一对应
mapper/       26 个   MyBatis-Plus
```

**分层是「视频 + 评论」为中心的**，没有平台抽象层。`video` / `comment` 表字段直接对应抖音
（`aweme_id`、`cid`、`dedup_key`），这是后续多平台改造的主要成本来源。

## 5. 部署形态

`docker-compose.yml` 四个服务：

| 服务 | 镜像 | 宿主端口 |
|---|---|---|
| `mysql` | mysql:8.0 | 3307 |
| `flask` | 项目自建 | 内部 5000（不对外） |
| `backend` | 项目自建（内含 Python + Chrome） | 8080 |
| `frontend` | 项目自建（Nginx） | 5173 |

`backend` 镜像同时承担 Java 运行与 Python 脚本执行，并携带 Chrome（供 DrissionPage 使用）。

## 6. 已实现 / 部分实现 / 未实现

| 模块 | 状态 | 依据 |
|---|---|---|
| 采集任务中心 | **已实现** | `collection_task/_item/_log/_checkpoint` 四表 + 9 个接口 + 断点续采 |
| 内容与评论管理 | **已实现（单平台）** | `video`/`comment`/`comment_analysis`，评论树接口 `/api/comment/tree` |
| AI 分析 | **已实现（单层）** | 评论级 + 视频级 + 证据链 + 人工复核；见 04/05 号报告 |
| 报告与导出 | **已实现** | 报告版本 + PDF/XLSX（8 Sheet） |
| 系统管理 | **部分** | 用户/审计/配置有；权限仅 `user.role` 一个整数，无角色表 |
| 多平台数据源 | **未实现** | 无 `data_source` 概念，平台逻辑写死在代码 |
| 线索中心 | **未实现** | 25 张表中无线索/证据（线索级）相关表 |
| AI 基础平台 | **未实现** | provider/model 硬编码 `deepseek` / `deepseek-chat` |
| 运营分析（漏斗/获客成本） | **未实现** | 只有总量、地区、情感、词云等表层统计 |
| 文件资产 | **未实现（空壳）** | `file_asset` 表存在，但 **0 个 Java 文件引用** |

## 7. 与设计文档一致性的关键提醒

设计文档提到「25 表方案属于候选模型，不等于已实现」。本次审计确认：**25 张表确实全部存在于迁移脚本中**，
但其中 `file_asset` 是**建了表未接代码**的空壳，不能计入「已实现功能」。
