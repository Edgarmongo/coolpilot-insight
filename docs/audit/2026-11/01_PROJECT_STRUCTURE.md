# 01 · PROJECT_STRUCTURE

> 审计日期：2026-11-03 ｜ 审计模式：READ ONLY
> 仓库：`git@github.com:suhuideyu/douyin_opinion_analysis.git`（分支 `main`，HEAD `c59fba9`）

---

## 1. 目录总览

```
douyin_opinion_analysis/
├── dy-backend/                  # Java 后端（Spring Boot 2.7 / JDK 8）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/dy/comment/
│       │   ├── DyCommentApplication.java        # 入口
│       │   ├── annotation/RequireRole.java
│       │   ├── config/                          # 6 个配置类
│       │   ├── controller/                      # 6 个 Controller（26 个端点）
│       │   ├── dto/                             # 5 个 DTO
│       │   ├── entity/                          # 3 个实体
│       │   ├── interceptor/                     # JWT 拦截器 + ThreadLocal 上下文
│       │   ├── mapper/                          # 3 个 MyBatis-Plus Mapper
│       │   ├── service/ + service/impl/         # 5 个 Service
│       │   └── utils/                           # 5 个工具类
│       └── resources/application.yml
│
├── Py_Data/                     # Python 数据层（采集 + 清洗 + AI）
│   ├── douyin_comment.py        # ★ 抖音评论采集脚本（核心）
│   ├── config_browser.py        # Chrome 启动配置（headless / profile / 锁清理）
│   ├── clean/
│   │   ├── clean_main.py        # ★ 清洗入库（去重 + 情感标注）
│   │   ├── analysis_engine.py   # 情感分析（SnowNLP）
│   │   ├── wordcloud_service.py # ★ Flask 服务（分词 + DeepSeek Agent）
│   │   ├── wordcloud_gen.py     # 词云生成（单次脚本）
│   │   ├── sentiment_dict/      # 词典（529 词）★ 当前未被使用
│   │   └── requirements.txt
│   └── verify_region_map.py     # 前端渲染验证脚本（调试用）
│
├── dy-frontend/                 # Vue 3 前端（Vite 5）
│   ├── index.html, vite.config.js, package.json
│   ├── public/geo/china.json    # 地图 GeoJSON（569KB，本地化）
│   └── src/
│       ├── main.js, App.vue
│       ├── api/                 # index.js（接口定义）+ request.js（axios 封装）
│       ├── router/index.js      # 6 路由 + 1 管理页
│       ├── stores/user.js       # Pinia：user + app(当前视频)
│       ├── utils/useECharts.js
│       ├── components/          # 7 个图表组件
│       └── views/               # 7 个页面 + admin/
│
├── docker/                      # 容器化配置
│   ├── backend.Dockerfile       # JDK8 + Python3 + Chrome
│   ├── flask.Dockerfile
│   ├── frontend.Dockerfile
│   ├── nginx.conf
│   ├── maven-settings.xml       # 阿里云镜像
│   ├── migrate_user_fields.sql  # 幂等迁移脚本
│   └── DOCKER部署指南.md
│
├── docker-compose.yml           # 4 服务编排
├── dy_comment.sql               # 建库建表
├── .env.example                 # 环境变量示例
├── .dockerignore
├── README.md
├── 抖音爬虫维护笔记.md
├── 采集能力审计报告.md            # 上一轮审计（本文档的前身）
├── 简洁版使用指南.txt
└── 需求设计（抖音多视频评论分析与可视化平台）.md
```

---

## 2. 模块职责与入口

| 模块 | 入口文件 | 职责 |
|------|----------|------|
| Java 后端 | `DyCommentApplication.main()` | REST API；启动时自动拉起 Flask（容器模式由独立 flask 服务承担） |
| 采集脚本 | `Py_Data/douyin_comment.py` | DrissionPage 驱动 Chrome，拦截 `aweme/v1/web/comment/list`，输出 CSV |
| 清洗入库 | `Py_Data/clean/clean_main.py` | 读 CSV → 去重 → SnowNLP 情感标注 → 写 MySQL → 删 CSV |
| Flask AI | `Py_Data/clean/wordcloud_service.py` | `/wordcloud`（jieba 分词）、`/analyze_topic`（DeepSeek） |
| 前端 | `dy-frontend/src/main.js` | Vue3 + Element Plus + Pinia + ECharts |
| 编排 | `docker-compose.yml` | mysql / flask / backend / frontend |

---

## 3. 模块依赖关系

```
Vue3 前端 ──HTTP(/api)──> Spring Boot 后端
                              │
        ┌─────────────────────┼─────────────────────┐
        │ ProcessBuilder       │ HTTP(5000)          │ MyBatis-Plus
        ▼                     ▼                     ▼
  douyin_comment.py     wordcloud_service.py        MySQL 8
  clean_main.py              │                        (3 张表)
        │                     ▼
        └──────────────> DeepSeek API (deepseek-chat)
```

**关键依赖特征**：

- Java → Python 是**进程级调用**（`ProcessBuilder`），非 HTTP，故两者必须在同一容器或共享文件系统
- Java → Flask 是 HTTP，容器间可分离（通过 `FLASK_BASE_URL`）
- Flask → MySQL 直连（`pymysql`），与 Java 各自持有一份连接
- 前端 → 后端由 Nginx 反代 `/api`，同源无 CORS

---

## 4. 技术栈与版本（实测自 pom.xml / package.json / requirements.txt / 镜像）

| 层 | 组件 | 版本 | 依据 |
|----|------|------|------|
| 后端 | Spring Boot | 2.7.18 | `pom.xml` parent |
| 后端 | Java | 8 | `pom.xml` `java.version=1.8` |
| 后端 | MyBatis-Plus | 3.5.5 | `pom.xml` |
| 后端 | mysql-connector-java | 8.0.33 | `pom.xml` |
| 后端 | springdoc-openapi-ui | 1.7.0 | `pom.xml` |
| 后端 | java-jwt (auth0) | 4.4.0 | `pom.xml` |
| 后端 | Druid | 1.2.20 | `pom.xml` |
| 前端 | Vue | ^3.3.0 | `package.json` |
| 前端 | Vite | ^5.0.0 | `package.json` |
| 前端 | Element Plus | ^2.4.0 | `package.json` |
| 前端 | ECharts | ^5.4.0 | `package.json` |
| 前端 | Pinia | ^2.1.0 | `package.json` |
| Python | DrissionPage | 4.1.1.4 | 实测导入版本 |
| Python | SnowNLP | 0.12.3 | `requirements.txt` |
| Python | jieba | 0.42.1 | `requirements.txt` |
| Python | Flask | ≥3.0.0 | `requirements.txt` |
| Python | openai | ≥1.0.0 | `requirements.txt`（兼容 DeepSeek） |
| Python | pymysql | ≥1.0.0 | `requirements.txt` |
| DB | MySQL | 8.0 | `docker-compose.yml` |
| 浏览器 | Google Chrome | 156.0.8078.79 | 容器内实测 |
| 基础镜像 | eclipse-temurin | 8-jre-jammy | `backend.Dockerfile` |
| 基础镜像 | python | 3.12-slim | `flask.Dockerfile` |
| 基础镜像 | node / nginx | 18-alpine / alpine | `frontend.Dockerfile` |
| AI | DeepSeek | deepseek-chat | `wordcloud_service.py` |

---

## 5. 已存在但未使用的组件（重要）

| 组件 | 位置 | 状态 | 说明 |
|------|------|------|------|
| **情感词典** | `Py_Data/clean/sentiment_dict/{positive,negative}_words.txt` | **完全未被引用** | 共 529 词。`analysis_engine.py` 实际用 SnowNLP，词典是死代码。README 称"基于情感词典"与实现不符 |
| `wordcloud_gen.py` | `Py_Data/clean/` | 未被任何服务调用 | 单次跑批脚本，功能已被 `wordcloud_service.py` 覆盖 |
| `useECharts.js` | `dy-frontend/src/utils/` | 部分组件未使用 | 组合式封装，7 个图表组件中仅部分采用 |
| `RegionMap` 条形图 | 前端组件 | 已使用 | 与地图并存的双实例布局 |
| `id-type: auto` | `application.yml` | 生效中 | MyBatis-Plus 主键策略 |

**未引入的技术**：Redis、消息队列（RabbitMQ/Kafka）、Celery、APScheduler、Flyway/Liquibase、Elasticsearch、单元测试框架（`spring-boot-starter-test` 已引入但**无任何测试类**）、CI/CD。

---

## 6. 测试代码盘点

```
find . -path ./node_modules -prune -o -name "*Test*.java" -print   → 无结果
find . -name "test_*.py" -o -name "*_test.py"                      → 无结果
```

**结论：项目没有任何自动化测试**（证据等级 A，基于文件系统扫描）。

---

## 7. Git 工作区状态（审计基线）

- 分支：`main`，HEAD `c59fba9 Update README.md`
- 远程：`origin git@github.com:suhuideyu/douyin_opinion_analysis.git`
- 工作区：**存在未提交修改**（19 个已修改文件 + 新增文件），均为本轮审计**之前**的二次开发产出，非本轮审计产生
- `dy-backend/target/**` 已从 Git 索引移除并加入 `.gitignore`（编译产物不应入库）

---

## 8. 关键文件索引（审计时最常查阅）

| 关注点 | 文件 |
|--------|------|
| 采集核心 | `Py_Data/douyin_comment.py` |
| 浏览器配置 | `Py_Data/config_browser.py` |
| 清洗入库 | `Py_Data/clean/clean_main.py` |
| AI 服务 | `Py_Data/clean/wordcloud_service.py` |
| 情感算法 | `Py_Data/clean/analysis_engine.py` |
| 任务调度 | `dy-backend/.../service/impl/CollectServiceImpl.java` |
| 链接解析 | `dy-backend/.../utils/CollectUtils.java` |
| 鉴权 | `dy-backend/.../interceptor/JwtInterceptor.java`、`utils/TokenStore.java` |
| 配置 | `dy-backend/src/main/resources/application.yml` |
| 编排 | `docker-compose.yml` |
