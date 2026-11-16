"""SOURCE-03 Registry 契约测试。

覆盖 03-06 验收矩阵：

* 正常注册 / 重复注册 / 函数缺失 / 函数不可调用
* 单个平台加载失败时其它平台正常
* UNVERIFIED 能力不进入 AVAILABLE
* Schema 输出通过 JSON Schema 校验
* 旧接口兼容性

运行（容器内）::

    cd /app/collection-service && /opt/venv/bin/python tests/test_registry.py
"""

from __future__ import annotations

import json
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.adapters.base import PlatformAdapter, RawComment, RawContent  # noqa: E402
from app.registry.adapter_registry import AdapterRegistry  # noqa: E402
from app.registry.bootstrap import (  # noqa: E402
    CollectionRegistry,
    build_registry,
    load_evidence,
)
from app.registry.capability import (  # noqa: E402
    SCHEMA_VERSION,
    AccessMode,
    CapabilityCategory,
    CapabilityCode,
    CapabilitySpec,
    ImplementationStatus,
    LoadStatus,
)
from app.registry.capability_registry import CapabilityRegistry  # noqa: E402
from app.registry.errors import (  # noqa: E402
    FAILURE_TYPES,
    CollectorError,
    classify_exception,
    is_retryable,
)
from app.registry.function_resolver import resolve_callable  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from schema_check import SchemaError, load_schema, validate  # noqa: E402


# ----------------------------------------------------------------------
# 测试用的假适配器
# ----------------------------------------------------------------------
class _FakeAdapter(PlatformAdapter):
    source_code = "DOUYIN"
    connector_code = "FAKE_PUBLIC"
    connector_version = "1.0.0"

    @property
    def capabilities(self):
        from app.adapters.base import Capability

        return Capability(status="PENDING")

    def validate_config(self) -> None:
        return None

    def discover(self, query):
        return iter(())

    def collect_content(self, external_id) -> RawContent:
        return RawContent(external_id=external_id)

    def collect_comments(self, external_id, limit=300):
        return iter(())


def _spec(fn: str, code: str = CapabilityCode.COMMENT,
          status: str = ImplementationStatus.UNVERIFIED,
          required: tuple[str, ...] = ("videoId",)) -> CapabilitySpec:
    return CapabilitySpec(
        code=code,
        category=CapabilityCategory.COMMENT,
        name="测试能力",
        access_modes=(AccessMode.PUBLIC,),
        implementation_status=status,
        contract_version="1.0",
        adapter_function=fn,
        input_schema={"type": "object", "required": list(required)},
        output_schema={"type": "object"},
    )


# ----------------------------------------------------------------------
# 03-02 Adapter Registry
# ----------------------------------------------------------------------
def test_adapter_registry_normal_registration():
    reg = AdapterRegistry()
    entry = reg.register(_FakeAdapter(), [_spec("app.adapters.base.RawComment")])
    assert entry.load_status == LoadStatus.LOADED
    assert reg.has("FAKE_PUBLIC")
    assert reg.get("FAKE_PUBLIC") is not None
    assert len(reg.list()) == 1


def test_adapter_registry_rejects_duplicate():
    reg = AdapterRegistry()
    reg.register(_FakeAdapter(), [])
    try:
        reg.register(_FakeAdapter(), [])
    except CollectorError as e:
        assert e.code == "DUPLICATE_ADAPTER"
        return
    raise AssertionError("重复注册必须被拒绝")


def test_adapter_registry_loader_failure_isolated():
    """一个平台加载失败，其它平台必须照常工作。"""
    reg = AdapterRegistry()

    def _boom():
        raise ImportError("DrissionPage 未安装")

    failed = reg.register_loader("BROKEN", _boom, source_code="DOUYIN",
                                 module="app.adapters.broken")
    ok = reg.register_loader("GOOD", lambda: (_FakeAdapter(), []), source_code="DOUYIN",
                              module="app.adapters.good")

    assert failed.load_status == LoadStatus.FAILED
    assert failed.error_code == "ADAPTER_LOAD_FAILED"
    assert not reg.has("BROKEN")
    assert reg.get("BROKEN") is None

    assert ok.load_status == LoadStatus.LOADED
    assert reg.has("GOOD")
    assert len(reg.loaded()) == 1
    assert len(reg.failed()) == 1


def test_adapter_registry_invalid_registration_rejected():
    """缺 connector_code 的适配器不允许注册。"""
    reg = AdapterRegistry()

    class _NoCode(_FakeAdapter):
        connector_code = ""

    try:
        reg.register(_NoCode(), [])
    except CollectorError:
        return
    raise AssertionError("缺少 connector_code 必须被拒绝")


# ----------------------------------------------------------------------
# 03-03 Capability Registry
# ----------------------------------------------------------------------
def test_capability_requires_real_function():
    """声明了但函数解析不到 → 不出现在 Observed 清单。"""
    reg = CapabilityRegistry()
    reg.declare("X", _spec("app.does.not.exist.nope"))
    assert reg.list_capabilities("X") == []
    diags = reg.diagnostics("X")
    assert len(diags) == 1
    assert diags[0].reason == "INVALID_IMPLEMENTATION"


def test_capability_rejects_non_callable():
    """函数引用能解析但对象不可调用 → 同样不算兑现。"""
    reg = CapabilityRegistry()
    reg.declare("X", _spec("app.registry.capability.SCHEMA_VERSION"))
    assert reg.list_capabilities("X") == []
    assert reg.diagnostics("X")[0].reason == "INVALID_IMPLEMENTATION"


def test_capability_input_contract_mismatch():
    """inputSchema.required 与函数签名对不上 → 能力不兑现。"""
    reg = CapabilityRegistry()
    spec = CapabilitySpec(
        code=CapabilityCode.COMMENT,
        category=CapabilityCategory.COMMENT,
        name="签名不匹配",
        access_modes=(AccessMode.PUBLIC,),
        implementation_status=ImplementationStatus.UNVERIFIED,
        contract_version="1.0",
        adapter_function="app.registry.bootstrap.load_evidence",
        input_schema={"type": "object", "required": ["nonexistentParam"]},
    )
    reg.declare("X", spec)
    assert reg.list_capabilities("X") == []
    assert reg.diagnostics("X")[0].reason == "INVALID_IMPLEMENTATION"


def test_capability_valid_declaration_is_observed():
    reg = CapabilityRegistry()
    # load_evidence(path=None) —— 入参名是 path，声明里也要对得上
    reg.declare("X", _spec("app.registry.bootstrap.load_evidence", required=("path",)))
    observed = reg.list_capabilities("X")
    assert len(observed) == 1
    assert reg.diagnostics("X") == []


def test_capability_duplicate_function_registration_rejected():
    reg = CapabilityRegistry()
    reg.register_function("demo.fn", lambda: None)
    try:
        reg.register_function("demo.fn", lambda: None)
    except CollectorError as e:
        assert e.code == "DUPLICATE_ADAPTER"
        return
    raise AssertionError("重复注册函数必须被拒绝")


def test_resolve_callable_helper():
    assert resolve_callable("app.registry.bootstrap.load_evidence") is not None
    assert resolve_callable("app.registry.bootstrap.nope") is None
    assert resolve_callable("app.registry.capability.SCHEMA_VERSION") is None


# ----------------------------------------------------------------------
# 03-04 能力接口 + Schema 校验
# ----------------------------------------------------------------------
def test_schema_copy_in_sync():
    """随包 Schema 副本必须与仓库权威副本逐字一致，防止漂移。"""
    from schema_check import BUNDLED_SCHEMA_FILE, REPO_SCHEMA_FILE

    if not os.path.exists(REPO_SCHEMA_FILE):
        return  # 容器内只有随包副本，跳过
    with open(REPO_SCHEMA_FILE, "rb") as a, open(BUNDLED_SCHEMA_FILE, "rb") as b:
        assert a.read() == b.read(), "随包 Schema 与仓库权威副本已漂移"


def test_v1_documents_pass_json_schema():
    """Registry 输出的 v1 文档必须通过 SOURCE-02 的 JSON Schema。"""
    schema = load_schema()
    registry = build_registry()
    docs = registry.v1_documents()
    assert docs, "至少应有一个平台文档"
    for doc in docs:
        validate(doc, schema)


def test_v1_document_shape():
    registry = build_registry()
    doc = next(d for d in registry.v1_documents() if d["connectorCode"] == "DOUYIN_PUBLIC")
    assert doc["schemaVersion"] == SCHEMA_VERSION
    assert doc["sourceCode"] == "DOUYIN"
    assert doc["connectorVersion"] == "1.0.0"
    codes = {c["code"] for c in doc["capabilities"]}
    assert codes == {
        CapabilityCode.COMMENT,
        CapabilityCode.COMMENT_REPLY,
        CapabilityCode.CONTENT_DETAIL,
        CapabilityCode.KEYWORD_SEARCH,
    }
    for cap in doc["capabilities"]:
        assert cap["adapterFunction"], "能力必须绑定真实函数引用"
        assert cap["implementationStatus"] in ImplementationStatus.ALL
        assert cap["contractVersion"]
        assert cap["accessModes"]


def test_unverified_capability_never_available():
    """UNVERIFIED 的能力不得被当成可用。"""
    registry = build_registry()
    doc = next(d for d in registry.v1_documents() if d["connectorCode"] == "DOUYIN_PUBLIC")
    search = next(c for c in doc["capabilities"] if c["code"] == CapabilityCode.KEYWORD_SEARCH)
    assert search["implementationStatus"] == ImplementationStatus.UNVERIFIED
    # 旧字段口径：不可用的能力在布尔位上必须是 false
    douyin = next(i for i in registry.legacy_items() if i["sourceCode"] == "DOUYIN")
    assert douyin["keywordSearch"] is False


def test_verified_capability_promoted_by_evidence():
    """有真实运行证据的能力被提升为 VERIFIED；没有的保持 UNVERIFIED。"""
    registry = build_registry()
    doc = next(d for d in registry.v1_documents() if d["connectorCode"] == "DOUYIN_PUBLIC")
    by_code = {c["code"]: c for c in doc["capabilities"]}
    assert by_code["COMMENT"]["implementationStatus"] == ImplementationStatus.VERIFIED
    assert by_code["COMMENT"].get("verifiedAt")
    assert by_code["COMMENT_REPLY"]["implementationStatus"] == ImplementationStatus.VERIFIED
    assert by_code["CONTENT_DETAIL"]["implementationStatus"] == ImplementationStatus.VERIFIED
    # 关键词搜索没有验证器 → 永远 UNVERIFIED
    assert by_code["KEYWORD_SEARCH"]["implementationStatus"] == ImplementationStatus.UNVERIFIED
    assert "verifiedAt" not in by_code["KEYWORD_SEARCH"]


def test_evidence_promotion_requires_real_evidence():
    """没有证据文件时，能力一律 UNVERIFIED。"""
    registry = build_registry(evidence_path="/nonexistent/evidence.json")
    doc = next(d for d in registry.v1_documents() if d["connectorCode"] == "DOUYIN_PUBLIC")
    for cap in doc["capabilities"]:
        assert cap["implementationStatus"] == ImplementationStatus.UNVERIFIED
        assert "verifiedAt" not in cap


def test_legacy_items_keep_old_shape():
    """旧端点的 items 结构与语义必须保持不变。"""
    registry = build_registry()
    items = registry.legacy_items()
    assert items
    for item in items:
        assert set(item.keys()) == {"sourceCode", "comments", "keywordSearch", "status", "note"}
        assert isinstance(item["comments"], bool)
        assert isinstance(item["keywordSearch"], bool)
        assert isinstance(item["status"], str)
    stub = next(i for i in items if i["sourceCode"] == "STUB")
    assert stub["comments"] is False
    assert stub["keywordSearch"] is False
    assert stub["status"] == "PENDING"


def test_load_evidence_handles_missing_and_broken_file(tmp_path=None):
    assert load_evidence("/nonexistent/x.json") == {}
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        f.write("{ this is not json")
        path = f.name
    assert load_evidence(path) == {}


# ----------------------------------------------------------------------
# 03-05 错误分类
# ----------------------------------------------------------------------
def test_error_codes_map_to_seven_failure_types():
    from app.registry.errors import TECH_ERROR_SPECS

    for code, spec in TECH_ERROR_SPECS.items():
        assert spec.failure_type in FAILURE_TYPES, code
        assert isinstance(spec.retryable, bool)


def test_retryable_derives_from_failure_type():
    """重试与否由 failure_type 决定，不由文案决定。"""
    assert is_retryable("RATE_LIMITED") is True
    assert is_retryable("CONNECT_TIMEOUT") is True
    assert is_retryable("CREDENTIAL_MISSING") is False
    assert is_retryable("VIDEO_MISSING") is False


def test_classify_exception_is_stable():
    assert classify_exception(TimeoutError()).code == "CONNECT_TIMEOUT"
    assert classify_exception(ConnectionError()).code == "UPSTREAM_UNREACHABLE"
    assert classify_exception(ValueError("boom")).code == "PARSE_FAILED"
    assert classify_exception(RuntimeError("boom")).code == "ADAPTER_ERROR"
    assert classify_exception(CollectorError("RATE_LIMITED")).code == "RATE_LIMITED"


def test_unknown_error_code_falls_back():
    from app.registry.errors import technical_error

    assert technical_error("NO_SUCH_CODE").code == "ADAPTER_ERROR"


# ----------------------------------------------------------------------
# 诊断
# ----------------------------------------------------------------------
def test_diagnostics_explain_unresolved_function():
    registry = CollectionRegistry()
    reg = registry.capabilities
    reg.declare("X", _spec("app.does.not.exist.nope"))
    diags = registry.diagnostics()
    assert any(d["reason"] == "INVALID_IMPLEMENTATION" for d in diags)


if __name__ == "__main__":
    failed = 0
    names = [n for n in sorted(globals()) if n.startswith("test_") and callable(globals()[n])]
    for name in names:
        try:
            globals()[name]()
            print(f"  PASS  {name}")
        except Exception as e:
            failed += 1
            print(f"  FAIL  {name}: {type(e).__name__}: {e}")
    print(f"\n{len(names) - failed}/{len(names)} passed")
    sys.exit(1 if failed else 0)
