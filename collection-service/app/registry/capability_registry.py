"""能力注册表：能力 = 声明 + 函数绑定。

SOURCE-02 §02-04 的硬规则，在这里落地：

* 能力不是"写在 JSON 里就算数"，**必须**能解析到真实可调用函数，
  否则它不会出现在 Observed 清单里。
* ``adapterFunction`` 指向的函数不存在、不可调用，或声明本身不合约，
  都会产生一条**诊断记录**，说明为什么不可用——但不会伪造 UNAVAILABLE。
* "函数存在""函数可调用""函数执行成功"是三件事：
  本模块只证明前两件；第三件由验证证据（``verification_evidence.json``）表达。
"""

from __future__ import annotations

import inspect
from dataclasses import dataclass
from typing import Any, Callable

from app.registry.capability import CapabilitySpec, DiagnosticReason
from app.registry.errors import FAILURE_TYPES, CollectorError
from app.registry.function_resolver import resolve_callable, resolve_reference


@dataclass
class CapabilityDiagnostic:
    """能力不可用的原因。**这是原因，不是状态。**"""

    connector_code: str
    capability_code: str
    reason: str
    detail: str
    adapter_function: str

    def to_v1(self) -> dict[str, Any]:
        return {
            "connectorCode": self.connector_code,
            "capabilityCode": self.capability_code,
            "reason": self.reason,
            "detail": self.detail,
            "adapterFunction": self.adapter_function,
        }


def _normalize(name: str) -> str:
    return name.replace("_", "").replace("-", "").lower()


class CapabilityRegistry:
    """能力注册表。"""

    def __init__(self) -> None:
        self._functions: dict[str, Callable] = {}
        self._specs: list[tuple[str, CapabilitySpec]] = []
        # 评估结果缓存：(connector_code, capability_code) -> (ok, reason, detail)
        # 声明或函数注册变化时缓存失效，避免同一诊断重复入库。
        self._evaluations: dict[tuple[str, str], tuple[bool, str | None, str | None]] = {}

    # ------------------------------------------------------------------
    # 函数注册
    # ------------------------------------------------------------------
    def register_function(self, fn_name: str, fn: Callable) -> None:
        """显式注册一个可调用对象。

        重复注册同名函数会被拒绝——避免静默替换让能力"指向别的东西"。
        """
        if not callable(fn):
            raise CollectorError("ADAPTER_ERROR", f"注册对象不可调用: {fn_name}")
        if fn_name in self._functions:
            raise CollectorError("DUPLICATE_ADAPTER", f"函数重复注册: {fn_name}")
        self._functions[fn_name] = fn
        self._evaluations.clear()

    def resolve(self, fn_name: str) -> Callable | None:
        """解析函数引用。

        优先用显式注册表；没有再按引用路径动态导入。
        """
        if fn_name in self._functions:
            return self._functions[fn_name]
        return resolve_callable(fn_name)

    def registered_functions(self) -> list[str]:
        return sorted(self._functions)

    # ------------------------------------------------------------------
    # 能力声明
    # ------------------------------------------------------------------
    def declare(self, connector_code: str, spec: CapabilitySpec) -> None:
        """登记一条能力声明。**此时不做可用性判断**。"""
        self._specs.append((connector_code, spec))
        self._evaluations.clear()

    # ------------------------------------------------------------------
    # 校验
    # ------------------------------------------------------------------
    def _validate_spec(self, spec: CapabilitySpec, fn: Callable | None) -> str | None:
        """校验声明与真实函数是否对得上。返回问题描述，None 表示通过。"""
        if fn is None:
            return "函数引用无法解析为可调用对象"

        # 输入契约：必须是 object 型 JSON Schema
        if spec.input_schema is not None:
            if not isinstance(spec.input_schema, dict):
                return "inputSchema 必须是对象"
            if spec.input_schema.get("type") not in (None, "object"):
                return "inputSchema.type 必须是 object"
            required = spec.input_schema.get("required") or []
            if not isinstance(required, list):
                return "inputSchema.required 必须是数组"
            if required:
                try:
                    params = inspect.signature(fn).parameters
                except (TypeError, ValueError):
                    params = None
                if params is not None:
                    accepts_kwargs = any(
                        p.kind is inspect.Parameter.VAR_KEYWORD for p in params.values()
                    )
                    if not accepts_kwargs:
                        known = {_normalize(p) for p in params}
                        missing = [r for r in required if _normalize(r) not in known]
                        if missing:
                            return f"inputSchema.required 与函数签名不匹配: {missing}"

        # 输出契约：必须是对象
        if spec.output_schema is not None and not isinstance(spec.output_schema, dict):
            return "outputSchema 必须是对象"

        # 重试策略里的错误类别必须是既有七类之一
        if spec.retry_policy is not None:
            errors = spec.retry_policy.get("retryableErrors") or []
            for e in errors:
                if e not in FAILURE_TYPES:
                    return f"retryPolicy.retryableErrors 含未知失败类型: {e}"
        return None

    # ------------------------------------------------------------------
    # 清单
    # ------------------------------------------------------------------
    def _evaluate(self, connector_code: str, spec: CapabilitySpec) -> tuple[bool, str | None, str | None]:
        """评估一条声明是否兑现。返回 (是否兑现, 诊断原因, 诊断细节)。结果带缓存。"""
        cache_key = (connector_code, spec.code)
        cached = self._evaluations.get(cache_key)
        if cached is not None:
            return cached

        fn = self.resolve(spec.adapter_function)
        if fn is None:
            raw = resolve_reference(spec.adapter_function)
            detail = "属性不存在或模块无法导入" if raw is None else "引用指向的对象不可调用"
            result = (False, DiagnosticReason.FUNCTION_UNRESOLVED, detail)
        else:
            problem = self._validate_spec(spec, fn)
            if problem is not None:
                result = (False, DiagnosticReason.FUNCTION_UNRESOLVED, problem)
            else:
                result = (True, None, None)

        self._evaluations[cache_key] = result
        return result

    def list_capabilities(self, connector_code: str | None = None) -> list[CapabilitySpec]:
        """Observed 能力清单：**只包含函数能解析且声明合规的能力**。"""
        return [
            spec
            for code, spec in self._specs
            if (connector_code is None or code == connector_code) and self._evaluate(code, spec)[0]
        ]

    def unresolved_capabilities(self, connector_code: str | None = None) -> list[CapabilitySpec]:
        """诊断用：声明了但兑现不了的能力。"""
        return [
            spec
            for code, spec in self._specs
            if (connector_code is None or code == connector_code) and not self._evaluate(code, spec)[0]
        ]

    def diagnostics(self, connector_code: str | None = None) -> list[CapabilityDiagnostic]:
        """诊断记录：为什么某条能力兑现不了。"""
        out: list[CapabilityDiagnostic] = []
        for code, spec in self._specs:
            if connector_code is not None and code != connector_code:
                continue
            ok, reason, detail = self._evaluate(code, spec)
            if ok:
                continue
            out.append(
                CapabilityDiagnostic(
                    connector_code=code,
                    capability_code=spec.code,
                    reason=reason or DiagnosticReason.FUNCTION_UNRESOLVED,
                    detail=detail or "",
                    adapter_function=spec.adapter_function,
                )
            )
        return out

    def declared_specs(self, connector_code: str | None = None) -> list[CapabilitySpec]:
        """全部声明（含兑现不了的），供诊断与展示使用。"""
        return [
            spec
            for code, spec in self._specs
            if connector_code is None or code == connector_code
        ]
