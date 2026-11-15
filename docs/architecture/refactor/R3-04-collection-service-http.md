# R3-04 · 批次报告：采集服务化（stub 版）

日期：2026-11-15
范围：`collection-service` 提供 HTTP 接口 + stub 适配器，**Java 侧未切换到 HTTP**

## 1. 交付内容

| 文件 | 说明 |
|---|---|
| `collection-service/app/adapters/stub.py` | `StubAdapter implements PlatformAdapter`，返回确定性数据 |
| `collection-service/app/server.py` | Flask 服务，5 个端点（契约见 R1-07 §3） |
| `collection-service/requirements.txt` | Flask + DrissionPage |
| `collection-service/tests/test_server.py` | 9 条契约测试，无 pytest 也能跑 |

## 2. 为什么先做 stub

R3-03a 里我明确写过：「未执行真实采集，端到端待 R3-04 用可控 stub 验证」。
stub 的价值不是"演示能跑"，而是：

1. 把 `PlatformAdapter` 契约从**文档**变成**可执行代码**——实现一个 adapter 到底要实现哪些方法；
2. 让「Java → 采集服务 → 结果」这条链路可以在不碰抖音、不需要 Chrome 的前提下验证；
3. 后续接入小红书/快手时，有最小参照物。

**stub 的能力声明是诚实的**：

```python
Capability(comments=False, keyword_search=False, status="PENDING",
           note="stub 适配器，仅用于链路验证，不代表真实平台能力")
```

测试里专门断言 `comments is False`、`status == "PENDING"`——防止以后被人当成"可用适配器"。

## 3. HTTP 契约（与 R1-07 一致）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 存活 + 已加载适配器 |
| GET | `/v1/capabilities` | 各平台能力状态 |
| POST | `/v1/jobs` | 创建作业，返回 `202 + jobId` |
| GET | `/v1/jobs/<id>` | 状态与进度 |
| GET | `/v1/jobs/<id>/result` | 分页拉取结果 |

异步的理由：采集耗时 10~120 秒，同步 HTTP 必然超时。

**幂等**：同一 `jobId` 重复提交返回既有作业，不重复执行（实测通过）。

**鉴权**：配置了 `INTERNAL_TOKEN` 时校验 `X-Internal-Token`；未配置则放行（本地开发）。

## 4. 测试报告（真实执行，容器内）

```
$ docker exec -w /app/collection-service cpi-backend python tests/test_server.py
  PASS  test_capabilities_marks_stub_unavailable
  PASS  test_health
  PASS  test_job_idempotent
  PASS  test_job_lifecycle
  PASS  test_missing_external_id_rejected
  PASS  test_stub_adapter_respects_limit
  PASS  test_stub_adapter_returns_deterministic_comments
  PASS  test_stub_discover_not_supported
  PASS  test_unknown_job_returns_404

9/9 passed
```

覆盖：能力诚实标注、条数上限、**不支持发现时必须报错而不是返回假数据**、
健康检查、能力接口、作业全生命周期、幂等、参数校验、未知作业 404。

## 5. 真实 HTTP 验证（不是 test_client）

在容器内以进程方式启动服务，用 curl 走完整链路：

```
GET  /health                 → {"code":0,"msg":"ok","adapters":["stub"]}
POST /v1/jobs                → {"jobId":"e2e-1","status":"ACCEPTED"}
GET  /v1/jobs/e2e-1          → {"status":"SUCCEEDED","collected":5,"total":5}
GET  /v1/jobs/e2e-1/result   → {"items":[...2 条...],"nextCursor":2,"done":false}
```

## 6. 修掉的一个问题

`python app/server.py` 直接运行时报 `No module named 'app'`——因为 `sys.path[0]` 是 `app/` 目录而非服务根目录。
已在 `server.py` 顶部加路径引导，并注释原因。这类问题只在"换种方式启动"时暴露，测试用 `test_client` 是发现不了的。

## 7. Java 侧为什么没接

**刻意不接**。原因：当前 `CollectServiceImpl` 的数据流是「脚本写 CSV → 清洗读 CSV」，
HTTP 服务返回的是 JSON 评论列表。要让 Java 用 HTTP，必须同时改造成「评论直接入库」，
那属于 R3-05（content 的 ingest）范围。

现在就写一个 `HttpCollectionClient` 会是**没有调用方的类**，违反"不建无用途空类"。
接口 `CollectionClient` 已经存在（R3-03a），换实现只是 R3-05 的一个动作。

## 8. 遗留（明确记录）

| 项 | 说明 |
|---|---|
| 抖音适配器未包装 | `comment.py` 仍是脚本，未实现 `PlatformAdapter`；需要保持 CSV 输出兼容 |
| 采集服务无独立容器 | 目前可运行在 backend 容器内，独立 Dockerfile（含 Chrome）待 R3-05 |
| 作业表在内存 | 进程重启即丢；权威状态在 Java 的 `col_task_item` |
| `discover` 未实现 | stub 明确抛 NotImplementedError |

## 9. 状态

R3 完成度：

| 任务 | 状态 |
|---|---|
| R3-01 库基线 | ✅（未切库） |
| R3-02 source | ✅ |
| R3-03a 外部调用收敛 | ✅ |
| R3-03b collection 模块 | ✅ |
| R3-04 采集服务化 | ◐ 服务端完成，Java 接入待 R3-05 |
| R3-05 content + 切库 | ❌ 未开始 |
