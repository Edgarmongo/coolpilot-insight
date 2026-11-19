"""验证证据的**完整性、适用范围与有效期**。

SOURCE-03 补充审查的核心结论（见 ``SOURCE-03-SUPPLEMENT-REVIEW.md`` 审查项 1/3）：

> "手工编辑等同伪造"只是一句约定，**JSON 文件本身并不能阻止手工修改**。

因此每条证据不只记录"某次跑通过"，还必须记录"这份记录对应的是哪一版实现"：

======================  ======================================================
字段                   作用
======================  ======================================================
``contentHash``        记录本体的哈希。改动时间、统计、结论会被立刻发现。
``scope.implementationHash``  能力声明 + 目标函数源码的哈希。
                       **改了实现或改了声明，旧证据自动失效**——
                       不会"一劳永逸地"证明新版本仍然可用。
``scope.*``            绑定 sourceCode / connectorCode / connectorVersion /
                       capabilityCode / accessModes / contractVersion /
                       adapterFunction —— 证据只在它所声明的范围内有效。
``verifiedAt`` + TTL   证据有有效期。过期不再提升为 ``VERIFIED``。
======================  ======================================================

**任一校验不通过，该条证据不生效**，并产生一条诊断记录——
不静默降级，也不伪造状态。诊断原因码见 :class:`EvidenceRejection`。
这些原因码属于**证据校验层**，不属于 SOURCE-02 冻结的
``implementationStatus`` / ``effectiveStatus`` 枚举。
"""

from __future__ import annotations

import hashlib
import inspect
import json
import os
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

from app.registry.capability import CapabilitySpec

#: 证据默认有效期（天）。超过后不再提升为 VERIFIED。
DEFAULT_TTL_DAYS = 30

#: 环境变量：覆盖证据有效期。
ENV_TTL_DAYS = "CAPABILITY_EVIDENCE_TTL_DAYS"

#: 证据文件里的顶层 schema 版本（与能力契约 Schema 是两套东西）。
EVIDENCE_SCHEMA_VERSION = "2.0"


# ----------------------------------------------------------------------
# 哈希
# ----------------------------------------------------------------------
def canonical_json(obj: Any) -> str:
    """规范化 JSON —— 同一内容在任何机器上产生同一字符串。"""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, default=str)


def sha256_of(obj: Any) -> str:
    return hashlib.sha256(canonical_json(obj).encode("utf-8")).hexdigest()


def function_fingerprint(fn: Callable | None) -> str:
    """函数指纹：优先取源码，取不到就用 code object 的稳定表示。"""
    if fn is None:
        return ""
    try:
        src = inspect.getsource(fn)
        return hashlib.sha256(src.encode("utf-8")).hexdigest()
    except (OSError, TypeError):
        pass
    code = getattr(fn, "__code__", None)
    if code is not None:
        payload = {
            "name": fn.__name__,
            "file": os.path.basename(code.co_filename),
            "consts": [repr(c) for c in code.co_consts],
            "names": list(code.co_names),
        }
        return sha256_of(payload)
    return sha256_of(repr(fn))


def implementation_hash(spec: CapabilitySpec, fn: Callable | None) -> str:
    """能力声明 + 目标函数的联合指纹。

    只要改了声明（入参/出参/函数引用/契约版本）或改了函数实现，
    指纹就会变 —— 旧证据随之失效。
    """
    return sha256_of({
        "code": spec.code,
        "category": spec.category,
        "accessModes": sorted(spec.access_modes),
        "contractVersion": spec.contract_version,
        "adapterFunction": spec.adapter_function,
        "inputSchema": spec.input_schema,
        "outputSchema": spec.output_schema,
        "functionFingerprint": function_fingerprint(fn),
    })


# ----------------------------------------------------------------------
# 拒绝原因
# ----------------------------------------------------------------------
class EvidenceRejection:
    """证据未通过校验的原因码。**是原因，不是能力状态。**"""

    #: 记录内容被改动，与 contentHash 不符
    TAMPERED: str = "EVIDENCE_TAMPERED"
    #: 实现或声明变了，旧证据不再适用
    STALE: str = "EVIDENCE_STALE"
    #: 超过有效期
    EXPIRED: str = "EVIDENCE_EXPIRED"
    #: 接入版本 / 契约版本对不上
    VERSION_MISMATCH: str = "EVIDENCE_VERSION_MISMATCH"
    #: 适用范围（访问模式等）对不上
    SCOPE_MISMATCH: str = "EVIDENCE_SCOPE_MISMATCH"
    #: 证据结构本身不完整
    MALFORMED: str = "EVIDENCE_MALFORMED"

    ALL = (TAMPERED, STALE, EXPIRED, VERSION_MISMATCH, SCOPE_MISMATCH, MALFORMED)


@dataclass(frozen=True)
class EvidenceScope:
    """证据的适用范围。证据只在它声明的范围内有效。"""

    source_code: str
    connector_code: str
    connector_version: str
    capability_code: str
    access_modes: tuple[str, ...]
    contract_version: str
    adapter_function: str
    implementation_hash: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "sourceCode": self.source_code,
            "connectorCode": self.connector_code,
            "connectorVersion": self.connector_version,
            "capabilityCode": self.capability_code,
            "accessModes": list(self.access_modes),
            "contractVersion": self.contract_version,
            "adapterFunction": self.adapter_function,
            "implementationHash": self.implementation_hash,
        }

    @staticmethod
    def from_dict(data: Any) -> "EvidenceScope | None":
        if not isinstance(data, dict):
            return None
        try:
            modes = tuple(data.get("accessModes") or ())
            return EvidenceScope(
                source_code=str(data.get("sourceCode") or ""),
                connector_code=str(data.get("connectorCode") or ""),
                connector_version=str(data.get("connectorVersion") or ""),
                capability_code=str(data.get("capabilityCode") or ""),
                access_modes=modes,
                contract_version=str(data.get("contractVersion") or ""),
                adapter_function=str(data.get("adapterFunction") or ""),
                implementation_hash=str(data.get("implementationHash") or ""),
            )
        except Exception:
            return None


@dataclass(frozen=True)
class EvidenceRecord:
    """一条验证证据。"""

    implementation_status: str
    verified_at: str
    method: str
    scope: EvidenceScope
    observed: dict[str, Any] = field(default_factory=dict)
    message: str = ""
    content_hash: str = ""

    # ------------------------------------------------------------------
    def payload(self) -> dict[str, Any]:
        """参与 contentHash 计算的本体。**不含 contentHash 自身。**"""
        return {
            "implementationStatus": self.implementation_status,
            "verifiedAt": self.verified_at,
            "method": self.method,
            "scope": self.scope.to_dict(),
            "observed": self.observed,
            "message": self.message,
        }

    def compute_content_hash(self) -> str:
        return sha256_of(self.payload())

    def to_dict(self) -> dict[str, Any]:
        out = self.payload()
        out["contentHash"] = self.content_hash or self.compute_content_hash()
        return out

    @staticmethod
    def from_dict(data: Any) -> "EvidenceRecord | None":
        if not isinstance(data, dict):
            return None
        scope = EvidenceScope.from_dict(data.get("scope"))
        if scope is None:
            return None
        observed = data.get("observed")
        return EvidenceRecord(
            implementation_status=str(data.get("implementationStatus") or ""),
            verified_at=str(data.get("verifiedAt") or ""),
            method=str(data.get("method") or ""),
            scope=scope,
            observed=observed if isinstance(observed, dict) else {},
            message=str(data.get("message") or ""),
            content_hash=str(data.get("contentHash") or ""),
        )


@dataclass(frozen=True)
class EvidenceCheck:
    """一次证据校验的结果。"""

    accepted: bool
    reason: str | None = None
    detail: str = ""

    @staticmethod
    def ok() -> "EvidenceCheck":
        return EvidenceCheck(True, None, "")

    @staticmethod
    def reject(reason: str, detail: str) -> "EvidenceCheck":
        return EvidenceCheck(False, reason, detail)


# ----------------------------------------------------------------------
# 有效期
# ----------------------------------------------------------------------
def ttl_days(default: int = DEFAULT_TTL_DAYS) -> int:
    """证据有效期（天）。环境变量优先。"""
    raw = os.environ.get(ENV_TTL_DAYS)
    if raw:
        try:
            value = int(raw)
            if value > 0:
                return value
        except ValueError:
            pass
    return default


def _parse_time(value: str) -> datetime | None:
    if not value:
        return None
    text = value.strip().replace("Z", "+00:00")
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def is_expired(verified_at: str, days: int | None = None,
               now: datetime | None = None) -> bool:
    """证据是否已过有效期。时间无法解析时**不**判过期（宁可少判，不误杀）。"""
    limit = days if days is not None else ttl_days()
    dt = _parse_time(verified_at)
    if dt is None:
        return False
    ref = now or datetime.now(timezone.utc)
    return ref > dt + timedelta(days=limit)


# ----------------------------------------------------------------------
# 校验器
# ----------------------------------------------------------------------
class EvidenceVerifier:
    """校验证据是否仍然适用于当前实现。"""

    def __init__(self, ttl: int | None = None) -> None:
        self._ttl = ttl

    def verify(self, record: EvidenceRecord, spec: CapabilitySpec,
               connector_version: str, source_code: str,
               current_implementation_hash: str,
               now: datetime | None = None) -> EvidenceCheck:
        """逐条校验。**任何一条不通过都拒绝这条证据。**"""
        # 1) 记录本体完整性
        if not record.content_hash:
            return EvidenceCheck.reject(EvidenceRejection.MALFORMED,
                                        "证据缺少 contentHash，无法确认未被改动")
        if record.content_hash != record.compute_content_hash():
            return EvidenceCheck.reject(EvidenceRejection.TAMPERED,
                                        "证据内容与 contentHash 不一致，记录已被改动")

        # 2) 适用范围：平台 / 接入 / 能力
        scope = record.scope
        if source_code and scope.source_code and scope.source_code != source_code:
            return EvidenceCheck.reject(
                EvidenceRejection.SCOPE_MISMATCH,
                f"证据平台 {scope.source_code} 与当前 {source_code} 不一致")

        # 3) 版本绑定
        if connector_version and scope.connector_version != connector_version:
            return EvidenceCheck.reject(
                EvidenceRejection.VERSION_MISMATCH,
                f"接入版本已从 {scope.connector_version} 变为 {connector_version}")
        if scope.contract_version != spec.contract_version:
            return EvidenceCheck.reject(
                EvidenceRejection.VERSION_MISMATCH,
                f"契约版本已从 {scope.contract_version} 变为 {spec.contract_version}")

        # 4) 实现指纹：代码变了旧证据不适用
        if scope.implementation_hash != current_implementation_hash:
            return EvidenceCheck.reject(
                EvidenceRejection.STALE,
                "能力声明或目标函数已变更，本次验证证据不再适用")

        # 5) 访问模式：证据覆盖的范围必须覆盖当前声明
        if scope.access_modes and spec.access_modes:
            missing = [m for m in spec.access_modes if m not in scope.access_modes]
            if missing:
                return EvidenceCheck.reject(
                    EvidenceRejection.SCOPE_MISMATCH,
                    f"证据未覆盖当前声明的访问模式: {missing}")

        # 6) 有效期
        if is_expired(record.verified_at, self._ttl, now):
            return EvidenceCheck.reject(
                EvidenceRejection.EXPIRED,
                f"验证证据已超过 {self._ttl if self._ttl is not None else ttl_days()} 天有效期")

        return EvidenceCheck.ok()


# ----------------------------------------------------------------------
# 存取
# ----------------------------------------------------------------------
def build_record(spec: CapabilitySpec, connector_code: str, connector_version: str,
                 source_code: str, verified_at: str, method: str,
                 observed: dict[str, Any], message: str,
                 fn: Callable | None) -> EvidenceRecord:
    """构造一条**自带完整性信息**的证据记录。"""
    scope = EvidenceScope(
        source_code=source_code,
        connector_code=connector_code,
        connector_version=connector_version,
        capability_code=spec.code,
        access_modes=tuple(spec.access_modes),
        contract_version=spec.contract_version,
        adapter_function=spec.adapter_function,
        implementation_hash=implementation_hash(spec, fn),
    )
    record = EvidenceRecord(
        implementation_status="VERIFIED",
        verified_at=verified_at,
        method=method,
        scope=scope,
        observed=observed,
        message=message,
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


def read_records(payload: dict[str, Any]) -> dict[str, dict[str, EvidenceRecord]]:
    """从证据文件读出记录。结构不合法的记录直接跳过（不抛错）。"""
    out: dict[str, dict[str, EvidenceRecord]] = {}
    records = (payload or {}).get("records")
    if not isinstance(records, dict):
        return out
    for connector_code, caps in records.items():
        if not isinstance(caps, dict):
            continue
        bucket: dict[str, EvidenceRecord] = {}
        for cap_code, raw in caps.items():
            record = EvidenceRecord.from_dict(raw)
            if record is not None:
                bucket[cap_code] = record
        out[connector_code] = bucket
    return out


def write_records(path: str, records: dict[str, dict[str, EvidenceRecord]],
                  updated_at: str) -> None:
    """写出证据文件。每条记录都带 contentHash。"""
    payload: dict[str, Any] = {
        "schemaVersion": EVIDENCE_SCHEMA_VERSION,
        "note": ("验证证据由 tools/verify_capability.py 在真实平台跑通后写出。"
                 "每条记录带 contentHash（记录本体哈希）与 scope.implementationHash"
                 "（声明 + 目标函数指纹），任一被改动或实现变更都会使证据失效。"
                 "手工编辑本文件等同于伪造验证证据。"),
        "records": {
            connector_code: {
                cap_code: record.to_dict() for cap_code, record in caps.items()
            }
            for connector_code, caps in records.items()
        },
        "updatedAt": updated_at,
    }
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


__all__ = [
    "DEFAULT_TTL_DAYS",
    "EVIDENCE_SCHEMA_VERSION",
    "EvidenceCheck",
    "EvidenceRejection",
    "EvidenceRecord",
    "EvidenceScope",
    "EvidenceVerifier",
    "build_record",
    "canonical_json",
    "implementation_hash",
    "is_expired",
    "read_records",
    "sha256_of",
    "ttl_days",
    "write_records",
]
