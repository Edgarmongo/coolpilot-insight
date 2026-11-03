# CoolPilot Insight — 智能内容洞察平台

> **AI-Powered Content Intelligence Platform**
> 从内容数据到用户洞察，让 AI 发现真实需求。

> 一站式内容评论数据采集、清洗、分析与可视化平台。当前支持抖音视频评论，提供多视频管理、AI 核心话题分析、多维度数据可视化大屏。

> ⚠️ **部署前必读（安全）**
> 仓库内的数据库密码、JWT 密钥均为**本地开发默认值**，仅供开箱即用，**不可用于生产环境**。
> 部署前请复制 `.env.example` 为 `.env`，并至少替换：
> - `DB_PASSWORD`（默认 `root`）
> - `JWT_SECRET`（默认值已公开，可伪造登录态，务必改为随机串，如 `openssl rand -hex 32`）
>
> 建库脚本中的管理员账号为占位凭据，首次部署后请立即登录修改密码。

---

## 📖 项目简介

CoolPilot Insight 是一个面向抖音创作者和运营人员的评论数据分析工具。用户只需提供抖音视频链接，即可自动完成**评论采集 → 数据清洗（情感标注/去重） → 多维度分析 → 可视化大屏展示**的全流程。平台内置 DeepSeek API，可自动生成智能化的「核心话题分析报告」，帮助运营人员快速把握评论区舆情动态。

---

## ✨ 功能介绍

### 数据采集（Collect）
- 通过 DrissionPage 自动化浏览器采集抖音视频评论
- **多视频批量采集**：一次提交多个链接，任务内串行执行（受 Chrome 单实例约束）
- **流式落盘**：每页立即追加写入，中断不再丢失已采数据
- **幂等去重**：`cid` + 本地指纹双唯一键，重复采集同一视频不产生重复行
- **采集用户标识**：抖音号(unique_id)、抖音 UID、安全 UID(sec_uid)、IP 归属地
  - 注：抖音出于隐私保护**不返回用户真实 IP**，只能拿到 IP 归属地标签（如"辽宁""广东"）
- **楼中楼回复**：接口返回的嵌套回复一并入库（`parent_cid` / `is_reply`）

### 任务中心（Tasks）
- 采集任务持久化：主任务 + 子任务 + 执行日志 + 断点，容器重启后仍可查询
- 状态机：PENDING / RUNNING / SUCCESS / PARTIAL_SUCCESS / FAILED / CANCELLED
- **并发防护**：同一视频不允许两个活动任务（`uk_video_active` + 条件更新领取）
- 支持取消、失败重试、按状态筛选、实时日志查看

### 关键词发现（Keywords）
- 关键词 CRUD（按用户隔离，归一化后去重）
- 关键词搜索 → 自动发现候选视频 → 落 `video` / `keyword_video` / `discovery_task_item`
- 发现任务与采集任务解耦：发现不自动触发采集，避免无谓资源消耗

### AI 需求洞察（Insight）
- **评论级结构化分析**：情感 / 意图 / 主题 / 痛点 / 用户需求 / 购买意向 / 场景 / 竞品提及 / 置信度
  - 强制 JSON Schema 输出，失败可重试；同一版本重复分析自动跳过与覆盖
- **视频级洞察**：需求 / 痛点 / 产品机会 / 亮点 / 购买意向，每条结论附 `evidence_comment_ids`
- **证据可追溯**：所有结论落 `analysis_evidence`，可定位到原始评论
- **多视频对比**：并排对比多个视频的需求/痛点，自动识别共性痛点
- **成本治理**：每次调用写 `usage_record`（唯一权威源），日 Token 预算护栏 + 并发上限

### 报告中心（Reports）
- 基于已有洞察归档为版本化报告，**不重复调用 AI，不产生额外费用**
- 报告含数据范围、样本量、证据数、生成时间
- 支持 Markdown 导出

### 数据清洗（Clean）
- 自动去重、过滤无效评论
- **情感分析引擎**：SnowNLP + 529 词情感词典二次判定（灰区纠偏），阈值可配置
- 清洗结果一键入库（MySQL，`ON DUPLICATE KEY UPDATE` 幂等）

### 总览大屏（Dashboard）
- **统计卡片**：评论总数、参与用户数、总点赞数、平均点赞数
- **地区分布**：基于用户归属地的 ECharts 地图可视化（visualMap 渐变着色）
- **情感分析**：积极/中性/消极占比饼图
- **评论时间趋势**：日级别评论数量折线图
- **词云**：基于 jieba 分词的评论高频词可视化
- **桑基图**：地区 → 情感 → 话题 的评论流向分析
- **核心话题**（AI 驱动）：
  - DeepSeek AI 综合分析多维数据（关键词、情感分布、地区、高赞代表评论）
  - 自动生成结构化分析报告
  - AI 不可用时自动降级为统计报告，保证页面不崩溃
- **高赞评论排行**：Top 20 高赞评论列表（含抖音号）

### 用户系统
- 手机号注册/登录，**密码统一 BCrypt**（不再产生 MD5）
- JWT Token 认证；管理员账号由环境变量 `ADMIN_INIT_PASSWORD` 首次初始化，库内不存明文
- 数据授权三层：视频基础信息（公开）/ 评论数据（管理员、任务发起人、显式授权）/ 分析结果（跟随评论）
- 管理员后台：用户管理（管理员专用）

---

## 🛠️ 技术栈

| 层 | 技术 |
|----|------|
| 数据采集 | Python 3.12 + DrissionPage 4.x |
| 数据清洗 | Python 3.12 + jieba + 自定义情感词典 || AI Agent | DeepSeek API (`deepseek-chat`) + OpenAI SDK |
| 分词服务 | Flask 3.x + jieba（常驻服务，50ms 级响应） |
| 后端 | Spring Boot 2.7 + MyBatis-Plus 3.5 + MySQL 8.0 |
| API 文档 | springdoc-openapi (Swagger UI) |
| 前端 | Vue 3.3 + Vite 5 + Element Plus 2.4 + ECharts 5.4 + Pinia |
| 词云图 | echarts-wordcloud |
| 认证 | JWT Token + 内存 TokenStore |
| 缓存 | 内存 MemoryCache（TTL 30 分钟 / 分析缓存 5 分钟） |
| 数据库迁移 | **Flyway 8.5.13**（`db/migration` V1~V6，唯一结构变更入口） |

---

## 🗄️ 数据库设计（V1.1 冻结版）

25 张业务表，按领域划分：

| 领域 | 表 |
|------|----|
| 用户与治理 | `user`、`system_config`、`audit_log` |
| 关键词与视频 | `keyword`、`video`、`keyword_video`、`discovery_task`、`discovery_task_item` |
| 评论采集 | `comment`、`collection_task`、`collection_task_item`、`collection_checkpoint`、`collection_task_log` |
| AI 分析 | `analysis_profile`、`analysis_task`、`analysis_task_batch`、`analysis_task_item`、`comment_analysis`、`video_insight`、`analysis_evidence` |
| 授权/文件/报告 | `video_grant`、`file_asset`、`report`、`report_version`、`usage_record` |

**三个关键设计**：

1. **视频独立建模**：`video.id` 是内部主键，`aweme_id` 是平台标识；`comment.video_id` 指向内部 ID。
2. **评论双唯一键**：`uk_platform_cid (platform, cid)` + `uk_platform_dedup (platform, dedup_key)`。
   `cid` 允许 NULL（历史数据），`dedup_key` 恒非空（无 cid 时用本地指纹兜底）。
   > 实测本平台评论接口 **确实返回 cid**，19/19 条命中，策略为 `CID`。
3. **活动任务唯一**：`collection_task_item.uk_video_active (video_id, active_flag)`，
   终态把 `active_flag` 置 NULL（MySQL 唯一索引对 NULL 不生效）。

**迁移机制**：容器启动时 Flyway 自动执行 `V1~V6`，禁止 `baselineOnMigrate`。
MySQL 的 DDL 隐式提交意味着**迁移失败无法回滚**，失败后需按备份或全量重建恢复。

### 新增环境变量

| 变量 | 说明 |
|------|------|
| `ADMIN_INIT_PASSWORD` | 首次启动把哨兵管理员置为可用密码；留空则账号保持不可登录 |
| `FLASK_INTERNAL_TOKEN` | Java→Flask 服务间令牌；配置后 Flask 拒绝无令牌请求 |
| `FLYWAY_ENABLED` | 默认 true；仅在调试时可临时关闭 |
| `SENTIMENT_POS` / `SENTIMENT_NEG` | 情感判定阈值（默认 0.6 / 0.4） |
| `AI_MAX_CONCURRENCY` | AI 调用并发上限（默认 3） |
| `DEEPSEEK_API_KEY` | 未配置时 AI 洞察不可用，其余统计功能正常 |

---

## 📁 项目结构

```
CoolPilot Insight/
│
├── Py_Data/                                # Python 数据层
│   ├── douyin_comment.py                   # 抖音评论采集（DrissionPage 自动化浏览器）
│   ├── config_browser.py                   # Chrome 浏览器启动配置
│   ├── Datas/                              # 采集原始 CSV 数据目录
│   ├── clean/                              # 数据清洗与服务模块
│   │   ├── clean_main.py                   # 数据清洗入库入口（去重 + 情感标注）
│   │   ├── analysis_engine.py              # 情感分析引擎（基于词典的 NLP 分析）
│   │   ├── sentiment_dict/                 # 情感词典（积极/消极/程度/否定词库）
│   │   ├── wordcloud_gen.py                # 词云数据生成（单次脚本）
│   │   ├── wordcloud_service.py            # Flask 常驻服务（分词接口 + AI 话题分析端点）
│   │   ├── .env                            # DeepSeek API Key 配置
│   │   └── requirements.txt               # Python 依赖
│
├── dy-backend/                             # Spring Boot 后端
│   ├── pom.xml                             # Maven 依赖（Spring Boot 2.7）
│   └── src/main/java/com/dy/comment/
│       ├── DyCommentApplication.java       # 启动类（自动拉起 Flask 分词服务）
│       ├── controller/                     # REST API 控制器
│       │   ├── UserController              # 注册/登录/用户信息
│       │   ├── CollectController           # 采集任务管理
│       │   ├── CleanController             # 数据清洗管理
│       │   ├── CommentController           # 评论查询/删除/情感重判
│       │   ├── AnalysisController          # 总览分析（6 大维度 API）
│       │   └── AdminController             # 管理员用户管理
│       ├── service/impl/                   # 业务逻辑实现
│       │   ├── AnalysisServiceImpl         # 核心分析（含 5 维计算 + AI Agent 调用）
│       │   ├── CleanServiceImpl            # 清洗入库逻辑
│       │   ├── CollectServiceImpl          # 采集任务调度
│       │   ├── CommentServiceImpl          # 评论 CRUD
│       │   └── UserServiceImpl             # 用户认证与鉴权
│       ├── mapper/                         # MyBatis-Plus 数据映射
│       ├── entity/                         # 数据实体（User / Comment / AnalysisResult）
│       ├── interceptor/                    # JWT 请求拦截器、上下文持有者
│       ├── dto/                            # 数据传输对象（请求/响应）
│       ├── utils/                          # 工具类
│       │   ├── JwtUtil                     # JWT 令牌签发与验证
│       │   ├── TokenStore                  # 内存 Token 存储
│       │   ├── MemoryCache                 # 30 分钟 TTL 本地缓存
│       │   └── CollectUtils               # 抖音链接解析
│       └── config/                         # 配置
│           ├── GlobalExceptionHandler      # 全局异常处理
│           ├── WebMvcConfig                # CORS / 拦截器注册
│           └── OpenApiConfig               # Swagger / OpenAPI 配置
│
├── dy-frontend/                            # Vue 3 前端
│   ├── index.html                          # HTML 入口
│   ├── vite.config.js                      # Vite 构建配置（代理到后端 8080）
│   ├── package.json                        # 前端依赖
│   └── src/
│       ├── main.js                         # Vue 应用入口（挂载 Element Plus / Pinia / Router）
│       ├── App.vue                          # 根组件
│       ├── api/                            # Axios 封装
│       │   ├── request.js                  # HTTP 拦截器（Token 注入 / 错误处理）
│       │   └── index.js                    # API 方法定义
│       ├── router/index.js                 # 路由配置（6 个页面 + 1 个管理页面）
│       ├── stores/user.js                  # Pinia 状态管理（用户信息 / 当前视频）
│       ├── utils/useECharts.js             # ECharts 组合式函数（消除 90% 重复代码）
│       ├── views/                          # 页面组件
│       │   ├── LoginView / RegisterView    # 登录 / 注册
│       │   ├── DashboardView               # 总览分析大屏（核心页面）
│       │   ├── CollectView                 # 数据采集管理
│       │   ├── CleanView                   # 数据清洗管理
│       │   ├── CommentListView             # 评论明细列表
│       │   ├── ProfileView                 # 个人中心
│       │   └── admin/UserManageView        # 用户管理（管理员）
│       └── components/                     # 可复用图表组件
│           ├── AppLayout                   # 全局布局（侧边栏 + 顶栏 + 内容区）
│           ├── StatsCards                  # 统计卡片组
│           ├── RegionMap                   # 地区分布地图
│           ├── SentimentPie               # 情感占比饼图
│           ├── TrendLine                   # 时间趋势折线图
│           ├── TopComments                 # 高赞评论 Top 20
│           ├── WordCloud                   # 词云图
│           └── SankeyChart                # 桑基图
│
├── coolpilot_insight.sql                          # 建库建表 SQL（直接执行）
├── docker-compose.yml                     # Docker 一键部署编排
├── .env.example                           # 环境变量示例（复制为 .env 后使用）
├── docker/                                # 容器化配置
│   ├── backend.Dockerfile                 # 后端镜像（JDK8 + Python + Chrome）
│   ├── flask.Dockerfile                   # 分词/AI 服务镜像
│   ├── frontend.Dockerfile                # 前端镜像（Node 构建 + Nginx 托管）
│   ├── nginx.conf                         # 前端托管与 API 反代配置
│   ├── maven-settings.xml                 # 容器内 Maven 国内镜像配置
│   └── DOCKER部署指南.md                  # Docker 部署详解
├── 需求设计（抖音多视频评论分析与可视化平台）.md    # 完整需求设计文档
├── 抖音爬虫维护笔记.md                       # 爬虫故障排查指南
├── 简洁版使用指南.txt                        # 极简快速上手指南
└── README.md                               # 本文件
```

---

## 🔄 数据流

```
用户输入抖音链接
    ↓
Java CollectUtils 解析链接 → Python 爬虫采集 CSV
    ↓
前台勾选 CSV 文件 → Python clean_main 清洗入库
  （jieba 分词 → 情感词典判定 → MySQL 写入）
    ↓
总览大屏选择视频 → 后端 6 维度并行分析
  │
  ├── 地区分布    ← 按 region 分组统计
  ├── 情感分析    ← sentiment 聚合 + 趋势判定
  ├── 时间趋势    ← 按 publish_time 日聚合
  ├── 词云        ← Flask 分词服务 (50ms)
  ├── 桑基图      ← 地区 → 情感 → 话题 流向
  ├── 核心话题    ← AI 深度分析
  │    └── Java 计算 5 维度 → Flask POST → DeepSeek API → 结构化报告
  └── 高赞排行    ← 按 likes DESC LIMIT 20
    ↓
ECharts 渲染 → 总览大屏展示
```

### AI Agent 核心话题 — 架构说明

```
                            ┌─ 关键词 (wordcloud) ─┐
                            ├─ 情感分布            │
Java 后端计算 5 维度数据 ────┼─ 话题共现            │──→ Map<String, Object>
                            ├─ 用户动机            │
                            └─ 句子结构            │
                                    ↓
                          HTTP POST / JSON (localhost:5000)
                                    ↓
                    Flask wordcloud_service.py (analyze_topic 端点)
                                    ↓
                          DeepSeek API (deepseek-chat)
                                    ↓
                    结构化 Markdown 报告（数据概览 → 核心话题 → 建议）
                                    ↓
                          Java 缓存 30 分钟 → 前端展示

    容错机制: DeepSeek 不可用 → 自动降级为旧版统计报告 (buildFallbackReport)
              评论为空      → 跳过不调 Agent
```

---

## 🚀 快速启动

### 方式一：Docker 一键启动（推荐）

```bash
cp .env.example .env          # 可选：按需修改端口/密码
docker compose up -d --build
```

启动后访问：

| 服务 | 地址 |
|------|------|
| 前端页面 | http://localhost:5173 |
| 后端 API | http://localhost:8080/api |
| Swagger 文档 | http://localhost:8080/swagger-ui.html |

> 本机 3306 / 3000 端口已被占用，故 MySQL 映射至 3307、前端映射至 5173。
> 完整说明见 **[Docker 部署指南](docker/DOCKER部署指南.md)**。

### 方式二：本地开发

#### 1. 环境要求

- JDK 8+
- Node.js 18+
- Python 3.10+
- MySQL 8.0

### 2. 数据库

```sql
-- 直接执行项目根目录下的建库建表脚本
source coolpilot_insight.sql;
```

或手动执行：
```sql
CREATE DATABASE IF NOT EXISTS coolpilot_insight DEFAULT CHARSET utf8mb4;
use coolpilot_insight;
-- 然后执行 coolpilot_insight.sql 中的建表语句
```

插入管理员（可选）：
```sql
INSERT INTO coolpilot_insight.user (username, phone, password, role, created_at)
VALUES ('管理员', '13800000000', MD5('123456'), 1, NOW());
```

### 3. 配置 DeepSeek API Key（AI 话题分析）

```bash
# 方式一：编辑 Py_Data/clean/.env 文件
# 已创建，填入你的 API Key 即可
DEEPSEEK_API_KEY=sk-your-key-here

# 方式二：设置系统环境变量
# Windows: set DEEPSEEK_API_KEY=sk-your-key-here
```

> 不配置 API Key 不影响平台使用，核心话题将自动回退为传统统计分析报告。

### 4. 安装依赖 & 启动服务

```bash
# 终端1: Python 依赖
cd Py_Data/clean
python -m pip install -r requirements.txt

# 终端2: 后端（自动拉 Flask 分词服务 + 启动 Spring Boot）
cd dy-backend
mvn spring-boot:run

# 终端3: 前端
cd dy-frontend
npm install
npm run dev
```

### 5. 访问

| 服务 | 地址 |
|------|------|
| 前端页面 | `http://localhost:3000` |
| 后端 API | `http://localhost:8080` |
| Swagger 文档 | `http://localhost:8080/swagger-ui.html` |
| Flask 分词服务 | `http://127.0.0.1:5000`（内部分调用，无需直接访问） |

> **提示**：所有路径与外部地址均已支持环境变量覆盖，本地开发无需任何额外配置。
> 常用变量清单见 [Docker 部署指南 · 关键设计说明](docker/DOCKER部署指南.md)。

---

## 🧩 已实现的优化（v2.0+）

1. **AI 核心话题分析** — DeepSeek 驱动，5 维度综合分析 + 结构化报告输出
2. **BCrypt 密码加密** — 新密码 BCrypt 加盐，老 MD5 兼容过渡
3. **useECharts 组合式函数** — 消除图表组件 90% 重复代码
4. **SLF4J 日志 + 全局异常** — 结构化日志 + 统一错误追踪
5. **Swagger API 文档** — 接口在线查看和调试
6. **Flask 常驻分词服务** — jieba 预加载，响应从 2s 降到 50ms
7. **3 层容错降级** — AI Agent 不可用时自动回退，页面永不崩溃
8. **Markdown 格式化报告** — 标准 Markdown 渲染，支持 Emoji 层级、加粗、列表

---

## 🔜 后续优化方向

| 优先级 | 优化项 | 预期收益 |
|--------|--------|----------|
| P1 | 评论表预聚合 + 物化视图 | 分析查询 500ms → 20ms |
| P1 | Access Token + Refresh Token 续期 | 安全性提升 |
| P2 | 多模块 Maven 拆分 | 代码隔离、可复用 |
| P2 | API 限流 (Sentinel) | 防刷保护 |
| P2 | 单元测试 + 集成测试 | 回归保障 |
| P3 | 配置中心 (Nacos) | 动态配置 |
| P3 | 对象存储替代本地 CSV | 文件持久化 |
| P3 | 暗色模式 + Design Token | 视觉体验 |
| P3 | Docker Compose 一键部署 | 运维简化 |

---

## 📄 相关文档

- [需求设计文档](需求设计（抖音多视频评论分析与可视化平台）.md) — 完整的需求分析、功能设计、数据表设计
- [Docker 部署指南](docker/DOCKER部署指南.md) — 一键容器化启动、架构说明、常见问题
- [爬虫维护笔记](抖音爬虫维护笔记.md) — 爬虫故障排查指南
- [简洁版使用指南](简洁版使用指南.txt) — 快速上手指南
