# 03 · COLLECTION_AUDIT

> 抖音数据采集模块专项审计
> 证据等级：**A** = 已运行验证 ｜ **B** = 有实现未验证 ｜ **C** = 部分实现有明确限制 ｜ **D** = 未发现实现 ｜ **E** = 条件不足

---

## 0. 与任务卡基线的差异声明

任务卡 1.1 节列出"已确认支持"的 11 项业务流程。经本次代码审计与实测，其中 **3 项与实际实现不符**：

| 任务卡声称 | 审计实际结论 | 证据 |
|-----------|-------------|------|
| 2. 输入关键词搜索视频 | ❌ **D：无任何实现** | 全项目检索无抖音搜索页解析代码 |
| 3. 从关键词搜索结果发现多个视频 | ❌ **D：无任何实现** | 同上 |
| 4. 对多个视频批量评论采集 | ❌ **C：接口层不支持多视频** | `CollectRequest.videoId` 为 `String`；实测传数组 400 |

以下所有结论以**实际代码**为准。

---

## 1. 关键词搜索与视频发现（4.1）

### 1.1 结论总览

| # | 检查项 | 结论 | 等级 | 证据 |
|---|--------|------|------|------|
| 1 | 关键词搜索入口 | **不存在** | D | 无任何入口 |
| 2 | 访问抖音方式 | DrissionPage 驱动 Chrome | A | `douyin_comment.py:57` |
| 3 | 使用的 DrissionPage 能力 | `ChromiumPage` + `listen.start/wait` | A | `douyin_comment.py:57,143` |
| 4 | 是否需要登录 | 匿名态可采集（仅 3 页） | A | 实测采到 15 条 |
| 5 | 登录态保存 | Chrome user-data-dir（命名卷 `chrome-data`） | A | `config_browser.py` |
| 6 | 搜索结果解析 | **不存在** | D | — |
| 7 | 视频唯一标识提取 | 支持 5 种形式 | A | `douyin_comment.py:26-58` |
| 8 | 搜索结果翻页 | **不存在** | D | — |
| 9 | 关键词重复搜索 | **不存在** | D | — |
| 10 | 多关键词 | **不存在** | D | — |
| 11 | 按发布时间筛选 | **不存在** | D | — |
| 12 | 按点赞/评论数筛选 | **不存在** | D | — |
| 13 | 搜索结果数量限制 | N/A（无搜索） | — | — |
| 14 | 视频去重机制 | **不存在**（无视频表） | D | 3 张表无 video 表 |
| 15 | 搜索失败处理 | N/A（无搜索） | — | — |

### 1.2 全项目检索证据

```bash
grep -rn -i "search" --include="*.java" --include="*.py" --include="*.vue" .
```

命中全部为**评论内容检索**（`/api/comment/search`，查已入库评论文本）与
`douyin_comment.py` 中对 `?modal_id=` 形式的**链接解析**——这是「用户已知视频、仅链接形态不同」的补救，
**不是「关键词 → 视频列表」的发现能力**。

### 1.3 当前唯一可行的「视频发现」路径

```
人工在抖音搜索 → 打开视频 → 复制地址栏链接 → 粘贴到平台
```

`extract_video_id()` 兼容 5 种形式（`douyin_comment.py:26-58`）：

| 输入形式 | 支持 | 提取方式 |
|---------|------|---------|
| `7613237683142223214` | ✅ | 纯数字判定 |
| `/video/<id>` | ✅ | 正则 `/(?:video\|note\|share/video)/(\d+)` |
| `/note/<id>`（图文） | ✅ | 同上 |
| `/share/video/<id>` | ✅ | 同上 |
| `/jingxuan/search/x?modal_id=<id>` | ✅ | `parse_qs` 取 `modal_id` |
| `v.douyin.com/xxx`（短链） | ✅ | 提取失败 → 打开后从地址栏 `dp.url` 再取 |

### 1.4 实现关键词发现的最小路径（设计建议，未实施）

```
打开 https://www.douyin.com/search/{关键词}
  → listen.start("aweme/v1/web/general/search/single/")
  → 滚动搜索结果页触发翻页
  → 从响应提取 aweme_info.aweme_id / desc / statistics
  → 过滤（时间、点赞量、时长）
  → 去重（历史任务已发现的 id）
```

**可行性提示**：抖音搜索接口与评论接口同样受风控约束，匿名态可能仅返回有限结果，
且搜索页结构变动频率高于视频页（项目已有 `preflight_check` 体检机制可复用）。

---

## 2. 单视频评论采集（4.2）

### 2.1 结论总览

| # | 检查项 | 结论 | 等级 | 证据 |
|---|--------|------|------|------|
| 1 | 采集方式 | **接口拦截**（非 DOM 解析） | A | `listen.start("aweme/v1/web/comment/list")` |
| 2 | 一级评论采集 | 支持 | A | `json_data["comments"]` |
| 3 | 评论分页机制 | **滚动触发 + 被动等待** | C | 见 2.3 |
| 4 | 评论数量限制 | 匿名态 ≈15 条（3 页） | A | 实测多次稳定 15 条 |
| 5 | 评论排序方式 | 抖音默认（热门优先） | A | 接口返回顺序 |
| 6 | 评论时间处理 | `create_time` 时间戳 → 本地时区字符串 | A | `douyin_comment.py:175` |
| 7 | 用户信息范围 | 昵称/抖音号/UID/sec_uid/地区 | A | 实测 14/14 全覆盖 |
| 8 | 点赞数 | `digg_count` | A | — |
| 9 | **回复数量** | **未采集** | D | 见第 3 节 |
| 10 | 采集失败处理 | 捕获异常 + 打印 + 不生成文件 | A | `douyin_comment.py:209` |
| 11 | 评论去重 | 批内 + 跨批次双层 | A | 见 2.5 |
| 12 | 入库方式 | `clean_main.py` 批量 INSERT | A | `clean_main.py:98` |

### 2.2 采集实现（核心代码路径）

```python
# douyin_comment.py
dp = ChromiumPage(build_options())          # :57  启动 Chrome
dp.listen.start(LISTEN_API)                 # :60  监听评论接口
dp.get(TARGET_URL)                          # :61  打开视频页
time.sleep(5)                               # :63  等待首屏
dp.run_js('var c=...; if(c) c.scrollTop = 600;')  # :134 滚到评论区
while len(data_list) < MAX_COMMENTS:        # :154  采集循环
    resp = dp.listen.wait(timeout=15)       # :156  阻塞等待数据包
    json_data = resp.response.body
    for c in json_data["comments"]: ...     # :170  逐条提取
    if not json_data.get("has_more"): break # :199  抖音说没更多了
    scroll_comment_panel(dp)                # :201  滚动触发下一页
```

**采用接口拦截而非 DOM 解析**是正确选择：接口返回结构化 JSON，比解析 DOM 稳定得多。

### 2.3 分页机制的真实行为（关键限制）

分页**不是** API 游标翻页，而是：

```
滚动内部容器 → 抖音前端自动请求下一页 → 被动捕获数据包
```

代码中**没有使用抖音返回的 `cursor` 参数**。这导致：

- ❌ 无法精确跳到第 N 页
- ❌ 无法持久化游标 → **无法断点续采**（见第 5 节）
- ⚠️ 滚动是否触发请求依赖前端行为，页面改版可能失效

抖音接口返回 `has_more` 字段，脚本据此判断是否继续（`:198`）。匿名态下约 3 页后 `has_more=0`。

### 2.4 漏采风险评估

| 风险点 | 说明 | 等级 |
|-------|------|------|
| 滚动未触发 | 若容器选择器失效，页面不滚动 → 只能拿到首屏 | C（有 `preflight_check` 体检兜底） |
| 评论排序非时间序 | 抖音按热度排序，**新评论可能不在前几页** | C |
| 超出 15 条的评论 | 匿名态无法获取 | C |
| 表情标签过滤 | `clean_main.py:66` 剥离 `[哭哭]` 等方括号标签 | A（设计如此） |
| 空内容剔除 | `clean_main.py:51` 剔除昵称或内容为空的行 | A |

### 2.5 去重机制（已达标）

**第一层 · 批内去重**（`clean_main.py:53-60`）
```python
key = (nickname, content, publish_time)   # 同文件内去重
```

**第二层 · 跨批次去重**（`clean_main.py:89-93, 115-118`）
```python
cursor.execute("SELECT nickname, content FROM comment WHERE video_id = %s", (video_id,))
existing = set(cursor.fetchall())
...
if (nickname, content) in existing:
    skipped += 1; continue
```

**实测**：重复采集后入库输出「入库成功: 0 条，跳过重复 13 条」；库中 `COUNT(*)=14`、`COUNT(DISTINCT douyin_id)=14`，无重复。

**局限**：去重键未含 `douyin_id`，同昵称同内容的不同用户会被误判重复（概率低但存在）。

### 2.6 评论更新识别

**D：无法识别**。`comment` 表无 `cid`（抖音评论唯一 ID）字段，也无 `update_time`。
当前只能靠内容比对判断"是否新评论"。

---

## 3. 二级评论与回复采集（4.3）

| # | 检查项 | 结论 | 等级 |
|---|--------|------|------|
| 1 | 是否采集回复 | **不采集** | D |
| 2 | 回复分页 | N/A | — |
| 3 | 回复与一级评论关联 | N/A | — |
| 4 | 记录父评论 ID | **无**（表无 `reply_id` 字段） | D |
| 5 | 回复增量采集 | N/A | — |
| 6 | 回复重复插入 | N/A | — |

**证据**：
- `comment` 表字段：`id, user_id, video_id, nickname, region, publish_time, content, likes, sentiment, created_at, douyin_id, douyin_uid, sec_uid` —— **无 `reply_id` / `parent_cid` / `reply_count`**
- 采集脚本只遍历 `json_data["comments"]`（一级评论），未访问 `reply_comment` 字段
- 抖音接口的 `reply_comment` 字段存在于响应中但**未被使用**

**影响评估**：抖音评论区的"楼中楼"回复往往是真实问题与互动的核心，缺失会导致
- 用户需求识别不完整（追问、纠错常在回复里）
- 评论条数被低估
- 情感分析样本偏差

**建议**：新增 `reply_id`、`parent_cid`、`reply_count` 字段，采集时一并读取接口的 `reply_comment` 与 `reply_comment_total`。

---

## 4. 多视频批量采集（4.4）

### 4.1 三个概念的严格区分

任务卡要求明确区分，本审计逐一核实：

| 概念 | 定义 | 当前状态 | 等级 |
|------|------|---------|------|
| **多视频批量执行** | 一次任务处理 N 个视频 | **不支持** | D |
| **多视频并发执行** | N 个视频同时采集 | **不支持**（且无法安全实现，见 4.4） | D |
| **分布式任务调度** | 多节点、队列化、可恢复 | **完全无** | D |

### 4.2 接口层证据

`CollectRequest.java`：
```java
private String videoId;     // ← String，不是 List<String>
private int maxComments = 300;
private String collectMode = "online";
```

`CollectController.java:30-37`：
```java
String videoId = CollectUtils.extractVideoId(req.getVideoId());   // 单值提取
collectService.startCollect(videoId, max, mode);                    // 单任务
```

前端 `CollectView.vue:97-101`：
```js
await collectApi.start({ videoId: videoId.value, maxComments: ..., collectMode: ... })
```

**实测三种传法**：

| 传法 | 结果 |
|------|------|
| `"videoId": ["id1","id2"]` | ❌ 400 `Cannot deserialize value of type java.lang.String from Array value` |
| `"videoId": "id1,id2"` | ❌ 400 `无法识别视频编号` |
| 连续 3 次 POST 同一 ID | ⚠️ **全部返回 200**，启动 3 个独立任务 |

### 4.3 README 失实

`README.md` 明确写有「**支持批量多视频同时采集**」——
**该功能从不存在**。建议修正（文档问题，非代码问题）。

### 4.4 并发安全性实测（严重问题）

连续 POST 同一视频 3 次（均 200），执行日志：

```
Thread-2: =.= 采集失败：local variable 'fail' referenced before assignment
Thread-3: =.= 采集失败：local variable 'fail' referenced before assignment
Thread-4: φ(゜▽゜*)♪ 采集成功！共 15 条评论
```

**3 个并发任务仅 1 个成功。**

根因链：

```
3 个 Chrome 争抢同一 chrome-data profile
  → Chrome SingletonLock 机制：后启动者无法完成初始化
  → DrissionPage 内部缺陷触发：
     DrissionPage/_units/listener.py 异常路径引用未初始化的 fail 变量
     （已通过解压 wheel 定位该源码）
  → 同时 3 个进程写同一 Datas/{videoId}.csv，互相覆盖
```

**该异常是 DrissionPage 的缺陷，但触发条件完全在本项目的并发设计。**

### 4.5 任务管理能力核查（4.4 第 6~13 项）

| 能力 | 状态 | 等级 | 证据 |
|------|------|------|------|
| 任务状态管理 | 内存 `ConcurrentHashMap`，key=videoId | C | `CollectServiceImpl:20` |
| 暂停 | **不支持** | D | 无相关代码 |
| 恢复 | **不支持** | D | 无相关代码 |
| 失败重试 | **不支持** | D | 失败即置 FAILED |
| 单视频失败后继续 | 不适用（无批量） | — | — |
| 重复执行任务 | **允许，且互相干扰** | C | 实测见 4.4 |
| 执行日志 | 仅 `log.info` 逐行转写 stdout | C | `CollectServiceImpl:55` |
| 结果统计 | **不支持**（无统计接口） | D | — |

### 4.6 调度机制（5.3 交叉验证）

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 调度方式 | **裸线程** `new Thread(...).start()` | A |
| 线程池 | **无** | D |
| Python ThreadPoolExecutor | **无** | D |
| APScheduler / Celery / Redis / RabbitMQ | **无** | D |

`CollectServiceImpl.java:34`：
```java
new Thread(() -> { ... }).start();
```

问题：
- 线程数不受控（可无限创建）
- 无并发上限
- 无任务去重
- 无优雅停机（容器关闭时线程直接死掉）

---

## 5. 采集可靠性（4.5）

### 5.1 异常处理矩阵

| 异常场景 | 现有处理 | 等级 | 证据 |
|---------|---------|------|------|
| **浏览器启动失败** | `except` 捕获 → 状态 FAILED | A | `CollectServiceImpl:65-67` |
| **Chrome profile 被占用** | `cleanup_stale_locks()` 仅清理**属主进程已死**的锁 | A | `config_browser.py:57-88` |
| **并发抢 profile** | ❌ 无保护，实测 2/3 任务失败 | C | 实测日志 |
| **登录失效** | ⚠️ 无检测。匿名态仍返回 3 页数据，不报错 | C | — |
| **网络超时** | `listen.wait(timeout=15)` 超时 → break 正常退出 | A | `douyin_comment.py:156` |
| **请求失败** | `resp.response.body` 异常 → 外层 except | A | — |
| **页面结构变化** | ✅ `preflight_check()` 体检滚动容器，缺失即报错退出 | A | `douyin_comment.py:100-108` |
| **评论分页失败** | `has_more=0` → 正常结束 | A | `douyin_comment.py:199` |
| **数据库连接失败** | `except` → 状态 FAILED | A | `CollectServiceImpl:65` |
| **程序退出中途中断** | ❌ **已采数据全丢** | C | 见 5.2 |
| **单个评论解析失败** | ✅ `continue` 跳过该条 | A | `douyin_comment.py:181` |
| **未捕获异常** | ✅ `finally: dp.quit()` 释放浏览器 | A | `douyin_comment.py` 末尾 |

### 5.2 中断恢复能力实测

采集中途 `kill -9` 全部 Chrome：

```
中断前日志: 本页 5 条，累计 10 条      ← 已采到 10 条
中断后状态: {"status":"FAILED: exit code 137"}
残留 CSV:   （空）
```

**已采集的 10 条评论全部丢失。**

根因：`douyin_comment.py` 采用**末尾一次性写入**：

```python
while len(data_list) < MAX_COMMENTS:     # 全部评论先攒在内存
    ...
if data_list:
    with open(csv_path, "w", ...) as f:   # 循环结束后才写文件
        writer.writerows(data_list)
```

进程被杀 → 内存数据随进程消失 → 一个字节未落盘。

**恢复能力结论：D（不支持）**

缺失：边采边落盘、游标持久化、任务状态持久化、失败重试、从断点继续。

---

## 6. 采集模块量化问题清单

| 编号 | 问题 | 严重度 | 等级 |
|------|------|--------|------|
| C-01 | 无关键词搜索与视频发现能力 | P1 | D |
| C-02 | 不支持多视频批量/并发采集 | P1 | D |
| C-03 | 并发提交同一视频导致任务互相踩踏（实测 2/3 失败） | **P0** | C |
| C-04 | 采集数据仅末尾落盘，中断全丢（实测 10 条丢失） | **P0** | C |
| C-05 | 无任务去重，同一视频可重复启动 | P0 | D |
| C-06 | 任务状态仅内存，重启即丢 | P1 | D |
| C-07 | 无断点续采（未用 cursor） | P1 | D |
| C-08 | 无增量采集，每次全量重拉 | P1 | D |
| C-09 | 不采集二级评论与回复 | P1 | D |
| C-10 | 无评论唯一 ID（cid），无法识别评论更新 | P1 | D |
| C-11 | 无视频表，视频仅作字符串列存在 | P1 | D |
| C-12 | 裸线程无池化、无并发上限 | P1 | D |
| C-13 | 登录失效无检测，匿名态静默降级 | P2 | C |
| C-14 | 去重键未含用户标识，理论上可误判 | P3 | C |
| C-15 | 抖音按热度排序，新评论可能漏采 | P2 | C |
| C-16 | README 声称"支持批量多视频采集"与实现不符 | P2 | A |
