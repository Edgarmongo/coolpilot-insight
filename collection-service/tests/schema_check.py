"""轻量 JSON Schema 校验器（Draft 2020-12 子集）。

**为什么不直接用 ``jsonschema`` 库**：采集服务的运行镜像里不装它，
而"输出必须通过 Schema 校验"是 SOURCE-03 的硬验收项。
这里实现契约实际用到的那些关键字，够用且零依赖：

``$ref``（仅 ``#/$defs/*``）、``type``、``enum``、``const``、``required``、
``properties``、``additionalProperties``、``items``、``minItems``、
``minLength``、``minimum``、``pattern``、``format``（仅 ``date-time``）。

如果环境里恰好装了 ``jsonschema``，:func:`validate` 会优先用它——
两条路径都必须通过。
"""

from __future__ import annotations

import json
import os
import re
from typing import Any

#: 契约 Schema 的**权威副本**在仓库根 ``docs/architecture/collection/``。
#: 采集服务镜像里只 COPY 了 ``collection-service/``，所以另有一份**随包副本**
#: 放在 ``collection-service/app/registry/capability-schema.json``，
#: 由 :func:`test_schema_copy_in_sync` 守护二者不漂移。
_HERE = os.path.dirname(os.path.abspath(__file__))            # collection-service/tests
_SERVICE_ROOT = os.path.dirname(_HERE)                        # collection-service
_REPO_ROOT = os.path.dirname(_SERVICE_ROOT)                   # 仓库根

REPO_SCHEMA_FILE = os.path.join(
    _REPO_ROOT, "docs", "architecture", "collection", "SOURCE-02-capability-schema.json",
)
BUNDLED_SCHEMA_FILE = os.path.join(
    _SERVICE_ROOT, "app", "registry", "capability-schema.json",
)
SCHEMA_CANDIDATES = (REPO_SCHEMA_FILE, BUNDLED_SCHEMA_FILE)

_TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "number": (int, float),
    "integer": int,
    "boolean": bool,
    "null": type(None),
}


class SchemaError(AssertionError):
    """Schema 校验失败。"""


def load_schema(path: str | None = None) -> dict[str, Any]:
    """载入契约 Schema。

    默认按顺序找：仓库根的权威副本 → 随包副本（容器内用这个）。
    """
    if path:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    for candidate in SCHEMA_CANDIDATES:
        if os.path.exists(candidate):
            with open(candidate, encoding="utf-8") as f:
                return json.load(f)
    raise SchemaError(f"找不到契约 Schema，候选路径：{SCHEMA_CANDIDATES}")


def _resolve(schema: dict[str, Any], root: dict[str, Any]) -> dict[str, Any]:
    ref = schema.get("$ref")
    if not ref:
        return schema
    if not ref.startswith("#/"):
        raise SchemaError(f"仅支持文档内引用: {ref}")
    node: Any = root
    for part in ref[2:].split("/"):
        node = node[part]
    merged = dict(node)
    for k, v in schema.items():
        if k != "$ref":
            merged[k] = v
    return merged


def _check_type(value: Any, expected: str, path: str) -> None:
    py = _TYPES.get(expected)
    if py is None:
        return
    if expected == "integer" and isinstance(value, bool):
        raise SchemaError(f"{path}: 期望 integer，实际 boolean")
    if expected == "number" and isinstance(value, bool):
        raise SchemaError(f"{path}: 期望 number，实际 boolean")
    if not isinstance(value, py):
        raise SchemaError(f"{path}: 期望 {expected}，实际 {type(value).__name__}")


def _validate(value: Any, schema: dict[str, Any], root: dict[str, Any], path: str) -> None:
    schema = _resolve(schema, root)

    if "const" in schema and value != schema["const"]:
        raise SchemaError(f"{path}: 期望常量 {schema['const']!r}，实际 {value!r}")

    if "enum" in schema and value not in schema["enum"]:
        raise SchemaError(f"{path}: {value!r} 不在枚举 {schema['enum']} 内")

    if "type" in schema:
        expected = schema["type"]
        if isinstance(expected, list):
            for one in expected:
                try:
                    _check_type(value, one, path)
                    break
                except SchemaError:
                    continue
            else:
                raise SchemaError(f"{path}: 期望 {expected} 之一，实际 {type(value).__name__}")
        else:
            _check_type(value, expected, path)

    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            raise SchemaError(f"{path}: 长度 {len(value)} 小于 minLength {schema['minLength']}")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            raise SchemaError(f"{path}: {value!r} 不匹配 pattern {schema['pattern']}")
        if schema.get("format") == "date-time":
            if not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}", value):
                raise SchemaError(f"{path}: {value!r} 不是 ISO8601 date-time")

    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            raise SchemaError(f"{path}: {value} 小于 minimum {schema['minimum']}")

    if isinstance(value, dict):
        for key in schema.get("required", []):
            if key not in value:
                raise SchemaError(f"{path}: 缺少必填字段 {key}")
        props = schema.get("properties", {})
        for key, sub in value.items():
            if key in props:
                _validate(sub, props[key], root, f"{path}.{key}")
            elif schema.get("additionalProperties") is False:
                raise SchemaError(f"{path}: 不允许的额外字段 {key}")

    if isinstance(value, list):
        if "minItems" in schema and len(value) < schema["minItems"]:
            raise SchemaError(f"{path}: 元素数 {len(value)} 小于 minItems {schema['minItems']}")
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for i, item in enumerate(value):
                _validate(item, item_schema, root, f"{path}[{i}]")


def validate(value: Any, schema: dict[str, Any], path: str = "$") -> None:
    """校验失败抛 :class:`SchemaError`。"""
    try:
        import jsonschema  # type: ignore
    except ImportError:
        jsonschema = None

    if jsonschema is not None:
        validator = jsonschema.Draft202012Validator(schema)
        errors = sorted(validator.iter_errors(value), key=lambda e: list(e.path))
        if errors:
            first = errors[0]
            loc = "/".join(str(p) for p in first.path) or "$"
            raise SchemaError(f"{loc}: {first.message}")
        return

    _validate(value, schema, schema, path)
