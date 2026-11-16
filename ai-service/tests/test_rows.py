"""清洗行过滤规则（空内容回复的保留条件）。"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from app.rows import keep_row  # noqa: E402


def row(nickname="某人", content="正常内容", cid="7703837650353177401"):
    return {"昵称": nickname, "内容": content, "CID": cid}


def test_normal_row_kept():
    assert keep_row(row()) is True


def test_empty_content_with_cid_kept():
    """只有表情/图片的楼中楼回复：文本为空但带 cid —— 必须保留。

    丢弃它会让它永远进不了库，采集侧每次重抓，去重收敛不到 0。
    """
    assert keep_row(row(content="")) is True
    assert keep_row(row(content="   ")) is True


def test_empty_content_without_cid_dropped():
    assert keep_row(row(content="", cid="")) is False


def test_missing_nickname_dropped():
    assert keep_row(row(nickname="")) is False
    assert keep_row(row(nickname="  ", content="")) is False


def test_none_values_are_safe():
    assert keep_row({"昵称": None, "内容": None, "CID": None}) is False
    assert keep_row({"昵称": "某人", "内容": None, "CID": "c1"}) is True
