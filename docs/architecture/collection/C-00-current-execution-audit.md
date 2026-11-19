# C-00 · 现有执行流程审计

基线：`e1832df`　数据库：Flyway V24
方法：逐方法读源码（`CollectServiceImpl` 606→542 行后），不按类名推断

## 1. 真实调用链

```
CollectionTaskController
  POST /api/collect/start → CollectService.submit(videoIds, maxComments, mode, ownerId)
      ├─ CollectUtils.extractVideoId()       解析短链/编号
      ├─ VideoService.requireOrCreate()      外部 id → 内部 video.id（不存在则建）
      ├─ itemMapper.countActiveByVideo()     全局活动唯一预检（对应 uk_video_active）
      └─ 建 collection_task(status=PENDING) + 每个视频一条 task_item(PENDING)

  @Scheduled(fixedDelay=5s) CollectServiceImpl.dispatch()
      ├─ busy(AtomicBoolean) 单实例互斥
      ├─ taskMapper.selectPendingOldest()    ← 依赖 task.status='PENDING'
      ├─ taskMapper.claimPending()           条件更新 PENDING→RUNNING（真正的原子领取）
      └─ worker.submit(runTask(taskId))

  runTask(taskId)
      ├─ paramsOf(task) → maxComments / collectMode
      ├─ 遍历 item：跳过已完成；task.status='CANCEL_REQUESTED' 时标 item=CANCELLED
      ├─ runItem()：item RUNNING + worker_id + lease_until(15min) + heartbeat
      │      └─ invokeCollector() → CollectionClient.collectComments() → ProcessRunner
      ├─ 聚合：success/failed → task.status = SUCCESS | FAILED | PARTIAL_SUCCESS | CANCELLED
      └─ writeLog(STATUS_CHANGE)
```

## 2. 关键代码事实

| 事实 | 证据 |
|---|---|
| 调度按 **task** 领取，不是 run | `dispatch()` 调 `selectPendingOldest()` / `claimPending()` |
| 无 run / execution 概念 | 代码零引用 `col_run` / `col_execution`（V20 已建，未使用） |
| 原子领取靠 DB 条件更新 | `claimPending(id, startedAt)` 返回 affected==1 才执行 |
| `busy` 只保护单 JVM | `AtomicBoolean`，多实例无效；真正防重靠 DB |
| 取消是**轮询式** | `runTask` 每轮查 `task.status='CANCEL_REQUESTED'` |
| 租约 15 分钟 | `LEASE_MINUTES=15`，写 `lease_until` |
| checkpoint 只写 `persisted_count` | `saveCheckpoint()` 未写 `last_cid`/`page_no` |
| 提交时的防重是**预检** | `countActiveByVideo` 与 `uk_video_active` 双保险 |

## 3. 需要修改的真实文件

| 文件 | 改动性质 |
|---|---|
| `collection/application/impl/CollectServiceImpl.java` | dispatch 改 run 级；runTask 拆分出 Execution 概念；checkpoint 补字段 |
| `collection/application/CollectionQueryService.java` | 增加 run/execution 查询；task 时间字段语义 |
| `collection/application/CollectService.java` | 新增 `trigger()`、`listRuns()`、`runDetail()` |
| `collection/controller/CollectController.java` | 新增 run 相关端点 |
| `collection/infrastructure/persistence/` | 新增 `ColRunMapper`、`ColExecutionMapper` |
| `collection/domain/` | 新增 `ColRun`、`ColExecution` 实体 |
| `frontend/src/views/TaskCenterView.vue` | IDLE 映射、Run 历史、Execution 列表 |

**不需要改**：`ProcessRunner`、`CollectionClient`、Python 适配器（C-01 阶段不动采集行为）。

## 4. 三个真实风险

1. **`dispatch()` 现在完全不会触发**：迁移后无 `task.status='PENDING'`，调度器空转。这是当前系统状态，不是 C-01 引入的。
2. **`busy` 是单实例互斥**：若要支持多 Worker，必须完全依赖 DB 条件更新（已有基础）。
3. **取消语义是"轮询"**：`CANCEL_REQUESTED` 由 runTask 循环里检查，若 run 卡在单次调用里（最长 600s），取消会延迟。改 run 级后应把取消检查下沉到 execution 边界。

## 5. 与目标模型的差距

| 目标 | 现状 |
|---|---|
| Task → Run → Execution → Item | 只有 Task → Item |
| Run 承载单次结果 | Task.status 承载最后一次结果（已由 V22 收敛为 IDLE） |
| Execution 承载平台/账号/策略 | 无（params_json 里只有 maxComments/collectMode） |
| 账号授权校验 | 无（任务无 account_id） |
| failure_type 分类 | 无（只有 error_code/message） |
