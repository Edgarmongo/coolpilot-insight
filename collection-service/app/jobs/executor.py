"""作业执行体（SOURCE-06）。

**唯一职责**：把作业参数翻译成具体能力的调用，再把结果整理成统一条目。

三条边界：

1. **执行前重新派发一次**。提交时校验过不代表执行时仍然成立——
   排队期间 Adapter 可能被重载、证据可能过期。用提交时的结论去跑，
   等于用一个可能已经失效的事实去动生产数据。
2. **CSV 仍是产出契约**。``write_csv=True``，文件仍是 ``<aweme_id>.csv``
   （13 列 + BOM），Java 侧的落库与清洗链路完全不改。
3. **能力按显式适配表派发**。没有适配表条目 = 明确拒绝，
   不是"随便调一个看看"。
"""

from __future__ import annotations

from typing import Any, Callable

from app.jobs import dispatch as dispatch_mod
from app.jobs.queue import Job, SUCCEEDED, PARTIAL, CANCELLED, FAILED


class CapabilityNotDispatchable(Exception):
    """该能力已注册，但还没有接入 HTTP 作业调度。"""

    code = "CAPABILITY_NOT_DISPATCHABLE"


def _collect_comments(job: Job, fn: Callable[..., Any],
                     save_dir: str) -> tuple[list[dict[str, Any]], bool, str]:
    """``COMMENT`` / ``COMMENT_REPLY`` → ``collect_comments``。"""
    outcome = fn(
        job.external_content_id,
        job.max_items,
        use_login=bool(job.params.get("useLogin")),
        save_dir=save_dir,
        write_csv=True,
        printer=job.log,
    )
    rows = getattr(outcome, "comments", None) or []
    reason = str(getattr(outcome, "finished_reason", "") or "")
    job.finished_reason = reason
    job.pages = int(getattr(outcome, "pages", 0) or 0)
    message = str(getattr(outcome, "message", "") or "")
    if not rows and reason:
        # "0 条"必须带上原因：没抓到数据包 / 平台确实没有 / 预检失败，
        # 三种情况的处置完全不同，笼统的 NO_DATA 等于没说话。
        message = f"{message}（finishedReason={reason}）" if message else f"finishedReason={reason}"
    return ([_comment_item(row) for row in rows],
            bool(getattr(outcome, "ok", True)),
            message)


def _comment_item(row: dict[str, Any]) -> dict[str, Any]:
    """采集行 → 作业条目。

    字段名与 ``GET /v1/jobs/{id}/result`` 既有形状一致；
    ``dedupStrategy`` 如实标注这一条是靠 cid 还是内容指纹去重。
    """
    cid = (row.get("CID") or "").strip()
    return {
        "externalCommentId": cid or None,
        "parentExternalId": (row.get("PARENT_CID") or "").strip() or None,
        "text": row.get("内容") or "",
        "nickname": row.get("昵称") or None,
        "likes": int(row.get("点赞") or 0),
        "ipRegion": row.get("地区") or None,
        "publishedAt": row.get("日期") or None,
        "dedupStrategy": "CID" if cid else "FINGERPRINT",
    }


#: 能力 → 调用适配。**没有兜底项**：不在表里就拒绝。
_ADAPTERS: dict[
    str, Callable[[Job, Callable[..., Any], str], tuple[list[dict[str, Any]], bool, str]]
] = {
    "COMMENT": _collect_comments,
    "COMMENT_REPLY": _collect_comments,
}


def build_runner(registry, save_dir: str) -> Callable[[Job], None]:
    """构造 ``JobQueue`` 需要的 runner。"""

    def run(job: Job) -> None:
        # 执行前重新解析：排队期间 Registry 可能已经变了
        try:
            d = dispatch_mod.resolve(
                registry, job.connector_code, job.capability_code, job.access_mode)
        except dispatch_mod.DispatchError as e:
            job.status = FAILED
            job.error_code = e.code
            job.error_message = e.message
            return

        adapter = _ADAPTERS.get(job.capability_code)
        if adapter is None:
            job.status = FAILED
            job.error_code = CapabilityNotDispatchable.code
            job.error_message = (
                f"能力 {job.capability_code} 尚未接入 HTTP 作业调度"
                f"（已注册，函数 {d.adapter_function} 可用，但没有参数适配）")
            return

        try:
            items, ok, message = adapter(job, d.fn, save_dir)
        except Exception as e:  # 采集器自身的异常：如实记为失败
            job.status = FAILED
            job.error_code = getattr(e, "code", "COLLECT_FAILED")
            job.error_message = str(e)[:500]
            return

        if job.cancel_requested:
            # 运行中被取消：不强行杀 Chrome，但结果一律丢弃，
            # 免得"已取消"的作业还能从 /result 里读出数据。
            job.items = []
            job.collected = 0
            job.total = 0
            job.status = CANCELLED
            return

        job.items = items
        job.collected = len(items)
        job.total = len(items)

        # 0 条要分两种：
        #   · 收到过数据包（pages > 0）但全部被判重复 → 本次真的没有新增，算完成；
        #   · 一个数据包都没收到（pages == 0）→ 可能被风控拦了，算不完整。
        # 判据用"是否真的收到过数据"，而不是猜原因文案。
        if not items and ok and (job.pages or 0) > 0:
            job.status = SUCCEEDED
            job.error_code = None
            job.error_message = None
            return

        if not ok:
            job.status = FAILED
            job.error_code = "COLLECT_INCOMPLETE"
            job.error_message = message or "采集未完成"
        elif items:
            job.status = SUCCEEDED
        else:
            # 0 条既可能是"确实没有评论"也可能是"被平台拦了"。
            # 不把 0 条说成成功——那会让上游以为采完了。
            job.status = PARTIAL
            job.error_code = "NO_DATA"
            # 把采集器给出的原因塞进消息里：只说"没采到"等于把三种情况混成一种。
            job.error_message = f"本次没有采到任何评论（{message or '采集器未给出原因'}）"

    return run
