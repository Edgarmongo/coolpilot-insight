"""函数引用解析。

能力靠 ``adapterFunction`` 证明自己存在，所以解析规则必须**确定**：

* 支持 ``包路径.模块:函数名`` 与 ``包路径.模块.函数名`` 两种写法；
* 解析失败返回 ``None``（不抛异常），由调用方决定如何表达"不可用"；
* 解析出来的对象还要 **callable** —— 存在但不可调用同样不算数。
"""

from __future__ import annotations

import importlib
from typing import Any, Callable


def split_reference(reference: str) -> tuple[str, str]:
    """把函数引用拆成 (模块路径, 属性名)。"""
    if ":" in reference:
        module, _, attr = reference.partition(":")
        return module.strip(), attr.strip()
    module, _, attr = reference.rpartition(".")
    return module.strip(), attr.strip()


def resolve_reference(reference: str) -> Any | None:
    """解析函数引用。解析不到返回 ``None``，**不抛异常**。"""
    if not reference or not reference.strip():
        return None
    module_path, attr = split_reference(reference)
    if not module_path or not attr:
        return None
    try:
        module = importlib.import_module(module_path)
    except Exception:
        return None
    obj: Any = module
    for part in attr.split("."):
        obj = getattr(obj, part, None)
        if obj is None:
            return None
    return obj


def resolve_callable(reference: str) -> Callable | None:
    """解析函数引用并要求结果是可调用对象。"""
    obj = resolve_reference(reference)
    if obj is None or not callable(obj):
        return None
    return obj
