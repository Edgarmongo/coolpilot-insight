# 项目长期记忆 — 抖音评论分析与可视化平台

## 项目概况
Spring Boot 2.7 (Java 8) + Vue 3 + Python(Flask/jieba/DrissionPage) + MySQL 8 的抖音评论分析平台。

## 容器化架构（2026-11-03 完成）
`docker-compose.yml` 四服务：`mysql` / `flask`(分词+AI) / `backend`(Spring Boot，内置 Python+Chrome) / `frontend`(Nginx)。
Dockerfile 位于 `docker/`，部署文档 `docker/DOCKER部署指南.md`。

**关键约束**：
- 本机 3306、3000 已被占用，故容器映射 MySQL→3307、前端→5173。
- Chrome 必须用官方 deb（Ubuntu 22.04 的 chromium 是 snap 包，容器内不可用）。
  下载 139MB 的 deb 需加 `--http1.1 --retry 5 --retry-all-errors`，否则 HTTP/2 流中断（curl 92）。
- 后端镜像需同时具备 JDK8 + Python3 + Chrome 三者，缺一不可。
- Maven 官方源不可达，容器内用 `docker/maven-settings.xml`（阿里云 https 镜像）。
  注意：用户本地 `~/.m2/settings.xml` 指向失效的 http 阿里云地址，本地构建需 `-s` 指定可用 settings。

## 环境变量约定
所有硬编码路径已环境变量化，本地开发不设变量时行为与改造前一致：
`APP_PROJECT_ROOT` / `PYTHON_EXE` / `PY_DATA_DIR` / `PY_CLEAN_DIR` / `PY_DATAS_DIR` /
`FLASK_BASE_URL` / `FLASK_AUTOSTART` / `DB_HOST` / `DB_PORT` / `DB_USER` / `DB_PASSWORD` / `DB_NAME`。

配置入口：`dy-backend/.../config/AppProperties.java`（Java 侧）、各 Python 脚本顶部 `os.environ.get`。

## 已修复的历史缺陷
1. `dy_comment.sql` 第 56 行残留 markdown 代码围栏 ` ``` `，导致 MySQL 初始化语法报错 —— 已删除。
2. `requirements.txt` 缺 jieba/flask/snownlp/DrissionPage —— 已补全。
3. `GlobalExceptionHandler` 把参数缺失等客户端错误归为 500 且不打印堆栈 —— 已拆分 400/500 并加日志。
4. `douyin_comment.py` 的 `SAVE_DIR` 硬编码 `D:\idea_workspace\...` —— 改为脚本相对目录。
5. `config_browser.py` 硬编码本机 Chrome 路径 —— 改为环境变量 + headless 支持。
6. 爬虫链接解析只认 `/video/数字`，搜索页链接 `?modal_id=` 解析失败 —— 新增 `extract_video_id()` 兼容 5 种形式，Java `CollectUtils` 同步。
7. 爬虫没 `dp.quit()` 导致 Chrome 进程泄漏 + SingletonLock 锁死 profile —— 加 `finally: dp.quit()` + `cleanup_stale_locks()`。
8. 采集状态 key 不一致（start 用数字ID / status 用原始URL）—— `CollectController.status` 统一用 `extractVideoId`。
9. `clean_main.py` 入库无跨批次去重，重复采集同一视频会重复入库 —— `insert_to_db` 先查已有 `(昵称,内容)` 集合跳过重复。
10. `DashboardView.vue` 加载视频列表后不自动选中，`currentVideo` 为空 → `loadAll()` 静默 return → 总览全 0。已改为自动选中第一个。
11. `RegionMap.vue` 缺 visualMap → 地图省份不着色；且多 series 单实例会 canvas 渲染为 0 宽高。已改为双实例 + visualMap + 本地 GeoJSON。
12. 采集需补充用户标识（抖音号/UID/sec_uid）→ CSV 表头、clean_main 入库、Java 实体、DB 表结构均已扩展；
    另新增 `SchemaMigration` 启动时自动补列（幂等），已有库无需删库重建。
13. 高赞评论排行加「抖音ID」列 → 注意 `getTopComments` 与 `refreshAnalysis` **两处独立构造** topComments，
    改一处会被另一处覆盖。已抽出公共方法 `buildTopCommentItem()` 供两处复用。

## 后端约定
- `analysis_result.top_comments` 是持久化 JSON 缓存（`analysis_result` 表），
  与 `MemoryCache`（30分钟）是两层缓存。改字段后需调 `/api/analysis/refresh` 才会更新。

## 抖音用户字段说明
评论 API `aweme/v1/web/comment/list` 的 `user` 对象可用字段：
- `unique_id` — 抖音号（对外可见，如 `ymdl58295`），部分用户为空时回退 `short_id`
- `uid` — 内部数字 UID（如 `3847905010520956`）
- `sec_uid` — 安全 UID，拼接主页链接用：`https://www.douyin.com/user/{sec_uid}`
- 顶层 `ip_label` — **IP 归属地标签**（"辽宁""广东"等省级）
  ⚠️ 抖音**不返回用户真实 IP 地址**（隐私保护），只能拿到归属地标签，无法采集真实 IP。

## 前端约定
- 地图 GeoJSON 已本地化到 `dy-frontend/public/geo/china.json`（省名是全称，如"辽宁省"）；
  后端返回的是简称（"辽宁"），前端 `NAME_MAP` 负责转换。
- ECharts 单个实例内混用 bar 与 map series 会导致 grid 冲突、canvas 渲染异常；
  需要并排展示时用**两个独立实例**（见 RegionMap.vue）。

## 调试技巧
后端改动需 18 分钟重建镜像，调试时可用「注入 class」法快速迭代：
本地 `mvn -s <可用settings> compile` → 用 `tools/patch_jar.py`（Python 改 zip）把 target/classes
注入 fat jar → 挂载进容器运行。Windows Git Bash 下 docker 挂载需加 `MSYS_NO_PATHCONV=1`。

**注意**：`Py_Data` 是 `COPY` 进镜像的（非 bind mount），只有 `Py_Data/Datas` 是 bind mount。
改 Python 脚本后必须重建 backend 镜像才生效；改 `Datas` 里的 CSV 则实时生效。

## 已知限制
- 抖音对无头浏览器有风控，容器内采集可能失败；Chrome 登录态存于命名卷 `chrome-data`。
- 采集 CSV 通过 bind mount 挂到宿主机 `Py_Data/Datas/`，可在宿主机用真实浏览器采集后放回该目录。
- 抖音评论接口每页约 5 条、约 3 页后 `has_more=0`（未登录匿名态），一次最多采 15 条左右；
  采集上限设得更高也受此限制。需更多评论时需登录态或换接口。
