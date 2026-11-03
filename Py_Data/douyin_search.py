"""关键词搜索 → 视频发现。

输出协议（供 Java DiscoveryService 解析，每行一条）：
    VIDEO|<aweme_id>|<title>|<author>|<likes>|<url>

实现：
1. 优先监听搜索接口响应（结构稳定，字段完整）
2. 接口未捕获时回退解析 DOM 卡片链接
3. 全程只读公开搜索结果，不绕过任何访问控制
"""
import os
import re
import sys
import time
from urllib.parse import quote

from DrissionPage import ChromiumPage

from config_browser import build_options

if sys.stdout.encoding and sys.stdout.encoding.upper() != 'UTF-8':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

SEARCH_APIS = [
    "aweme/v1/web/general/search/single",
    "aweme/v1/web/search/item",
]


def clean(text, limit=120):
    if not text:
        return ""
    return re.sub(r'[\r\n\t|]+', ' ', str(text)).strip()[:limit]


def emit(aweme_id, title, author, likes, url=""):
    print(f"VIDEO|{aweme_id}|{clean(title)}|{clean(author, 60)}|{int(likes or 0)}|{url}")


def extract_from_response(body, seen):
    """从搜索接口响应里提取视频条目。"""
    count = 0
    data = body.get("data") if isinstance(body, dict) else None
    if not isinstance(data, list):
        return 0
    for block in data:
        aweme = block.get("aweme_info") or block.get("aweme") or {}
        if not aweme:
            continue
        aweme_id = str(aweme.get("aweme_id") or "")
        if not aweme_id.isdigit() or aweme_id in seen:
            continue
        seen.add(aweme_id)
        stats = aweme.get("statistics") or {}
        author = (aweme.get("author") or {}).get("nickname", "")
        emit(aweme_id, aweme.get("desc", ""), author, stats.get("digg_count", 0),
             f"https://www.douyin.com/video/{aweme_id}")
        count += 1
    return count


def fallback_dom(dp, seen):
    """接口未捕获时，从 DOM 卡片提取视频链接。"""
    js = """
    var out = [];
    document.querySelectorAll('a[href*="/video/"]').forEach(function(a){
        var m = a.href.match(/\\/video\\/(\\d{6,})/);
        if (m) out.push({id: m[1], text: (a.innerText||'').slice(0,120)});
    });
    return out;
    """
    try:
        items = dp.run_js(js) or []
    except Exception:
        return 0
    count = 0
    for it in items:
        aweme_id = str(it.get("id") or "")
        if not aweme_id.isdigit() or aweme_id in seen:
            continue
        seen.add(aweme_id)
        emit(aweme_id, it.get("text", ""), "", 0, f"https://www.douyin.com/video/{aweme_id}")
        count += 1
    return count


def main():
    if len(sys.argv) < 2:
        print("用法: python douyin_search.py <关键词> [上限]")
        return 2
    keyword = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else 50

    url = f"https://www.douyin.com/search/{quote(keyword)}?type=video"
    dp = None
    seen = set()
    try:
        dp = ChromiumPage(build_options())
        for api in SEARCH_APIS:
            dp.listen.start(api)
        dp.get(url)
        print(f"[search] 已打开搜索页: {keyword}", file=sys.stderr)
        time.sleep(6)

        empty_rounds = 0
        for page in range(8):
            if len(seen) >= limit:
                break
            got = 0
            # 尽量吃掉当前缓冲区的所有响应
            for _ in range(3):
                resp = dp.listen.wait(timeout=5)
                if not resp:
                    break
                try:
                    got += extract_from_response(resp.response.body, seen)
                except Exception as e:
                    print(f"[search] 解析响应失败: {e}", file=sys.stderr)
            if got == 0:
                got = fallback_dom(dp, seen)
            if got == 0:
                empty_rounds += 1
                if empty_rounds >= 2:
                    break
            else:
                empty_rounds = 0
            # 滚动加载下一页
            dp.run_js("window.scrollBy(0, window.innerHeight * 2);")
            time.sleep(2.5)

        print(f"[search] 共发现 {len(seen)} 个视频", file=sys.stderr)
        return 0
    except Exception as e:
        print(f"[search] 失败: {e}", file=sys.stderr)
        return 1
    finally:
        if dp is not None:
            try:
                dp.quit()
            except Exception:
                pass


if __name__ == '__main__':
    sys.exit(main())
