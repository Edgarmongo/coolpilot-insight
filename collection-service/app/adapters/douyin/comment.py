"""抖音评论采集器（命令行薄壳）。

**核心逻辑已移入** ``collector.py`` —— 因为 SOURCE-03 要求能力声明必须绑定
真实可调用函数，而原来的脚本在模块顶层就开始采集，import 即触发真实请求。

本文件保留的**唯一职责**是命令行入口，行为与原脚本逐字一致：

    python comment.py <视频ID或链接> [采集上限] [--use-login]

Java 侧 ``ProcessCollectionClient`` 仍以 ``python comment.py <id> <max>``
方式调用，产出的 ``<aweme_id>.csv`` 格式、列名、进度文件均未改变。
"""
import os
import sys

# 解决 Windows 控制台 GBK 编码问题
if sys.stdout.encoding and sys.stdout.encoding.upper() != "UTF-8":
    import io

    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

# 同目录模块：脚本方式运行时 sys.path[0] 即本目录
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from collector import run_cli  # noqa: E402

if __name__ == "__main__":
    sys.exit(run_cli())
