"""适配器（接入实现）注册表。

SOURCE-02 §02-04 的规则：

* 重复注册同一 ``connectorCode`` → **拒绝**，不静默覆盖；
* 单个适配器加载失败 → 该接入标记 ``FAILED``，**不影响其它接入**；
* ``list()`` 返回全部**已尝试注册**的接入，成功与失败都在列——
  失败项没有能力清单，因为没有实例可问。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

from app.registry.capability import (
    AdapterMetadata,
    CapabilitySpec,
    LoadStatus,
    PLATFORM_SOURCE_CODES,
)
from app.registry.errors import CollectorError, classify_exception


@dataclass
class AdapterEntry:
    """注册表中的一个接入实现。"""

    connector_code: str
    source_code: str
    connector_version: str
    module: str
    platform_scoped: bool = True
    adapter: Any | None = None
    capabilities: list[CapabilitySpec] = field(default_factory=list)
    load_status: str = LoadStatus.LOADED
    error_code: str | None = None
    error_message: str | None = None
    legacy_status: str | None = None
    legacy_note: str | None = None

    def to_metadata(self) -> AdapterMetadata:
        return AdapterMetadata(
            connector_code=self.connector_code,
            source_code=self.source_code,
            connector_version=self.connector_version,
            load_status=self.load_status,
            module=self.module,
            platform_scoped=self.platform_scoped,
            error_code=self.error_code,
            error_message=self.error_message,
            capabilities=list(self.capabilities),
            legacy_status=self.legacy_status,
            legacy_note=self.legacy_note,
        )


def _legacy_value(adapter: Any, name: str) -> str | None:
    """取适配器上的旧端点覆盖值。

    ``legacy_status`` / ``legacy_note`` 在基类是方法，因此这里要区分
    "可调用的方法" 与 "普通属性" 两种写法。
    """
    attr = getattr(adapter, name, None)
    if attr is None:
        return None
    if callable(attr):
        try:
            value = attr()
        except Exception:
            return None
        return value if isinstance(value, str) else None
    return attr if isinstance(attr, str) else None


class AdapterRegistry:
    """适配器注册表。"""

    def __init__(self) -> None:
        self._entries: dict[str, AdapterEntry] = {}

    # ------------------------------------------------------------------
    # 注册
    # ------------------------------------------------------------------
    def register(
        self,
        adapter: Any,
        capabilities: list[CapabilitySpec] | None = None,
        *,
        connector_code: str | None = None,
        source_code: str | None = None,
        connector_version: str | None = None,
        module: str = "",
        platform_scoped: bool | None = None,
    ) -> AdapterEntry:
        """注册一个**已实例化**的适配器。"""
        code = connector_code or getattr(adapter, "connector_code", None)
        if not code:
            raise CollectorError("ADAPTER_ERROR", "适配器缺少 connector_code")
        if code in self._entries:
            raise CollectorError("DUPLICATE_ADAPTER", f"接入实现重复注册: {code}")

        src = source_code or getattr(adapter, "source_code", "")
        scoped = platform_scoped if platform_scoped is not None else (
            src in PLATFORM_SOURCE_CODES
        )
        entry = AdapterEntry(
            connector_code=code,
            source_code=src,
            connector_version=connector_version or getattr(adapter, "connector_version", "0.0.0"),
            module=module or type(adapter).__module__,
            platform_scoped=scoped,
            adapter=adapter,
            capabilities=list(capabilities or []),
            load_status=LoadStatus.LOADED,
            legacy_status=_legacy_value(adapter, "legacy_status"),
            legacy_note=_legacy_value(adapter, "legacy_note"),
        )
        self._entries[code] = entry
        return entry

    def register_loader(
        self,
        connector_code: str,
        loader: Callable[[], tuple[Any, list[CapabilitySpec]]],
        *,
        source_code: str = "",
        connector_version: str = "0.0.0",
        module: str = "",
        platform_scoped: bool = True,
    ) -> AdapterEntry:
        """通过加载器注册接入实现。

        加载器抛异常时**不抛出**，而是把该接入标记为 ``FAILED``——
        一个平台挂掉不能连累其它平台。
        """
        if connector_code in self._entries:
            raise CollectorError("DUPLICATE_ADAPTER", f"接入实现重复注册: {connector_code}")

        entry = AdapterEntry(
            connector_code=connector_code,
            source_code=source_code,
            connector_version=connector_version,
            module=module,
            platform_scoped=platform_scoped,
        )
        try:
            adapter, capabilities = loader()
        except Exception as exc:  # noqa: BLE001 —— 故意兜住所有异常
            spec = classify_exception(exc)
            entry.load_status = LoadStatus.FAILED
            entry.error_code = spec.code
            entry.error_message = str(exc)[:500]
            self._entries[connector_code] = entry
            return entry

        entry.adapter = adapter
        entry.capabilities = list(capabilities or [])
        entry.load_status = LoadStatus.LOADED
        entry.legacy_status = _legacy_value(adapter, "legacy_status")
        entry.legacy_note = _legacy_value(adapter, "legacy_note")
        self._entries[connector_code] = entry
        return entry

    # ------------------------------------------------------------------
    # 查询
    # ------------------------------------------------------------------
    def get(self, connector_code: str) -> Any | None:
        """取适配器实例。未注册或加载失败返回 ``None``。"""
        entry = self._entries.get(connector_code)
        if entry is None or entry.load_status != LoadStatus.LOADED:
            return None
        return entry.adapter

    def entry(self, connector_code: str) -> AdapterEntry | None:
        return self._entries.get(connector_code)

    def has(self, connector_code: str) -> bool:
        """是否**成功加载**。加载失败不算 has。"""
        entry = self._entries.get(connector_code)
        return entry is not None and entry.load_status == LoadStatus.LOADED

    def list(self) -> list[AdapterEntry]:
        """全部接入实现（含加载失败的）。"""
        return list(self._entries.values())

    def loaded(self) -> list[AdapterEntry]:
        return [e for e in self._entries.values() if e.load_status == LoadStatus.LOADED]

    def failed(self) -> list[AdapterEntry]:
        return [e for e in self._entries.values() if e.load_status == LoadStatus.FAILED]

    def __len__(self) -> int:
        return len(self._entries)

    def __contains__(self, connector_code: object) -> bool:
        return connector_code in self._entries
