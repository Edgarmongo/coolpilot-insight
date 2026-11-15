"""采集服务的最小契约测试。

在容器内执行：
    cd /app/collection-service && python -m pytest tests -q
或直接运行：
    python tests/test_server.py
"""

import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from app.adapters.stub import StubAdapter  # noqa: E402
from app.server import app  # noqa: E402


def test_stub_adapter_returns_deterministic_comments():
    a = StubAdapter(content_id="v1", comment_count=3)
    comments = list(a.collect_comments("v1", 10))
    assert len(comments) == 3
    assert comments[0].external_id == "v1-c1"
    assert comments[2].likes == 3
    # 能力必须诚实标注为不可用
    assert a.capabilities.comments is False
    assert a.capabilities.status == "PENDING"


def test_stub_adapter_respects_limit():
    a = StubAdapter(content_id="v1", comment_count=50)
    assert len(list(a.collect_comments("v1", 5))) == 5


def test_stub_discover_not_supported():
    a = StubAdapter()
    try:
        list(a.discover({"keyword": "x"}))
    except NotImplementedError:
        return
    raise AssertionError("stub 不应假装支持发现")


def test_health():
    c = app.test_client()
    r = c.get("/health")
    assert r.status_code == 200
    assert r.get_json()["code"] == 0


def test_capabilities_marks_stub_unavailable():
    c = app.test_client()
    r = c.get("/v1/capabilities")
    item = r.get_json()["items"][0]
    assert item["sourceCode"] == "STUB"
    assert item["comments"] is False
    assert item["status"] == "PENDING"


def test_job_lifecycle():
    c = app.test_client()

    r = c.post("/v1/jobs", json={"externalContentId": "abc", "maxItems": 4, "jobId": "j-test-1"})
    assert r.status_code == 202
    job_id = r.get_json()["jobId"]

    # 后台线程执行，轮询至结束
    status = None
    for _ in range(50):
        s = c.get(f"/v1/jobs/{job_id}").get_json()
        status = s["status"]
        if status in ("SUCCEEDED", "FAILED"):
            break
        time.sleep(0.1)
    assert status == "SUCCEEDED", f"作业未完成: {status}"

    res = c.get(f"/v1/jobs/{job_id}/result").get_json()
    assert len(res["items"]) == 4
    assert res["done"] is True
    assert res["items"][0]["dedupStrategy"] == "CID"


def test_job_idempotent():
    c = app.test_client()
    body = {"externalContentId": "xyz", "maxItems": 2, "jobId": "j-idem"}
    assert c.post("/v1/jobs", json=body).get_json()["jobId"] == "j-idem"
    # 重复提交同一 jobId：返回既有作业，不新建
    second = c.post("/v1/jobs", json=body)
    assert second.status_code == 202
    assert second.get_json()["jobId"] == "j-idem"


def test_missing_external_id_rejected():
    c = app.test_client()
    r = c.post("/v1/jobs", json={})
    assert r.status_code == 400
    assert r.get_json()["code"] == 40001


def test_unknown_job_returns_404():
    c = app.test_client()
    assert c.get("/v1/jobs/nope").status_code == 404


if __name__ == "__main__":
    # 无 pytest 也能跑：容器内只需 python，便于验证
    failed = 0
    names = [n for n in sorted(globals()) if n.startswith("test_") and callable(globals()[n])]
    for name in names:
        try:
            globals()[name]()
            print(f"  PASS  {name}")
        except Exception as e:
            failed += 1
            print(f"  FAIL  {name}: {type(e).__name__}: {e}")
    print(f"\n{len(names) - failed}/{len(names)} passed")
    sys.exit(1 if failed else 0)
