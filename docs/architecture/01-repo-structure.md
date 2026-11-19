# 仓库结构说明

日期：2026-11-15　对应提交：见 `git log --oneline -- docs/architecture/`

## 1. 目标结构与当前落地

```
coolpilot-insight/
├── frontend/            Vue 3 + Vite
├── backend/             Spring Boot 4.1 / JDK 21
├── collection-service/  采集侧（Python，平台适配器）
├── ai-service/          清洗与 AI 侧（Python / Flask）
├── deploy/
│   ├── compose/         编排定义
│   └── docker/          镜像定义、nginx、maven 配置
├── docs/                product / audit / migration / architecture / integration
├── tools/               代码生成、表结构漂移检查
└── compose.yaml         统一入口（include deploy/compose）
```

## 2. 各目录职责与依赖方向

| 目录 | 职责 | 依赖 |
|---|---|---|
| `frontend` | 管理界面，只通过 HTTP 调后端 | → backend `/api/**` |
| `backend` | 业务、鉴权、任务编排、分析落库 | → MySQL；以**子进程**方式调用两个 Python 目录；→ Flask HTTP |
| `collection-service` | 平台适配器（抖音已实现，其余待接入） | → 目标平台、本机 Chrome；产出 CSV 到 `datas/` |
| `ai-service` | 分词、情感、话题分析、报告渲染（Flask 常驻） | ← backend 调用；← 读取 CSV |

调用关系（运行期）：

```
浏览器 → frontend(Nginx) → backend :8080
                             ├─ subprocess: collection-service/app/adapters/douyin/comment.py
                             ├─ subprocess: ai-service/app/clean.py
                             └─ HTTP: ai-service Flask :5000  (app/server.py)
```

> 注意：`backend` 按「目录 + 文件名」调用 Python（`PY_DATA_DIR` / `PY_CLEAN_DIR`），
> 因此改动脚本文件名或目录必须同步更新 `AppProperties` 默认值与 compose 环境变量。

## 3. 为什么根目录保留 compose.yaml

真正的编排在 `deploy/compose/docker-compose.yml`。根目录的 `compose.yaml` 只有一段 `include`：

```yaml
include:
  - path: deploy/compose/docker-compose.yml
```

两个原因：

1. 保留 `docker compose up -d` 一条命令，不必记 `-f deploy/compose/...`；
2. `.env` 的查找目录是**项目目录**，即 compose 文件所在目录。编排文件下沉后，
   根目录的 `.env` 会读不到，`JWT_SECRET` 这类必填变量会直接报错退出。

被 include 文件内的相对路径（`context`、`volumes`）以**该文件所在目录**为基准，
因此编排里写的是 `context: ../..` 与 `../../collection-service/datas`。

## 4. 本次改动的影响面

| 变更 | 影响 |
|---|---|
| `dy-backend` → `backend`，`dy-frontend` → `frontend` | Maven/npm 命令、Dockerfile COPY、README |
| `Py_Data` 拆分 | `douyin_comment.py`→`comment.py`、`douyin_search.py`→`search.py`、`config_browser.py`→`browser.py`、`clean_main.py`→`clean.py`、`wordcloud_service.py`→`server.py` |
| 脚本改名 | 后端 4 处 `cmd.add("xxx.py")` + `AppProperties` 默认路径 + `flask.Dockerfile` CMD |
| `docker/` → `deploy/docker/`，编排 → `deploy/compose/` | Dockerfile 路径、`maven-settings.xml` 路径、部署指南 |
| CSV 数据目录 | `Py_Data/Datas` → `collection-service/datas`（compose 挂载 + `PY_DATAS_DIR`） |

## 5. 验证记录

| 项 | 结果 |
|---|---|
| `mvn test`（在 `backend/`） | 17/17 通过 |
| `npm run build`（在 `frontend/`） | 通过 |
| `docker compose config`（根目录） | 通过，context 解析到仓库根、卷指向 `collection-service/datas` |
| `docker compose build` | 三个镜像全部构建成功 |
| `docker compose up -d` | mysql / flask / backend healthy，frontend 200 |
| 业务回归 | 登录 200；`/api/overview` 返回 3 视频 / 19 评论 / 14 用户（与重构前一致） |
| AI 服务 | 新路径 `/app/app/server.py` 启动，`/health` 返回 `{"code":0,"msg":"ok"}` |

## 6. 未做的部分（有意保留）

**Java 包名与模块未按目标拆分。** 目标结构里 backend 下面是
`source / collection / content / analysis / lead / conversion / analytics / integration / common`，
根包已于 R2-01 迁移为 `com.coolpilot.insight`，但内部仍是 `{controller,service,mapper,entity,...}` 技术分层。

不做的理由：

- 100 个 Java 文件需要同时改包名、import、MyBatis XML 的 `namespace` 与 `resultMap type`，
  一次动完风险高、收益低；
- 更重要的是**领域边界还没定**。线索域（lead）、转化域（conversion）尚未建模，
  现在按目标切包，等于先画格子再往里塞，后续大概率返工；
- 设计文档本身也写明「这些是目标组织方式，不是要求第一阶段直接移动旧项目文件」。

建议时机：P1 完成统一内容模型后再拆包，按 `source → collection → content → analysis → lead`
的顺序逐域迁移，每次迁移都跑一遍现有 17 个集成测试兜底。

**采集适配器尚未实现 `PlatformAdapter` 契约。** `collection-service/app/adapters/base.py`
先确立了接口（能力声明、RawContent/RawComment），抖音脚本仍是脚本形态，
P1 阶段包装成 `DouyinAdapter` 后，小红书 / 快手 / Web 按同一契约接入。
