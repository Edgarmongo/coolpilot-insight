"""能力契约的数据结构（SOURCE-02 §02-03 的 Python 侧实现）。

这个模块**只描述**，不执行。真正的"能力是否存在"由
:mod:`app.registry.capability_registry` 通过解析 ``adapterFunction`` 来证明。

枚举值必须与 ``docs/architecture/collection/SOURCE-02-capability-schema.json``
逐字一致。**不要为了凑场景说明表而扩充枚举**——说明表里的
``NOT_LOADED`` / ``INVALID_IMPLEMENTATION`` 属于诊断原因，不是状态枚举：
``NOT_LOADED`` 是适配器加载状态（见 ``LoadStatus``），
``INVALID_IMPLEMENTATION`` 只作为诊断原因码出现在 diagnostics 区。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Final

#: 顶层 Schema 版本。1.x 向后兼容（只加字段），2.0 破坏性变更。
#:
#: 1.1 —— 新增 ``capabilities[].semantics``（能力读写语义）。
#:        本契约只描述"能从平台取到什么"，因此 semantics 的唯一合法值是 READ。
#:        把这件事写进 Schema 而不是只写在文档里，是为了让"读取楼中楼回复"
#:        与"发布评论回复"在结构上无法混淆——后者在本版本根本没有表达位置。
SCHEMA_VERSION: Final = "1.1"

#: Java 侧认识的最高 Schema 主版本。超过此值 Java 拒绝解析并标记 UNKNOWN。
SUPPORTED_SCHEMA_MAJOR: Final = 1


class ImplementationStatus:
    """能力实现状态（对应 Schema ``capabilities[].implementationStatus``）。"""

    #: 代码存在但从未验证 —— 不可执行
    UNVERIFIED: Final = "UNVERIFIED"
    #: 已实现且本地测试通过 —— 允许但需标注
    IMPLEMENTED: Final = "IMPLEMENTED"
    #: 已通过真实平台验证 —— 可执行
    VERIFIED: Final = "VERIFIED"
    #: 可用但受限 —— 降级执行
    DEGRADED: Final = "DEGRADED"
    #: 被显式禁用 —— 不可执行
    DISABLED: Final = "DISABLED"
    #: 平台不支持 —— 不可执行
    UNSUPPORTED: Final = "UNSUPPORTED"

    ALL: Final = (UNVERIFIED, IMPLEMENTED, VERIFIED, DEGRADED, DISABLED, UNSUPPORTED)


class AccessMode:
    """访问模式（对应 Schema ``capabilities[].accessModes``）。"""

    #: 不需要账号
    PUBLIC: Final = "PUBLIC"
    #: 必须绑定 isUsable=true 的账号
    AUTHENTICATED: Final = "AUTHENTICATED"

    ALL: Final = (PUBLIC, AUTHENTICATED)


class CapabilityCode:
    """能力码（对应 Schema ``capabilities[].code``）。

    命名约定：``<对象>_<子对象>``，全部是**读取**能力，
    读写语义由 :class:`CapabilitySemantics` 单独表达。

    特别说明 ``COMMENT_REPLY``：它指**读取**评论下的楼中楼回复，
    与"发布评论回复"是完全不同的能力。本契约不包含任何写能力。
    """

    KEYWORD_SEARCH: Final = "KEYWORD_SEARCH"
    CONTENT_DETAIL: Final = "CONTENT_DETAIL"
    COMMENT: Final = "COMMENT"
    COMMENT_REPLY: Final = "COMMENT_REPLY"
    AUTHOR_PROFILE: Final = "AUTHOR_PROFILE"
    AUTHOR_POSTS: Final = "AUTHOR_POSTS"
    MEDIA_DOWNLOAD: Final = "MEDIA_DOWNLOAD"

    ALL: Final = (
        KEYWORD_SEARCH,
        CONTENT_DETAIL,
        COMMENT,
        COMMENT_REPLY,
        AUTHOR_PROFILE,
        AUTHOR_POSTS,
        MEDIA_DOWNLOAD,
    )


class CapabilityCategory:
    """能力分类（对应 Schema ``capabilities[].category``）。"""

    SEARCH: Final = "SEARCH"
    CONTENT: Final = "CONTENT"
    COMMENT: Final = "COMMENT"
    USER: Final = "USER"
    MEDIA: Final = "MEDIA"

    ALL: Final = (SEARCH, CONTENT, COMMENT, USER, MEDIA)


class CapabilitySemantics:
    """能力的读写语义（对应 Schema ``capabilities[].semantics``）。

    **本契约只表达只读采集能力。** 唯一合法值是 ``READ``：

    * ``COMMENT``      —— 读取评论列表
    * ``COMMENT_REPLY`` —— 读取评论下的楼中楼回复（**不是**发布回复）

    写操作（发布评论、点赞、关注、私信等）**不在本契约的能力模型内**。
    这一层不是靠命名约定，而是靠 Schema 的枚举约束——写能力没有表达位置，
    因此"具备 COMMENT_REPLY"永远不会被误读成"具备发布回复"。
    """

    READ: Final = "READ"

    ALL: Final = (READ,)


class LoadStatus:
    """适配器加载状态（**不是** 能力实现状态）。"""

    LOADED: Final = "LOADED"
    FAILED: Final = "FAILED"

    ALL: Final = (LOADED, FAILED)


class DiagnosticReason:
    """诊断原因码。用于解释"为什么这个能力不可用"。

    这些是**原因**，不是状态——不进入 ``implementationStatus`` / ``effectiveStatus`` 枚举。
    """

    #: 能力声明的函数引用解析不到真实可调用对象
    FUNCTION_UNRESOLVED: Final = "INVALID_IMPLEMENTATION"
    #: 适配器整体加载失败
    ADAPTER_NOT_LOADED: Final = "NOT_LOADED"
    #: 适配器版本与 Java 侧期望不匹配
    VERSION_MISMATCH: Final = "VERSION_MISMATCH"
    #: 该能力被显式禁用
    DISABLED: Final = "DISABLED"
    #: 平台不支持
    UNSUPPORTED: Final = "UNSUPPORTED"

    ALL: Final = (FUNCTION_UNRESOLVED, ADAPTER_NOT_LOADED, VERSION_MISMATCH, DISABLED, UNSUPPORTED)


#: Schema 中 ``sourceCode`` 的合法取值（= 平台枚举）。
#: 非平台接入（如链路验证用的 stub）**没有** sourceCode，不进入 v1 平台文档。
PLATFORM_SOURCE_CODES: Final[tuple[str, ...]] = ("DOUYIN", "XIAOHONGSHU", "KUAISHOU", "WEB")


@dataclass(frozen=True)
class CapabilitySpec:
    """一条能力声明。

    注意：本对象只承载"声明"。声明是否**兑现**，取决于
    ``adapter_function`` 能否解析到真实可调用函数。
    """

    code: str
    category: str
    name: str
    access_modes: tuple[str, ...]
    implementation_status: str
    contract_version: str
    adapter_function: str
    input_schema: dict[str, Any] | None = None
    output_schema: dict[str, Any] | None = None
    limits: dict[str, Any] | None = None
    retry_policy: dict[str, Any] | None = None
    verified_at: str | None = None
    note: str = ""
    #: 读写语义。本契约只支持只读能力，取值只能是 ``READ``。
    semantics: str = CapabilitySemantics.READ
    #: 验证证据的绑定信息与校验结论（可选）。存在证据记录时才输出。
    evidence: dict[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.code not in CapabilityCode.ALL:
            raise ValueError(f"未知能力码: {self.code}")
        if self.category not in CapabilityCategory.ALL:
            raise ValueError(f"未知能力分类: {self.category}")
        if self.implementation_status not in ImplementationStatus.ALL:
            raise ValueError(f"未知实现状态: {self.implementation_status}")
        if not self.access_modes:
            raise ValueError(f"能力 {self.code} 必须声明至少一种访问模式")
        for mode in self.access_modes:
            if mode not in AccessMode.ALL:
                raise ValueError(f"未知访问模式: {mode}")
        if self.semantics not in CapabilitySemantics.ALL:
            raise ValueError(f"未知能力语义: {self.semantics}")
        if not self.adapter_function:
            raise ValueError(f"能力 {self.code} 必须绑定 adapterFunction")

    @property
    def key(self) -> str:
        return f"{self.category}:{self.code}"

    def to_v1(self) -> dict[str, Any]:
        """输出为 SOURCE-02 §02-03 的 Schema 形状（camelCase）。"""
        out: dict[str, Any] = {
            "code": self.code,
            "category": self.category,
            "name": self.name,
            "accessModes": list(self.access_modes),
            "implementationStatus": self.implementation_status,
            "contractVersion": self.contract_version,
            "adapterFunction": self.adapter_function,
        }
        if self.input_schema is not None:
            out["inputSchema"] = self.input_schema
        if self.output_schema is not None:
            out["outputSchema"] = self.output_schema
        if self.limits is not None:
            out["limits"] = self.limits
        if self.retry_policy is not None:
            out["retryPolicy"] = self.retry_policy
        if self.verified_at:
            out["verifiedAt"] = self.verified_at
        if self.note:
            out["note"] = self.note
        out["semantics"] = self.semantics
        if self.evidence is not None:
            out["evidence"] = self.evidence
        return out


@dataclass
class AdapterMetadata:
    """适配器（接入实现）的元信息。"""

    connector_code: str
    source_code: str
    connector_version: str
    load_status: str
    module: str
    #: 非平台接入（如 stub 链路验证替身）没有 sourceCode，不进入 v1 平台文档
    platform_scoped: bool = True
    error_code: str | None = None
    error_message: str | None = None
    capabilities: list[CapabilitySpec] = field(default_factory=list)
    legacy_status: str | None = None
    legacy_note: str | None = None

    def __post_init__(self) -> None:
        """``platform_scoped`` 必须是真正的 ``bool``。

        它不是装饰性字段：Java 侧靠它把链路验证替身（``STUB_PUBLIC``）从
        **真实平台能力准入**里排除。一旦这里出现 ``"false"`` 这样的字符串，
        下游按真值判断时会把它当成"真实接入"——属于失败开放（fail-open）。

        所以这里不做事后 ``bool()`` 转换，而是**构造时就拒绝**：
        宁可启动失败，也不产出一个语义模糊的快照。
        """
        if not isinstance(self.platform_scoped, bool):
            raise TypeError(
                "platform_scoped 必须是 bool，实际为 "
                f"{type(self.platform_scoped).__name__}: {self.platform_scoped!r}"
            )

    def to_v1(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "connectorCode": self.connector_code,
            "sourceCode": self.source_code,
            "connectorVersion": self.connector_version,
            "loadStatus": self.load_status,
            "module": self.module,
            "platformScoped": self.platform_scoped,
            "capabilityCount": len(self.capabilities),
        }
        if self.error_code:
            out["errorCode"] = self.error_code
        if self.error_message:
            out["errorMessage"] = self.error_message
        return out


def legacy_status_from(implementation_status: str) -> str:
    """v1 实现状态 → 旧端点用的状态词。

    旧词表（与历史 ``Capability.status`` 一致，保留大写形态）：
    ``AVAILABLE`` / ``LIMITED`` / ``DISABLED`` / ``PENDING``。
    映射规则（保守）：

    * VERIFIED / IMPLEMENTED → AVAILABLE（确有能力且可用）
    * DEGRADED → LIMITED（可用但受限）
    * UNVERIFIED → PENDING（代码在，但从没验证过）
    * DISABLED / UNSUPPORTED → DISABLED
    """
    return {
        ImplementationStatus.VERIFIED: "AVAILABLE",
        ImplementationStatus.IMPLEMENTED: "AVAILABLE",
        ImplementationStatus.DEGRADED: "LIMITED",
        ImplementationStatus.UNVERIFIED: "PENDING",
        ImplementationStatus.DISABLED: "DISABLED",
        ImplementationStatus.UNSUPPORTED: "DISABLED",
    }.get(implementation_status, "PENDING")


def aggregate_legacy_status(specs: list["CapabilitySpec"]) -> str:
    """把一组能力的实现状态汇总成旧端点的单一状态词。

    取"最好"的一个作为该接入的整体状态——接入只要有一项可用能力，
    就不该整体被判成不可用；具体逐项状态由 v1 清单表达。
    """
    order = [
        ImplementationStatus.VERIFIED,
        ImplementationStatus.IMPLEMENTED,
        ImplementationStatus.DEGRADED,
        ImplementationStatus.UNVERIFIED,
        ImplementationStatus.DISABLED,
        ImplementationStatus.UNSUPPORTED,
    ]
    if not specs:
        return "PENDING"
    present = {s.implementation_status for s in specs}
    for status in order:
        if status in present:
            return legacy_status_from(status)
    return "PENDING"
