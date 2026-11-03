# 02 · ARCHITECTURE

> 依据真实代码与 `docker-compose.yml` 绘制，未添加任何实际不存在的组件。
> 证据等级：**A**（已通过运行验证的组件标注 `[已验证]`）

---

## 1. 部署态架构（当前运行中，4 容器）

```
┌──────────────────────────────────────────────────────────────────────┐
│  宿主机 (Windows / Docker Desktop)                                   │
│  端口占用: 3306(MySQL本地) 3000(前端本地)  →  容器改用 3307 / 5173  │
└──────────────────────────────────────────────────────────────────────┘

   用户浏览器
       │  http://localhost:5173
       ▼
┌──────────────────────────────────────────────────────────────────────┐
│ dy-frontend  [nginx:alpine]                       容器网络 dy-net   │
│   /usr/share/nginx/html  ← vite build 产物                        │
│   /geo/china.json       ← 本地地图数据                             │
│   location /api/  →  proxy_pass http://backend:8080/api/           │
│   location /     →  try_files $uri $uri/ /index.html (SPA history) │
└──────────────────────────────────────────────────────────────────────┘
       │                    ▲                        │
       │ /api              │ /api (反向代理)         │
       ▼                    │                        │
┌──────────────────────────────────────────────────────────────────────┐
│ dy-backend  [eclipse-temurin:8-jre-jammy]                           │
│   java -jar app.jar  (Spring Boot 2.7.18, PID 1)                   │
│   ├── REST API 26 端点                                             │
│   ├── JWT 拦截器 + ThreadLocal 用户上下文                          │
│   ├── MemoryCache (30min) / TokenStore (内存)                       │
│   └── ProcessBuilder → Python 脚本 (同容器内)                       │
│         ├── douyin_comment.py  (采集)                              │
│         └── clean_main.py      (清洗入库)                           │
│   /app/Py_Data/Datas  ←── bind mount ──┐                           │
│   /app/chrome-data    ←── 命名卷 chrome-data                      │
│   Python 3.10 + Chrome 156 (headless)                             │
└──────────────────────────────────────────────────────────────────────┘
       │                                   │
       │ HTTP :5000                        │ JDBC :3306
       ▼                                   ▼
┌──────────────────────────┐   ┌────────────────────────────┐
│ dy-flask                 │   │ dy-mysql  [mysql:8.0]      │
│ [python:3.12-slim]       │   │   3 张表: user/comment/    │
│ wordcloud_service.py     │   │            analysis_result │
│   /wordcloud  (jieba)    │   │   命名卷 mysql-data        │
│   /analyze_topic (AI)    │   └────────────────────────────┘
│         │                │
│         ▼ HTTPS
│   DeepSeek API (deepseek-chat)
```

---

## 2. 组件交互矩阵

| 源 | 目标 | 协议 | 代码位置 | 状态 |
|----|------|------|----------|------|
| 浏览器 | frontend | HTTP | Nginx :80 | [已验证] |
| frontend | backend | HTTP `/api` | `docker/nginx.conf` | [已验证] |
| backend | douyin_comment.py | **进程调用** | `CollectServiceImpl:46` | [已验证] |
| backend | clean_main.py | **进程调用** | `CleanServiceImpl:55` | [已验证] |
| backend | flask | HTTP :5000 | `AnalysisServiceImpl:351` | [已验证] |
| backend | MySQL | JDBC | `application.yml` | [已验证] |
| flask | MySQL | JDBC(pymysql) | `wordcloud_service.py:177` | [已验证] |
| clean_main | MySQL | JDBC(pymysql) | `clean_main.py:34` | [已验证] |
| flask | DeepSeek | HTTPS | `wordcloud_service.py:138` | [已验证] |
| backend | frontend→flask | — | `FLASK_BASE_URL=http://flask:5000` | [已验证] |
| 宿主机 | backend/Py_Data/Datas | bind mount | `docker-compose.yml` | [已验证] |

---

## 3. 数据流全景

```
【采集链路】
用户输入链接/ID
   → CollectUtils.extractVideoId()  提取数字ID（兼容5种形式）
   → CollectServiceImpl.startCollect() 起裸线程
   → ProcessBuilder: python douyin_comment.py <id> <max>
   → DrissionPage 启动 Chrome(headless) → 打开 /video/{id}
   → 拦截 aweme/v1/web/comment/list → 滚动容器翻页
   → 内存累积 data_list → 末尾一次性写 Datas/{id}.csv

【清洗链路】
用户在前端勾选 CSV
   → CleanController.run() → CleanServiceImpl
   → ProcessBuilder: python clean_main.py <files>
   → 读 CSV → 批内去重(昵称,内容,日期) → SnowNLP 情感标注
   → 查库取已有(昵称,内容) → 跨批次去重 → INSERT MySQL
   → 删除本地 CSV

【分析链路】
Dashboard 页面
   → /api/analysis/{summary,region,sentiment,trend,top-comments,wordcloud,sankey}
   → AnalysisServiceImpl 直接查 comment 表（30min MemoryCache）
   → wordcloud/sankey 需调用 flask → jieba 分词
   → /api/analysis/topic → computeTopicDims(5维度)
   → flask /analyze_topic → DeepSeek → Markdown 报告
   → 缓存 30 分钟
```

---

## 4. 架构特征与约束

### 4.1 进程耦合（关键约束）

Java → Python 采用 `ProcessBuilder` 而非 HTTP/RPC，**两者必须共享文件系统**：

```
dy-backend 容器
  ├── /app/app.jar
  ├── /app/Py_Data/          ← 与 Java 同容器
  └── /app/Py_Data/Datas     ← bind mount 到宿主机
```

**影响**：无法把采集脚本拆成独立容器做独立扩缩容；无法用消息队列解耦。

### 4.2 Chrome 单例约束

所有采集任务共用 `chrome-data` 命名卷作为 Chrome user-data-dir。
Chrome 的 `SingletonLock` 机制要求**同一 profile 同时只能有一个实例**。

**影响**：并发采集必然互相干扰（详见 `03_COLLECTION_AUDIT.md`）。

### 4.3 双层缓存

| 层 | 位置 | TTL | 重启后 |
|----|------|-----|--------|
| 内存缓存 | `MemoryCache` (ConcurrentHashMap) | 30 分钟 | 丢失 |
| Token 存储 | `TokenStore` (ConcurrentHashMap) | 24 小时 | **丢失（所有用户被登出）** |
| 分析结果 | `analysis_result` 表 | 持久化 | 保留 |

### 4.4 数据隔离模型

`user_id` 列 + `applyUserFilter()`：

```java
if (userId != null && (role == null || role != 1)) {
    wrapper.eq(Comment::getUserId, userId);   // 非管理员只看自己的
}
```

管理员（role=1）可见全部数据。

---

## 5. 架构中缺失的基础设施

| 能力 | 现状 | 架构影响 |
|------|------|---------|
| 消息队列 | 无 | 任务执行与请求强耦合，只能用裸线程 |
| 分布式锁 | 无 | 无法防重复调度，无法协调多实例 |
| 任务持久化 | 无（内存 Map） | 状态重启即丢，无历史可查 |
| 分布式缓存 | 无 | 多实例部署时 MemoryCache/TokenStore 不共享 |
| 对象存储 | 无 | CSV 走本地磁盘 + bind mount |
| 日志聚合 | 无 | 仅容器 stdout，`docker compose logs` 查看 |
| 链路追踪 | 无 | Java→Flask→DeepSeek 三跳无统一 ID |
| 健康检查 | 有（容器级） | 无应用级就绪探针（Flask/Java 各自 TCP 探针） |

---

## 6. 架构优势（应保留）

1. **三层职责清晰**：Java 管业务与鉴权，Python 管采集与 NLP，Flask 管 AI 编排
2. **容器化完整**：4 服务一条 `docker compose up -d` 即可运行，依赖已本地化
3. **进程调用简单可靠**：采集脚本无需独立部署与版本协调
4. **前端同源**：Nginx 反代规避 CORS
5. **无状态可水平扩展**：Java 后端本身无状态（除内存缓存）

---

## 7. 架构瓶颈（须在演进中解决）

| 瓶颈 | 表现 | 证据 |
|------|------|------|
| 采集并发度 = 1 | Chrome profile 独占，无法并发 | 实测 3 并发仅 1 成功 |
| 任务状态易失 | 重启后状态丢失 | `STATUS_MAP` 为内存 Map |
| 数据无断点 | 中断后已采数据全丢 | 实测 kill -9 后 CSV 为空 |
| 无增量 | 每次全量重拉，风控暴露高 | 无 cursor 持久化 |
| 无关键词发现 | 需人工提供视频 | 全项目无搜索解析代码 |
| 无视频表 | 视频仅作为 comment 的字符串列存在 | 3 张表无 video 表 |
