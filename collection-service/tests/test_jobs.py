"""作业派发 / 排队 / 执行（SOURCE-06）。

这一组测试的重点不是"能跑通"，而是**拒绝得对不对**：
派发是唯一能把"声明过的能力"变成"真的去平台抓数据"的入口，
任何一条 fail-open 都会让 Desired/Effective 变成摆设。
"""

from __future__ import annotations

import threading
import time
import types

import pytest

from app.jobs import dispatch
from app.jobs.executor import build_runner
from app.jobs.queue import (
    ACCEPTED, CANCELLED, FAILED, PARTIAL, RUNNING, SUCCEEDED, Job, JobQueue, QueueFull,
)
from app.registry.capability import (
    AccessMode, CapabilityCategory, CapabilityCode, CapabilitySpec, ImplementationStatus,
)

CONNECTOR = "DOUYIN_PUBLIC"


@pytest.fixture(autouse=True)
def _dedup_from_csv(monkeypatch):
    """执行体测试统一走 CSV 记忆。

    A-API 路径会真的去连 backend，单测里既慢又不稳定；API 客户端本身
    由 tests/test_dedup.py 覆盖，执行体侧的接线由下面两条专门的用例覆盖。
    """
    monkeypatch.setenv("COLLECT_DEDUP_SOURCE", "csv")


# ----------------------------------------------------------------------
# 假注册表：只实现派发用到的三个接口，避免测试被真实装配牵着走
# ----------------------------------------------------------------------
class _Entry:
    def __init__(self, connector_code=CONNECTOR, load_status="LOADED",
                 platform_scoped=True, error_code=None, error_message=None):
        self.connector_code = connector_code
        self.load_status = load_status
        self.platform_scoped = platform_scoped
        self.error_code = error_code
        self.error_message = error_message


class _Adapters:
    def __init__(self, entries):
        self._entries = entries

    def list(self):
        return list(self._entries)


class _Capabilities:
    def __init__(self, functions):
        self._functions = functions

    def resolve(self, fn_name):
        return self._functions.get(fn_name)


class FakeRegistry:
    def __init__(self, entries, specs, functions=None):
        self.adapters = _Adapters(entries)
        self.capabilities = _Capabilities(functions or {})
        self._specs = specs

    def observed_specs(self, connector_code):
        return list(self._specs.get(connector_code, []))


def spec(code=CapabilityCode.COMMENT, status=ImplementationStatus.VERIFIED,
         accepted=True, fn="app.fake.collect", modes=(AccessMode.PUBLIC,)):
    return CapabilitySpec(
        code=code,
        category=CapabilityCategory.COMMENT,
        name="测试能力",
        access_modes=tuple(modes),
        implementation_status=status,
        contract_version="1.0",
        adapter_function=fn,
        evidence={"accepted": accepted} if accepted is not None else None,
    )


def registry_with(specs, functions=None, **entry_kwargs):
    # 注意不能写 `functions or default`：空 dict 是"故意一个函数都不注册"，
    # 会被 falsy 判断悄悄换成默认函数，于是"函数解析不到"这条分支永远测不到。
    if functions is None:
        functions = {"app.fake.collect": lambda *a, **k: None}
    return FakeRegistry([_Entry(**entry_kwargs)], {CONNECTOR: specs}, functions)


# ----------------------------------------------------------------------
# 派发判定
# ----------------------------------------------------------------------
def test_dispatch_ok():
    reg = registry_with([spec()])
    d = dispatch.resolve(reg, CONNECTOR, CapabilityCode.COMMENT, AccessMode.PUBLIC)
    assert d.connector_code == CONNECTOR
    assert d.adapter_function == "app.fake.collect"
    assert callable(d.fn)


def test_unknown_connector_rejected():
    reg = FakeRegistry([], {})
    with pytest.raises(dispatch.DispatchError) as ei:
        dispatch.resolve(reg, "NOPE", CapabilityCode.COMMENT, AccessMode.PUBLIC)
    assert ei.value.code == dispatch.CONNECTOR_NOT_FOUND
    assert ei.value.http_status == 404


def test_adapter_not_loaded_rejected():
    reg = registry_with([spec()], load_status="FAILED")
    with pytest.raises(dispatch.DispatchError) as ei:
        dispatch.resolve(reg, CONNECTOR, CapabilityCode.COMMENT, AccessMode.PUBLIC)
    assert ei.value.code == dispatch.ADAPTER_NOT_LOADED


def test_stub_rejected_by_default(monkeypatch):
    monkeypatch.delenv("COLLECTION_ALLOW_STUB", raising=False)
    reg = registry_with([spec()], platform_scoped=False)
    with pytest.raises(dispatch.DispatchError) as ei:
        dispatch.resolve(reg, CONNECTOR, CapabilityCode.COMMENT, AccessMode.PUBLIC)
    assert ei.value.code == dispatch.STUB_NOT_ALLOWED
    assert ei.value.http_status == 403


def test_stub_allowed_only_when_env_set(monkeypatch):
    """显式开关只解除"替身"这一层，不会跳过能力/证据校验。"""
    monkeypatch.setenv("COLLECTION_ALLOW_STUB", "1")
    reg = registry_with([], platform_scoped=False)   # 替身没有任何能力
    with pytest.raises(dispatch.DispatchError) as ei:
        dispatch.resolve(reg, CONNECTOR, CapabilityCode.COMMENT, AccessMode.PUBLIC)
    assert ei.value.code == dispatch.CAPABILITY_NOT_REGISTERED


def test_capability_not_registered():
    reg = registry_with([spec(code=CapabilityCode.CONTENT_DETAIL)])
    with pytest.raises(dispatch.DispatchError) as ei:
        dispatch.resolve(reg, CONNECTOR, CapabilityCode.COMMENT, AccessMode.PUBLIC)
    assert ei.value.code == dispatch.CAPABILITY_NOT_REGISTERED


def test_function_unresolved():
    reg = registry_with([spec()], functions={})
    with pytest.raises(dispatch.DispatchError) as ei:
        dispatch.resolve(reg, CONNECTOR, CapabilityCode.COMMENT, AccessMode.PUBLIC)
    assert ei.value.code == dispatch.FUNCTION_UNRESOLVED


def test_access_mode_unsupported():
    reg = registry_with([spec(modes=(AccessMode.PUBLIC,))])
    with pytest.raises(dispatch.DispatchError) as ei:
        dispatch.resolve(reg, CONNECTOR, CapabilityCode.COMMENT, AccessMode.AUTHENTICATED)
    assert ei.value.code == dispatch.ACCESS_MODE_UNSUPPORTED


@pytest.mark.parametrize("status", [
    ImplementationStatus.UNVERIFIED,
    ImplementationStatus.IMPLEMENTED,   # 本地测试通过 ≠ 平台跑通
    ImplementationStatus.DEGRADED,
])
def test_non_verified_status_rejected(status):
    reg = registry_with([spec(status=status)])
    with pytest.raises(dispatch.DispatchError) as ei:
        dispatch.resolve(reg, CONNECTOR, CapabilityCode.COMMENT, AccessMode.PUBLIC)
    assert ei.value.code == dispatch.NOT_EXECUTABLE


def test_evidence_rejected_blocks_even_if_verified():
    reg = registry_with([spec(status=ImplementationStatus.VERIFIED, accepted=False)])
    with pytest.raises(dispatch.DispatchError) as ei:
        dispatch.resolve(reg, CONNECTOR, CapabilityCode.COMMENT, AccessMode.PUBLIC)
    assert ei.value.code == dispatch.NOT_EXECUTABLE
    assert "证据" in ei.value.message


def test_missing_evidence_blocks():
    reg = registry_with([spec(accepted=None)])
    with pytest.raises(dispatch.DispatchError) as ei:
        dispatch.resolve(reg, CONNECTOR, CapabilityCode.COMMENT, AccessMode.PUBLIC)
    assert ei.value.code == dispatch.NOT_EXECUTABLE


def test_missing_codes_rejected():
    reg = registry_with([spec()])
    with pytest.raises(dispatch.DispatchError):
        dispatch.resolve(reg, "", CapabilityCode.COMMENT, AccessMode.PUBLIC)


# ----------------------------------------------------------------------
# 队列
# ----------------------------------------------------------------------
def make_job(job_id="j1"):
    return Job(job_id=job_id, connector_code=CONNECTOR,
               capability_code=CapabilityCode.COMMENT, access_mode=AccessMode.PUBLIC,
               external_content_id="123", max_items=10)


def wait_status(q, job_id, statuses, timeout=3.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = q.get(job_id)
        if job and job.status in statuses:
            return job
        time.sleep(0.01)
    return q.get(job_id)


def test_single_worker_never_runs_two_jobs(monkeypatch):
    """Chrome 单实例：任一时刻 RUNNING 必须 ≤ 1。"""
    running = {"count": 0, "max": 0}
    guard = threading.Lock()

    def runner(job):
        with guard:
            running["count"] += 1
            running["max"] = max(running["max"], running["count"])
        time.sleep(0.05)
        with guard:
            running["count"] -= 1
        job.status = SUCCEEDED

    q = JobQueue(runner)
    for i in range(3):
        q.submit(make_job(f"j{i}"))

    for i in range(3):
        wait_status(q, f"j{i}", {SUCCEEDED})
    assert running["max"] == 1


def test_idempotent_submit():
    q = JobQueue(lambda job: setattr(job, "status", SUCCEEDED))
    first, created1 = q.submit(make_job("same"))
    second, created2 = q.submit(make_job("same"))
    assert created1 is True
    assert created2 is False
    assert first is second


def test_queue_position_increases():
    gate = threading.Event()

    def runner(job):
        gate.wait(2)
        job.status = SUCCEEDED

    q = JobQueue(runner)
    q.submit(make_job("a"))
    q.submit(make_job("b"))
    q.submit(make_job("c"))
    # 第一个在跑，b/c 排队；位置必须能区分先后
    time.sleep(0.05)
    positions = [q.queue_position(q.get(j)) for j in ("b", "c")]
    assert positions == sorted(positions)
    assert positions[-1] >= 1
    gate.set()


def test_cancel_queued_job_is_immediate():
    gate = threading.Event()
    executed = []

    def runner(job):
        if job.job_id == "first":
            gate.wait(2)
        executed.append(job.job_id)
        job.status = SUCCEEDED

    q = JobQueue(runner)
    q.submit(make_job("first"))
    q.submit(make_job("second"))
    time.sleep(0.05)

    cancelled = q.cancel("second")
    assert cancelled.status == CANCELLED

    gate.set()
    wait_status(q, "second", {CANCELLED})
    time.sleep(0.1)
    assert "second" not in executed, "已取消的排队作业不得被执行"
    assert q.get("second").items == []


def test_cancel_finished_job_does_not_rewrite_status():
    q = JobQueue(lambda job: setattr(job, "status", SUCCEEDED))
    q.submit(make_job("done"))
    wait_status(q, "done", {SUCCEEDED})
    assert q.cancel("done").status == SUCCEEDED


def test_queue_full_rejected():
    gate = threading.Event()
    q = JobQueue(lambda job: gate.wait(2), max_pending=1)
    q.submit(make_job("a"))
    with pytest.raises(QueueFull):
        q.submit(make_job("b"))
    gate.set()


# ----------------------------------------------------------------------
# 执行体
# ----------------------------------------------------------------------
def make_outcome(rows, ok=True, message="", finished_reason="", pages=1):
    return types.SimpleNamespace(comments=rows, ok=ok, message=message,
                                 finished_reason=finished_reason, pages=pages)


ROW_WITH_CID = {"CID": "c1", "PARENT_CID": "", "内容": "hi", "昵称": "n",
                "点赞": "3", "地区": "上海", "日期": "2026-01-01"}
ROW_NO_CID = {"CID": "", "PARENT_CID": "", "内容": "hi", "昵称": "n",
              "点赞": 0, "地区": "", "日期": ""}


def run_once(rows, monkeypatch, job=None, ok=True, message="", finished_reason="", pages=1):
    calls = {}

    def fn(video_id, max_comments, **kwargs):
        calls["video_id"] = video_id
        calls["max_comments"] = max_comments
        calls["kwargs"] = kwargs
        return make_outcome(rows, ok=ok, message=message,
                            finished_reason=finished_reason, pages=pages)

    reg = registry_with([spec()], functions={"app.fake.collect": fn})
    job = job or make_job()
    build_runner(reg, "/tmp")(job)
    return job, calls


def test_executor_maps_rows(monkeypatch):
    job, calls = run_once([ROW_WITH_CID, ROW_NO_CID], monkeypatch)

    assert job.status == SUCCEEDED
    assert calls["video_id"] == "123"
    assert calls["max_comments"] == 10
    # CSV 仍是产出契约：必须让采集器落盘
    assert calls["kwargs"]["write_csv"] is True

    first, second = job.items
    assert first["externalCommentId"] == "c1"
    assert first["dedupStrategy"] == "CID"
    assert first["likes"] == 3
    # 没有 cid 的条目如实标注靠指纹去重，而不是假装有 cid
    assert second["externalCommentId"] is None
    assert second["dedupStrategy"] == "FINGERPRINT"


def test_executor_zero_items_is_partial_not_success(monkeypatch):
    """0 条既可能是无评论，也可能是被风控拦了——不能谎称成功。"""
    job, _ = run_once([], monkeypatch, pages=0)
    assert job.status == PARTIAL
    assert job.error_code == "NO_DATA"


def test_executor_incomplete_marks_failed(monkeypatch):
    job, _ = run_once([ROW_WITH_CID], monkeypatch, ok=False, message="浏览器启动失败")
    assert job.status == FAILED
    assert job.error_code == "COLLECT_INCOMPLETE"


def test_executor_rejects_unadapted_capability(monkeypatch):
    """CONTENT_DETAIL 已注册但没接作业适配 → 明确拒绝，不是随便调一个函数。"""
    reg = registry_with([spec(code=CapabilityCode.CONTENT_DETAIL)],
                        functions={"app.fake.collect": lambda *a, **k: None})
    job = Job(job_id="x", connector_code=CONNECTOR,
              capability_code=CapabilityCode.CONTENT_DETAIL, access_mode=AccessMode.PUBLIC,
              external_content_id="123", max_items=1)
    build_runner(reg, "/tmp")(job)

    assert job.status == FAILED
    assert job.error_code == "CAPABILITY_NOT_DISPATCHABLE"


def test_executor_rechecks_registry_before_running(monkeypatch):
    """排队期间证据失效 → 执行时必须失败，而不是按提交时的结论照跑。"""
    reg = registry_with([spec(status=ImplementationStatus.UNVERIFIED)])
    job = make_job()
    build_runner(reg, "/tmp")(job)
    assert job.status == FAILED
    assert job.error_code == dispatch.NOT_EXECUTABLE


def test_executor_discards_result_when_cancelled(monkeypatch):
    job = make_job()
    job.cancel_requested = True
    job, _ = run_once([ROW_WITH_CID], monkeypatch, job=job)

    assert job.status == CANCELLED
    assert job.items == [], "已取消的作业不得留下结果"
    assert job.collected == 0


def test_zero_items_keeps_finished_reason(monkeypatch):
    """"0 条"必须带原因：没抓到数据包 / 平台确实没有，处置完全不同。"""
    job, _ = run_once([], monkeypatch, finished_reason="NO_RESPONSE", pages=0)
    assert job.status == PARTIAL
    assert "NO_RESPONSE" in (job.error_message or "")


def test_printer_output_is_kept_in_job_logs(monkeypatch):
    """作业模式没有控制台，采集器的过程输出必须留在作业里，否则 0 条无从排查。"""
    def fn(video_id, max_comments, **kwargs):
        kwargs["printer"]("( ‵▽′)ψ 浏览器启动成功")
        kwargs["printer"]("w(ﾟДﾟ)w 未捕获到评论数据包，结束采集")
        return make_outcome([ROW_WITH_CID])

    reg = registry_with([spec()], functions={"app.fake.collect": fn})
    job = make_job()
    build_runner(reg, "/tmp")(job)

    assert any("浏览器启动成功" in line for line in job.logs)
    assert any("未捕获到评论数据包" in line for line in job.logs)
    assert "logTail" in job.to_status(0)


def test_log_sanitizes_control_chars():
    """采集器日志带前导换行与回车，原样返回会让调用方的 JSON 解析直接报错。"""
    import json

    job = make_job()
    job.log("\n ( •̀ ω •́ )y 正在采集第 1 页\r")
    job.log("a\x00b")

    assert all("\n" not in line and "\r" not in line for line in job.logs)
    assert "\x00" not in "".join(job.logs)
    json.dumps(job.to_status(0))          # 必须能序列化，否则状态接口 500


def test_zero_items_after_receiving_pages_is_success(monkeypatch):
    """收到过数据包但全部判重 → 本次没有新增，这是完成不是失败。

    实测触发：同一视频第二次采集，19 个 cid 全部跳过，
    采集器在最后一页超时，旧逻辑据此判 PARTIAL/NO_DATA，看起来像采坏了。
    """
    job, _ = run_once([], monkeypatch, finished_reason="NO_RESPONSE", pages=3)
    assert job.status == SUCCEEDED
    assert job.error_code is None
    assert job.pages == 3


def test_zero_items_without_any_page_stays_partial(monkeypatch):
    """一个数据包都没收到 → 连不上或被拦，不能算成功。"""
    job, _ = run_once([], monkeypatch, finished_reason="NO_RESPONSE", pages=0)
    assert job.status == PARTIAL
    assert "NO_RESPONSE" in (job.error_message or "")


# ----------------------------------------------------------------------
# 去重记忆接线（U1 A-API）
# ----------------------------------------------------------------------
class _FakeSeen:
    def __init__(self, cids=(), fps=()):
        self.cids = set(cids)
        self.fingerprints = set(fps)
        self.total = len(self.cids) + len(self.fingerprints)
        self.pages = 1
        self.truncated = False


def test_executor_passes_api_seen_sets_to_collector(monkeypatch):
    """接口给的历史必须真的传到采集函数，否则接线等于没接。"""
    from app.jobs import dedup as dedup_mod

    monkeypatch.setenv("COLLECT_DEDUP_SOURCE", "api")
    monkeypatch.setattr(dedup_mod, "fetch_seen",
                        lambda _id: _FakeSeen(cids=["c1", "c2"], fps=["f" * 40]))

    captured = {}

    def fn(video_id, max_comments, **kwargs):
        captured.update(kwargs)
        return make_outcome([ROW_WITH_CID])

    reg = registry_with([spec()], functions={"app.fake.collect": fn})
    job = make_job()
    build_runner(reg, "/tmp")(job)

    assert job.dedup_source == "api"
    assert job.dedup_seen == 3
    assert captured["seen_cids"] == {"c1", "c2"}
    assert captured["seen_fps"] == {"f" * 40}


def test_executor_fails_closed_when_dedup_unavailable(monkeypatch):
    """拿不到历史 → 作业失败，**不允许**退化成"没有历史"继续盲采。"""
    from app.jobs import dedup as dedup_mod

    def boom(_id):
        raise dedup_mod.DedupUnavailable("去重接口不可达: refused")

    monkeypatch.setenv("COLLECT_DEDUP_SOURCE", "api")
    monkeypatch.setattr(dedup_mod, "fetch_seen", boom)

    called = {"n": 0}

    def fn(*a, **k):
        called["n"] += 1
        return make_outcome([])

    reg = registry_with([spec()], functions={"app.fake.collect": fn})
    job = make_job()
    build_runner(reg, "/tmp")(job)

    assert job.status == FAILED
    assert job.error_code == "DEDUP_SOURCE_UNAVAILABLE"
    assert called["n"] == 0, "拿不到去重记忆时不得启动采集"
