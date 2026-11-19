# R0-05 · Python 服务审计

两个目录：`collection-service/`（采集）、`ai-service/`（清洗与 AI）
语言/运行时：Python 3.10.12（容器内）；宿主为 3.13.14

## 1. 目录与规模（实测行数）

| 文件 | 行数 | 职责 |
|---|---:|---|
| `collection-service/app/adapters/base.py` | 103 | **适配器契约**（`PlatformAdapter` / `RawContent` / `RawComment` / `Capability`） |
| `collection-service/app/adapters/douyin/comment.py` | 312 | 抖音评论采集（DrissionPage 驱动真实 Chrome） |
| `collection-service/app/adapters/douyin/search.py` | 146 | 抖音关键词搜索（**未验证**） |
| `collection-service/app/adapters/douyin/browser.py` | 112 | Chrome 启动参数（headless / 用户目录 / 无沙箱） |
| `collection-service/tests/verify_region_map.py` | 57 | 地图着色验证工具（非业务） |
| `ai-service/app/server.py` | 600 | Flask 常驻服务：分词、话题、评论分析、洞察、报告渲染 |
| `ai-service/app/clean.py` | 291 | CSV 清洗入库（去重 + 情感标注 + 写库） |
| `ai-service/app/analysis_engine.py` | 137 | 词典情感引擎（SnowNLP + 自定义词典） |
| `ai-service/app/wordcloud_gen.py` | 65 | 词云数据生成 |
| 其余 `__init__.py` | 0 | 包标记 |

无 `requirements.txt` 之外的依赖声明文件；依赖清单在 `ai-service/requirements.txt`。

## 2. Collection Service 能力矩阵

| 能力 | 状态 | 证据 |
|---|---|---|
| 抖音评论采集 | **已实现且实测** | `comment.py`；实测 cid 19/19、双唯一键幂等、匿名约 3 页/15–20 条 |
| 楼中楼回复 | 已实现 | `comment.py` 处理 `reply_comment`，落 `parent_cid`/`is_root` |
| 断点续采 | 已实现 | 进度文件 `save_progress` + 已采 cid 载入 |
| 去重（本地侧） | 已实现 | `fingerprint(nickname, dt, content)` 兜底 |
| 抖音关键词搜索 | **未验证** | `search.py` 存在双策略（接口响应解析 + DOM 兜底），从未跑通 |
| 小红书 / 快手 / Web 采集 | **未实现** | 目录不存在，`base.py` 仅有契约 |
| 统一 `PlatformAdapter` 实现 | **未实现** | 抖音仍为命令行脚本，未实现 `base.PlatformAdapter` |
| 限流 / 重试 | **部分** | 依赖 Chrome 滚动间隔，无统一限流器 |

## 3. AI Service 能力矩阵

### Flask 路由（实测 6 个）

| 方法 | 路径 | 功能 |
|---|---|---|
| GET | `/health` | 存活检查（返回 `{"code":0,"msg":"ok"}`，实测） |
| GET | `/wordcloud` | 词云数据 |
| POST | `/analyze_topic` | 话题分析（DeepSeek，可降级） |
| POST | `/analyze/comments` | 评论级结构化分析（意图/痛点/购买意向/话题） |
| POST | `/analyze/insight` | 视频级洞察 |
| POST | `/render/report` | 报告渲染（PDF/XLSX） |

### 能力判定

| 能力 | 状态 | 说明 |
|---|---|---|
| 中文分词（jieba） | 已实现 | `server.py` 内 |
| 情感分析 | 已实现 | `analysis_engine.py` + `sentiment_dict/`（积极/消极词典） |
| 评论级 LLM 分析 | 已实现且实测 | 实测 19/19 成功，结果落 `comment_analysis` |
| 视频级洞察 | 已实现且实测 | 落 `video_insight` + 77 条 `analysis_evidence` |
| 报告渲染 PDF/XLSX | 已实现且实测 | `reportlab` / `openpyxl` |
| Model Gateway（多模型路由） | **未实现** | 直连 DeepSeek，provider 硬编码 |
| Embedding / Rerank | **未实现** | 无代码 |
| Prompt 版本管理 | **未实现** | Prompt 内联在代码字符串中 |
| 结构化输出 Schema 校验 | **部分** | 有 JSON 解析与容错，无 Pydantic Schema |
| 分析效果评估 | **未实现** | 无标注集、无评估脚本 |
| Ollama / vLLM Adapter | **未实现** | 无代码 |
| FastAPI | **未采用** | 实际为 Flask 3.1.3 |

## 4. Java ↔ Python 当前通信关系（实测）

| 方向 | 方式 | 证据 |
|---|---|---|
| Java → Flask（分词/分析/报告） | **HTTP** | `AiAnalysisService`、`AnalysisServiceImpl`、`ReportService` 直接发 HTTP |
| Java → 采集脚本 | **ProcessBuilder 子进程** | `CollectServiceImpl:376`（`comment.py`）、`DiscoveryService:246`（`search.py`） |
| Java → 清洗脚本 | **ProcessBuilder 子进程** | `CleanServiceImpl:101`（`clean.py`） |
| Java 启动 Flask | **ProcessBuilder**（本地开发时） | `CoolPilotInsightApplication:41`（`server.py`），容器内由 compose 独立启动 |
| Python → MySQL | **直连** | `clean.py` 用 `pymysql` 写 `comment`/`video` |

**问题**：
- 无统一客户端，超时/重试/异常翻译各写各的
- Python 直接写业务库，绕过了 Java 的业务规则（如审计、权限、幂等校验）
- 无 traceId 贯穿，排障只能靠时间对齐

## 5. Docker 配置

| 服务 | 镜像来源 | 关键点 |
|---|---|---|
| `flask` | `deploy/docker/flask.Dockerfile` | `COPY ai-service/app` → `/app/app`；`CMD python app/server.py`；无对外端口 |
| `backend` | `deploy/docker/backend.Dockerfile` | 同时装 Python 依赖 + Chrome；`PY_DATA_DIR`/`PY_CLEAN_DIR` 指向挂载外的镜像内路径 |

环境变量：`PY_DATA_DIR=/app/collection-service/app/adapters/douyin`、`PY_CLEAN_DIR=/app/ai-service/app`、
`PY_DATAS_DIR=/app/collection-service/datas`、`FLASK_BASE_URL=http://flask:5000`、`FLASK_AUTOSTART=false`。

## 6. 测试情况

- `collection-service`：**0 个自动化测试**（`verify_region_map.py` 是人工验证脚本）
- `ai-service`：**0 个自动化测试**
- 现有 21 个 Java 集成测试不覆盖 Python 侧

## 7. 结论与 GAP

| 目标要求 | CURRENT | GAP |
|---|---|---|
| Collection Service 独立服务 + Adapter | 脚本 + 契约文件 | 需实现 `DouyinAdapter`，补 FastAPI/HTTP 接口 |
| AI Service 独立服务 + Model Gateway | 单 Flask，Provider 硬编码 | 需引入模型网关、Prompt 版本、Schema 校验 |
| 多平台 | 仅抖音 | 小红书/快手/Web 全缺 |
| 统一通信契约 | 散落的 HTTP + 子进程 | 需 R1 定义契约，R5 落地 |

**没有一项 AI 能力是虚构的**：LLM 分析、洞察、报告渲染都有实测数据支撑；
而 Model Gateway、Embedding、Rerank、评估体系属于**未实现**，不得计入现有能力。
