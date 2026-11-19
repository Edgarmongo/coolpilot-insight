# CP-INSIGHT-COLLECTION-C-04 · Execution 生命周期与数量限制修复

状态：**设计与评估，未实施**　基线：`dcf2609`

## 1. Schema 影响评估（结论：**无需 Migration**）

`col_execution` 现有 21 列已覆盖裁决要求的全部字段：

| 裁决要求 | 现有列 | 结论 |
|---|---|---|
| id | `id` | ✅ |
| run_id | `run_id` | ✅ |
| status | `status` | ✅ |
| started_at / finished_at | 同名列 | ✅ |
| failure_type / error_code / error_message | 同名列 | ✅ |
| 可追溯的 Item 关联 | **`collection_task_item.execution_id`（反向指针）** | ✅ 够用 |

**唯一需要注意**：没有 `item_id` 正向列。Execution → Item 需要反查
（`SELECT * FROM collection_task_item WHERE execution_id=?`），一个 Execution 只对应一个 Item，
反查是 O(1)（有 `idx_item_run`，可再加 `execution_id` 索引——**不建议现在加**，数据量小）。

**数量字段映射**（不新增 `processed_count`）：

| 语义 | 用哪列 |
|---|---|
| 平台返回条数 | `fetched_count` |
| 实际处理（截断后） | `inserted_count + duplicate_count` |
| 入库 / 去重 / 失败 | `inserted_count` / `duplicate_count` / `failed_count` |

`idem_key` 为 NOT NULL UNIQUE，生成规则：`{runId}:{itemId}:{attemptNo}`
（同一 Run 内同一 Item 的不同尝试各占一条，重试不冲突）。

## 2. 状态机（Execution）

```
创建(PENDING) → RUNNING → SUCCEEDED | FAILED | CANCELLED
PENDING → SKIPPED（启动前校验失败，未真正调用 Collector）
```

**硬规则**：只有真正 `invokeCollector()` 才从 PENDING 转 RUNNING。
Java 侧 `video == null` 之类的校验失败 → `SKIPPED`，`error_code=VIDEO_MISSING`，
**不得**写成"已执行失败"。

现有代码位置（`CollectServiceImpl.runItem`）：

| 分支 | 现状 | C-04 后 |
|---|---|---|
| `video == null` | 标 item FAILED | **不建 Execution**，item SKIPPED |
| 租约领取成功 | 标 item RUNNING | 同时建 Execution → RUNNING |
| `invokeCollector` 返回 false | 标 item FAILED | Execution → FAILED + failure_type |
| 清洗失败 | 标 item FAILED | Execution → FAILED（`DATA`） |
| 完成 | 标 item SUCCESS | Execution → SUCCEEDED + 统计 |

## 3. failure_type 映射（七类，来自 error_code）

| error_code 前缀 | failure_type |
|---|---|
| `AUTH_*` | AUTHENTICATION |
| `FORBIDDEN_*` | AUTHORIZATION |
| `RATE_*` / `RISK_*` | RATE_LIMIT |
| `NET_*` / `TIMEOUT*` | NETWORK |
| `VIDEO_MISSING` / `CONTENT_GONE` | PLATFORM |
| `PARSE_*` / `DATA_*` / 清洗失败 | DATA |
| 其他（进程异常） | SYSTEM |

用户取消 → `CANCELLED`，**不设 failure_type**。

## 4. Item 关联时机（裁决新增约束）

**不得在 Run 创建时批量更新**。改为在 `runItem()` 领取租约成功后：

```sql
UPDATE collection_task_item SET run_id=:runId, execution_id=:execId
 WHERE id=:itemId AND status IN ('PENDING','RETRY_WAIT');
```

- 只更新真正进入执行流程的 Item
- SUCCESS 且未参与的 Item 保持原 `run_id`（有测试守护）
- 重试时更新为新 Run/Execution

## 5. Python 修复（C4-03）

`comment.py` 截断最后一页：

```python
remaining = MAX_COMMENTS - data_count
if remaining <= 0: break
rows = rows[:remaining]
data_count += len(rows)      # 按实际追加数量更新
```

边界：
- `MAX_COMMENTS == 0` → 不采集，直接结束
- 剩余 < 页大小 → 只追加剩余
- 达到上限 → 停止滚动与翻页
- CSV 条数、`PAGE_DONE` 日志、Java 收到的条数三者一致

**不改**：CID 提取、清洗、去重、分页、登录/匿名策略、访问方式。

## 6. 实施批次

| 批次 | 内容 | 验收 |
|---|---|---|
| C4-01 | Execution 创建/状态机 | 真实调用才创建；SKIPPED 不伪造 |
| C4-02 | Item 关联（领取后写） | run_id/execution_id 正确；SUCCESS 不被覆盖 |
| C4-03 | Python 截断 | 请求 5 → 处理 ≤5，三处数量一致 |
| C4-04 | 统计对齐 | fetched/inserted/duplicate 与 CSV 行数吻合 |
| C4-05 | 历史兼容 | 历史 NULL 不伪造；历史 item 不变 |
| C4-06 | 测试 | 成功/失败/取消/重试/校验失败 5 类 |

## 7. 测试方案

| # | 用例 | 断言 |
|---|---|---|
| 1 | 正常执行 | 1 Execution SUCCEEDED，统计三数一致 |
| 2 | video 不存在 | **0 Execution**，item SKIPPED |
| 3 | Collector 失败 | 1 Execution FAILED + failure_type 正确 |
| 4 | 重试 | 新 Execution，`idem_key` 不与首次冲突 |
| 5 | 取消 | Execution CANCELLED，failure_type 为空 |
| 6 | 历史 SUCCESS item | 新 Run 执行后 `run_id` 不变 |
| 7 | maxComments=5 | Python 处理 ≤5，CSV 行数 = 5 |
| 8 | maxComments=0 | 不采集，不建 Execution |

## 8. 新视频验证（C4 完成后）

前置：确认视频可访问、历史未采集、无 `uk_video_active` 冲突。
验收：至少 1 条新增评论 + CID 去重 + checkpoint 三字段一致 + 真实 Execution。

## 9. 待确认

1. 是否需要给 `collection_task_item.execution_id` 加索引（当前无，反查走全表）
2. `col_execution` 无 `item_id` 正向列，用反向指针是否可接受
3. 取消时 Execution 是否也要记录"取消发生时的统计"（当前设计只记状态）
