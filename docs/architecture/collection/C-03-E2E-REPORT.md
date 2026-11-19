# C-03 · 匿名采集端到端验证报告

日期：2026-11-16 03:17　结论：**B（链路验证通过，但暴露 3 个缺陷）**

## 1. 标识

| 项 | 值 |
|---|---|
| Task | **5**（`CT20261116031613339`，专用 E2E，未复用历史任务） |
| Item | **6**（video_id=1，`7703721523349239951`） |
| Run | **9**（run_no=1） |
| Execution | **未创建**（0 行）——见 §5 缺陷 1 |

## 2. 状态转换（实测时间）

| 时间 | 状态 | 证据 |
|---|---|---|
| 03:16:13 | Task 创建 → `IDLE`，Item 6 `PENDING`，`active_flag=1` | DB |
| 03:16:16 | Run 9 创建 → `PENDING` | 触发接口返回 |
| 03:16:26 | 调度器领取 → `RUNNING`（`started_at` 写入） | DB |
| 03:16:46 | `COMPLETED`（耗时 **20 秒**） | DB |
| 03:16:46 | Item 6 → `SUCCESS`，Task 5 → `IDLE` | DB |

## 3. Python Collector 确实执行（关键证据）

`collection_task_log` 原文（终端显示为 `?` 是 mysql 客户端编码，库内正常）：

```
STATUS_CHANGE  开始执行运行 1
STATUS_CHANGE  运行 #1 开始执行
STATUS_CHANGE  开始采集 7703721523349239951
PAGE_DONE      本页采集到 19 条
STATUS_CHANGE  本次运行结束：成功 1 / 失败 0
```

`PAGE_DONE 19 条` 只能来自 Python（Java 侧不产生该事件）。

## 4. 数据结果

| 指标 | 值 | 说明 |
|---|---|---|
| Python 返回 | **19 条** | 单页 DOM 滚动结果 |
| 新增入库 | **0 条** | `comment` 表总数执行前后均为 19 |
| 重复（CID 命中） | **19 条** | 与历史采集完全重合，属预期 |
| 失败 | 0 | — |
| Run 统计 | total=1 / success=1 / failed=0 | — |
| Checkpoint | `persisted_count=19`，`last_cid=NULL`，`page_no=0` | last_cid 未写入（C-05 范围） |

**"0 新增"是正确结果，不是失败**：视频 1 的 19 条评论在 11-04 已采集入库，
本次重申采集被 CID 去重全部拦截。这正是去重机制生效的证明。

## 5. 暴露的缺陷（3 个）

### 缺陷 1：未创建 Execution（C-04 范围）

`col_execution` 0 行。Run 统计直接来自 item 聚合，没有经过执行单元。
数据库已就绪但代码未使用——属 Batch 4 待实现，不是本次回归。

### 缺陷 2：新 Item 未关联 run_id（**本批遗漏**）

Item 6 的 `run_id` 为 NULL。C2-05 要求"新 Item 关联 run_id 和 execution_id"，
而 `submit()` 创建 item 时拿不到 run（run 在 trigger 时才创建）。

**修复方向**：Run 创建后、执行前，把该 run 下的 item 补写 `run_id`；
或执行时由 dispatch 回填。属 Batch 4 的 Execution 生命周期一并处理。

### 缺陷 3：`maxComments=5` 未生效（采集侧问题）

请求 5 条，实际返回 19 条。`comment.py` 按"整页滚动结果"返回，
未按 `maxComments` 截断。Java 侧 `requested_max=5` 记录了意图，但未强制。

**影响**：小规模验证时会多采（本场景无害，因去重拦截），
但真实限流场景下会超出预期请求量。

## 6. 不能宣称已验证的部分

| 环节 | 状态 |
|---|---|
| Task/Run 状态机 | ✅ 已验证 |
| 调度器领取 PENDING Run | ✅ 已验证 |
| Python 实际执行 | ✅ 已验证（日志 PAGE_DONE） |
| 数据持久化链路 | ⚠️ **部分**：本次 0 新增，未证明"新增写入"路径 |
| CID 去重 | ✅ 已验证（19 条全部拦截） |
| Checkpoint last_cid | ❌ 未写入（C-05） |
| Execution 生命周期 | ❌ 未实现（C-04） |
| 登录态采集 | ❌ 未验证（无账号） |
| 失败/重试/取消 | ❌ 未验证（本次未触发） |

**诚实的结论**：本次验证了"触发 → 领取 → 真实采集 → 去重 → 终态"这条主链路，
但**没有验证"新增入库"**——因为所有数据都是重复的。要验证新增，
需要换一个从未采集过的视频。

## 7. 数据未被破坏

- `comment` 表 19 条，与执行前一致，无删除无修改
- 历史 4 个任务、5 条 item 状态未变
- 仅新增：Task 5、Item 6、Run 9、1 条 checkpoint、5 条日志
