"""能力真实验证工具。

**这个工具是"VERIFIED"的唯一来源。** 代码里的能力声明一律从 ``UNVERIFIED``
起步；只有本工具在真实平台上跑通、并把证据写进
``app/registry/verification_evidence.json`` 之后，能力才会被提升为 ``VERIFIED``。

用法（容器内）：

    cd /app/collection-service && /opt/venv/bin/python tools/verify_capability.py \
        --video 7703721523349239951 --max 30 --out /tmp/evidence.json

要点：

* 每次只打**一个**视频的公开接口，不做批量、不做轮询，避免变成对平台的探测；
* 只有存在验证器的能力才能被提升。没有验证器的能力（例如关键词搜索——
  其风控行为未知）永远停在 ``UNVERIFIED``；
* 失败**不写证据**：没跑通就是没跑通，不留"半成功"记录。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from typing import Any, Callable

# 允许 `python tools/verify_capability.py` 直接运行
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.registry.bootstrap import EVIDENCE_FILE, load_evidence  # noqa: E402
from app.registry.capability import ImplementationStatus  # noqa: E402


def _now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


# ----------------------------------------------------------------------
# 验证器
# ----------------------------------------------------------------------
def _verify_comment(video_id: str, max_comments: int) -> tuple[bool, dict[str, Any], str]:
    """评论采集：验证能真正拿到评论且带 cid。"""
    from app.adapters.douyin.collector import collect_comments

    outcome = collect_comments(video_id, max_comments, use_login=False, write_csv=False)
    rows = outcome.comments
    with_cid = [r for r in rows if r.get("CID")]
    observed = {
        "target": video_id,
        "collected": len(rows),
        "cidCoverage": f"{len(with_cid)}/{len(rows)}",
        "finishedReason": outcome.finished_reason,
        "pages": outcome.pages,
    }
    ok = bool(rows) and len(with_cid) == len(rows)
    message = "评论采集成功且全部带 cid" if ok else (
        f"未取到有效评论（collected={len(rows)}, cid={len(with_cid)}, "
        f"reason={outcome.finished_reason}）"
    )
    return ok, observed, message


def _verify_reply(video_id: str, max_comments: int) -> tuple[bool, dict[str, Any], str]:
    """楼中楼：验证接口的 reply_comment 字段能带回嵌套回复。"""
    from app.adapters.douyin.collector import collect_comments

    outcome = collect_comments(video_id, max_comments, use_login=False, write_csv=False)
    replies = [r for r in outcome.comments if str(r.get("是否回复")) == "1"]
    observed = {
        "target": video_id,
        "collected": len(outcome.comments),
        "replies": len(replies),
        "finishedReason": outcome.finished_reason,
    }
    ok = bool(replies)
    message = (f"取到 {len(replies)} 条楼中楼回复" if ok
               else "未取到嵌套回复（该视频可能没有回复，或接口未返回 reply_comment）")
    return ok, observed, message


def _verify_detail(video_id: str, max_comments: int) -> tuple[bool, dict[str, Any], str]:
    """内容详情：验证能取到标题与作者。"""
    from app.adapters.douyin.collector import collect_content_detail

    meta = collect_content_detail(video_id) or {}
    observed = {
        "target": video_id,
        "hasDesc": bool(meta.get("desc")),
        "hasAuthor": bool(meta.get("author")),
        "title": (meta.get("title") or "")[:80],
    }
    ok = bool(meta.get("desc"))
    message = "取到视频详情" if ok else "未取到视频详情"
    return ok, observed, message


#: ``(connectorCode, capabilityCode)`` → 验证器。
#: **没有验证器的能力无法被提升为 VERIFIED**——这是刻意的：
#: 关键词搜索的风控行为未知，在评估清楚之前只能停在 UNVERIFIED。
VERIFIERS: dict[tuple[str, str], Callable[[str, int], tuple[bool, dict[str, Any], str]]] = {
    ("DOUYIN_PUBLIC", "COMMENT"): _verify_comment,
    ("DOUYIN_PUBLIC", "COMMENT_REPLY"): _verify_reply,
    ("DOUYIN_PUBLIC", "CONTENT_DETAIL"): _verify_detail,
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="能力真实验证工具")
    parser.add_argument("--video", required=True, help="用于验证的视频 ID 或链接")
    parser.add_argument("--max", type=int, default=30, help="单次采集上限")
    parser.add_argument("--connector", default="DOUYIN_PUBLIC")
    parser.add_argument("--out", default=EVIDENCE_FILE, help="证据文件输出路径")
    args = parser.parse_args(argv)

    evidence = load_evidence(args.out)
    records = evidence.setdefault("records", {})
    conn = records.setdefault(args.connector, {})

    targets = [k for k in VERIFIERS if k[0] == args.connector]
    if not targets:
        print(f"[verify] {args.connector} 没有可执行的验证器，能力保持 UNVERIFIED")
        return 2

    exit_code = 0
    for connector_code, capability_code in targets:
        verifier = VERIFIERS[(connector_code, capability_code)]
        print(f"[verify] 验证 {connector_code}/{capability_code} …")
        try:
            ok, observed, message = verifier(args.video, args.max)
        except Exception as e:  # noqa: BLE001 —— 验证失败不是脚本失败
            print(f"[verify]   异常：{type(e).__name__}: {e}")
            ok, observed, message = False, {"error": f"{type(e).__name__}: {e}"}, "验证过程异常"

        print(f"[verify]   结果：{'通过' if ok else '未通过'} —— {message}")
        if ok:
            conn[capability_code] = {
                "implementationStatus": ImplementationStatus.VERIFIED,
                "verifiedAt": _now(),
                "method": "REAL_PLATFORM_RUN",
                "observed": observed,
                "message": message,
            }
        else:
            # 失败不写证据：没跑通就是没跑通
            exit_code = 1

    evidence["schemaVersion"] = "1.0"
    evidence["updatedAt"] = _now()
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(evidence, f, ensure_ascii=False, indent=2)
    print(f"[verify] 证据已写入 {args.out}")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
