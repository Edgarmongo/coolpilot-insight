# 14 · TARGET_ARCHITECTURE

> 目标架构设计（仅设计，未实施）
> 原则：复用现有架构 · 不为用技术而用技术 · 不默认重写 Java 后端或 Python 采集 · 不默认引入 Redis/Celery/MQ

---

## 1. 设计原则

| 原则 | 落地方式 |
|------|---------|
| 最大化复用 | 现有 4 容器拓扑、26 个 API、Python 采集脚本、Flask AI 服务全部保留 |
| 不引入新中间件 | 任务调度用 **MySQL 任务表 + 轮询**，不引入 Celery/RabbitMQ |
| 不重写 | Java 后端增量增强，Python 采集脚本增量增强 |
| 按需引入 | 仅在实测成为瓶颈时才考虑向量库等（当前阶段明确不引入） |
| 数据底座先行 | 先补 `cid`/`video`/`collect_task` 三张表，再上 AI 能力 |

---

## 2. 目标架构图

```
┌────────────────────────────────────────────────────────────────────────┐
│  浏览器                                                                │
│  总览 · 采集任务中心 · 关键词管理 · 视频管理 · 评论管理                 │
│  AI 需求分析 · 报告中心 · 用户管理                                      │
└────────────────────────────┬───────────────────────────────────────────┘
                             │ HTTP /api
┌────────────────────────────▼───────────────────────────────────────────┐
│  dy-frontend  [nginx + Vue3]                                           │
│  · /api 反代 → backend                                                 │
│  · /geo 本地地图                                                       │
│  · 任务中心页面（轮询任务状态）                                          │
└────────────────────────────┬───────────────────────────────────────────┘
                             │
┌────────────────────────────▼───────────────────────────────────────────┐
│  dy-backend  [Spring Boot 2.7 / JDK8]        ← 保留，仅增强             │
│  ┌────────────────────────────────────────────────────────────┐        │
│  │ REST API 层（现有 26 个 + 新增任务/关键词/视频/洞察）        │        │
│  ├────────────────────────────────────────────────────────────┤        │
│  │ Service 层                                                 │        │
│  │  · CollectService   → 任务调度（ThreadPoolExecutor core=2）  │        │
│  │  · AnalysisService  → 统计 + 洞察编排                      │        │
│  │  · CleanService     → 异步化清洗                            │        │
│  ├────────────────────────────────────────────────────────────┤        │
│  │ @Scheduled TaskScheduler ──── 轮询 collect_task(PENDING)     │        │
│  ├────────────────────────────────────────────────────────────┤        │
│  │ MyBatis-Plus Mapper                                          │        │
│  └────────────────────────────────────────────────────────────┘        │
│         │  ProcessBuilder            │ HTTP                             │
│         ▼                           ▼                                  │
│  ┌──────────────────┐      ┌────────────────────┐                      │
│  │ Python 采集器     │      │ Flask AI 服务       │                      │
│  │ (容器内, 不变)    │      │ (容器内, 增强)      │                      │
│  │ · 搜索发现(新增)  │      │ · /wordcloud        │                      │
│  │ · 评论采集        │      │ · /analyze_topic    │                      │
│  │ · 回复采集(新增)  │      │ · /analyze_comments(新增)│                │
│  │ · append 落盘    │      │ · /analyze_insights(新增)│                │
│  │ · 独立 profile    │      │ · Semaphore 限流    │                      │
│  └──────────────────┘      └─────────┬──────────┘                      │
│         │                            │ HTTPS                           │
│         ▼                            ▼                                 │
│    Datas 目录                  DeepSeek API                             │
└─────────────────────────────────┬────────────────────────────────────────┘
                                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│  dy-mysql  [MySQL 8.0]                          ← 新增 4 张表           │
│  现有: user / comment / analysis_result                                  │
│  新增: video · keyword · collect_task · video_insight                     │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 分层设计

### 3.1 数据采集层

#### 3.1.1 关键词管理

```
keyword 表：id · keyword · platform · filters(JSON) · status
             · last_discovered_at · discovered_count · user_id
```

| 能力 | 实现 |
|------|------|
| 关键词 CRUD | REST API + 前端页面 |
| 定时发现 | `@Scheduled` + `collect_task` 表（type=KEYWORD_SEARCH） |
| 手动发现 | POST /api/keyword/{id}/discover |
| 过滤条件 | 发布时间范围、最低点赞、最小时长（存 JSON） |
| 去重 | `video` 表以 `(platform, aweme_id)` 唯一，已存在则跳过 |

#### 3.1.2 视频发现（新增能力）

```
douyin_search.py  (Py_Data/)
  → 打开 /search/{keyword}
  → listen.start("aweme/v1/web/general/search/single/")
  → 滚动触发翻页
  → 提取 aweme_info: aweme_id / desc / author / statistics / create_time
  → 过滤条件在 Python 侧应用
  → 输出 JSON 交 Java 落 video 表
```

**复用现有能力**：完全沿用 `douyin_comment.py` 的模式
（`ChromiumPage` + `listen.start/wait` + `build_options()`），
**不引入新框架**。

**复用 `preflight_check`**：搜索页结构变化时同样体检。

#### 3.1.3 评论采集（含回复）

```
douyin_comment.py 增强
  → 采集一级评论（现有）
  → 新增：读取接口的 reply_comment 字段采集楼中楼
  → 新增：每页 append 写入 CSV（而非末尾一次性）
  → 新增：写入 cid / reply_id / parent_cid / reply_count
  → 新增：--cursor 参数支持断点续采
  → 新增：--profile 参数支持独立 profile（并发安全）
```

#### 3.1.4 任务调度

**不引入 MQ**，用数据库任务表 + 轮询：

```java
@Component
public class TaskScheduler {
    @Scheduled(fixedDelay = 5000)
    public void poll() {
        // 抢占式：UPDATE ... WHERE status='PENDING' LIMIT n
        // 用 MySQL 行锁保证多实例安全（SELECT ... FOR UPDATE SKIP LOCKED）
        // 提交到 ThreadPoolExecutor(core=2, max=3)
    }
}
```

**为什么不需要 Redis/Celery**：
- 任务由人工/定时触发，QPS 极低
- MySQL 8.0 支持 `SKIP LOCKED`，本身就是分布式队列
- 引入 MQ 会增加运维复杂度而无收益

**并发安全方案**（关键）：

| 方案 | 说明 | 建议 |
|------|------|------|
| A. 全局串行（并发=1） | 最简单，绝对安全 | ✅ **首选** |
| B. 每任务独立 profile | `chrome-data/task-{id}`，可并发 | 需评估磁盘与内存 |
| C. 共享 profile + 端口隔离 | 不推荐（SingletonLock 冲突已实测） | ❌ |

**建议先做 A，需要并发时再做 B。**

### 3.2 数据处理层

```
清洗（clean_main.py 增强）
  → 保留现有双层去重
  → 新增：cid 唯一索引兜底（INSERT IGNORE）
  → 新增：回复数据入库
  → 新增：清洗异步化（提交任务，不阻塞 HTTP）
  → 新增：数据质量检查（空值率、重复率、异常值）
```

```
标准化
  → 地区归一化（现有 normalizeRegion）
  → 新增：video 表维护视频元数据快照
  → 新增：评论与视频关联（video_id 外键）
```

### 3.3 AI 分析层

```
Flask AI 服务增强
  /wordcloud              现有
  /analyze_topic          现有（Markdown 报告，保留）
  /analyze_comments       新增：批量评论 → 结构化 JSON（评论级）
  /analyze_insights       新增：视频级结构化洞察
  /aggregate_keyword      新增：关键词级聚合

工程保障
  → threading.Semaphore(3) 限流
  → Token 计数器 + 每日预算
  → 结果持久化到 video_insight 表
  → Prompt 版本号管理
```

### 3.4 业务应用层

| 新页面 | 依赖 API | 说明 |
|-------|---------|------|
| 采集任务中心 | `collect_task` CRUD + 状态轮询 + 日志 | 任务列表/详情/进度/日志/重试/取消 |
| 关键词管理 | `keyword` CRUD + 发现触发 | 关键词列表/过滤条件/发现结果 |
| 视频管理 | `video` CRUD | 视频列表/元数据/已采集状态 |
| 多视频对比 | 多视频聚合分析 API | 并排对比需求/痛点/情感 |
| AI 需求分析 | `/analysis/insights` | 结构化需求/痛点/意向卡片 |
| 报告中心 | 报告列表/导出 | 历史报告检索与导出 |

---

## 4. 数据模型演进

### 4.1 新增表

```sql
-- 视频元数据与发现台账
CREATE TABLE video (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  platform      VARCHAR(20)  NOT NULL DEFAULT 'douyin',
  aweme_id      VARCHAR(32)  NOT NULL COMMENT '抖音视频ID',
  title         TEXT         NULL,
  author_name   VARCHAR(100) NULL,
  author_id     VARCHAR(64)  NULL,
  duration      INT          NULL COMMENT '时长(秒)',
  like_count    INT          NOT NULL DEFAULT 0,
  comment_count INT          NOT NULL DEFAULT 0,
  share_count   INT          NOT NULL DEFAULT 0,
  cover_url     VARCHAR(500) NULL,
  published_at  DATETIME     NULL,
  keyword_id    BIGINT       NULL COMMENT '来源关键词',
  discovered_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_platform_aweme (platform, aweme_id),
  INDEX idx_keyword (keyword_id),
  INDEX idx_published (published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频表';

-- 关键词
CREATE TABLE keyword (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  keyword       VARCHAR(200) NOT NULL,
  platform      VARCHAR(20)  NOT NULL DEFAULT 'douyin',
  filters       TEXT         NULL COMMENT '过滤条件JSON',
  status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  schedule_cron VARCHAR(100) NULL COMMENT '定时表达式',
  last_run_at   DATETIME     NULL,
  discovered_cnt INT         NOT NULL DEFAULT 0,
  user_id       BIGINT       NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_keyword (user_id, keyword, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键词表';

-- 采集任务（见 04 号文档 5.1）
-- collect_task

-- 视频级 AI 洞察（持久化，避免重复付费）
CREATE TABLE video_insight (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  video_id      VARCHAR(32) NOT NULL,
  user_id       BIGINT      NULL,
  insight_type  VARCHAR(30) NOT NULL DEFAULT 'STRUCTURED',
  content       MEDIUMTEXT  NOT NULL COMMENT '结构化JSON',
  prompt_version VARCHAR(20) NULL,
  model         VARCHAR(30)  NULL,
  token_used    INT         NOT NULL DEFAULT 0,
  analyzed_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_video_type (video_id, insight_type),
  INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频AI洞察';
```

### 4.2 comment 表演进

```sql
ALTER TABLE comment
  ADD COLUMN cid        VARCHAR(32)  NULL COMMENT '抖音评论唯一ID' AFTER id,
  ADD COLUMN reply_id   VARCHAR(32)  NULL COMMENT '父评论ID',
  ADD COLUMN parent_cid VARCHAR(32)  NULL COMMENT '所属一级评论ID',
  ADD COLUMN reply_count INT         NOT NULL DEFAULT 0 COMMENT '回复数',
  ADD COLUMN is_reply   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否楼中楼回复';
ALTER TABLE comment ADD UNIQUE KEY uk_cid (cid);
ALTER TABLE comment ADD INDEX idx_user_id (user_id);
```

### 4.3 演进后表关系

```
keyword 1───N video 1───N comment
                        └──N comment(自引用: parent_cid → cid)

user    1───N collect_task
user    1───N keyword
user    1───N comment (user_id)
video   1───1 video_insight
```

---

## 5. 关键技术决策与权衡

| 决策点 | 选择 | 备选 | 理由 |
|-------|------|------|------|
| 任务队列 | **MySQL 任务表 + @Scheduled 轮询** | Celery/RabbitMQ | 任务量低；避免运维复杂度；MySQL 8 支持 SKIP LOCKED 天然支持多实例 |
| 分布式锁 | **MySQL 行锁（FOR UPDATE SKIP LOCKED）** | Redis 分布式锁 | 同上；且避免引入 Redis 依赖 |
| Token 共享 | **JWT 为主 + DB 存储可选** | 引入 Redis | 当前 `TokenStore` 内存导致重启登出；方案二：Token 落 `user_token` 表或直接去掉服务端校验（纯 JWT + 短期过期） |
| 采集并发 | **先串行（=1）** | 独立 profile 并发 | 实测共享 profile 冲突；串行最安全，必要时再演进 |
| 爬虫框架 | **继续用 DrissionPage** | Playwright/Selenium | 已有实现可用；换框架无收益 |
| AI 调用 | **继续用 Flask + OpenAI SDK** | LangChain | 直白可控；LangChain 对单次调用无收益 |
| 向量检索 | **暂不引入** | Milvus/PGVector | RAG 非必需（见 08 号文档 4.1） |
| 主题聚类 | **LLM 抽标签 + 频次聚合** | KMeans/嵌入聚类 | 标签有业务含义，可跨视频聚合；KMeans 簇号无含义 |
| 后端语言 | **不重写 Java** | 换 Go/Node | 现有 26 API 稳定 |
| 采集语言 | **不重写 Python** | — | 现有脚本可用 |

---

## 6. 架构演进路径

```
现状（4容器 / 3表 / 26 API）
  │
  ├── 阶段1（P0 可靠性）：任务表 + 状态落库 + append 落盘 + 并发串行化
  │     → 不加表也能做的：append 落盘、并发检查、getVideoList 优化
  │
  ├── 阶段2（P0 数据底座）：comment.cid + 唯一索引 + video 表 + keyword 表
  │
  ├── 阶段3（P1 AI 能力）：评论级结构化分析 + 视频级洞察 + 洞察持久化
  │
  ├── 阶段4（P1 多视频）：videoId 数组 + 任务编排 + 多视频对比
  │
  └── 阶段5（P2 业务化）：定时发现 + 行业趋势 + 竞品分析 + 报告中心
```

**阶段 1 与 2 可并行**（前者改代码，后者改表结构）。

---

## 7. 明确不做的事（避免过度设计）

| 不做 | 理由 |
|------|------|
| 引入 Redis | 任务量不匹配；MySQL 足够 |
| 引入消息队列 | 同上 |
| 引入 Elasticsearch | 评论检索用 MySQL LIKE 已够（万级） |
| 引入向量数据库 | RAG 非必需 |
| 重写 Java 后端 | 现有实现稳定，改造增量即可 |
| 重写 Python 采集 | DrissionPage 方案验证有效 |
| 微服务拆分 | 4 个容器已是合理粒度 |
| 引入工作流引擎（Airflow等） | 任务调度用 @Scheduled 足够 |
| 多平台适配抽象层 | 阶段太早（当前只做抖音） |
