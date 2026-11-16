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

**已知限制**（留给 SOURCE-04）：``/v1/jobs`` 仍是 stub 实现，
生产采集走 Java → 子进程 → ``comment.py`` 的老链路，本任务不动它。
"""

from __future__ import annotations

import os
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

# 允许 `python app/server.py` 直接运行：把服务根目录加入 sys.path，
# 否则 cwd 不同会导致 `No module named 'app'`
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from flask import Flask, jsonify, request  # noqa: E402

from app.adapters.stub import StubAdapter  # noqa: E402
from app.registry.bootstrap import get_registry  # noqa: E402
from app.registry.capability import SCHEMA_VERSION  # noqa: E402
from app.registry.errors import classify_exception  # noqa: E402

app = Flask(__name__)

# 作业内存表：进程重启即丢。当前是「单实例 + 短生命周期」的取舍，
# 任务状态的权威记录在 Java 侧的 col_task_item，这里只是执行上下文。
_JOBS: dict[str, "Job"] = {}
_LOCK = threading.Lock()

_INTERNAL_TOKEN = os.environ.get("INTERNAL_TOKEN", "")


@dataclass
class Job:
    job_id: str
    source_code: str
    external_content_id: str
    max_items: int
    status: str = "ACCEPTED"          # ACCEPTED/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED
    collected: int = 0
    total: int = 0
    items: list[dict[str, Any]] = field(default_factory=list)
    error_code: str | None = None
    error_message: str | None = None
    started_at: float | None = None
    updated_at: float = field(default_factory=time.time)


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


def _run_job(job: Job) -> None:
    try:
        with _LOCK:
            job.status = "RUNNING"
            job.started_at = time.time()
            job.updated_at = job.started_at

        adapter = StubAdapter(content_id=job.external_content_id, comment_count=job.max_items)
        comments = list(adapter.collect_comments(job.external_content_id, job.max_items))

        with _LOCK:
            job.items = [
                {
                    "externalCommentId": c.external_id,
                    "parentExternalId": c.parent_external_id,
                    "text": c.text,
                    "nickname": c.nickname,
                    "likes": c.likes,
                    "ipRegion": c.ip_region,
                    "publishedAt": c.published_at,
                    "dedupStrategy": "CID" if c.external_id else "FINGERPRINT",
                }
                for c in comments
            ]
            job.collected = len(job.items)
            job.total = len(job.items)
            job.status = "SUCCEEDED"
            job.updated_at = time.time()
    except Exception as e:  # 单作业失败不能影响服务
        spec = classify_exception(e)
        with _LOCK:
            job.status = "FAILED"
            job.error_code = spec.code
            job.error_message = str(e)[:500]
            job.updated_at = time.time()


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
# 作业（兼容期：仍为 stub 实现）
# ----------------------------------------------------------------------
@app.post("/v1/jobs")
def create_job():
    denied = _require_token()
    if denied:
        return denied

    body = request.get_json(silent=True) or {}
    external_id = (body.get("externalContentId") or "").strip()
    if not external_id:
        return jsonify({"code": 40001, "message": "externalContentId 不能为空"}), 400

    job_id = body.get("jobId") or str(uuid.uuid4())[:12]
    with _LOCK:
        if job_id in _JOBS:
            # 幂等：同一 jobId 重复提交返回既有作业，不重复执行
            return jsonify({"jobId": job_id, "status": _JOBS[job_id].status}), 202
        job = Job(
            job_id=job_id,
            source_code=body.get("sourceCode", "STUB"),
            external_content_id=external_id,
            max_items=int(body.get("maxItems") or 100),
        )
        _JOBS[job_id] = job

    threading.Thread(target=_run_job, args=(job,), daemon=True).start()
    return jsonify({"jobId": job_id, "status": "ACCEPTED"}), 202


@app.get("/v1/jobs/<job_id>")
def job_status(job_id: str):
    job = _JOBS.get(job_id)
    if not job:
        return jsonify({"code": 40400, "message": "作业不存在"}), 404
    return jsonify({
        "jobId": job.job_id,
        "status": job.status,
        "collected": job.collected,
        "total": job.total,
        "errorCode": job.error_code,
        "errorMessage": job.error_message,
    })


@app.get("/v1/jobs/<job_id>/result")
def job_result(job_id: str):
    job = _JOBS.get(job_id)
    if not job:
        return jsonify({"code": 40400, "message": "作业不存在"}), 404
    cursor = int(request.args.get("cursor", 0))
    size = int(request.args.get("size", 100))
    chunk = job.items[cursor:cursor + size]
    next_cursor = cursor + len(chunk)
    return jsonify({
        "items": chunk,
        "nextCursor": next_cursor,
        "done": next_cursor >= len(job.items),
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("COLLECTION_PORT", "5001")))
