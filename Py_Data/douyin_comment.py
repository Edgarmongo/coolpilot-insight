from DrissionPage import ChromiumPage
from datetime import datetime
import csv
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
# 数据保存目录：优先取环境变量，否则用脚本同级的 Datas 目录（容器内外一致）
SAVE_DIR = os.environ.get('PY_DATAS_DIR') or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), 'Datas')
LISTEN_API = "aweme/v1/web/comment/list"
# ===================================================


def extract_video_id(raw):
    """从各种抖音输入中提取纯数字视频 ID。

    兼容场景：
      1. 纯数字 ID                       -> 7613237683142223214
      2. 视频页链接 /video/<id>          -> https://www.douyin.com/video/7613...
      3. 图文链接 /note/<id>
      4. 分享短链打开后跳转的最终地址
      5. 搜索结果页 /jingxuan/search/xxx?modal_id=<id>  ← 从"搜索点开的视频"复制来的链接
    """
    if not raw:
        return None
    raw = raw.strip()

    # 1. 纯数字
    if raw.isdigit():
        return raw

    # 2. 查询参数 modal_id（搜索结果页点开视频后地址栏的样子）
    try:
        query = parse_qs(urlparse(raw).query)
        modal_id = query.get('modal_id', [None])[0]
        if modal_id and modal_id.isdigit():
            return modal_id
    except Exception:
        pass

    # 3. 路径中的 /video/<id>、/note/<id>、/share/video/<id>
    m = re.search(r'/(?:video|note|share/video)/(\d+)', raw)
    if m:
        return m.group(1)

    return None


# 视频编号：优先从命令行参数读取，否则使用默认值
raw_input = sys.argv[1] if len(sys.argv) > 1 else "7613237683142223214"

# 采集数量：从命令行参数读取，否则 300
if len(sys.argv) > 2:
    MAX_COMMENTS = int(sys.argv[2])
else:
    MAX_COMMENTS = 300

# 是否使用登录态（--use-login / --use-login=1）
# 开启时：使用 CHROME_USER_DATA_DIR 指向的登录态目录，并关闭 headless，
# 以便复用容器内已登录的抖音会话，绕过匿名态的评论限流。
USE_LOGIN = False
for arg in sys.argv[3:]:
    if arg in ("--use-login", "--use-login=1", "--use-login=true"):
        USE_LOGIN = True
        break

VIDEO_ID = extract_video_id(raw_input)
if VIDEO_ID:
    # 无论用户贴的是哪种形式，统一跳到视频详情页。
    # 搜索页 /jingxuan/search/... 的评论区结构与视频页不同，直接打开会抓不到评论包。
    TARGET_URL = f"https://www.douyin.com/video/{VIDEO_ID}"
else:
    # 提取失败时保留原样打开（可能是 v.douyin.com 短链，打开后从地址栏再取一次）
    VIDEO_ID = raw_input
    TARGET_URL = raw_input if raw_input.startswith("http") or "douyin.com" in raw_input \
        else f"https://www.douyin.com/video/{raw_input}"
    print(f"w(ﾟДﾟ)w 未能从输入中解析出视频ID，将尝试直接打开并从最终地址识别")
    print(f"   输入内容: {raw_input[:80]}")

os.makedirs(SAVE_DIR, exist_ok=True)
# ======================================================

CONTAINER_JS = 'document.querySelector(".parent-route-container") || document.querySelector("#douyin-right-container>div:nth-child(2)")'
SCROLL_CONTAINER_JS = """
var c = document.querySelector(".parent-route-container")
      || document.querySelector("#douyin-right-container>div:nth-child(2)");
return c ? {found:true, cls:(c.className||'').slice(0,60), sh:c.scrollHeight, ch:c.clientHeight} : {found:false};
"""


def scroll_comment_panel(dp):
    """翻页：向下滚动内部滚动容器，触发加载更多评论"""
    dp.run_js(f'var c={CONTAINER_JS}; if(c) c.scrollBy(0, 800);')
    time.sleep(2)


def preflight_check(dp):
    """采集前体检：确认评论区滚动容器存在，页面结构变了立刻报错而不闷头跑。"""
    info = dp.run_js(SCROLL_CONTAINER_JS)
    if not info or not info.get('found'):
        print("w(ﾟДﾟ)w 滚动容器不存在，抖音页面结构可能已改版！")
        print("   请参考《抖音爬虫维护笔记.md》第四节重新定位滚动容器。")
        return False
    print(f"✅ 评论区滚动容器正常 (scrollHeight={info.get('sh')}, clientHeight={info.get('ch')})")
    return True


data_list = []
csv_path = os.path.join(SAVE_DIR, f"{VIDEO_ID}.csv")

dp = None
try:
    # 登录态采集：使用登录态目录 + 非 headless（无头模式易触发风控且登录态可能失效）
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

    # 从最终地址栏提取真实视频 ID（覆盖短链跳转、以及上面解析失败的情况）
    m = re.search(r'/video/(\d+)', dp.url) or re.search(r'modal_id=(\d+)', dp.url)
    if m:
        VIDEO_ID = m.group(1)
    csv_path = os.path.join(SAVE_DIR, f"{VIDEO_ID}.csv")
    print(f"  视频ID: {VIDEO_ID}")

    if not preflight_check(dp):
        sys.exit(1)

    # 滚动内部容器以触发评论区加载
    dp.run_js(f'var c={CONTAINER_JS}; if(c) c.scrollTop = 600;')
    time.sleep(3)
    print("(●ˇ∀ˇ●) 已滚动到评论区")

    page = 0
    while len(data_list) < MAX_COMMENTS:
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

        page_count = len(json_data["comments"])
        for c in json_data["comments"]:
            if len(data_list) >= MAX_COMMENTS:
                break
            try:
                dt = datetime.fromtimestamp(c["create_time"]).strftime("%Y-%m-%d %H:%M:%S")
                user = c.get("user") or {}
                data_list.append({
                    "昵称": user.get("nickname", ""),
                    # 用户标识：抖音号(unique_id) / 内部UID / 安全UID(主页链接用)
                    "抖音号": user.get("unique_id", "") or user.get("short_id", ""),
                    "UID": user.get("uid", "") or "",
                    "SEC_UID": user.get("sec_uid", "") or "",
                    # IP 归属地：抖音出于隐私保护只返回归属地标签，不返回真实 IP
                    "地区": c.get("ip_label", "未知") or "未知",
                    "日期": dt,
                    "内容": c["text"],
                    "点赞": c["digg_count"]
                })
                print(f"  {user.get('nickname','')}｜{user.get('unique_id','')}｜{c.get('ip_label','未知')}｜{dt}｜点赞{c['digg_count']}｜{c['text'][:24]}...")
            except Exception as e:
                print(f"w(ﾟДﾟ)w 解析单条评论失败：{e}")
                continue

        print(f"（￣︶￣）↗ 本页 {page_count} 条，累计 {len(data_list)} 条")

        if len(data_list) >= MAX_COMMENTS:
            print(f"( •̀ ω •́ )✧ 已达到 {MAX_COMMENTS} 条上限，结束采集")
            break

        has_more = json_data.get("has_more", 0)
        if not has_more:
            print("(○´･д･)ﾉ 已是最后一页，结束采集")
            break
        scroll_comment_panel(dp)

    if data_list:
        with open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=["昵称", "抖音号", "UID", "SEC_UID", "地区", "日期", "内容", "点赞"])
            writer.writeheader()
            writer.writerows(data_list)
        print(f"\n φ(゜▽゜*)♪ 采集成功！共 {len(data_list)} 条评论，已保存到：{csv_path}")
    else:
        print("\n =.= 未采集到任何数据，不生成文件")

except Exception as e:
    print(f"\n =.= 采集失败：{e}")
    print("本次不生成新CSV文件，下次运行仍沿用当前序号")

finally:
    # 必须关闭浏览器：否则进程残留会锁住用户数据目录，导致下次采集失败
    if dp is not None:
        try:
            dp.quit()
            print("( °▽°)╯ 浏览器已关闭")
        except Exception:
            pass
