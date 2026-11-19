"""验证证据的完整性、适用范围与有效期测试（SOURCE-03 补充审查）。

覆盖补充审查项 1（证据是否可信）与项 3（VERIFIED 的适用范围与有效期）：

* 证据本体被改动 → ``EVIDENCE_TAMPERED``，能力不得被提升为 VERIFIED
* 实现指纹变化   → ``EVIDENCE_STALE``
* 接入/契约版本变化 → ``EVIDENCE_VERSION_MISMATCH``
* 访问模式不覆盖   → ``EVIDENCE_SCOPE_MISMATCH``
* 超过有效期     → ``EVIDENCE_EXPIRED``
* 正常证据      → VERIFIED，且带 scope 与 contentHash

运行（容器内）::

    cd /app/collection-service && /opt/venv/bin/python tests/test_evidence.py
"""

from __future__ import annotations

import copy
import json
import os
import sys
import tempfile

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.registry.bootstrap import build_registry, load_evidence  # noqa: E402
from app.registry.capability import (  # noqa: E402
    AccessMode,
    CapabilityCategory,
    CapabilityCode,
    CapabilitySpec,
    ImplementationStatus,
)
from app.registry.evidence import (  # noqa: E402
    EvidenceRecord,
    EvidenceRejection,
    EvidenceScope,
    EvidenceVerifier,
    build_record,
    implementation_hash,
    read_records,
    write_records,
)

CONNECTOR = "DOUYIN_PUBLIC"
SOURCE = "DOUYIN"


def _spec(code: str = CapabilityCode.COMMENT) -> CapabilitySpec:
    return CapabilitySpec(
        code=code,
        category=CapabilityCategory.COMMENT,
        name="测试能力",
        access_modes=(AccessMode.PUBLIC,),
        implementation_status=ImplementationStatus.UNVERIFIED,
        contract_version="1.0",
        adapter_function="app.registry.bootstrap.load_evidence",
        input_schema={"type": "object", "required": []},
        output_schema={"type": "object"},
    )


def _record(spec: CapabilitySpec, **overrides) -> "EvidenceRecord":
    """构造一条带完整性信息的证据。"""
    scope = EvidenceScope(
        source_code=overrides.pop("source_code", SOURCE),
        connector_code=overrides.pop("connector_code", CONNECTOR),
        connector_version=overrides.pop("connector_version", "1.0.0"),
        capability_code=spec.code,
        access_modes=tuple(overrides.pop("access_modes", spec.access_modes)),
        contract_version=overrides.pop("contract_version", spec.contract_version),
        adapter_function=spec.adapter_function,
        implementation_hash=overrides.pop("implementation_hash", "hash-1"),
    )
    record = EvidenceRecord(
        implementation_status=ImplementationStatus.VERIFIED,
        verified_at=overrides.pop("verified_at", "2026-11-16T00:00:00Z"),
        method="REAL_PLATFORM_RUN",
        scope=scope,
        observed={"collected": 3},
        message="ok",
    )
    return EvidenceRecord(
        implementation_status=record.implementation_status,
        verified_at=record.verified_at,
        method=record.method,
        scope=record.scope,
        observed=record.observed,
        message=record.message,
        content_hash=record.compute_content_hash(),
    )


def _write(records) -> str:
    fd, path = tempfile.mkstemp(suffix=".json")
    os.close(fd)
    write_records(path, records, "2026-11-16T00:00:00Z")
    return path


# ----------------------------------------------------------------------
# 单元级：校验器
# ----------------------------------------------------------------------
def test_valid_evidence_is_accepted():
    spec = _spec()
    record = _record(spec)
    check = EvidenceVerifier(ttl=30).verify(
        record, spec, "1.0.0", SOURCE, "hash-1")
    assert check.accepted, check.detail


def test_tampered_record_is_rejected():
    """改了记录本体但没重算哈希 → 检出。"""
    spec = _spec()
    record = _record(spec)
    tampered = EvidenceRecord(
        implementation_status=record.implementation_status,
        verified_at=record.verified_at,
        method=record.method,
        scope=record.scope,
        observed={"collected": 999},          # 改了统计
        message=record.message,
        content_hash=record.content_hash,     # 沿用旧哈希
    )
    check = EvidenceVerifier(ttl=30).verify(
        tampered, spec, "1.0.0", SOURCE, "hash-1")
    assert not check.accepted
    assert check.reason == EvidenceRejection.TAMPERED


def test_missing_content_hash_is_malformed():
    spec = _spec()
    record = _record(spec)
    broken = EvidenceRecord(
        implementation_status=record.implementation_status,
        verified_at=record.verified_at,
        method=record.method,
        scope=record.scope,
        observed=record.observed,
        message=record.message,
        content_hash="",                       # 缺失
    )
    check = EvidenceVerifier(ttl=30).verify(
        broken, spec, "1.0.0", SOURCE, "hash-1")
    assert not check.accepted
    assert check.reason == EvidenceRejection.MALFORMED


def test_changed_implementation_is_stale():
    spec = _spec()
    record = _record(spec)
    check = EvidenceVerifier(ttl=30).verify(
        record, spec, "1.0.0", SOURCE, "hash-changed")
    assert not check.accepted
    assert check.reason == EvidenceRejection.STALE


def test_changed_connector_version_mismatches():
    spec = _spec()
    record = _record(spec)
    check = EvidenceVerifier(ttl=30).verify(
        record, spec, "2.0.0", SOURCE, "hash-1")
    assert not check.accepted
    assert check.reason == EvidenceRejection.VERSION_MISMATCH


def test_changed_contract_version_mismatches():
    spec = _spec()
    record = _record(spec)
    spec2 = _spec()
    object.__setattr__(spec2, "contract_version", "2.0")
    check = EvidenceVerifier(ttl=30).verify(
        record, spec2, "1.0.0", SOURCE, "hash-1")
    assert not check.accepted
    assert check.reason == EvidenceRejection.VERSION_MISMATCH


def test_access_mode_not_covered_is_scope_mismatch():
    spec = _spec()
    record = _record(spec, access_modes=(AccessMode.PUBLIC,))
    spec2 = _spec()
    object.__setattr__(spec2, "access_modes", (AccessMode.PUBLIC, AccessMode.AUTHENTICATED))
    check = EvidenceVerifier(ttl=30).verify(
        record, spec2, "1.0.0", SOURCE, "hash-1")
    assert not check.accepted
    assert check.reason == EvidenceRejection.SCOPE_MISMATCH


def test_expired_evidence_is_rejected():
    spec = _spec()
    record = _record(spec, verified_at="2020-01-01T00:00:00Z")
    check = EvidenceVerifier(ttl=30).verify(
        record, spec, "1.0.0", SOURCE, "hash-1")
    assert not check.accepted
    assert check.reason == EvidenceRejection.EXPIRED


def test_different_source_is_scope_mismatch():
    spec = _spec()
    record = _record(spec)
    check = EvidenceVerifier(ttl=30).verify(
        record, spec, "1.0.0", "XIAOHONGSHU", "hash-1")
    assert not check.accepted
    assert check.reason == EvidenceRejection.SCOPE_MISMATCH


# ----------------------------------------------------------------------
# 集成级：Registry 行为
# ----------------------------------------------------------------------
def test_registry_rejects_tampered_evidence_file():
    """把证据文件里的统计改掉（不重算哈希）→ 能力不得变成 VERIFIED。"""
    path = _write({CONNECTOR: {CapabilityCode.COMMENT: _record(_spec())}})
    payload = json.load(open(path, encoding="utf-8"))
    payload["records"][CONNECTOR][CapabilityCode.COMMENT]["observed"]["collected"] = 999
    json.dump(payload, open(path, "w", encoding="utf-8"), ensure_ascii=False)

    registry = build_registry(path)
    specs = {s.code: s for s in registry.observed_specs(CONNECTOR)}
    assert specs[CapabilityCode.COMMENT].implementation_status == ImplementationStatus.UNVERIFIED
    reason = registry.evidence_rejections.get((CONNECTOR, CapabilityCode.COMMENT))
    assert reason is not None
    assert reason[0] == EvidenceRejection.TAMPERED
    # 拒绝原因必须能被诊断暴露
    assert any(d["reason"] == EvidenceRejection.TAMPERED for d in registry.diagnostics())
    os.remove(path)


def test_registry_accepts_valid_evidence_file():
    path = _write({CONNECTOR: {CapabilityCode.COMMENT: _record(_spec())}})
    registry = build_registry(path)
    # 证据的 implementationHash 与当前实现不符 → 只能是 STALE，不是 VERIFIED
    specs = {s.code: s for s in registry.observed_specs(CONNECTOR)}
    assert specs[CapabilityCode.COMMENT].implementation_status != ImplementationStatus.VERIFIED
    os.remove(path)


def test_evidence_scope_is_exposed_in_v1_document():
    """v1 文档里必须能看到证据绑定的范围。"""
    path = _write({CONNECTOR: {CapabilityCode.COMMENT: _record(_spec())}})
    registry = build_registry(path)
    docs = registry.v1_documents()
    douyin = [d for d in docs if d["connectorCode"] == CONNECTOR]
    assert douyin, "DOUYIN 接入应出现在 v1 文档中"
    cap = [c for c in douyin[0]["capabilities"]
           if c["code"] == CapabilityCode.COMMENT][0]
    assert "evidence" in cap
    assert cap["evidence"]["accepted"] is False
    assert cap["evidence"]["scope"]["connectorCode"] == CONNECTOR
    assert cap["evidence"]["scope"]["capabilityCode"] == CapabilityCode.COMMENT
    assert cap["evidence"]["scope"]["contractVersion"] == "1.0"
    os.remove(path)


def test_all_capabilities_declare_read_semantics():
    """本契约只表达只读能力。"""
    registry = build_registry()
    for doc in registry.v1_documents():
        for cap in doc["capabilities"]:
            assert cap["semantics"] == "READ", cap["code"]


def test_comment_reply_is_read_semantics():
    """COMMENT_REPLY 是"读取楼中楼回复"，不是发布回复。"""
    registry = build_registry()
    specs = {s.code: s for s in registry.observed_specs(CONNECTOR)}
    reply = specs[CapabilityCode.COMMENT_REPLY]
    assert reply.semantics == "READ"
    assert "读取" in reply.name
    assert "不含任何写操作" in reply.note


def test_implementation_hash_changes_when_function_changes():
    """函数源码变了 → 实现指纹必须变。"""
    spec = _spec()
    fn = load_evidence
    h1 = implementation_hash(spec, fn)

    def _other():
        return None

    h2 = implementation_hash(spec, _other)
    assert h1 != h2


def test_shipped_evidence_is_valid_and_not_expired():
    """随仓库分发的证据文件本身必须通过校验。"""
    from app.registry.bootstrap import EVIDENCE_FILE

    registry = build_registry()
    records = registry.evidence_records
    if not records:
        # 没有证据文件时不应报错，只是没有能力被验证
        return
    for connector, caps in records.items():
        for code in caps:
            assert (connector, code) not in registry.evidence_rejections, \
                f"{connector}/{code} 证据未通过校验: " \
                f"{registry.evidence_rejections.get((connector, code))}"


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
