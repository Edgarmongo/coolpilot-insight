# 04 · TASK_CENTER_AUDIT

> 采集任务中心专项审计
> 结论：**当前系统不具备任务中心。** 仅存在一个临时的、单视频的、内存态的状态标记。

---

## 1. 任务生命周期（5.1）

### 1.1 现有状态定义

`CollectServiceImpl.java:20,32,61-66`：

```java
private static final Map<String, String> STATUS_MAP = new ConcurrentHashMap<>();

STATUS_MAP.put(videoId, "RUNNING");
STATUS_MAP.put(videoId, "SUCCESS");
STATUS_MAP.put(videoId, "FAILED: exit code " + exitCode);
STATUS_MAP.put(videoId, "FAILED: " + e.getMessage());
```

| 目标状态 | 现有支持 | 说明 |
|---------|---------|------|
| PENDING（等待执行） | ❌ | 无队列概念，任务立即起线程 |
| RUNNING | ✅ | 字符串 `"RUNNING"` |
| SUCCESS | ✅ | 字符串 `"SUCCESS"` |
| PARTIAL_SUCCESS | ❌ | 无（也无多视频场景） |
| FAILED | ✅ | 字符串 `"FAILED: ..."`，携带原因 |
| PAUSED | ❌ | 无 |
| CANCELLED | ❌ | 无 |
| （额外）IDLE | ✅ | `getOrDefault(videoId, "IDLE")` |

**共 4 个状态**：IDLE / RUNNING / SUCCESS / FAILED。

### 1.2 状态流转

```
        startCollect()
IDLE ──────────────→ RUNNING ─────→ SUCCESS
                        │
                        └──────────→ FAILED (exit code N / 异常信息)
```

无回退、无重试、无中间态。

### 1.3 持久化方式

**无持久化。** `STATUS_MAP` 是 `static final ConcurrentHashMap`：

| 特性 | 现状 | 影响 |
|------|------|------|
| 存储位置 | JVM 堆内存 | **容器重启/重新部署 → 全部丢失** |
| key | `videoId`（字符串） | 同一视频重复提交 → **状态互相覆盖**，无法区分任务实例 |
| value | 状态字符串 | 无时间戳、无进度、无任务 ID |
| 多实例 | 每实例独立 | 负载均衡到不同实例时状态查不到 |

**实测**：容器重启后 `GET /api/collect/status` 返回 `IDLE`，尽管该视频此前采集成功。

---

## 2. 任务管理能力（5.2）

| 能力 | 状态 | 等级 | 证据 |
|------|------|------|------|
| 关键词任务创建 | ❌ 无此概念 | D | 无关键词功能 |
| 视频发现任务创建 | ❌ 无此概念 | D | 无发现功能 |
| 评论采集任务创建 | ✅ 单视频 | C | `POST /api/collect/start` |
| 任务执行进度 | ❌ 仅 RUNNING/SUCCESS 二值 | D | — |
| 任务失败原因 | ⚠️ 有但粗糙（整段异常消息拼接字符串） | C | `CollectServiceImpl:66` |
| 任务重试次数 | ❌ | D | — |
| 任务执行时间 | ❌ 无开始时间记录 | D | — |
| 任务完成时间 | ❌ | D | — |
| 任务日志 | ⚠️ 仅容器 stdout，非结构化、不可查 | C | `CollectServiceImpl:55` |
| 任务结果统计 | ❌ 无统计接口 | D | — |
| 任务间依赖关系 | N/A（无任务编排） | — | — |

### 2.1 失败原因的表示问题

```java
STATUS_MAP.put(videoId, "FAILED: " + e.getMessage());
```

前端 `CollectView.vue` 判断：
```js
else if (s === 'FAILED' || s.startsWith('FAILED')) { ... }
```

问题：
- 状态与原因**混在一个字符串**里，无法结构化查询
- 无法区分「采集失败」与「网络失败」与「页面改版」
- 无法统计失败类型分布

---

## 3. 调度机制（5.3）

### 3.1 现有调度方式（逐项排除）

| 候选 | 是否存在 | 判定依据 |
|------|---------|---------|
| 同步执行 | ❌ | 已用 `new Thread()` 异步化 |
| **Java 裸线程** | ✅ **唯一方式** | `CollectServiceImpl:34` `new Thread(() -> {...}).start()` |
| Java 线程池 | ❌ | 无 `ExecutorService` / `ThreadPoolTaskExecutor` |
| Python ThreadPoolExecutor | ❌ | Python 脚本全为同步流程 |
| APScheduler | ❌ | 未安装 |
| Celery | ❌ | 未安装 |
| Redis Queue | ❌ | 未引入 Redis |
| RabbitMQ | ❌ | 未引入 |
| 其他 | ❌ | — |

### 3.2 五个专项分析

#### ① 是否存在阻塞主线程的情况

**采集本身不阻塞**（`new Thread` 异步），但存在两处同步阻塞：

| 位置 | 阻塞时长 | 影响 |
|------|---------|------|
| `CollectServiceImpl:52-57` | **整个采集过程** | 该线程阻塞在 `reader.readLine()` 直到 Python 进程结束（1~3 分钟） |
| `AnalysisServiceImpl:351-368` | HTTP 30s 超时 | **Tomcat 工作线程**被阻塞 30 秒 |

第 2 项值得注意：`callTopicAgent` 用同步 `HttpURLConnection` 直连 Flask，
`conn.setReadTimeout(30000)`。当 DeepSeek 响应慢时，**占用一个 Tomcat 线程 30 秒**。
Tomcat 默认 200 线程，理论上可并发 6 次慢 AI 调用即耗尽线程池。

**采集的 `readLine()` 阻塞在独立线程上，不影响 Tomcat**，但每个任务占一个 OS 线程。

#### ② 是否存在重复调度

**存在。** 无任何去重检查：

```java
public void startCollect(String videoId, int maxComments, String collectMode) {
    STATUS_MAP.put(videoId, "RUNNING");   // ← 直接覆盖，不检查已有状态
    new Thread(() -> { ... }).start();
}
```

实测：连续 3 次 POST 同一视频 → **全部 200**，启动 3 个独立采集任务 → 2 个因 profile 争抢失败。

#### ③ 是否存在任务状态不一致

**存在，三处不一致：**

| 不一致点 | 说明 |
|---------|------|
| 状态与实际进程脱节 | Python 进程被杀（OOM/外部 kill），Java 侧 `waitFor()` 返回非 0 → 状态置 FAILED，尚属一致；但**若 Java 自身重启**，状态丢失而 Python 可能仍在跑 |
| 同视频多任务状态覆盖 | 3 个任务共享一个 key `videoId`，后启动的覆盖前面的状态，前端看到的可能是任意一个任务的状态 |
| 状态无进度 | 前端只知道 RUNNING，不知已采几条 |

#### ④ 是否存在并发写入问题

**存在三处：**

| 问题 | 位置 | 后果 |
|------|------|------|
| Chrome profile 争抢 | 共享 `chrome-data` 卷 | **实测 2/3 任务失败** |
| CSV 文件覆盖 | 3 个进程写同一 `Datas/{videoId}.csv` | 数据互相覆盖/损坏 |
| 数据库重复插入风险 | `clean_main.py` 的跨批次去重是「先查后插」 | 单机单进程无问题；**多实例并发清洗同一视频存在竞态**（无唯一索引兜底，见 05 号文档） |

#### ⑤ 是否存在任务丢失风险

**存在多个场景：**

| 场景 | 后果 | 等级 |
|------|------|------|
| 容器重启 | 采集进程被杀，已采数据全丢（末尾落盘），状态丢 | A（实测） |
| 任务异常未捕获 | `except` 捕获，状态置 FAILED，不丢状态但不丢数据 | A |
| 无任务队列 | 请求返回即"已启动"，但进程可能在启动前就死 | C |
| 无重试 | 网络抖动导致失败即永久失败 | D |

---

## 4. 现有能力的准确定位

### 4.1 系统实际提供什么

```
POST /api/collect/start     → 异步启动一个单视频采集（裸线程）
GET  /api/collect/status     → 查询该视频的最近一次状态（内存）
POST /api/collect/upload    → 上传 CSV（人工补充路径）
```

这是一个**「触发即忘」的单任务触发器**，而非任务中心。

### 4.2 与"任务中心"的能力差距

| 维度 | 任务中心应有 | 现状 |
|------|-------------|------|
| 任务实体 | 独立的任务表，有 ID | ❌ 用 videoId 当 key |
| 任务持久化 | 落库可查历史 | ❌ 内存 |
| 多任务 | 一任务多子任务（多视频） | ❌ |
| 并发控制 | 队列 + 消费者 + 限流 | ❌ 裸线程无限创建 |
| 幂等 | 同参数任务去重/合并 | ❌ |
| 进度 | 实时进度条 | ❌ |
| 重试 | 指数退避重试 | ❌ |
| 取消 | 主动终止 | ❌ |
| 暂停/恢复 | 人工干预 | ❌ |
| 断点续采 | 游标持久化 | ❌ |
| 日志 | 任务级日志可查 | ❌ stdout |
| 统计 | 成功率/耗时分布 | ❌ |
| 告警 | 失败通知 | ❌ |

---

## 5. 建议的任务中心设计（未实施）

### 5.1 核心表结构

```sql
CREATE TABLE collect_task (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_no       VARCHAR(64)  NOT NULL COMMENT '任务业务编号',
  task_type     VARCHAR(20)  NOT NULL DEFAULT 'COMMENT'
                COMMENT 'COMMENT=评论采集 KEYWORD_SEARCH=关键词搜索',
  keyword       VARCHAR(200) NULL COMMENT '关键词(搜索任务用)',
  video_id      VARCHAR(32)  NULL COMMENT '视频ID(评论采集用)',
  video_ids     TEXT         NULL COMMENT '视频ID列表(JSON,批量用)',
  user_id       BIGINT       NULL,
  mode          VARCHAR(20)  NOT NULL DEFAULT 'online',
  max_comments  INT          NOT NULL DEFAULT 300,
  status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  collected     INT          NOT NULL DEFAULT 0,
  total         INT          NOT NULL DEFAULT 0,
  cursor        VARCHAR(256) NULL COMMENT '抖音翻页游标,支持断点续采',
  error_msg     TEXT         NULL,
  retry_count   INT          NOT NULL DEFAULT 0,
  started_at    DATETIME     NULL,
  finished_at   DATETIME     NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_no (task_no),
  INDEX idx_status_created (status, created_at),
  INDEX idx_user (user_id),
  INDEX idx_video (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集任务表';
```

### 5.2 状态机

```
PENDING ──→ RUNNING ──┬──→ SUCCESS
   │          │  ▲    ├──→ PARTIAL_SUCCESS  (批量中部分成功)
   │          │  │    └──→ FAILED
   │          │  └──(重试)──┘
   │          └──→ CANCELLED
   └──→ CANCELLED
```

### 5.3 调度实现（不引入新中间件）

任务卡要求"不默认引入 Redis、Celery 或消息队列"。当前架构下**数据库轮询调度器已足够**：

```java
@Component
public class TaskScheduler {
    // 固定间隔轮询 PENDING 任务
    // 用 MySQL 行锁抢占: UPDATE collect_task SET status='RUNNING'
    //   WHERE id=? AND status='PENDING'  → affectedRows==1 才执行
    // 天然支持多实例部署（依赖数据库行锁，不需 Redis）
}
```

**为什么不需要 Redis/Celery**：
- 任务量级为「人工触发的抖音采集」，QPS 极低
- MySQL 的 `SELECT ... FOR UPDATE SKIP LOCKED`（MySQL 8.0 已支持）即可做分布式抢占
- 引入 Redis 只会增加运维复杂度而无收益

### 5.4 并发安全方案

| 问题 | 方案 |
|------|------|
| Chrome profile 争抢 | **每任务独立 profile**：`chrome-data/task-{taskId}`；或严格串行（并发度=1） |
| CSV 覆盖 | 输出到 `Datas/{videoId}_{taskNo}.csv`，清洗时按前缀归并 |
| 同视频重复提交 | 创建任务前检查 `video_id + status IN (PENDING,RUNNING)` → 返回已有任务 |
| 线程无上限 | 固定 `ThreadPoolExecutor(core=2, max=3, queue=100)`，队列满则任务保持 PENDING |

---

## 6. 结论

| 审计项 | 结论 | 等级 |
|--------|------|------|
| 任务生命周期 | 仅 4 状态，无队列概念 | C |
| 任务管理能力 | 8 项能力中 7 项缺失 | D |
| 调度机制 | Java 裸线程，唯一方式 | A |
| 阻塞主线程 | 采集不阻塞；**AI 调用阻塞 Tomcat 线程 30s** | C |
| 重复调度 | **存在**，实测 3 次提交全部执行 | A |
| 状态不一致 | **存在三处**，实测同视频状态互相覆盖 | A |
| 并发写入 | **存在三处**，实测 2/3 任务失败 | A |
| 任务丢失 | **存在**，实测中断 10 条数据全丢 | A |

**总评：项目当前是「单任务触发器」，不是「任务中心」。**
从触发器演进到任务中心，需要 P0/P1 级别的结构性改造（任务表 + 状态落库 + 调度器 + 并发治理）。
