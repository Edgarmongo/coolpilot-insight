# R0-00 · 审计准备与安全检查

审计时间：2026-11-15 18:00（GMT+8）
审计执行者：WorkBuddy　任务：CP-INSIGHT-REFACTOR-00（仅 R0 + R1）

## 1. 目标确认

| 检查项 | 结果 |
|---|---|
| 工作目录 | `D:/devworkspace/software/workspace/coolpilot-platform/code/coolpilot-insight` |
| 是否本项目 | 是。`backend/pom.xml` artifactId = `coolpilot-insight-server`；前端 title 为 CoolPilot Insight |
| 同机其他项目 | `AIProjects/DataAnalysis/douyin_opinion_analysis`（同一代码的旧副本）、`douyin_tiktok_api`（独立项目，容器前缀 `dtk-`） |

**注意**：`douyin_opinion_analysis` 与本项目 `docker-compose.yml` 均声明 `name: coolpilot-insight`，
共用容器名 `cpi-*` 与数据卷 `coolpilot-insight_*`。任何 Docker/数据库操作都必须确认作用于哪个副本。

## 2. Git 状态

| 项 | 值 |
|---|---|
| 分支 | `feat/boot4-jdk21-mybatis` |
| HEAD | `3ef5ea3` · 2026-11-15 · `refactor(arch): R2 批次一 —— 落地 common 与 system 模块` |
| remote | `origin` = `git@github.com:Edgarmongo/coolpilot-insight.git` |
| 工作区 | 干净（`git status --short` 输出为空） |
| 标签 | `r2-common-system`（R2 批次一的可回退点） |

## 3. 与任务前提的偏差（必须记录）

任务书定义本任务只做 R0+R1，且禁止修改代码。实际执行前，**R2 批次一已经完成并提交**（`3ef5ea3`）：

- `common` 与 `system` 两个模块已落地（原 `entity/mapper/service/controller` 中对应类已迁入）
- `AdminController` 的 Mapper 注入已消除，逻辑下沉到 `UserAdminService`
- 新增 `PageResult`、`UserView`、`UserAdminServiceTest`

**影响**：本次 R0 审计反映的是 **R2 批次一之后**的状态，不是重构前状态。
R0-02 中关于 "AdminController 是否直接注入 UserMapper" 的结论与此前不同（现已消除）。
本次**未回滚**该提交——回滚属于未授权的破坏性操作。

## 4. 运行中的服务与数据边界

| 容器 | 状态 | 端口 | 归属 |
|---|---|---|---|
| `cpi-backend` | healthy | 8080 | 本项目 |
| `cpi-flask` | healthy | 内部 5000 | 本项目 |
| `cpi-mysql` | healthy | 3307→3306 | 本项目 |
| `cpi-frontend` | Up | 5173 | 本项目 |

数据库：MySQL **8.0.46**，库名 `coolpilot_insight`，位于容器 `cpi-mysql`。

**不得触碰的资源**：

- 同机 `dtk-postgres-1` / `dtk-redis-1` 及其数据卷 `dtk_*`（抖音采集项目）
- 卷 `coolpilot_mysql_data`、`coolpilot_upload_data`（另一套 coolpilot 系列项目）
- 卷 `coolpilot-insight_chrome-data`（本项目 Chrome 登录态，R3 重建时不应删）

## 5. 本次执行的读操作清单

| 操作 | 是否只读 | 说明 |
|---|---|---|
| `git status / log / tag` | 是 | 读取仓库状态 |
| `find` / `grep` 统计源码 | 是 | 只读文件 |
| `mvn test` | 是（对业务库） | 测试使用 Testcontainers 独立 MySQL 容器，端口随机，**不连接 3307 业务库** |
| `SELECT COUNT(*)` 等查询 | 是 | 仅统计，无 DDL/DML |
| `docker ps / exec mysql -e SELECT` | 是 | 未重启、未删除容器或卷 |

## 6. 未执行的操作（明确声明）

- 未修改任何 Java / Python / 前端业务代码
- 未修改 pom.xml、requirements.txt、package.json
- 未修改 compose、Dockerfile、配置与环境变量
- 未执行任何 DDL、Flyway 迁移、数据删除
- 未删除或重命名任何项目文件
- 未在报告中输出任何密钥、密码、Token 的真实值

## 7. 结论

审计对象确认无误，工作区干净，测试执行不会污染业务数据（Testcontainers 隔离）。
**具备继续执行 R0-01 至 R0-09 的条件。**
