# C-03 · 端到端验证准备报告（未执行）

日期：2026-11-16 03:14　**等待确认后才触发真实采集**

## 1. 现有任务与 Item 状态（实测）

| Task | 状态 | Item | video_id | Item 状态 |
|---|---|---|---|---|
| 1 | IDLE | 1, 2 | 1, 2 | FAILED, FAILED |
| 2 | IDLE | 3 | 1 | SUCCESS |
| 3 | IDLE | 4 | 1 | SUCCESS |
| 4 | IDLE | 5 | 3 | FAILED |

## 2. 关键发现：直接触发历史任务**不会产生任何采集**

`runTask()` 只处理 `status ∈ {PENDING, RETRY_WAIT}` 的 item：

```java
if (!"PENDING".equals(item.getStatus()) && !"RETRY_WAIT".equals(item.getStatus())) { ...continue; }
```

现有 item 全是 `FAILED` / `SUCCESS` → 新 Run 会立刻"遍历完、无实际采集"直接 COMPLETED。
**用它做端到端验证会得到"成功但什么都没采"的假结果。**

## 3. 建议的测试任务设计

| 项 | 建议 |
|---|---|
| Task | 新建专用任务 `E2E-TEST`，**不要复用历史任务** |
| 视频数 | 1 个（复用已知 id `7703721523349239951`） |
| 采集范围 | `maxComments=5`（最小化，验证链路而非抓全量） |
| Item 初始状态 | PENDING（必须是新 item，create 时天然满足） |
| 预期耗时 | 30~90 秒（匿名态 1 页） |

这样做不会污染历史任务，也不会改动既有 item 状态。

## 4. 重复采集风险评估

| 风险 | 评估 |
|---|---|
| 重复插入视频 | `video` 表按 `aweme_id` 复用，不新建 |
| 重复插入评论 | 双重保障：`ct_comment` 与 `comment` 均有唯一键；同一 cid 不会重复 |
| 覆盖历史数据 | 不会——采集只 INSERT，不 UPDATE 已有评论 |
| 触发历史任务 | 不会——历史任务 item 非 PENDING，且调度器只领 PENDING Run |

## 5. Python Collector 超时与取消

| 项 | 现状 |
|---|---|
| 超时 | `ProcessRunner` 有超时强杀（默认值在 integration 层，本轮未改动） |
| 取消 | **协作式**：`runTask` 每轮 item 前查 `task.status='CANCEL_REQUESTED'`；单次 Python 调用期间无法中断 |
| 僵尸兜底 | 新增 `sweepStaleRuns()`，30 分钟无终态自动置 FAILED |

## 6. 凭证泄漏检查

| 项 | 结论 |
|---|---|
| 采集账号凭证 | 当前走匿名公开访问，**不传任何凭证** |
| 日志 | `writeLog` 只写事件与消息；`ProcessRunner` 记录命令行参数（不含凭证，因无凭证） |
| 快照 | `config_snapshot` 只含 `params`/`triggerType`/`sourceId`，无凭证字段 |

**结论：本次测试不存在凭证暴露面。**

## 7. 执行前需要你确认

1. 是否按 §3 新建专用 Task（`E2E-TEST`，1 视频，maxComments=5）
2. 目标视频确认：`7703721523349239951`（历史采过 19 条评论，可对照 CID 去重效果）
3. 采集模式：`online`（匿名）还是 `online-login`（需登录态）

确认后我执行单个测试 Task，并按 §第三阶段要求记录全链路数据。
