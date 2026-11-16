"""抖音评论采集核心逻辑（可被 import 的真实实现）。

**为什么要有这个文件**：SOURCE-03 要求能力必须绑定"真实可调用函数"。
原来的 ``comment.py`` 是**脚本**——模块顶层直接读 ``sys.argv`` 并启动浏览器，
import 它就等于发起一次真实采集。所以核心逻辑被抽到这里：

* ``collect_comments()`` / ``collect_content_detail()`` —— 真实可调用函数，
  能力声明的 ``adapterFunction`` 指向它们；
* ``run_cli()`` —— 保留原脚本的 CLI 契约（参数、CSV 列、进度文件、去重规则），
  ``comment.py`` 变成调用它的薄壳，**Java 侧的子进程调用方式完全不变**。

不依赖 ``app`` 包，因此既能以包模块导入（Registry 用），也能以脚本方式运行。
"""

from __future__ import annotations

import csv
import hashlib
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable
from urllib.parse import parse_qs, urlparse

try:  # 包内导入（Registry 走这条）
    from .browser import build_options
except ImportError:  # 脚本模式（comment.py 走这条）
    from browser import build_options  # type: ignore[no-redef]

# ====================== 配置 ======================
SAVE_DIR = os.environ.get("PY_DATAS_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "datas"
)
LISTEN_API = "aweme/v1/web/comment/list"
CSV_FIELDS = ["昵称", "抖音号", "UID", "SEC_UID", "地区", "日期",
              "内容", "点赞", "CID", "PARENT_CID", "ROOT_CID", "回复数", "是否回复"]
# ===================================================

CONTAINER_JS = 'document.querySelector(".parent-route-container") || document.querySelector("#douyin-right-container>div:nth-child(2)")'
SCROLL_CONTAINER_JS = """
var c = document.querySelector(".parent-route-container")
      || document.querySelector("#douyin-right-container>div:nth-child(2)");
return c ? {found:true, cls:(c.className||'').slice(0,60), sh:c.scrollHeight, ch:c.clientHeight} : {found:false};
"""

# 结束原因（稳定值，便于上层统计；不参与机器判定）
FINISH_MAX_LIMIT = "MAX_LIMIT"
FINISH_NO_MORE = "NO_MORE_DATA"
FINISH_NO_RESPONSE = "NO_RESPONSE"
FINISH_PREFLIGHT_FAILED = "PREFLIGHT_FAILED"
FINISH_BROWSER_ERROR = "BROWSER_ERROR"


@dataclass
class CollectOutcome:
    """一次采集的结果。"""

    video_id: str
    comments: list[dict[str, Any]] = field(default_factory=list)
    pages: int = 0
    csv_path: str | None = None
    progress_path: str | None = None
    meta: dict[str, Any] = field(default_factory=dict)
    finished_reason: str = FINISH_NO_MORE
    ok: bool = True
    message: str = ""


# ----------------------------------------------------------------------
# 基础工具
# ----------------------------------------------------------------------
def extract_video_id(raw: str | None) -> str | None:
    """从各种抖音输入中提取纯数字视频 ID。"""
    if not raw:
        return None
    raw = raw.strip()
    if raw.isdigit():
        return raw
    try:
        query = parse_qs(urlparse(raw).query)
        modal_id = query.get("modal_id", [None])[0]
        if modal_id and modal_id.isdigit():
            return modal_id
    except Exception:
        pass
    m = re.search(r"/(?:video|note|share/video)/(\d+)", raw)
    if m:
        return m.group(1)
    return None


def fingerprint(nickname: str | None, dt: str | None, content: str | None) -> str:
    raw = f"{nickname or ''}|{dt or ''}|{(content or '')[:120]}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:40]


def ensure_header(csv_path: str) -> None:
    if os.path.exists(csv_path) and os.path.getsize(csv_path) > 0:
        return
    with open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
        csv.DictWriter(f, fieldnames=CSV_FIELDS).writeheader()


def append_rows(csv_path: str, rows: list[dict[str, Any]]) -> None:
    """每页追加落盘：中断时已采数据不会丢。"""
    if not rows:
        return
    with open(csv_path, "a", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writerows(rows)


def load_existing_cids(csv_path: str) -> tuple[set[str], set[str]]:
    """读取已有 CSV 的 cid 与内容指纹，用于跨次运行去重。"""
    cids: set[str] = set()
    fingerprints: set[str] = set()
    if not os.path.exists(csv_path):
        return cids, fingerprints
    try:
        with open(csv_path, encoding="utf-8-sig", newline="") as f:
            for row in csv.DictReader(f):
                cid = (row.get("CID") or "").strip()
                if cid:
                    cids.add(cid)
                fingerprints.add(fingerprint(row.get("昵称"), row.get("日期"), row.get("内容")))
    except Exception as e:
        print(f"   ⚠️ 读取历史 CSV 失败（将按新文件处理）: {e}")
    return cids, fingerprints


def save_progress(progress_path: str, page: int, count: int, last_cid: str, done: bool = False) -> None:
    try:
        with open(progress_path, "w", encoding="utf-8") as f:
            json.dump({
                "page": page,
                "collected": count,
                "lastCid": last_cid,
                "finished": done,
                "updatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            }, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def to_row(c: dict[str, Any], parent_cid: str | None = None, is_reply: int = 0) -> dict[str, Any]:
    """把平台返回的评论结构转成 CSV 行。"""
    user = c.get("user") or {}
    dt = datetime.fromtimestamp(c["create_time"]).strftime("%Y-%m-%d %H:%M:%S")
    reply_total = c.get("reply_comment_total") or 0
    if not reply_total:
        nested = c.get("reply_comment") or {}
        if isinstance(nested, dict):
            reply_total = nested.get("reply_comment_total") or 0
    cid = str(c.get("cid") or c.get("comment_id") or "")
    return {
        "昵称": user.get("nickname", ""),
        "抖音号": user.get("unique_id", "") or user.get("short_id", ""),
        "UID": str(user.get("uid", "") or ""),
        "SEC_UID": user.get("sec_uid", "") or "",
        "地区": c.get("ip_label", "未知") or "未知",
        "日期": dt,
        "内容": c.get("text", ""),
        "点赞": c.get("digg_count", 0),
        "CID": cid,
        "PARENT_CID": parent_cid or "",
        "ROOT_CID": c.get("root_comment_id") or (parent_cid or cid),
        "回复数": reply_total,
        "是否回复": is_reply,
    }


# ----------------------------------------------------------------------
# 浏览器交互
# ----------------------------------------------------------------------
def scroll_comment_panel(dp: Any) -> None:
    dp.run_js(f'var c={CONTAINER_JS}; if(c) c.scrollBy(0, 800);')
    time.sleep(2)


def preflight_check(dp: Any) -> bool:
    info = dp.run_js(SCROLL_CONTAINER_JS)
    if not info or not info.get("found"):
        print("w(ﾟДﾟ)w 滚动容器不存在，抖音页面结构可能已改版！")
        return False
    print(f"✅ 评论区滚动容器正常 (scrollHeight={info.get('sh')}, clientHeight={info.get('ch')})")
    return True


def capture_video_meta(dp: Any, video_id: str, save_dir: str | None = None) -> dict[str, Any]:
    """尽力抓取视频元数据；失败不影响评论采集。"""
    js = """
    var pick = function(sels){ for (var i=0;i<sels.length;i++){ var e=document.querySelector(sels[i]); if(e && e.innerText) return e.innerText.trim(); } return ''; };
    var desc = pick(['[data-e2e="video-desc"]','h1']);
    var author = pick(['[data-e2e="video-author-name"]','.author-name']);
    var likeText = pick(['[data-e2e="video-player-digg"]','[data-e2e="like-count"]']);
    return {desc: desc, author: author, likeText: likeText, title: document.title};
    """
    target_dir = save_dir or SAVE_DIR
    try:
        meta = dp.run_js(js) or {}
        meta["videoId"] = video_id
        meta["capturedAt"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        try:
            os.makedirs(target_dir, exist_ok=True)
            path = os.path.join(target_dir, f"{video_id}.meta.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump(meta, f, ensure_ascii=False, indent=2)
            print(f"  视频元数据已保存: {meta.get('desc','')[:40]}")
        except Exception as e:
            print(f"  视频元数据写入失败（不影响采集）: {e}")
        return meta
    except Exception as e:
        print(f"  视频元数据抓取失败（不影响评论采集）: {e}")
        return {}


def _build_browser(use_login: bool) -> Any:
    """按登录态要求构造浏览器实例。"""
    from DrissionPage import ChromiumPage  # 延迟导入：缺依赖时只影响抖音接入

    if use_login:
        os.environ.setdefault("CHROME_USER_DATA_DIR", "/app/chrome-data")
        os.environ["CHROME_HEADLESS"] = "0"
        print("(🔐) 使用登录态采集（chrome-data 卷）")
    return ChromiumPage(build_options())


# ----------------------------------------------------------------------
# 真实可调用能力函数
# ----------------------------------------------------------------------
def collect_comments(
    video_id: str,
    max_comments: int = 300,
    use_login: bool = False,
    save_dir: str | None = None,
    write_csv: bool = True,
    printer: Callable[[str], None] = print,
) -> CollectOutcome:
    """采集指定视频的评论。

    这是 ``COMMENT`` / ``COMMENT_REPLY`` 能力的真实实现。

    :param video_id: 视频 ID 或抖音链接
    :param max_comments: 本次采集上限
    :param use_login: 是否使用登录态
    :param save_dir: CSV 落地目录，默认取 ``PY_DATAS_DIR``
    :param write_csv: 是否落盘 CSV（Registry 观测时可不落盘）
    :param printer: 输出函数，便于测试捕获
    """
    target_dir = save_dir or SAVE_DIR
    resolved_id = extract_video_id(video_id) or (video_id or "").strip()
    if not resolved_id:
        return CollectOutcome(video_id="", ok=False,
                              finished_reason=FINISH_BROWSER_ERROR,
                              message="无法解析视频 ID")

    target_url = f"https://www.douyin.com/video/{resolved_id}"
    if write_csv:
        os.makedirs(target_dir, exist_ok=True)

    outcome = CollectOutcome(video_id=resolved_id)
    dp = None
    data_count = 0
    try:
        dp = _build_browser(use_login)
        printer("( ‵▽′)ψ 浏览器启动成功")

        dp.listen.start(LISTEN_API)
        dp.get(target_url)
        printer("( ‵▽′)ψ 抖音页面已打开，请等待评论加载...")
        time.sleep(5)

        m = re.search(r"/video/(\d+)", dp.url) or re.search(r"modal_id=(\d+)", dp.url)
        if m:
            resolved_id = m.group(1)
            outcome.video_id = resolved_id

        csv_path = os.path.join(target_dir, f"{resolved_id}.csv")
        progress_path = os.path.join(target_dir, f"{resolved_id}.progress.json")
        outcome.csv_path = csv_path
        outcome.progress_path = progress_path
        printer(f"  视频ID: {resolved_id}")

        if not preflight_check(dp):
            outcome.ok = False
            outcome.finished_reason = FINISH_PREFLIGHT_FAILED
            outcome.message = "评论区滚动容器不存在，抖音页面结构可能已改版"
            return outcome

        outcome.meta = capture_video_meta(dp, resolved_id, target_dir)

        seen_cids: set[str] = set()
        seen_fps: set[str] = set()
        if write_csv:
            seen_cids, seen_fps = load_existing_cids(csv_path)
            if seen_cids or seen_fps:
                printer(f"  检测到历史数据：{len(seen_cids)} 个 cid / {len(seen_fps)} 条指纹，本次将自动跳过重复")
            ensure_header(csv_path)

        dp.run_js(f'var c={CONTAINER_JS}; if(c) c.scrollTop = 600;')
        time.sleep(3)
        printer("(●ˇ∀ˇ●) 已滚动到评论区")

        page = 0
        last_cid = ""
        finished = FINISH_NO_MORE
        while data_count < max_comments:
            page += 1
            printer(f"\n ( •̀ ω •́ )y 正在采集第 {page} 页")

            resp = dp.listen.wait(timeout=15)
            if not resp:
                printer("w(ﾟДﾟ)w 未捕获到评论数据包，结束采集")
                finished = FINISH_NO_RESPONSE
                break

            json_data = resp.response.body
            if "comments" not in json_data or not json_data["comments"]:
                printer("w(ﾟДﾟ)w 无更多评论数据，结束采集")
                finished = FINISH_NO_MORE
                break

            rows: list[dict[str, Any]] = []
            for c in json_data["comments"]:
                if data_count + len(rows) >= max_comments:
                    break
                try:
                    row = to_row(c)
                    cid = row["CID"]
                    fp = fingerprint(row["昵称"], row["日期"], row["内容"])
                    if cid and cid in seen_cids:
                        continue
                    if not cid and fp in seen_fps:
                        continue
                    rows.append(row)
                    if cid:
                        seen_cids.add(cid)
                    seen_fps.add(fp)
                    last_cid = cid or last_cid
                    printer(f"  {row['昵称'][:12]}｜{row['抖音号']}｜{row['地区']}｜赞{row['点赞']}｜cid={cid[:12]}｜{row['内容'][:20]}...")
                    # 楼中楼：接口返回的嵌套回复一并入库
                    for rc in (c.get("reply_comment") or []):
                        if data_count + len(rows) >= max_comments:
                            break
                        rrow = to_row(rc, parent_cid=cid, is_reply=1)
                        if rrow["CID"] and rrow["CID"] in seen_cids:
                            continue
                        rows.append(rrow)
                        if rrow["CID"]:
                            seen_cids.add(rrow["CID"])
                except Exception as e:
                    printer(f"w(ﾟДﾟ)w 解析单条评论失败：{e}")
                    continue

            if write_csv:
                append_rows(csv_path, rows)
                save_progress(progress_path, page, data_count + len(rows), last_cid)
            data_count += len(rows)
            outcome.comments.extend(rows)
            outcome.pages = page
            printer(f"（￣︶￣）↗ 本页新增 {len(rows)} 条，本次累计 {data_count} 条（已落盘）")

            if data_count >= max_comments:
                printer(f"( •̀ ω •́ )✧ 已达到 {max_comments} 条上限，结束采集")
                finished = FINISH_MAX_LIMIT
                break
            if not json_data.get("has_more", 0):
                printer("(○´･д･)ﾉ 已是最后一页，结束采集")
                finished = FINISH_NO_MORE
                break
            scroll_comment_panel(dp)

        if write_csv:
            save_progress(progress_path, page, data_count, last_cid, done=True)
        outcome.finished_reason = finished
        if data_count:
            if write_csv:
                printer(f"\n φ(゜▽゜*)♪ 采集完成：本次新增 {data_count} 条，文件 {csv_path}")
            else:
                printer(f"\n φ(゜▽゜*)♪ 采集完成：本次新增 {data_count} 条（未落盘）")
        else:
            printer("\n =.= 本次没有新增评论（可能已全部采过，或被风控限流）")
        return outcome

    except Exception as e:
        printer(f"\n =.= 采集失败：{e}")
        printer("已落盘的数据保留在 CSV 中，下次运行会自动跳过重复")
        outcome.ok = False
        outcome.finished_reason = FINISH_BROWSER_ERROR
        outcome.message = str(e)
        return outcome
    finally:
        if dp is not None:
            try:
                dp.quit()
                printer("( °▽°)╯ 浏览器已关闭")
            except Exception:
                pass


def collect_content_detail(
    video_id: str,
    save_dir: str | None = None,
    printer: Callable[[str], None] = print,
) -> dict[str, Any]:
    """采集单条视频的公开详情（标题 / 作者 / 点赞文案）。

    这是 ``CONTENT_DETAIL`` 能力的真实实现。只读取公开页面，
    不携带任何伪造凭证。
    """
    resolved_id = extract_video_id(video_id) or (video_id or "").strip()
    if not resolved_id:
        return {}
    dp = None
    try:
        dp = _build_browser(False)
        dp.get(f"https://www.douyin.com/video/{resolved_id}")
        time.sleep(3)
        meta = capture_video_meta(dp, resolved_id, save_dir)
        return meta
    except Exception as e:
        printer(f"w(ﾟДﾟ)w 内容详情采集失败: {e}")
        return {}
    finally:
        if dp is not None:
            try:
                dp.quit()
            except Exception:
                pass


# ----------------------------------------------------------------------
# CLI 入口（保持原 comment.py 的命令行契约）
# ----------------------------------------------------------------------
def run_cli(argv: list[str] | None = None) -> int:
    """原 ``comment.py`` 的 CLI 行为，搬到这里。

    用法：``python comment.py <视频ID或链接> [采集上限] [--use-login]``
    """
    args = list(sys.argv[1:] if argv is None else argv)
    raw_input = args[0] if args else ""
    max_comments = int(args[1]) if len(args) > 1 and args[1].isdigit() else 300
    use_login = any(a in ("--use-login", "--use-login=1", "--use-login=true") for a in args[2:])

    resolved_id = extract_video_id(raw_input)
    if resolved_id:
        target_url = f"https://www.douyin.com/video/{resolved_id}"
    else:
        resolved_id = raw_input
        target_url = raw_input if raw_input.startswith("http") or "douyin.com" in raw_input \
            else f"https://www.douyin.com/video/{raw_input}"
        print("w(ﾟДﾟ)w 未能从输入中解析出视频ID，将尝试直接打开并从最终地址识别")
        print(f"   输入内容: {raw_input[:80]}")

    os.makedirs(SAVE_DIR, exist_ok=True)

    dp = None
    data_count = 0
    try:
        if use_login:
            os.environ.setdefault("CHROME_USER_DATA_DIR", "/app/chrome-data")
            os.environ["CHROME_HEADLESS"] = "0"
            print("(🔐) 使用登录态采集（chrome-data 卷）")
        dp = _build_browser(use_login)
        print("( ‵▽′)ψ 浏览器启动成功")

        dp.listen.start(LISTEN_API)
        dp.get(target_url)
        print("( ‵▽′)ψ 抖音页面已打开，请等待评论加载...")
        time.sleep(5)

        m = re.search(r"/video/(\d+)", dp.url) or re.search(r"modal_id=(\d+)", dp.url)
        if m:
            resolved_id = m.group(1)
        csv_path = os.path.join(SAVE_DIR, f"{resolved_id}.csv")
        progress_path = os.path.join(SAVE_DIR, f"{resolved_id}.progress.json")
        print(f"  视频ID: {resolved_id}")

        if not preflight_check(dp):
            return 1

        capture_video_meta(dp, resolved_id, SAVE_DIR)

        seen_cids, seen_fps = load_existing_cids(csv_path)
        if seen_cids or seen_fps:
            print(f"  检测到历史数据：{len(seen_cids)} 个 cid / {len(seen_fps)} 条指纹，本次将自动跳过重复")
        ensure_header(csv_path)

        dp.run_js(f'var c={CONTAINER_JS}; if(c) c.scrollTop = 600;')
        time.sleep(3)
        print("(●ˇ∀ˇ●) 已滚动到评论区")

        page = 0
        last_cid = ""
        while data_count < max_comments:
            page += 1
            print(f"\n ( •̀ ω •́ )y 正在采集第 {page} 页")

            resp = dp.listen.wait(timeout=15)
            if not resp:
                print("w(ﾟДﾟ)w 未捕获到评论数据包，结束采集")
                break

            json_data = resp.response.body
            if "comments" not in json_data or not json_data["comments"]:
                print("w(ﾟДﾟ)w 无更多评论数据，结束采集")
                break

            rows = []
            for c in json_data["comments"]:
                if data_count + len(rows) >= max_comments:
                    break
                try:
                    row = to_row(c)
                    cid = row["CID"]
                    fp = fingerprint(row["昵称"], row["日期"], row["内容"])
                    if cid and cid in seen_cids:
                        continue
                    if not cid and fp in seen_fps:
                        continue
                    rows.append(row)
                    if cid:
                        seen_cids.add(cid)
                    seen_fps.add(fp)
                    last_cid = cid or last_cid
                    print(f"  {row['昵称'][:12]}｜{row['抖音号']}｜{row['地区']}｜赞{row['点赞']}｜cid={cid[:12]}｜{row['内容'][:20]}...")
                    for rc in (c.get("reply_comment") or []):
                        if data_count + len(rows) >= max_comments:
                            break
                        rrow = to_row(rc, parent_cid=cid, is_reply=1)
                        if rrow["CID"] and rrow["CID"] in seen_cids:
                            continue
                        rows.append(rrow)
                        if rrow["CID"]:
                            seen_cids.add(rrow["CID"])
                except Exception as e:
                    print(f"w(ﾟДﾟ)w 解析单条评论失败：{e}")
                    continue

            append_rows(csv_path, rows)
            data_count += len(rows)
            save_progress(progress_path, page, data_count, last_cid)
            print(f"（￣︶￣）↗ 本页新增 {len(rows)} 条，本次累计 {data_count} 条（已落盘）")

            if data_count >= max_comments:
                print(f"( •̀ ω •́ )✧ 已达到 {max_comments} 条上限，结束采集")
                break
            if not json_data.get("has_more", 0):
                print("(○´･д･)ﾉ 已是最后一页，结束采集")
                break
            scroll_comment_panel(dp)

        save_progress(progress_path, page, data_count, last_cid, done=True)
        if data_count:
            print(f"\n φ(゜▽゜*)♪ 采集完成：本次新增 {data_count} 条，文件 {csv_path}")
        else:
            print("\n =.= 本次没有新增评论（可能已全部采过，或被风控限流）")
        return 0

    except Exception as e:
        print(f"\n =.= 采集失败：{e}")
        print("已落盘的数据保留在 CSV 中，下次运行会自动跳过重复")
        return 1
    finally:
        if dp is not None:
            try:
                dp.quit()
                print("( °▽°)╯ 浏览器已关闭")
            except Exception:
                pass
