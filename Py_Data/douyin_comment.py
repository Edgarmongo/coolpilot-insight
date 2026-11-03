"""抖音评论采集器（流式落盘版）。

关键改动（相对旧版）：
1. 采集 cid / parent_cid / root_cid / 回复数 / 是否回复 —— 建立评论唯一标识
2. 每页立即追加落盘 —— 中断不再丢已采数据
3. 启动时读取已有 CSV 的 cid 集合 —— 重复运行不产生重复行
4. 顺带抓取视频元数据到 <aweme_id>.meta.json —— 供 video 表补全
5. 进度文件 <aweme_id>.progress.json —— 记录页数与最后一条 cid

命令行：
    python douyin_comment.py <视频ID或链接> [采集上限] [--use-login]
"""
from DrissionPage import ChromiumPage
from datetime import datetime
import csv
import json
import os
import re
import sys
import time
from urllib.parse import parse_qs, urlparse

from config_browser import build_options

# 解决 Windows 控制台 GBK 编码问题
if sys.stdout.encoding and sys.stdout.encoding.upper() != 'UTF-8':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

# ====================== 配置 ======================
SAVE_DIR = os.environ.get('PY_DATAS_DIR') or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), 'Datas')
LISTEN_API = "aweme/v1/web/comment/list"
CSV_FIELDS = ["昵称", "抖音号", "UID", "SEC_UID", "地区", "日期",
              "内容", "点赞", "CID", "PARENT_CID", "ROOT_CID", "回复数", "是否回复"]
# ===================================================


def extract_video_id(raw):
    """从各种抖音输入中提取纯数字视频 ID。"""
    if not raw:
        return None
    raw = raw.strip()
    if raw.isdigit():
        return raw
    try:
        query = parse_qs(urlparse(raw).query)
        modal_id = query.get('modal_id', [None])[0]
        if modal_id and modal_id.isdigit():
            return modal_id
    except Exception:
        pass
    m = re.search(r'/(?:video|note|share/video)/(\d+)', raw)
    if m:
        return m.group(1)
    return None


raw_input = sys.argv[1] if len(sys.argv) > 1 else ""
MAX_COMMENTS = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else 300

USE_LOGIN = any(a in ("--use-login", "--use-login=1", "--use-login=true") for a in sys.argv[3:])

VIDEO_ID = extract_video_id(raw_input)
if VIDEO_ID:
    TARGET_URL = f"https://www.douyin.com/video/{VIDEO_ID}"
else:
    VIDEO_ID = raw_input
    TARGET_URL = raw_input if raw_input.startswith("http") or "douyin.com" in raw_input \
        else f"https://www.douyin.com/video/{raw_input}"
    print("w(ﾟДﾟ)w 未能从输入中解析出视频ID，将尝试直接打开并从最终地址识别")
    print(f"   输入内容: {raw_input[:80]}")

os.makedirs(SAVE_DIR, exist_ok=True)

CONTAINER_JS = 'document.querySelector(".parent-route-container") || document.querySelector("#douyin-right-container>div:nth-child(2)")'
SCROLL_CONTAINER_JS = """
var c = document.querySelector(".parent-route-container")
      || document.querySelector("#douyin-right-container>div:nth-child(2)");
return c ? {found:true, cls:(c.className||'').slice(0,60), sh:c.scrollHeight, ch:c.clientHeight} : {found:false};
"""


def scroll_comment_panel(dp):
    dp.run_js(f'var c={CONTAINER_JS}; if(c) c.scrollBy(0, 800);')
    time.sleep(2)


def preflight_check(dp):
    info = dp.run_js(SCROLL_CONTAINER_JS)
    if not info or not info.get('found'):
        print("w(ﾟДﾟ)w 滚动容器不存在，抖音页面结构可能已改版！")
        return False
    print(f"✅ 评论区滚动容器正常 (scrollHeight={info.get('sh')}, clientHeight={info.get('ch')})")
    return True


def load_existing_cids(csv_path):
    """读取已有 CSV 的 cid 与内容指纹，用于跨次运行去重。"""
    cids, fingerprints = set(), set()
    if not os.path.exists(csv_path):
        return cids, fingerprints
    try:
        with open(csv_path, encoding='utf-8-sig', newline='') as f:
            for row in csv.DictReader(f):
                cid = (row.get('CID') or '').strip()
                if cid:
                    cids.add(cid)
                fingerprints.add(fingerprint(row.get('昵称'), row.get('日期'), row.get('内容')))
    except Exception as e:
        print(f"   ⚠️ 读取历史 CSV 失败（将按新文件处理）: {e}")
    return cids, fingerprints


def fingerprint(nickname, dt, content):
    import hashlib
    raw = f"{nickname or ''}|{dt or ''}|{(content or '')[:120]}"
    return hashlib.sha256(raw.encode('utf-8')).hexdigest()[:40]


def ensure_header(csv_path):
    if os.path.exists(csv_path) and os.path.getsize(csv_path) > 0:
        return
    with open(csv_path, 'w', encoding='utf-8-sig', newline='') as f:
        csv.DictWriter(f, fieldnames=CSV_FIELDS).writeheader()


def append_rows(csv_path, rows):
    """每页追加落盘：中断时已采数据不会丢。"""
    if not rows:
        return
    with open(csv_path, 'a', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writerows(rows)


def save_progress(progress_path, page, count, last_cid, done=False):
    try:
        with open(progress_path, 'w', encoding='utf-8') as f:
            json.dump({
                "page": page,
                "collected": count,
                "lastCid": last_cid,
                "finished": done,
                "updatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            }, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def capture_video_meta(dp, video_id):
    """尽力抓取视频元数据；失败不影响评论采集。"""
    js = """
    var pick = function(sels){ for (var i=0;i<sels.length;i++){ var e=document.querySelector(sels[i]); if(e && e.innerText) return e.innerText.trim(); } return ''; };
    var desc = pick(['[data-e2e="video-desc"]','h1']);
    var author = pick(['[data-e2e="video-author-name"]','.author-name']);
    var likeText = pick(['[data-e2e="video-player-digg"]','[data-e2e="like-count"]']);
    return {desc: desc, author: author, likeText: likeText, title: document.title};
    """
    try:
        meta = dp.run_js(js) or {}
        meta['videoId'] = video_id
        meta['capturedAt'] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        path = os.path.join(SAVE_DIR, f"{video_id}.meta.json")
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        print(f"  视频元数据已保存: {meta.get('desc','')[:40]}")
    except Exception as e:
        print(f"  视频元数据抓取失败（不影响评论采集）: {e}")


def to_row(c, parent_cid=None, is_reply=0):
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


data_count = 0
dp = None
try:
    if USE_LOGIN:
        os.environ.setdefault("CHROME_USER_DATA_DIR", "/app/chrome-data")
        os.environ["CHROME_HEADLESS"] = "0"
        print("(🔐) 使用登录态采集（chrome-data 卷）")
    dp = ChromiumPage(build_options())
    print("( ‵▽′)ψ 浏览器启动成功")

    dp.listen.start(LISTEN_API)
    dp.get(TARGET_URL)
    print("( ‵▽′)ψ 抖音页面已打开，请等待评论加载...")
    time.sleep(5)

    m = re.search(r'/video/(\d+)', dp.url) or re.search(r'modal_id=(\d+)', dp.url)
    if m:
        VIDEO_ID = m.group(1)
    csv_path = os.path.join(SAVE_DIR, f"{VIDEO_ID}.csv")
    progress_path = os.path.join(SAVE_DIR, f"{VIDEO_ID}.progress.json")
    print(f"  视频ID: {VIDEO_ID}")

    if not preflight_check(dp):
        sys.exit(1)

    capture_video_meta(dp, VIDEO_ID)

    seen_cids, seen_fps = load_existing_cids(csv_path)
    if seen_cids or seen_fps:
        print(f"  检测到历史数据：{len(seen_cids)} 个 cid / {len(seen_fps)} 条指纹，本次将自动跳过重复")
    ensure_header(csv_path)

    dp.run_js(f'var c={CONTAINER_JS}; if(c) c.scrollTop = 600;')
    time.sleep(3)
    print("(●ˇ∀ˇ●) 已滚动到评论区")

    page = 0
    last_cid = ""
    while data_count < MAX_COMMENTS:
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
            if data_count + len(rows) >= MAX_COMMENTS:
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
                # 楼中楼：接口返回的嵌套回复一并入库
                for rc in (c.get("reply_comment") or []):
                    if data_count + len(rows) >= MAX_COMMENTS:
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

        if data_count >= MAX_COMMENTS:
            print(f"( •̀ ω •́ )✧ 已达到 {MAX_COMMENTS} 条上限，结束采集")
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

except Exception as e:
    print(f"\n =.= 采集失败：{e}")
    print("已落盘的数据保留在 CSV 中，下次运行会自动跳过重复")

finally:
    if dp is not None:
        try:
            dp.quit()
            print("( °▽°)╯ 浏览器已关闭")
        except Exception:
            pass
