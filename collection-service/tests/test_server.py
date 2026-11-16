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

import pytest  # noqa: E402


@pytest.fixture(autouse=True)
def _dedup_from_csv(monkeypatch):
    """HTTP 层测试不发真实去重查询（A-API 由 test_dedup 覆盖）。"""
    monkeypatch.setenv("COLLECT_DEDUP_SOURCE", "csv")


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


def _fake_registry_for_jobs():
    """构造一个"真的可执行"的注册表：VERIFIED + 证据已接受 + 函数可解析。

    为什么不在 HTTP 测试里直接用真实 Registry：生产 Registry 里没有一个能力
    同时满足 VERIFIED 与 evidence.accepted（那是 SOURCE-06 的准入要求），
    要在这里跑通生命周期，只能注入受控注册表——
    **而不是放宽派发判定**。
    """
    import types
    from app.registry.capability import (
        AccessMode, CapabilityCategory, CapabilityCode, CapabilitySpec, ImplementationStatus,
    )

    class Entry:
        connector_code = "DOUYIN_PUBLIC"
        load_status = "LOADED"
        platform_scoped = True
        error_code = None
        error_message = None

    def fake_collect(video_id, max_comments, **kwargs):
        # 只验行映射，不碰浏览器；CSV 落盘由真实采集器负责，此处不重复实现
        return types.SimpleNamespace(
            comments=[{"CID": "c1", "PARENT_CID": "", "内容": "hi", "昵称": "n",
                       "点赞": "2", "地区": "上海", "日期": "2026-01-01"}],
            ok=True, message="")

    spec = CapabilitySpec(
        code=CapabilityCode.COMMENT, category=CapabilityCategory.COMMENT, name="评论采集",
        access_modes=(AccessMode.PUBLIC,), implementation_status=ImplementationStatus.VERIFIED,
        contract_version="1.0", adapter_function="fake.collect",
        evidence={"accepted": True})

    class Caps:
        def resolve(self, name):
            return fake_collect if name == "fake.collect" else None

    class Adapters:
        def list(self):
            return [Entry()]

    class Reg:
        adapters = Adapters()
        capabilities = Caps()

        def observed_specs(self, connector):
            return [spec] if connector == "DOUYIN_PUBLIC" else []

    return Reg()


def _use_fake_registry(monkeypatch):
    import app.server as server
    monkeypatch.setattr(server, "get_registry", lambda: _fake_registry_for_jobs())
    monkeypatch.setattr(server, "_QUEUE", None)   # 队列缓存了旧 Registry，必须重建
    return server


def test_job_lifecycle(monkeypatch):
    """SOURCE-06：作业按 Registry 派发 → 排队执行 → 结果可分页拉取。"""
    _use_fake_registry(monkeypatch)
    c = app.test_client()

    r = c.post("/v1/jobs", json={
        "connectorCode": "DOUYIN_PUBLIC", "capabilityCode": "COMMENT",
        "accessMode": "PUBLIC", "externalContentId": "abc",
        "maxItems": 4, "jobId": "j-test-1",
    })
    assert r.status_code == 202
    job_id = r.get_json()["jobId"]

    status = None
    for _ in range(50):
        s = c.get(f"/v1/jobs/{job_id}").get_json()
        status = s["status"]
        if status in ("SUCCEEDED", "FAILED", "PARTIAL"):
            break
        time.sleep(0.1)
    assert status == "SUCCEEDED", f"作业未完成: {status}"

    res = c.get(f"/v1/jobs/{job_id}/result").get_json()
    assert len(res["items"]) == 1
    assert res["done"] is True
    assert res["items"][0]["dedupStrategy"] == "CID"
    assert res["items"][0]["externalCommentId"] == "c1"


def test_job_idempotent(monkeypatch):
    _use_fake_registry(monkeypatch)
    c = app.test_client()
    body = {"connectorCode": "DOUYIN_PUBLIC", "capabilityCode": "COMMENT",
            "externalContentId": "xyz", "maxItems": 2, "jobId": "j-idem"}

    first = c.post("/v1/jobs", json=body)
    assert first.status_code == 202
    assert first.get_json()["created"] is True

    second = c.post("/v1/jobs", json=body)
    assert second.status_code == 202
    assert second.get_json()["jobId"] == "j-idem"
    assert second.get_json()["created"] is False, "重复提交不得重复入队"


def test_job_requires_dispatch_target():
    """没有 connectorCode / capabilityCode 不再默认跑 STUB——那是 fail-open。"""
    c = app.test_client()
    r = c.post("/v1/jobs", json={"externalContentId": "abc"})
    assert r.status_code == 400
    assert r.get_json()["code"] == "PARAM_INVALID"


def test_job_stub_rejected_by_default(monkeypatch):
    """真实 Registry 里的 STUB 是链路验证替身，生产不允许执行。"""
    import app.server as server
    monkeypatch.setattr(server, "_QUEUE", None)
    monkeypatch.delenv("COLLECTION_ALLOW_STUB", raising=False)

    c = app.test_client()
    r = c.post("/v1/jobs", json={"connectorCode": "STUB_PUBLIC", "capabilityCode": "COMMENT",
                                 "externalContentId": "abc"})
    assert r.status_code == 403
    assert r.get_json()["code"] == "STUB_NOT_ALLOWED"


def test_job_unknown_connector(monkeypatch):
    c = app.test_client()
    r = c.post("/v1/jobs", json={"connectorCode": "NOPE", "capabilityCode": "COMMENT",
                                 "externalContentId": "abc"})
    assert r.status_code == 404
    assert r.get_json()["code"] == "CONNECTOR_NOT_FOUND"


def test_missing_external_id_rejected():
    c = app.test_client()
    r = c.post("/v1/jobs", json={"connectorCode": "DOUYIN_PUBLIC",
                                 "capabilityCode": "COMMENT"})
    assert r.status_code == 400
    assert r.get_json()["code"] == "PARAM_INVALID"


def test_unknown_job_returns_404():
    c = app.test_client()
    assert c.get("/v1/jobs/nope").status_code == 404
    assert c.post("/v1/jobs/nope/cancel").status_code == 404


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
