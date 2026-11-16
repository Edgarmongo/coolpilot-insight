"""清洗前的行过滤规则（单独成模块，便于脱离 pymysql 单测）。

规则演进过一次，值得写下来：

* 旧规则：`昵称` 与 `内容` 都非空才保留。
* 问题：平台会返回**只有表情/图片的楼中楼回复**——文本为空但它是真实存在的评论实体。
  被丢弃后它永远进不了库，而采集侧每次都把它当"新增"重新抓一遍，
  导致 U1 的去重永远收敛不到 `collected=0`。
* 新规则：昵称必填；内容为空时**只要有 `CID` 就保留**（证明它是平台实体，
  而不是解析出来的脏行）。完全没有 cid 又没有内容的行仍然丢弃。
"""

from __future__ import annotations

from typing import Any


def keep_row(row: dict[str, Any]) -> bool:
    """该行是否应当进入入库流程。"""
    nickname = (row.get("昵称") or "").strip()
    if not nickname:
        return False
    if (row.get("内容") or "").strip():
        return True
    # 内容为空：只有带 cid 的才留（真实平台实体）
    return bool((row.get("CID") or "").strip())
