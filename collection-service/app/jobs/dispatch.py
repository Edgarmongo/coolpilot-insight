"""按 Registry 派发作业（SOURCE-06 · C1 / C2）。

**唯一职责**：给定 ``(connectorCode, capabilityCode, accessMode)``，
回答两个问题——"由谁执行"和"现在能不能执行"。

它不做 IO、不启动浏览器、不写 CSV。这样判定逻辑可以穷举测试，
而不是等到真跑一次采集才发现某条分支写反了。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Callable

#: 与 ``SOURCE-04-04-EFFECTIVE-REASON.md`` 逐字对应的拒绝理由。
#: 两侧用同一套词，上游拿到的错误可以直接映射成 Effective 结论。
CONNECTOR_NOT_FOUND = "CONNECTOR_NOT_FOUND"
ADAPTER_NOT_LOADED = "ADAPTER_NOT_LOADED"
CAPABILITY_NOT_REGISTERED = "CAPABILITY_NOT_REGISTERED"
FUNCTION_UNRESOLVED = "FUNCTION_UNRESOLVED"
ACCESS_MODE_UNSUPPORTED = "ACCESS_MODE_UNSUPPORTED"
NOT_EXECUTABLE = "NOT_EXECUTABLE"
STUB_NOT_ALLOWED = "STUB_NOT_ALLOWED"

#: HTTP 状态码。404 只给"确实不存在"，其余是"存在但当前不可执行"——用 409 区分开，
#: 否则调用方无法判断"是不是我把参数写错了"。
HTTP_STATUS = {
    CONNECTOR_NOT_FOUND: 404,
    ADAPTER_NOT_LOADED: 409,
    CAPABILITY_NOT_REGISTERED: 409,
    FUNCTION_UNRESOLVED: 409,
    ACCESS_MODE_UNSUPPORTED: 409,
    NOT_EXECUTABLE: 409,
    STUB_NOT_ALLOWED: 403,
}

#: 只有这个状态才允许真实执行（``IMPLEMENTED`` 表示"本地测试通过"，不是平台跑通过）。
EXECUTABLE_STATUS = "VERIFIED"


class DispatchError(Exception):
    """派发被拒绝。``code`` 是上面的常量之一。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = HTTP_STATUS.get(code, 409)

    def as_body(self) -> dict[str, Any]:
        return {"code": self.code, "message": self.message}


@dataclass(frozen=True)
class Dispatch:
    """一次成功派发的产物。"""

    connector_code: str
    capability_code: str
    access_mode: str
    adapter_function: str
    spec: Any
    entry: Any
    fn: Callable[..., Any]


def _find_entry(registry, connector_code: str):
    for entry in registry.adapters.list():
        if entry.connector_code == connector_code:
            return entry
    return None


def _find_spec(registry, connector_code: str, capability_code: str):
    # observed_specs 已经把验证证据应用上去了（evidence 已判定、状态已提升），
    # 因此这里看到的就是"运行时真实的 Observed"，不是声明时的原样。
    for spec in registry.observed_specs(connector_code):
        if spec.code == capability_code:
            return spec
    return None


def _evidence_accepted(spec: Any) -> bool:
    evidence = getattr(spec, "evidence", None)
    return isinstance(evidence, dict) and evidence.get("accepted") is True


def resolve(registry, connector_code: str, capability_code: str,
            access_mode: str) -> Dispatch:
    """解析并校验。任何一步不成立都抛 :class:`DispatchError`，**不返回 None**。

    返回值不是 Optional 是有意的：让"没解析到"必须走异常路径，
    调用方无法用 ``if dispatch:`` 把失败悄悄当成"跳过"。
    """
    if not connector_code or not capability_code:
        raise DispatchError(CAPABILITY_NOT_REGISTERED, "connectorCode 与 capabilityCode 都必须给出")

    entry = _find_entry(registry, connector_code)
    if entry is None:
        raise DispatchError(
            CONNECTOR_NOT_FOUND,
            f"未知接入: {connector_code}（Registry 中已注册: "
            f"{[e.connector_code for e in registry.adapters.list()]}）")

    if entry.load_status != "LOADED":
        raise DispatchError(
            ADAPTER_NOT_LOADED,
            f"接入 {connector_code} 未加载成功: {entry.error_code or ''} {entry.error_message or ''}".strip())

    # 替身只能在开发时显式打开，且必须自己声明为 STUB_PUBLIC。
    # 否则"没配好"会退化成"用假数据跑成功了"，这是最危险的一种失败。
    if not entry.platform_scoped and os.environ.get("COLLECTION_ALLOW_STUB") != "1":
        raise DispatchError(
            STUB_NOT_ALLOWED,
            f"接入 {connector_code} 是链路验证替身，生产不允许执行"
            "（如确需使用，请显式设置 COLLECTION_ALLOW_STUB=1）")

    spec = _find_spec(registry, connector_code, capability_code)
    if spec is None:
        raise DispatchError(
            CAPABILITY_NOT_REGISTERED,
            f"接入 {connector_code} 没有能力 {capability_code}")

    fn = registry.capabilities.resolve(getattr(spec, "adapter_function", "") or "")
    if fn is None or not callable(fn):
        raise DispatchError(
            FUNCTION_UNRESOLVED,
            f"能力 {capability_code} 的函数引用无法解析: {getattr(spec, 'adapter_function', '')}")

    modes = tuple(getattr(spec, "access_modes", ()) or ())
    if access_mode not in modes:
        raise DispatchError(
            ACCESS_MODE_UNSUPPORTED,
            f"能力 {capability_code} 不支持访问模式 {access_mode}（允许: {list(modes)}）")

    if getattr(spec, "implementation_status", None) != EXECUTABLE_STATUS:
        raise DispatchError(
            NOT_EXECUTABLE,
            f"能力 {capability_code} 的实现状态是 "
            f"{getattr(spec, 'implementation_status', None)}，不是 {EXECUTABLE_STATUS}")

    if not _evidence_accepted(spec):
        raise DispatchError(
            NOT_EXECUTABLE,
            f"能力 {capability_code} 没有已接受的验证证据"
            f"（evidence.accepted={getattr(spec, 'evidence', None) and spec.evidence.get('accepted')}）")

    return Dispatch(
        connector_code=connector_code,
        capability_code=capability_code,
        access_mode=access_mode,
        adapter_function=getattr(spec, "adapter_function", ""),
        spec=spec,
        entry=entry,
        fn=fn,
    )
