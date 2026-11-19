"""异常信息脱敏（SOURCE-04-FOLLOWUP-FIX-02）。

清洗脚本的输出会沿着这条链路走：

    clean.py stdout → ProcessRunner 捕获 → CommandResult.output()
      → CollectServiceImpl.error → collection_task_item.error_message
                                → collection_task_log.message
      → HTTP 响应 / 管理页面

也就是说，**脚本往 stdout 打的每一行，最终都可能变成对外可见的错误信息**。
而 pymysql 的异常消息里经常带值：

    (1062, "Duplicate entry '7703837650353177401' for key 'uk_platform_cid'")
    (1406, "Data too long for column 'content' at row 1")

cid、昵称、正文片段都可能出现在里面。所以异常**只输出类型与数据库错误码**，
不输出 message、不输出 args 的可读部分。

需要完整堆栈时走受控诊断日志（单独开启、单独保管），不要走 stdout。
"""

from __future__ import annotations

from typing import Any

#: 允许出现在输出里的"结构化"信息只有这两类：异常类名、数据库错误码。
#: 其余一律不放行。
_SAFE_CODE_TYPES = (int,)


def error_summary(exc: BaseException | Any) -> str:
    """把异常压成 `类名(错误码)` 形式，**不含任何消息文本**。

    >>> error_summary(RuntimeError("boom"))
    'RuntimeError'
    """
    if exc is None:
        return "UnknownError"

    name = type(exc).__name__

    args = getattr(exc, "args", ()) or ()
    code = args[0] if args and isinstance(args[0], _SAFE_CODE_TYPES) else None
    if code is None:
        return name
    return f"{name}({code})"
