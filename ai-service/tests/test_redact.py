"""清洗异常脱敏（SOURCE-04-FOLLOWUP-FIX-02 · P2）。

要证明的是一件事：**异常消息里带的值，不会出现在清洗脚本的输出里**。
pymysql 的消息常含 cid 与字段值，而 stdout 会被 ProcessRunner 捕获、
最终写进 error_message / task_log / 页面。
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from app.redact import error_summary  # noqa: E402


class FakeDbError(Exception):
    """模拟 pymysql 的 (errcode, message) 结构。"""


CID = "7703837650353177401"
NICK = "绝望的大鼻嘎"
TEXT = "把这视频给俱乐部发过去"


def test_summary_keeps_only_type_and_code():
    e = FakeDbError(1062, f"Duplicate entry '{CID}' for key 'uk_platform_cid'")
    out = error_summary(e)

    assert out == "FakeDbError(1062)"
    assert CID not in out


def test_summary_does_not_leak_cid_or_content():
    e = FakeDbError(1406, f"Data too long for column 'content': {NICK}|{TEXT}|{CID}")
    out = error_summary(e)

    assert CID not in out
    assert NICK not in out
    assert TEXT not in out


def test_summary_without_numeric_code():
    e = RuntimeError(f"{NICK} 点赞失败 cid={CID}")
    out = error_summary(e)

    assert out == "RuntimeError"
    assert CID not in out and NICK not in out


def test_summary_handles_none():
    assert error_summary(None) == "UnknownError"
    assert error_summary(Exception()) == "Exception"
