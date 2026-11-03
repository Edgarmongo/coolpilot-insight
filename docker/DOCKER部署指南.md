# Docker 快速启动指南

本项目已完整容器化，一条命令即可启动全部服务。

---

## 一、一键启动

```bash
cd douyin_opinion_analysis

# 可选：复制环境变量文件按需修改
cp .env.example .env

# 构建并后台启动
docker compose up -d --build
```

启动完成后：

| 服务 | 地址 | 说明 |
|------|------|------|
| 前端页面 | http://localhost:5173 | Nginx 托管，已配置 `/api` 反代 |
| 后端 API | http://localhost:8080/api | Spring Boot |
| Swagger 文档 | http://localhost:8080/swagger-ui.html | 接口调试 |
| Flask 分词服务 | http://localhost:5000 | 内部服务，通常无需直接访问 |
| MySQL | localhost:3307 | 容器内为 3306 |

> **端口说明**：本机 3306、3000 已被占用，因此 MySQL 映射到 **3307**、前端映射到 **5173**。
> 如需修改，编辑 `.env` 中的 `MYSQL_HOST_PORT` / `FRONTEND_HOST_PORT` / `BACKEND_HOST_PORT`。

### 验证是否就绪

```bash
# 查看容器状态（等待全部 healthy/Up）
docker compose ps

# 查看后端启动日志
docker compose logs -f backend
```

### 默认管理员账号

`dy_comment.sql` 初始化时已自动创建管理员：

- 手机号：`13800000000`（或 `10000000000`）
- 密码：`123456`

普通用户可在前端「注册」页自行创建。

---

## 二、常用运维命令

```bash
# 启动 / 停止 / 重启
docker compose up -d
docker compose stop
docker compose restart backend

# 查看日志
docker compose logs -f backend
docker compose logs -f flask

# 仅重新构建某个服务
docker compose build backend && docker compose up -d backend

# 完全清除（含数据库数据卷）
docker compose down -v
```

---

## 三、容器架构

```
┌─────────────┐   /api 反代    ┌──────────────┐
│  frontend   │ ─────────────▶ │   backend    │
│  (nginx)    │                │ Spring Boot  │
│   :80       │                │  + Python    │
└─────────────┘                │  + Chrome    │
                               └──────┬───────┘
                    采集/清洗脚本调用   │ HTTP
                                      ▼
      ┌───────────┐          ┌──────────────┐      ┌────────┐
      │  爬虫CSV  │◀─────────│    flask     │      │ mysql  │
      │ Datas 目录 │          │ 分词 + AI    │─────▶│ :3306  │
      └───────────┘          └──────────────┘      └────────┘
```

**四个服务职责：**

| 服务 | 镜像 | 职责 |
|------|------|------|
| `mysql` | mysql:8.0 | 数据存储，首次启动自动执行 `dy_comment.sql` |
| `flask` | python:3.12-slim | jieba 分词 + DeepSeek AI 话题分析 |
| `backend` | eclipse-temurin:8-jre-jammy | Spring Boot 主服务，内置 Python/Chrome 以执行爬虫与清洗脚本 |
| `frontend` | node:18-alpine → nginx:alpine | 构建前端静态产物并托管，反代 `/api` |

---

## 四、关键设计说明

### 1. 路径与地址全部环境变量化

原代码中存在大量 Windows 硬编码路径（如 `.venv/Scripts/python.exe`、`D:\idea_workspace\...\Datas`），
容器内必然失效。现已统一抽到 `AppProperties`（Java）与环境变量（Python）：

| 环境变量 | 用途 | 本地默认值 |
|---------|------|-----------|
| `APP_PROJECT_ROOT` | 项目根目录 | 当前工作目录的上一级 |
| `PYTHON_EXE` | Python 解释器 | 自动探测 `.venv` |
| `PY_DATA_DIR` | 爬虫脚本目录 | `<root>/Py_Data` |
| `PY_CLEAN_DIR` | 清洗脚本目录 | `<root>/Py_Data/clean` |
| `PY_DATAS_DIR` | 采集 CSV 目录 | `<root>/Py_Data/Datas` |
| `FLASK_BASE_URL` | 分词服务地址 | `http://127.0.0.1:5000` |
| `FLASK_AUTOSTART` | 是否由后端拉起 Flask | `true` |
| `DB_HOST` / `DB_PORT` / `DB_USER` / `DB_PASSWORD` / `DB_NAME` | 数据库连接 | `localhost` / `3306` / `root` / `root` / `dy_comment` |

**这套改造不影响本地开发**：不设任何环境变量时，行为与改造前完全一致，
`mvn spring-boot:run` 仍会自动拉起 Flask 并调用本地 Chrome。

### 2. 爬虫在容器内的运行方式

Chrome 在容器中无 GUI，必须 headless 运行，且 root 用户下需关闭沙箱：

```
CHROME_HEADLESS=1
CHROME_NO_SANDBOX=1
CHROME_BROWSER_PATH=/usr/bin/google-chrome
```

`CHROME_USER_DATA_DIR` 指向持久化卷 `/app/chrome-data`，
用于保留抖音登录态 —— **首次采集建议先手动登录一次**，之后登录态会随卷保留。

> ⚠️ 抖音对无头浏览器有风控。若采集失败（拿不到评论），参见第六节。

### 3. 前端用 Nginx 而非 Vite dev server

生产环境不需要开发服务器。Nginx 托管构建产物并反代 `/api`，
前后端同源，天然规避 CORS。Vue Router 使用 history 模式，
故配置了 `try_files $uri $uri/ /index.html`，刷新页面不会 404。

### 4. 数据库字段自动迁移

后端启动时会自动检查并补齐 `comment` 表的新增字段（如 `douyin_id` / `douyin_uid` / `sec_uid`），
已有数据库无需删库重建，日志中会输出：

```
[Schema] 已自动补齐字段: comment.douyin_id, comment.douyin_uid, comment.sec_uid
```

若自动迁移被跳过（如数据库尚未就绪），可手动执行幂等迁移脚本：

```bash
docker compose exec -T mysql mysql -uroot -proot dy_comment < docker/migrate_user_fields.sql
```

该脚本可重复执行，已存在的字段会自动跳过。

---

## 五、DeepSeek AI 话题分析（可选）

不配置不影响平台使用，核心话题会自动降级为传统统计报告。

```bash
# 在 .env 中填写
DEEPSEEK_API_KEY=sk-your-key-here
```

配置后需重启 flask 服务使环境变量生效：

```bash
docker compose up -d --force-recreate flask
```

---

## 六、常见问题

### 爬虫采集不到评论

抖音风控会拦截无头浏览器。按顺序尝试：

1. **提升容器资源**：`docker compose up -d --scale backend=1` 并在 `.env` 调大内存
2. **保留登录态**：进入容器手动登录一次，登录态保存在 `chrome-data` 卷
   ```bash
   docker compose exec backend bash
   # 临时以有头模式启动 Chrome 并完成登录
   ```
3. **改用本机采集**：既然采集依赖真实浏览器与登录态，本机执行往往更可靠
   ```bash
   # 本机执行（需先 pip install -r Py_Data/clean/requirements.txt）
   python Py_Data/douyin_comment.py <视频ID>
   # 产出的 CSV 放到 Py_Data/Datas/，容器内清洗服务会自动读取
   ```

### 后端日志报「Flask 分词服务启动失败」

容器内 `FLASK_AUTOSTART=false`，这是**正常**的 —— Flask 由独立容器提供。
若确实词云不可用，检查：

```bash
docker compose logs flask
docker compose exec backend curl -s http://flask:5000/wordcloud?video_id=test
```

### 数据库连不上

```bash
docker compose logs mysql
docker compose exec backend curl -s "http://localhost:8080/api/analysis/summary?video_id=test"
```

MySQL 首次启动会自动导入 `dy_comment.sql`（约 30 秒）。
若修改过该脚本，需 `docker compose down -v` 后重建数据卷。

### 端口被占用

改 `.env` 中的 `MYSQL_HOST_PORT` / `BACKEND_HOST_PORT` / `FRONTEND_HOST_PORT`，
然后 `docker compose up -d`。

### 构建慢 / 拉取镜像失败

Dockerfile 已配置国内镜像（阿里云 Maven、阿里云 PyPI、npmmirror）。
若仍失败，检查 `~/.docker/daemon.json` 中的 `registry-mirrors` 是否生效。

---

## 七、数据持久化

| 内容 | 存储位置 |
|------|---------|
| MySQL 数据 | 命名卷 `mysql-data` |
| 采集的 CSV | 宿主机 `./Py_Data/Datas/`（bind mount） |
| Chrome 登录态 | 命名卷 `chrome-data` |

`docker compose down` 不会删除这些数据；`docker compose down -v` 会删除命名卷。
