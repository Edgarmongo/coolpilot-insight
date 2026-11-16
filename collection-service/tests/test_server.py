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
from app.registry.capability import SCHEMA_VERSION  # noqa: E402
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


# ----------------------------------------------------------------------
# SOURCE-03：Registry 驱动的健康检查与能力接口
# ----------------------------------------------------------------------
def test_health_reports_real_registry():
    """/health 必须反映真实注册表，而不是硬编码的 adapter 列表。"""
    c = app.test_client()
    r = c.get("/health")
    assert r.status_code == 200
    body = r.get_json()
    assert body["code"] == 0
    registry = body["registry"]
    assert registry["schemaVersion"] == SCHEMA_VERSION
    codes = {a["connectorCode"] for a in registry["adapters"]}
    assert "DOUYIN_PUBLIC" in codes
    assert "STUB_PUBLIC" in codes
    assert registry["loadedCount"] >= 2
    assert registry["failedCount"] == 0
    # 旧字段仍在（兼容期）
    assert isinstance(body["adapters"], list)
    assert "DOUYIN_PUBLIC" in body["adapters"]
    assert body["error"] is None
    assert body["meta"]["traceId"]


def test_health_does_not_trigger_platform_requests():
    """健康检查不得发起真实平台采集——只读注册表。"""
    from app.adapters.douyin import collector

    called = []
    original = collector.collect_comments

    def _spy(*args, **kwargs):
        called.append(1)
        return original(*args, **kwargs)

    collector.collect_comments = _spy
    try:
        c = app.test_client()
        c.get("/health")
        c.get("/v1/capabilities")
        c.get("/v1/adapters")
    finally:
        collector.collect_comments = original
    assert not called, "健康检查触发了真实采集"


def test_capabilities_exposes_v1_schema():
    c = app.test_client()
    r = c.get("/v1/capabilities")
    assert r.status_code == 200
    body = r.get_json()
    assert body["schemaVersion"] == SCHEMA_VERSION
    assert body["connectors"], "必须有 v1 平台文档"
    doc = next(d for d in body["connectors"] if d["connectorCode"] == "DOUYIN_PUBLIC")
    assert doc["sourceCode"] == "DOUYIN"
    assert doc["connectorVersion"] == "1.0.0"
    for cap in doc["capabilities"]:
        assert cap["adapterFunction"]
        assert cap["implementationStatus"] in (
            "UNVERIFIED", "IMPLEMENTED", "VERIFIED", "DEGRADED", "DISABLED", "UNSUPPORTED")
    assert body["data"]["connectors"] == body["connectors"]


def test_capabilities_legacy_items_preserved():
    """旧结构必须逐字保持，避免打断既有调用方。"""
    c = app.test_client()
    body = c.get("/v1/capabilities").get_json()
    item = body["items"][0]
    assert item["sourceCode"] == "STUB"
    assert item["comments"] is False
    assert item["keywordSearch"] is False
    assert item["status"] == "PENDING"


def test_adapters_endpoint_lists_real_connectors():
    c = app.test_client()
    r = c.get("/v1/adapters")
    assert r.status_code == 200
    body = r.get_json()
    codes = {a["connectorCode"] for a in body["data"]["adapters"]}
    assert {"DOUYIN_PUBLIC", "STUB_PUBLIC"} <= codes
    douyin = next(a for a in body["data"]["adapters"] if a["connectorCode"] == "DOUYIN_PUBLIC")
    assert douyin["loadStatus"] == "LOADED"
    assert douyin["capabilityCount"] == 4


def test_adapter_detail_and_capabilities():
    c = app.test_client()
    detail = c.get("/v1/adapters/DOUYIN_PUBLIC").get_json()
    assert detail["data"]["adapter"]["connectorCode"] == "DOUYIN_PUBLIC"
    assert len(detail["data"]["adapter"]["capabilities"]) == 4

    caps = c.get("/v1/adapters/DOUYIN_PUBLIC/capabilities").get_json()["data"]
    assert caps["sourceCode"] == "DOUYIN"
    assert caps["schemaVersion"] == SCHEMA_VERSION
    assert len(caps["capabilities"]) == 4


def test_adapter_capabilities_for_non_platform_connector():
    """链路验证替身没有 sourceCode，不产出 v1 平台文档。"""
    c = app.test_client()
    r = c.get("/v1/adapters/STUB_PUBLIC/capabilities")
    assert r.status_code == 200
    caps = r.get_json()["data"]
    assert caps["capabilities"] == []
    assert "sourceCode" not in caps


def test_unknown_adapter_returns_404():
    c = app.test_client()
    r = c.get("/v1/adapters/NOPE")
    assert r.status_code == 404
    assert r.get_json()["code"] == 40400


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
