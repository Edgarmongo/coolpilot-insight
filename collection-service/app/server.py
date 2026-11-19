"""采集服务 HTTP 接口。

契约见 docs/architecture/refactor/R1-07-service-communication.md §3：

    GET  /health
    GET  /v1/capabilities
    POST /v1/jobs                 创建采集作业
    GET  /v1/jobs/<id>            查询状态与进度
    GET  /v1/jobs/<id>/result     拉取结果（分页游标）

作业在后台线程执行，接口立即返回 jobId——采集耗时 10~120 秒，
同步 HTTP 必然超时，这也是契约里定为异步的原因。

**SOURCE-03 变更**：
``/health`` 与 ``/v1/capabilities`` 不再返回硬编码 stub，
而是基于真实的 Adapter / Capability Registry 输出（SOURCE-02 §02-03 v1 Schema）。
旧字段保留在兼容期，新结构以 ``data`` / ``registry`` / ``connectors`` 并列提供，
Java 调用方不必立刻改造。

新增（SOURCE-02 §02-05）：

    GET  /v1/adapters
    GET  /v1/adapters/<connectorCode>
    GET  /v1/adapters/<connectorCode>/capabilities

**SOURCE-04-02 新增**：

    GET  /v1/registry/snapshot

一次返回 ``capabilities`` + ``diagnostics`` + ``adapters``，并带不可变的
``snapshotId``（内容哈希）。存在的理由：Java 计算 Effective 需要能力清单与
Adapter 诊断，若分两次请求，Registry 可能在两次之间变化，两端口径不一致就会
拼出一个"从未同时存在过"的事实。统一快照从源头消除这个窗口。

原有 ``/v1/capabilities`` 与 ``/v1/adapters`` **继续保留**（不破坏 SOURCE-03
已冻结的契约），只是不再作为 Java 的一致性计算输入。

**已知限制**（留给 SOURCE-04）：``/v1/jobs`` 仍是 stub 实现，
生产采集走 Java → 子进程 → ``comment.py`` 的老链路，本任务不动它。
"""

from __future__ import annotations

import os
import sys
import threading
import uuid
from typing import Any

# 允许 `python app/server.py` 直接运行：把服务根目录加入 sys.path，
# 否则 cwd 不同会导致 `No module named 'app'`
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from flask import Flask, jsonify, request  # noqa: E402

from app.jobs import dispatch as job_dispatch  # noqa: E402
from app.jobs.executor import build_runner  # noqa: E402
from app.jobs.queue import Job, JobQueue, QueueFull  # noqa: E402
from app.registry.bootstrap import get_registry  # noqa: E402
from app.registry.capability import SCHEMA_VERSION  # noqa: E402

app = Flask(__name__)

# 作业队列：进程重启即丢。当前是「单实例 + 短生命周期」的取舍，
# 任务状态的权威记录在 Java 侧的 col_task_item，这里只是执行上下文。
#
# 队列是**单 worker** 的：Chrome 只有一个，共享 chrome-data 卷（ADR-007），
# 并发启动会撞 profile 锁，表现为"采集随机失败"。
_QUEUE: JobQueue | None = None
_QUEUE_LOCK = threading.Lock()

_INTERNAL_TOKEN = os.environ.get("INTERNAL_TOKEN", "")


def _job_queue() -> JobQueue:
    """延迟构造：runner 需要 Registry，而 Registry 首访才装配。"""
    global _QUEUE
    with _QUEUE_LOCK:
        if _QUEUE is None:
            save_dir = os.environ.get("PY_DATAS_DIR") or "/app/collection-service/datas"
            _QUEUE = JobQueue(build_runner(get_registry(), save_dir))
        return _QUEUE


# ----------------------------------------------------------------------
# 响应信封
# ----------------------------------------------------------------------
def _trace_id() -> str:
    """沿用调用方传入的 traceId，没有就生成一个。"""
    return request.headers.get("X-Trace-Id") or request.headers.get("X-Request-Id") \
        or uuid.uuid4().hex[:16]


def _envelope(data: Any, error: Any = None) -> dict[str, Any]:
    """SOURCE-02 §02-05 的统一响应信封。"""
    return {"code": 0, "msg": "ok", "data": data, "error": error,
            "meta": {"traceId": _trace_id()}}


def _error_envelope(code: int, message: str, http_status: int) -> tuple[Any, int]:
    """错误响应。保留旧的 ``code``/``message`` 字段，同时补上信封字段。"""
    return jsonify({
        "code": code,
        "message": message,
        "data": None,
        "error": {"code": code, "message": message},
        "meta": {"traceId": _trace_id()},
    }), http_status


def _require_token():
    """服务间鉴权。未配置 token 时放行（本地开发），配置后强制校验。"""
    if not _INTERNAL_TOKEN:
        return None
    if request.headers.get("X-Internal-Token") != _INTERNAL_TOKEN:
        return jsonify({"code": 401, "message": "invalid internal token"}), 401
    return None


# ----------------------------------------------------------------------
# 健康检查与能力
# ----------------------------------------------------------------------
@app.get("/health")
def health():
    """服务存活 + Registry 状态。

    **只读本地注册表，绝不触发平台采集请求**（SOURCE-02 §02-05 硬规则）。

    本端点**保持公开**：容器健康检查需要在没有令牌的情况下探活，
    且响应只含适配器元信息（模块名、版本、能力数量），不含任何凭证信息。
    能力清单等业务端点（``/v1/capabilities``、``/v1/adapters*``）在配置了
    ``INTERNAL_TOKEN`` 时要求携带 ``X-Internal-Token``。
    """
    registry = get_registry()
    payload = registry.health_payload()
    return jsonify({
        # —— 旧字段（兼容期保留）——
        "code": 0,
        "msg": "ok",
        "adapters": [a["connectorCode"] for a in payload["adapters"]],
        # —— 新结构 ——
        "registry": payload,
        "data": payload,
        "error": None,
        "meta": {"traceId": _trace_id()},
    })


@app.get("/v1/capabilities")
def capabilities():
    """能力清单。

    * ``items`` —— 旧结构，字段与语义保持不变（兼容期）；
    * ``connectors`` —— SOURCE-02 §02-03 v1 Schema 的平台能力文档；
    * ``data`` —— 与 ``connectors`` 相同，放在统一信封里。
    """
    denied = _require_token()
    if denied is not None:
        return denied
    registry = get_registry()
    items = registry.legacy_items()
    connectors = registry.v1_documents()
    return jsonify({
        "code": 0,
        "msg": "ok",
        "items": items,
        "schemaVersion": SCHEMA_VERSION,
        "connectors": connectors,
        "data": {"schemaVersion": SCHEMA_VERSION, "connectors": connectors},
        "error": None,
        "meta": {"traceId": _trace_id()},
    })


@app.get("/v1/registry/snapshot")
def registry_snapshot():
    """**统一 Registry 快照**（SOURCE-04-02 §A）。

    设计要点（缺一不可）：

    1. ``capabilities`` 与 ``diagnostics`` 来自**同一次**评估，不是两次独立计算；
    2. ``snapshotId`` 由规范化后的快照**内容**计算 —— 内容不变则 id 不变，
       任一变化（Adapter 加载状态 / 能力 / 证据 / 诊断）都会换 id；
    3. ``generatedAt`` 只用于观察与排障，**不参与** id 计算，
       因此**不能**用它判断一致性；
    4. 本端点只读本地注册表，**不触发任何平台请求**。

    Java 侧只能消费完整快照：不得把两个不同快照的内容合并，
    也不得在本端点失败时回退到双端点拼接。
    """
    denied = _require_token()
    if denied is not None:
        return denied
    registry = get_registry()
    return jsonify(_envelope(registry.snapshot()))


@app.get("/v1/adapters")
def adapters():
    """Observed 适配器清单（含加载状态与能力数）。"""
    denied = _require_token()
    if denied is not None:
        return denied
    registry = get_registry()
    views = registry.adapter_views()
    data = {
        "schemaVersion": SCHEMA_VERSION,
        "adapters": views,
        "diagnostics": registry.diagnostics(),
    }
    return jsonify(_envelope(data))


@app.get("/v1/adapters/<connector_code>")
def adapter_detail(connector_code: str):
    """单个适配器详情 + 它的 Observed 能力。"""
    denied = _require_token()
    if denied is not None:
        return denied
    registry = get_registry()
    entry = registry.adapters.entry(connector_code)
    if entry is None:
        return _error_envelope(40400, f"接入实现不存在: {connector_code}", 404)
    specs = registry.observed_specs(connector_code)
    view = entry.to_metadata().to_v1()
    view["capabilities"] = [s.to_v1() for s in specs]
    view["capabilityCount"] = len(specs)
    data = {
        "schemaVersion": SCHEMA_VERSION,
        "adapter": view,
        "diagnostics": [d.to_v1() for d in registry.capabilities.diagnostics(connector_code)],
    }
    return jsonify(_envelope(data))


@app.get("/v1/adapters/<connector_code>/capabilities")
def adapter_capabilities(connector_code: str):
    """该接入的 Observed 能力清单（SOURCE-02 §02-03 Schema）。

    非平台接入（如链路验证替身）没有 ``sourceCode``，因此不产出 v1 平台文档，
    只返回空能力清单 + 说明。
    """
    denied = _require_token()
    if denied is not None:
        return denied
    registry = get_registry()
    entry = registry.adapters.entry(connector_code)
    if entry is None:
        return _error_envelope(40400, f"接入实现不存在: {connector_code}", 404)
    specs = registry.observed_specs(connector_code)
    if not entry.platform_scoped:
        data = {
            "schemaVersion": SCHEMA_VERSION,
            "connectorCode": connector_code,
            "capabilities": [],
            "note": "非平台接入（链路验证替身），不产出 sourceCode 与 v1 平台文档",
        }
    else:
        data = {
            "schemaVersion": SCHEMA_VERSION,
            "sourceCode": entry.source_code,
            "connectorCode": connector_code,
            "connectorVersion": entry.connector_version,
            "capabilities": [s.to_v1() for s in specs],
        }
    return jsonify(_envelope(data))


# ----------------------------------------------------------------------
# 作业（SOURCE-06：按 Registry 派发 + 单 worker 排队）
# ----------------------------------------------------------------------
@app.post("/v1/jobs")
def create_job():
    """创建采集作业。

    **提交前就派发一次**：不能跑的作业不该进入队列——
    让调用方等 30 秒才拿到 409，比立刻拒绝糟糕得多。
    执行时会再判一次（排队期间 Registry 可能已经变了）。
    """
    denied = _require_token()
    if denied:
        return denied

    body = request.get_json(silent=True) or {}
    external_id = str(body.get("externalContentId") or "").strip()
    if not external_id:
        return jsonify({"code": "PARAM_INVALID", "message": "externalContentId 不能为空"}), 400

    connector_code = str(body.get("connectorCode") or "").strip()
    capability_code = str(body.get("capabilityCode") or "").strip()
    access_mode = str(body.get("accessMode") or "PUBLIC").strip()
    if not connector_code or not capability_code:
        return jsonify({
            "code": "PARAM_INVALID",
            "message": "connectorCode 与 capabilityCode 必填（派发由 Registry 决定，不接受隐式默认）",
        }), 400

    try:
        job_dispatch.resolve(get_registry(), connector_code, capability_code, access_mode)
    except job_dispatch.DispatchError as e:
        return jsonify(e.as_body()), e.http_status

    job_id = str(body.get("jobId") or uuid.uuid4().hex[:12])
    max_items = int(body.get("maxItems") or 100)
    job = Job(
        job_id=job_id,
        connector_code=connector_code,
        capability_code=capability_code,
        access_mode=access_mode,
        external_content_id=external_id,
        max_items=max_items,
        params=dict(body.get("params") or {}),
    )

    try:
        stored, created = _job_queue().submit(job)
    except QueueFull as e:
        return jsonify({"code": "QUEUE_FULL", "message": str(e)}), 429

    return jsonify({
        "jobId": stored.job_id,
        "status": stored.status,
        "queuePosition": _job_queue().queue_position(stored),
        "created": created,
    }), 202


@app.get("/v1/jobs/<job_id>")
def job_status(job_id: str):
    job = _job_queue().get(job_id)
    if not job:
        return jsonify({"code": "JOB_NOT_FOUND", "message": "作业不存在"}), 404
    return jsonify(job.to_status(_job_queue().queue_position(job)))


@app.get("/v1/jobs/<job_id>/result")
def job_result(job_id: str):
    job = _job_queue().get(job_id)
    if not job:
        return jsonify({"code": "JOB_NOT_FOUND", "message": "作业不存在"}), 404
    cursor = max(int(request.args.get("cursor", 0) or 0), 0)
    size = min(max(int(request.args.get("size", 100) or 100), 1), 1000)
    chunk = job.items[cursor:cursor + size]
    next_cursor = cursor + len(chunk)
    return jsonify({
        "jobId": job.job_id,
        "status": job.status,
        "items": chunk,
        "nextCursor": next_cursor,
        "done": next_cursor >= len(job.items),
    })


@app.post("/v1/jobs/<job_id>/cancel")
def job_cancel(job_id: str):
    """尽力取消：排队中立即生效；执行中只置标志，结束时丢弃结果。

    **不杀 Chrome**：强杀会留下 profile 锁，下一次启动直接失败。
    """
    denied = _require_token()
    if denied:
        return denied

    job = _job_queue().cancel(job_id)
    if job is None:
        return jsonify({"code": "JOB_NOT_FOUND", "message": "作业不存在"}), 404
    return jsonify({
        "jobId": job.job_id,
        "status": job.status,
        "cancelRequested": job.cancel_requested,
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("COLLECTION_PORT", "5001")))
