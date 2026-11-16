"""统一 Registry 快照测试（SOURCE-04-02 §A / 验收 A01~A04）。

覆盖：

* ``snapshotId`` 由**内容**决定：同内容同 ID、内容变化换 ID、``generatedAt`` 不参与
* ``capabilities`` 与 ``diagnostics`` **同源**（同一份评估，不是两次计算）
* 原有 ``/v1/capabilities`` 与 ``/v1/adapters`` 仍然正常（不破坏 SOURCE-03 契约）
* 快照结构完整、必需字段齐全

运行（容器内）::

    cd /app/collection-service && /opt/venv/bin/python tests/test_snapshot.py
"""

from __future__ import annotations

import json
import os
import sys
import tempfile

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.registry.bootstrap import build_registry  # noqa: E402
from app.registry.capability import (  # noqa: E402
    AccessMode,
    CapabilityCategory,
    CapabilityCode,
    CapabilitySpec,
    ImplementationStatus,
    SCHEMA_VERSION,
)

CONNECTOR = "DOUYIN_PUBLIC"


def _empty_evidence_path() -> str:
    """一个不存在的证据路径 → 所有能力停在 UNVERIFIED。"""
    fd, path = tempfile.mkstemp(suffix=".json")
    os.close(fd)
    os.remove(path)
    return path


# ----------------------------------------------------------------------
# A01 快照结构与字段
# ----------------------------------------------------------------------
def test_snapshot_has_required_fields():
    snap = build_registry().snapshot()
    for key in ("snapshotId", "contractVersion", "schemaVersion",
                "generatedAt", "adapters", "capabilities", "diagnostics"):
        assert key in snap, f"快照缺少必需字段: {key}"
    assert snap["schemaVersion"] == SCHEMA_VERSION
    # contractVersion 与 schemaVersion **独立演进**，不得断言二者相等。
    # 之前 `SNAPSHOT_CONTRACT_VERSION = SCHEMA_VERSION` 的写法会让
    # "Schema 没变但快照协议变了" 无法被 Java 检出（SOURCE-04-02 评审 M5）。
    assert snap["contractVersion"] != ""
    assert "." in snap["contractVersion"]
    assert isinstance(snap["adapters"], list)
    assert isinstance(snap["capabilities"], list)
    assert isinstance(snap["diagnostics"], list)


def test_snapshot_id_is_content_addressed():
    """snapshotId 必须形如 sha256:<64 hex>，不是时间戳。"""
    snap = build_registry().snapshot()
    sid = snap["snapshotId"]
    assert sid.startswith("sha256:"), sid
    digest = sid.split(":", 1)[1]
    assert len(digest) == 64, digest
    int(digest, 16)  # 必须是合法十六进制


# ----------------------------------------------------------------------
# A03 ID 稳定性：同内容同 ID
# ----------------------------------------------------------------------
def test_same_content_same_id():
    registry = build_registry()
    a = registry.snapshot()
    b = registry.snapshot()
    assert a["snapshotId"] == b["snapshotId"], "同一 Registry 连续两次快照 ID 必须相同"


def test_two_registries_with_same_content_share_id():
    """两个独立构建、内容相同的 Registry 必须产生相同 ID。"""
    a = build_registry().snapshot()
    b = build_registry().snapshot()
    assert a["snapshotId"] == b["snapshotId"]


def test_generated_at_does_not_affect_id():
    """generatedAt 不参与 ID 计算 —— 否则每次请求 ID 都变。"""
    registry = build_registry()
    a = registry.snapshot()
    b = registry.snapshot()
    assert a["snapshotId"] == b["snapshotId"]
    # generatedAt 是独立字段，与 ID 无关
    assert "generatedAt" in a and "generatedAt" in b


# ----------------------------------------------------------------------
# A03 内容变化 → ID 变化
# ----------------------------------------------------------------------
def test_evidence_change_changes_id():
    """证据不同 → implementationStatus 不同 → ID 必须不同。"""
    with_evidence = build_registry().snapshot()
    without_evidence = build_registry(_empty_evidence_path()).snapshot()
    assert with_evidence["snapshotId"] != without_evidence["snapshotId"], \
        "证据变化必须改变 snapshotId"


def test_declaring_new_capability_changes_id():
    """新增一条能力声明 → ID 必须不同。"""
    registry = build_registry()
    before = registry.snapshot()["snapshotId"]

    registry.capabilities.declare(CONNECTOR, CapabilitySpec(
        code=CapabilityCode.AUTHOR_PROFILE,
        category=CapabilityCategory.USER,
        name="公开用户资料",
        access_modes=(AccessMode.PUBLIC,),
        implementation_status=ImplementationStatus.UNVERIFIED,
        contract_version="1.0",
        adapter_function="app.registry.bootstrap.load_evidence",
    ))
    after = registry.snapshot()["snapshotId"]
    assert before != after, "新增能力声明必须改变 snapshotId"


def test_broken_function_changes_both_diagnostics_and_id():
    """函数解析失败 → diagnostics 多一条，ID 也变。"""
    registry = build_registry()
    before = registry.snapshot()

    registry.capabilities.declare(CONNECTOR, CapabilitySpec(
        code=CapabilityCode.MEDIA_DOWNLOAD,
        category=CapabilityCategory.MEDIA,
        name="媒体下载",
        access_modes=(AccessMode.PUBLIC,),
        implementation_status=ImplementationStatus.UNVERIFIED,
        contract_version="1.0",
        adapter_function="app.does.not.exist.nope",
    ))
    after = registry.snapshot()

    assert after["snapshotId"] != before["snapshotId"]
    new_diags = [d for d in after["diagnostics"]
                 if d.get("capabilityCode") == CapabilityCode.MEDIA_DOWNLOAD]
    assert new_diags, "函数解析失败必须产生诊断"
    assert new_diags[0]["reason"] == "INVALID_IMPLEMENTATION"


def test_snapshot_after_mutation_is_fully_consistent():
    """模拟「两次请求之间 Registry 发生变化」：新快照必须**整体**反映新状态。

    这是验收要求里那条集成测试的 Python 侧落点：
    变化后的快照不能是"新旧混合"——不能既保留旧 ID，又包含新能力。
    """
    registry = build_registry()
    before = registry.snapshot()

    registry.capabilities.declare(CONNECTOR, CapabilitySpec(
        code=CapabilityCode.AUTHOR_POSTS,
        category=CapabilityCategory.USER,
        name="公开作品列表",
        access_modes=(AccessMode.PUBLIC,),
        implementation_status=ImplementationStatus.UNVERIFIED,
        contract_version="1.0",
        adapter_function="app.registry.bootstrap.load_evidence",
    ))
    after = registry.snapshot()

    # 1) ID 必须变
    assert after["snapshotId"] != before["snapshotId"]

    # 2) 新快照必须包含新能力（整体更新，不是部分保留）
    codes = {cap["code"]
             for conn in after["capabilities"]
             for cap in conn["capabilities"]}
    assert CapabilityCode.AUTHOR_POSTS in codes, "新快照必须包含新增能力"

    # 3) 旧能力不能丢
    assert CapabilityCode.COMMENT in codes

    # 4) 内部仍然自洽（同源校验在变化后依旧成立）
    observed = {(c["connectorCode"], cap["code"])
                for c in after["capabilities"] for cap in c["capabilities"]}
    diagnosed = {(d["connectorCode"], d["capabilityCode"])
                 for d in after["diagnostics"] if d.get("capabilityCode")}
    assert not (observed & diagnosed), "变化后的快照仍须内部自洽"

    # 5) 旧快照对象不可变：before 的内容不能被后续操作污染
    before_codes = {cap["code"]
                    for conn in before["capabilities"]
                    for cap in conn["capabilities"]}
    assert CapabilityCode.AUTHOR_POSTS not in before_codes, \
        "已经取出的旧快照不应被后续变化污染"


# ----------------------------------------------------------------------
# A02 同源：capabilities 与 diagnostics 互相排斥
# ----------------------------------------------------------------------
def test_capabilities_and_diagnostics_are_disjoint():
    """同一次评估里，一条能力要么进 capabilities，要么进 diagnostics，不能两边都有。"""
    snap = build_registry().snapshot()

    observed = set()
    for connector in snap["capabilities"]:
        for cap in connector["capabilities"]:
            observed.add((connector["connectorCode"], cap["code"]))

    diagnosed = {
        (d["connectorCode"], d["capabilityCode"])
        for d in snap["diagnostics"]
        if d.get("capabilityCode")
    }

    overlap = observed & diagnosed
    assert not overlap, f"同一能力不能同时出现在能力清单与诊断里: {overlap}"


def test_snapshot_capabilities_match_capabilities_endpoint():
    """快照的 capabilities 与 /v1/capabilities 的 connectors 语义一致（不影响旧端点）。"""
    from app.server import app

    registry_snap = build_registry().snapshot()
    client = app.test_client()
    resp = client.get("/v1/capabilities")
    assert resp.status_code == 200
    body = resp.get_json()

    snap_codes = sorted(
        (c["connectorCode"], cap["code"])
        for c in registry_snap["capabilities"] for cap in c["capabilities"]
    )
    endpoint_codes = sorted(
        (c["connectorCode"], cap["code"])
        for c in body["connectors"] for cap in c["capabilities"]
    )
    assert snap_codes == endpoint_codes


# ----------------------------------------------------------------------
# A01 / A04 HTTP 端点
# ----------------------------------------------------------------------
def test_snapshot_endpoint_returns_envelope():
    from app.server import app

    client = app.test_client()
    resp = client.get("/v1/registry/snapshot")
    assert resp.status_code == 200
    body = resp.get_json()
    assert body["code"] == 0
    assert body["error"] is None
    data = body["data"]
    assert data["snapshotId"].startswith("sha256:")
    assert "capabilities" in data and "diagnostics" in data


def test_snapshot_endpoint_id_stable_across_calls():
    from app.server import app

    client = app.test_client()
    a = client.get("/v1/registry/snapshot").get_json()["data"]
    b = client.get("/v1/registry/snapshot").get_json()["data"]
    assert a["snapshotId"] == b["snapshotId"]


def test_legacy_endpoints_still_work():
    """A04：原有 /v1/capabilities 与 /v1/adapters 必须继续可用。"""
    from app.server import app

    client = app.test_client()
    assert client.get("/v1/capabilities").status_code == 200
    assert client.get("/v1/adapters").status_code == 200
    assert client.get("/v1/adapters/DOUYIN_PUBLIC").status_code == 200
    assert client.get("/v1/adapters/DOUYIN_PUBLIC/capabilities").status_code == 200


def test_snapshot_does_not_trigger_platform_requests():
    """快照只读本地注册表 —— 不应出现任何**作业运行结果**结构。

    注意：不能拿 ``finishedReason`` / ``replies`` 这类**字段名**做断言 ——
    它们合法地出现在能力的 ``outputSchema`` 契约声明里（那是出参定义，
    不是采集结果）。真正该断言的是"没有作业结果对象"：
    快照里不应出现 ``jobId`` / ``nextCursor`` / ``done`` 这些 /v1/jobs 的产物。
    """
    snap = build_registry().snapshot()
    raw = json.dumps(snap, ensure_ascii=False)
    for forbidden in ("\"jobId\"", "\"nextCursor\"", "\"total\"", "\"startedAt\""):
        assert forbidden not in raw, f"快照不应包含作业运行结果字段: {forbidden}"


# ----------------------------------------------------------------------
# 快照内容可供 Effective 计算使用
# ----------------------------------------------------------------------
def test_snapshot_exposes_diagnostics_needed_for_effective():
    """Java 区分「未注册」与「函数解析失败」需要 diagnostics —— 快照必须提供。"""
    snap = build_registry().snapshot()
    assert "diagnostics" in snap
    for d in snap["diagnostics"]:
        assert "connectorCode" in d
        assert "reason" in d


def test_snapshot_exposes_adapter_load_status():
    """Java 判 ADAPTER_MISSING 需要 loadStatus —— 快照必须提供 adapters。"""
    snap = build_registry().snapshot()
    assert snap["adapters"], "快照必须包含适配器清单"
    for a in snap["adapters"]:
        assert a["loadStatus"] in ("LOADED", "FAILED"), a


if __name__ == "__main__":
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
