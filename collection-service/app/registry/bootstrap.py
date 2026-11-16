"""Registry 组装：把适配器与能力装配成可观测的 Observed 视图。

**验证证据是数据，不是代码。** 代码里的能力声明一律从 ``UNVERIFIED`` 起步，
只有 ``registry/verification_evidence.json`` 里存在真实运行记录时，
才会被提升为 ``VERIFIED``。证据文件由 ``tools/verify_capability.py``
在真实跑通后写出——这样"函数存在"永远不会被误当成"平台验证通过"。
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from typing import Any

from app.registry.adapter_registry import AdapterRegistry
from app.registry.capability import (
    SCHEMA_VERSION,
    CapabilitySpec,
    DiagnosticReason,
    ImplementationStatus,
    LoadStatus,
    aggregate_legacy_status,
)
from app.registry.capability_registry import CapabilityRegistry
from app.registry.errors import CollectorError
from app.registry.evidence import (
    EvidenceCheck,
    EvidenceRecord,
    EvidenceVerifier,
    implementation_hash,
    read_records,
    ttl_days,
)

#: 验证证据文件位置（随代码分发，默认是空证据）。
EVIDENCE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                              "verification_evidence.json")


@dataclass
class CollectionRegistry:
    """适配器注册表 + 能力注册表 + 验证证据。"""

    adapters: AdapterRegistry = field(default_factory=AdapterRegistry)
    capabilities: CapabilityRegistry = field(default_factory=CapabilityRegistry)
    evidence: dict[str, Any] = field(default_factory=dict)
    #: 解析后的证据记录：connectorCode -> capabilityCode -> EvidenceRecord
    evidence_records: dict[str, dict[str, EvidenceRecord]] = field(default_factory=dict)
    #: 证据校验器
    verifier: EvidenceVerifier = field(default_factory=EvidenceVerifier)
    #: 证据被拒绝的原因：(connectorCode, capabilityCode) -> (reason, detail)
    evidence_rejections: dict[tuple[str, str], tuple[str, str]] = field(default_factory=dict)
    #: 实现指纹缓存：(connectorCode, capabilityCode) -> hash
    _hash_cache: dict[tuple[str, str], str] = field(default_factory=dict, repr=False)

    # ------------------------------------------------------------------
    # 证据
    # ------------------------------------------------------------------
    def _evidence_for(self, connector_code: str, capability_code: str) -> dict | None:
        records = (self.evidence.get("records") or {})
        conn = records.get(connector_code) or {}
        rec = conn.get(capability_code)
        return rec if isinstance(rec, dict) else None

    def _adapter_entry(self, connector_code: str):
        for entry in self.adapters.list():
            if entry.connector_code == connector_code:
                return entry
        return None

    def _current_implementation_hash(self, connector_code: str,
                                     spec: CapabilitySpec) -> str:
        """当前实现指纹。带缓存——同一份声明不必反复算哈希。"""
        key = (connector_code, spec.code)
        cached = self._hash_cache.get(key)
        if cached is not None:
            return cached
        fn = self.capabilities.resolve(spec.adapter_function)
        value = implementation_hash(spec, fn)
        self._hash_cache[key] = value
        return value

    def _check_evidence(self, connector_code: str, spec: CapabilitySpec) -> EvidenceCheck:
        """校验一条证据是否仍然适用于当前实现。"""
        record = (self.evidence_records.get(connector_code) or {}).get(spec.code)
        if record is None:
            return EvidenceCheck.ok()

        entry = self._adapter_entry(connector_code)
        connector_version = entry.connector_version if entry else ""
        source_code = entry.source_code if entry else ""
        current_hash = self._current_implementation_hash(connector_code, spec)

        check = self.verifier.verify(
            record, spec,
            connector_version=connector_version,
            source_code=source_code,
            current_implementation_hash=current_hash,
        )
        if not check.accepted:
            self.evidence_rejections[(connector_code, spec.code)] = (
                check.reason or "", check.detail)
        else:
            self.evidence_rejections.pop((connector_code, spec.code), None)
        return check

    def apply_evidence(self, connector_code: str, spec: CapabilitySpec) -> CapabilitySpec:
        """把验证证据应用到能力声明上。

        证据必须**通过全部校验**才生效：

        * 记录本体未被改动（``contentHash``）；
        * 适用范围与当前接入一致（平台、接入版本、契约版本、访问模式）；
        * 实现指纹一致（声明或函数代码没变过）；
        * 未超过有效期。

        任一不满足，该条证据**不生效**，能力保持声明时的状态，
        并在 diagnostics 中留下原因。
        """
        record = (self.evidence_records.get(connector_code) or {}).get(spec.code)
        if record is None:
            return spec
        if record.implementation_status != ImplementationStatus.VERIFIED:
            return spec
        if spec.implementation_status == ImplementationStatus.DISABLED:
            return spec

        check = self._check_evidence(connector_code, spec)
        if not check.accepted:
            # 证据存在但没通过校验：能力保持声明状态，并把拒绝原因暴露出去
            return self._with_evidence(spec, {
                "verifiedAt": record.verified_at,
                "method": record.method,
                "scope": record.scope.to_dict(),
                "contentHash": record.content_hash,
                "accepted": False,
                "rejectionReason": check.reason,
                "rejectionDetail": check.detail,
            })

        return self._with_evidence(spec, {
            "verifiedAt": record.verified_at,
            "method": record.method,
            "scope": record.scope.to_dict(),
            "contentHash": record.content_hash,
            "accepted": True,
        }, verified=True)

    @staticmethod
    def _with_evidence(spec: CapabilitySpec, evidence: dict[str, Any],
                       verified: bool = False) -> CapabilitySpec:
        """复制一份声明并附上证据信息。"""
        return CapabilitySpec(
            code=spec.code,
            category=spec.category,
            name=spec.name,
            access_modes=spec.access_modes,
            implementation_status=(ImplementationStatus.VERIFIED if verified
                                   else spec.implementation_status),
            contract_version=spec.contract_version,
            adapter_function=spec.adapter_function,
            input_schema=spec.input_schema,
            output_schema=spec.output_schema,
            limits=spec.limits,
            retry_policy=spec.retry_policy,
            verified_at=(evidence.get("verifiedAt") if verified else spec.verified_at),
            note=spec.note,
            semantics=spec.semantics,
            evidence=evidence,
        )

    # ------------------------------------------------------------------
    # 视图
    # ------------------------------------------------------------------
    def v1_documents(self) -> list[dict[str, Any]]:
        """符合 SOURCE-02 §02-03 Schema 的平台能力文档列表。

        只有**平台级**接入（sourceCode 在 Schema 枚举内）才有文档；
        非平台接入（链路验证替身）不产出 sourceCode，不进入该视图。
        """
        docs: list[dict[str, Any]] = []
        for entry in self.adapters.list():
            if not entry.platform_scoped:
                continue
            specs = self.observed_specs(entry.connector_code)
            docs.append({
                "schemaVersion": SCHEMA_VERSION,
                "sourceCode": entry.source_code,
                "connectorCode": entry.connector_code,
                "connectorVersion": entry.connector_version,
                "capabilities": [s.to_v1() for s in specs],
            })
        return docs

    def observed_specs(self, connector_code: str) -> list[CapabilitySpec]:
        """该接入**兑现了**的能力声明（含验证证据提升）。"""
        observed = self.capabilities.list_capabilities(connector_code)
        return [self.apply_evidence(connector_code, s) for s in observed]

    def adapter_views(self) -> list[dict[str, Any]]:
        """全部接入实现的元信息（含加载失败的），``/v1/adapters`` 的数据源。"""
        out: list[dict[str, Any]] = []
        for entry in self.adapters.list():
            view = entry.to_metadata().to_v1()
            specs = self.observed_specs(entry.connector_code)
            view["capabilityCount"] = len(specs)
            view["capabilities"] = [s.to_v1() for s in specs]
            out.append(view)
        return out

    def legacy_items(self) -> list[dict[str, Any]]:
        """旧端点的 ``items`` 结构（兼容期保留）。

        布尔字段沿用旧语义：**只有能力兑现且可用（VERIFIED / IMPLEMENTED /
        DEGRADED）才为 true**。UNVERIFIED 的能力在旧字段里是 false——
        这与 ``ds_source.capability_json`` 里
        ``"keywordSearch":false,"keywordStatus":"PENDING"`` 的历史口径一致。
        """
        items: list[dict[str, Any]] = []
        usable = {
            ImplementationStatus.VERIFIED,
            ImplementationStatus.IMPLEMENTED,
            ImplementationStatus.DEGRADED,
        }
        for entry in self.adapters.list():
            specs = self.observed_specs(entry.connector_code)
            codes = {s.code for s in specs if s.implementation_status in usable}
            status = entry.legacy_status or aggregate_legacy_status(specs)
            note = entry.legacy_note
            if note is None:
                note = "; ".join(s.note for s in specs if s.note) or None
            items.append({
                "sourceCode": entry.source_code,
                "comments": "COMMENT" in codes,
                "keywordSearch": "KEYWORD_SEARCH" in codes,
                "status": status,
                "note": note,
            })
        return items

    def health_payload(self) -> dict[str, Any]:
        """``/health`` 里的 registry 段。**不触发任何平台请求。**"""
        entries = self.adapters.list()
        return {
            "schemaVersion": SCHEMA_VERSION,
            "adapters": [
                {
                    "connectorCode": e.connector_code,
                    "sourceCode": e.source_code,
                    "connectorVersion": e.connector_version,
                    "loadStatus": e.load_status,
                    "module": e.module,
                    "platformScoped": e.platform_scoped,
                    "capabilityCount": len(self.capabilities.list_capabilities(e.connector_code)),
                }
                for e in entries
            ],
            "loadedCount": len(self.adapters.loaded()),
            "failedCount": len(self.adapters.failed()),
            "capabilityCount": len(self.capabilities.list_capabilities()),
            "degraded": bool(self.adapters.failed()),
            "evidence": {
                "recordCount": sum(len(v) for v in self.evidence_records.values()),
                "acceptedCount": sum(
                    1 for key, caps in self.evidence_records.items()
                    for cap in caps
                    if (key, cap) not in self.evidence_rejections
                ),
                "rejectedCount": len(self.evidence_rejections),
                "ttlDays": ttl_days(),
            },
        }

    def diagnostics(self) -> list[dict[str, Any]]:
        """能力诊断：为什么某条能力兑现不了。"""
        out: list[dict[str, Any]] = []
        for d in self.capabilities.diagnostics():
            out.append(d.to_v1())
        for entry in self.adapters.failed():
            out.append({
                "connectorCode": entry.connector_code,
                "capabilityCode": None,
                "reason": DiagnosticReason.ADAPTER_NOT_LOADED,
                "detail": entry.error_message or "",
                "adapterFunction": None,
            })
        # 证据被拒绝：能力本身兑现了，但没有有效的验证证据支撑 VERIFIED
        for (connector_code, capability_code), (reason, detail) in \
                sorted(self.evidence_rejections.items()):
            out.append({
                "connectorCode": connector_code,
                "capabilityCode": capability_code,
                "reason": reason,
                "detail": detail,
                "adapterFunction": None,
            })
        return out


def load_evidence(path: str | None = None) -> dict[str, Any]:
    """读取验证证据文件。文件缺失或损坏都返回空证据——**不能因此报错**。"""
    target = path or EVIDENCE_FILE
    if not os.path.exists(target):
        return {}
    try:
        with open(target, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _load_stub() -> tuple[Any, list[CapabilitySpec]]:
    from app.adapters.stub import StubAdapter

    adapter = StubAdapter()
    return adapter, adapter.capability_specs()


def _load_douyin_public() -> tuple[Any, list[CapabilitySpec]]:
    from app.adapters.douyin.douyin_adapter import DouyinPublicAdapter

    adapter = DouyinPublicAdapter()
    return adapter, adapter.capability_specs()


#: 接入实现的加载清单。**每个平台独立加载**——一个失败不连累其它。
LOADERS = (
    {
        "connectorCode": "STUB_PUBLIC",
        "sourceCode": "STUB",
        "connectorVersion": "1.0.0",
        "module": "app.adapters.stub",
        "platformScoped": False,
        "loader": _load_stub,
    },
    {
        "connectorCode": "DOUYIN_PUBLIC",
        "sourceCode": "DOUYIN",
        "connectorVersion": "1.0.0",
        "module": "app.adapters.douyin.douyin_adapter",
        "platformScoped": True,
        "loader": _load_douyin_public,
    },
)


def build_registry(evidence_path: str | None = None) -> CollectionRegistry:
    """装配默认 Registry。

    任何一个平台加载失败都会被标记为 ``FAILED``，**不影响其它平台**——
    这正是 03-06 要验收的场景之一。
    """
    registry = CollectionRegistry(evidence=load_evidence(evidence_path))
    registry.evidence_records = read_records(registry.evidence)

    for item in LOADERS:
        entry = registry.adapters.register_loader(
            item["connectorCode"],
            item["loader"],
            source_code=item["sourceCode"],
            connector_version=item["connectorVersion"],
            module=item["module"],
            platform_scoped=item["platformScoped"],
        )
        for spec in entry.capabilities:
            registry.capabilities.declare(entry.connector_code, spec)

    return registry


def get_registry() -> CollectionRegistry:
    """进程级单例。启动时构建一次。"""
    global _REGISTRY
    if _REGISTRY is None:
        _REGISTRY = build_registry()
    return _REGISTRY


_REGISTRY: CollectionRegistry | None = None


def reset_registry() -> None:
    """测试用：清掉单例。"""
    global _REGISTRY
    _REGISTRY = None


__all__ = [
    "CollectionRegistry",
    "build_registry",
    "get_registry",
    "reset_registry",
    "load_evidence",
    "EVIDENCE_FILE",
    "CollectorError",
    "LoadStatus",
]
