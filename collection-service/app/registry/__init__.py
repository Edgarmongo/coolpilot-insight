"""采集服务的能力注册表（SOURCE-03）。

* :mod:`app.registry.capability` —— v1 契约的数据结构与枚举
* :mod:`app.registry.adapter_registry` —— 接入实现注册表
* :mod:`app.registry.capability_registry` —— 能力注册表（能力 = 声明 + 函数绑定）
* :mod:`app.registry.bootstrap` —— 组装默认 Registry
* :mod:`app.registry.errors` —— 技术错误码与 failure_type 映射
"""

from app.registry.bootstrap import (
    CollectionRegistry,
    build_registry,
    get_registry,
    reset_registry,
)

__all__ = [
    "CollectionRegistry",
    "build_registry",
    "get_registry",
    "reset_registry",
]
