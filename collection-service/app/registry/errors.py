"""技术错误码与业务失败类型的映射。

契约来源：``docs/architecture/collection/SOURCE-02-CONTRACT.md`` §02-06。

两条硬规则，实现里不要绕过：

1. ``retryable`` **由 failure_type 决定**，调用方不允许读错误文案去猜。
2. 机器判断只看 ``code`` 与 ``failureType``；``message`` 只给人看、只进日志。

错误码本身是**稳定契约**：新增可以，语义变更不行。Java 侧按码分派重试策略。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Final

# --------------------------------------------------------------------------
# 七类业务失败类型（复用既有定义，不新建）
# --------------------------------------------------------------------------
FAILURE_AUTHENTICATION: Final = "AUTHENTICATION"
FAILURE_AUTHORIZATION: Final = "AUTHORIZATION"
FAILURE_RATE_LIMIT: Final = "RATE_LIMIT"
FAILURE_NETWORK: Final = "NETWORK"
FAILURE_PLATFORM: Final = "PLATFORM"
FAILURE_DATA: Final = "DATA"
FAILURE_SYSTEM: Final = "SYSTEM"

FAILURE_TYPES: Final[tuple[str, ...]] = (
    FAILURE_AUTHENTICATION,
    FAILURE_AUTHORIZATION,
    FAILURE_RATE_LIMIT,
    FAILURE_NETWORK,
    FAILURE_PLATFORM,
    FAILURE_DATA,
    FAILURE_SYSTEM,
)


@dataclass(frozen=True)
class ErrorSpec:
    """一个技术错误码的完整定义。"""

    code: str
    failure_type: str
    retryable: bool
    message: str


def _spec(code: str, failure_type: str, retryable: bool, message: str) -> ErrorSpec:
    return ErrorSpec(code=code, failure_type=failure_type, retryable=retryable, message=message)


# --------------------------------------------------------------------------
# 技术错误码表
# --------------------------------------------------------------------------
TECH_ERROR_SPECS: Final[dict[str, ErrorSpec]] = {
    # 凭证 / 授权
    "CREDENTIAL_MISSING": _spec("CREDENTIAL_MISSING", FAILURE_AUTHENTICATION, False, "未配置凭证引用"),
    "CREDENTIAL_NOT_FOUND": _spec("CREDENTIAL_NOT_FOUND", FAILURE_AUTHENTICATION, False, "凭证引用指向的文件不可读"),
    "CREDENTIAL_EXPIRED": _spec("CREDENTIAL_EXPIRED", FAILURE_AUTHENTICATION, False, "凭证已过期"),
    "AUTH_REQUIRED": _spec("AUTH_REQUIRED", FAILURE_AUTHENTICATION, False, "需要登录但未提供有效账号"),
    "AUTHORIZATION_DENIED": _spec("AUTHORIZATION_DENIED", FAILURE_AUTHORIZATION, False, "授权范围不足"),
    # 限流 / 网络
    "RATE_LIMITED": _spec("RATE_LIMITED", FAILURE_RATE_LIMIT, True, "触发平台限流，退避后重试"),
    "CONNECT_TIMEOUT": _spec("CONNECT_TIMEOUT", FAILURE_NETWORK, True, "平台连接超时"),
    "UPSTREAM_UNREACHABLE": _spec("UPSTREAM_UNREACHABLE", FAILURE_NETWORK, True, "上游不可达"),
    # 平台
    "PLATFORM_UNAVAILABLE": _spec("PLATFORM_UNAVAILABLE", FAILURE_PLATFORM, False, "平台接口不可用或已变更"),
    "VIDEO_MISSING": _spec("VIDEO_MISSING", FAILURE_PLATFORM, False, "目标视频不存在或已删除"),
    # 数据
    "PARSE_FAILED": _spec("PARSE_FAILED", FAILURE_DATA, True, "响应解析失败"),
    # 系统
    "ADAPTER_ERROR": _spec("ADAPTER_ERROR", FAILURE_SYSTEM, True, "适配器执行异常"),
    "SERVICE_UNAVAILABLE": _spec("SERVICE_UNAVAILABLE", FAILURE_SYSTEM, True, "采集服务不可达"),
    "SECRET_STORAGE_UNAVAILABLE": _spec("SECRET_STORAGE_UNAVAILABLE", FAILURE_SYSTEM, True, "凭证存储目录不可用"),
    # 注册与解析（SOURCE-03 新增）
    "ADAPTER_LOAD_FAILED": _spec("ADAPTER_LOAD_FAILED", FAILURE_SYSTEM, True, "适配器加载失败"),
    "FUNCTION_UNRESOLVED": _spec("FUNCTION_UNRESOLVED", FAILURE_SYSTEM, False, "能力声明的函数引用无法解析"),
    "DUPLICATE_ADAPTER": _spec("DUPLICATE_ADAPTER", FAILURE_SYSTEM, False, "同一接入实现重复注册"),
    "ADAPTER_NOT_FOUND": _spec("ADAPTER_NOT_FOUND", FAILURE_SYSTEM, False, "接入实现不存在"),
    "CAPABILITY_DECLARATION_INVALID": _spec(
        "CAPABILITY_DECLARATION_INVALID", FAILURE_SYSTEM, False, "能力声明不符合契约"
    ),
}


def technical_error(code: str) -> ErrorSpec:
    """按码取错误定义；未知码归到 ADAPTER_ERROR，避免抛出未定义异常。"""
    return TECH_ERROR_SPECS.get(code, TECH_ERROR_SPECS["ADAPTER_ERROR"])


def failure_type_of(code: str) -> str:
    return technical_error(code).failure_type


def is_retryable(code: str) -> bool:
    """重试与否只看 failure_type，不看文案。"""
    return technical_error(code).retryable


class CollectorError(RuntimeError):
    """带稳定技术错误码的采集异常。

    Java 侧按 ``code`` 分派重试；``failureType`` 直接来自错误码表。
    """

    def __init__(self, code: str, message: str | None = None, detail: str | None = None):
        spec = technical_error(code)
        super().__init__(message or spec.message)
        self.code = code
        self.failure_type = spec.failure_type
        self.retryable = spec.retryable
        self.detail = detail

    def to_dict(self) -> dict:
        return {
            "code": self.code,
            "failureType": self.failure_type,
            "retryable": self.retryable,
            "message": str(self),
            "detail": self.detail,
        }


# --------------------------------------------------------------------------
# 异常 → 技术错误码
# --------------------------------------------------------------------------
def classify_exception(exc: BaseException) -> ErrorSpec:
    """把任意异常翻译成稳定技术错误码。

    刻意**不**用异常文案做判断——文案会变，码不会。
    """
    if isinstance(exc, CollectorError):
        return technical_error(exc.code)
    if isinstance(exc, TimeoutError):
        return technical_error("CONNECT_TIMEOUT")
    if isinstance(exc, ConnectionError):
        return technical_error("UPSTREAM_UNREACHABLE")
    if isinstance(exc, (ValueError, KeyError, TypeError)):
        return technical_error("PARSE_FAILED")
    if isinstance(exc, NotImplementedError):
        return technical_error("PLATFORM_UNAVAILABLE")
    if isinstance(exc, ImportError):
        return technical_error("ADAPTER_LOAD_FAILED")
    return technical_error("ADAPTER_ERROR")
