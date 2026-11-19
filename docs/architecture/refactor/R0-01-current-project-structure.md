# R0-01 · 工程结构与技术栈

## 1. 工程结构（实测）

```
coolpilot-insight/
├── backend/                      Spring Boot 后端（Maven）
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/coolskyai/coolpilot/insight/   104 个 .java
│       ├── main/resources/{application.yml, db/migration/ (8 个), mapper/ (25 个 XML)}
│       └── test/java/                                    5 个测试类
├── frontend/                     Vue 3 + Vite
│   └── src/{api, components, config, router, stores, utils, views}
├── collection-service/           采集（Python 脚本 + 适配器契约）
│   ├── app/adapters/{base.py, douyin/{comment,search,browser}.py}
│   ├── datas/                    CSV 输出（容器挂载）
│   └── tests/verify_region_map.py
├── ai-service/                   清洗与 AI（Flask）
│   ├── app/{server,clean,analysis_engine,wordcloud_gen}.py
│   ├── app/sentiment_dict/       情感词典
│   └── requirements.txt
├── deploy/{compose/docker-compose.yml, docker/{backend,flask,frontend}.Dockerfile, nginx.conf, maven-settings.xml}
├── docs/{product, audit, migration, architecture, integration}
├── tools/{codegen/generate_mybatis_crud.py, check_schema_drift.py}
├── compose.yaml                  根入口（include deploy/compose）
├── .env / .env.example
├── coolpilot_insight.sql         历史建表脚本（已被 Flyway 取代）
└── README.md
```

## 2. 技术栈版本（实测命令输出）

| 组件 | 实际版本 | 证据 |
|---|---|---|
| JDK | **21.0.5**（Temurin） | `java -version` |
| Maven | 3.9.9 | `mvn -v` |
| Spring Boot | **4.1.2** | `backend/pom.xml` parent |
| MyBatis | mybatis-spring-boot-starter 4.1.0（原生，非 MyBatis-Plus） | `pom.xml` |
| Flyway | spring-boot-flyway + flyway-core/mysql（版本由 Boot BOM 管理） | `pom.xml` |
| MySQL | **8.0.46** | `docker exec cpi-mysql mysql -e "select version()"` |
| Node.js | v22.22.2 | `node -v` |
| Vue | 3.3 / Vite 5 / Element Plus 2.4 / ECharts 5.4 | `frontend/package.json` |
| Python（宿主） | 3.13.14 | `python --version` |
| Python（容器） | **3.10.12**（Ubuntu 22.04 自带） | `docker exec cpi-backend python3 --version` |
| Flask | 3.1.3 | 容器内 pip 安装记录 |
| Chrome（容器内） | 157.0.8103.78 | `google-chrome --version` |
| Docker Compose | v5.5.1 | `docker compose version` |

> 注意：宿主 Python 3.13 与容器 Python 3.10 不一致。本地直接跑采集脚本时以宿主为准，
> 容器内以 3.10 为准，两者依赖版本可能不同。

## 3. 工程入口

| 入口 | 路径 |
|---|---|
| 后端启动类 | `backend/src/main/java/.../CoolPilotInsightApplication.java` |
| 后端配置 | `backend/src/main/resources/application.yml` |
| 数据库迁移 | `backend/src/main/resources/db/migration/V1~V8` |
| 前端入口 | `frontend/src/main.js` + `router/index.js` + `components/AppLayout.vue` |
| Flask 服务 | `ai-service/app/server.py`（6 个路由） |
| 采集脚本 | `collection-service/app/adapters/douyin/comment.py`（命令行入口） |
| 编排入口 | `compose.yaml` → `deploy/compose/docker-compose.yml` |

## 4. 目录职责（当前）

| 目录 | 职责 | 备注 |
|---|---|---|
| `backend/.../common/` | 响应、异常、安全、工具 | R2 批次一已建 |
| `backend/.../system/` | 用户、审计、配置 | R2 批次一已建 |
| `backend/.../controller|service|entity|mapper/` | 其余 8 个业务域，**仍是技术分层结构** | 待 R2 后续批次 |
| `backend/.../integration/config/` | `AppProperties`（Python 路径、Flask 地址） | 仅配置，尚无客户端实现 |
| `frontend/src/views/` | 13 个页面（含 admin） | 菜单按 7 组组织 |
| `ai-service/` | 分词、情感、话题、报告渲染 | 单进程 Flask |
| `collection-service/` | 抖音采集 | 脚本形态，未实现 `PlatformAdapter` |

## 5. 疑似废弃 / 冗余

| 项 | 判定 | 证据 |
|---|---|---|
| `coolpilot_insight.sql` | **仅迁移存在**（历史建表脚本） | Flyway 为唯一结构入口，README 已声明 |
| `docs/audit/2026-11/`（25 篇） | 历史审计快照，非废弃 | 记录早期状态，有留存价值 |
| `backend/.../dto/CollectRequest.java` | 位置未迁移 | 应在 R2 collection 批次迁入 |
| `backend/.../utils/{TokenStore,CollectUtils}.java` | 位置未迁移 | 分别待迁 `common.security` 与 collection |
| `tools/codegen/generate_mybatis_crud.py` | 一次性迁移工具 | 实体注解已移除，当前 dry-run 全部跳过，仅保留追溯价值 |

## 6. 当前架构 vs 目标架构（差异摘要）

| 维度 | CURRENT | TARGET | 差距 |
|---|---|---|---|
| Java 组织 | 技术分层（104 类中仅 `common`+`system` 已模块化） | 10 模块 × 4 层 | 8 个模块未迁 |
| API 版本前缀 | 无版本（`/api/...`） | `/api/v1/{module}/...` | 全量改造 |
| 数据库 | 25 表，无模块前缀 | 约 30 表，带 `sys_/ds_/col_/ct_/an_/ld_/cv_` 前缀 | 重建 |
| Python 服务 | 脚本 + 单 Flask 进程 | Collection Service / AI Service 分离 | 部分（目录已分，协议未定） |
| 外部调用 | 散落在 Service（4 处 ProcessBuilder + 3 处 HTTP） | 统一 integration 客户端 | 全量抽取 |
| 前端 | 7 组菜单，12 个可用项 | 按 10 模块组织 | 需重排并补页面 |

以上差距的逐项证据见 R0-02 至 R0-09。
