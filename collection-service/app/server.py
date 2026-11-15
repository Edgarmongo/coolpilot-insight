"""采集服务 HTTP 接口。

契约见 docs/architecture/refactor/R1-07-service-communication.md §3：

    GET  /health
    GET  /v1/capabilities
    POST /v1/jobs                 创建采集作业
    GET  /v1/jobs/<id>            查询状态与进度
    GET  /v1/jobs/<id>/result     拉取结果（分页游标）

作业在后台线程执行，接口立即返回 jobId——采集耗时 10~120 秒，
同步 HTTP 必然超时，这也是契约里定为异步的原因。

当前只接了 stub 适配器（链路验证用）。抖音适配器仍以脚本形式存在，
R3-04 后续步骤再包装成实现同一契约的 adapter。
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
        with _LOCK:
            job.status = "FAILED"
            job.error_code = "ADAPTER_ERROR"
            job.error_message = str(e)[:500]
            job.updated_at = time.time()


@app.get("/health")
def health():
    return jsonify({"code": 0, "msg": "ok", "adapters": ["stub"]})


@app.get("/v1/capabilities")
def capabilities():
    stub = StubAdapter()
    cap = stub.capabilities
    return jsonify({
        "items": [{
            "sourceCode": stub.source_code,
            "comments": cap.comments,
            "keywordSearch": cap.keyword_search,
            "status": cap.status,
            "note": cap.note,
        }]
    })


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
