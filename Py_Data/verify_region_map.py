"""验证地区分布地图着色效果（用容器内 Chrome 真实渲染）。"""
import json
import time
import urllib.request

from DrissionPage import ChromiumPage

from config_browser import build_options

dp = ChromiumPage(build_options())
dp.set.window.size(1400, 1100)
try:
    req = urllib.request.Request(
        "http://backend:8080/api/user/login",
        data=json.dumps({"phone": "13800000000", "password": "123456"}).encode(),
        headers={"Content-Type": "application/json"},
    )
    token = json.loads(urllib.request.urlopen(req).read())["data"]["token"]

    dp.get("http://frontend/")
    dp.run_js("localStorage.setItem('token', '%s')" % token)
    dp.run_js(
        "localStorage.setItem('userInfo', JSON.stringify("
        "{userId:2, phone:'13800000000', role:1}))"
    )
    dp.get("http://frontend/dashboard")
    time.sleep(10)

    idx = dp.run_js(
        "var cards = document.querySelectorAll('.chart-card');"
        "for (var i=0;i<cards.length;i++){"
        "  if(cards[i].innerText.indexOf('地区分布')>=0){ return i; } }"
        "return -1;"
    )
    print("地区分布卡片索引:", idx)

    if idx is not None and idx >= 0:
        dp.run_js(
            "document.querySelectorAll('.chart-card')[%d]"
            ".scrollIntoView({block:'center'});" % idx
        )
        time.sleep(2)
        dp.get_screenshot(path="/tmp/region_color.png")
        print("截图已保存")

        # 读取图表卡片内的文字，确认条形图与图例都在
        txt = dp.run_js(
            "var cards=document.querySelectorAll('.chart-card');"
            "return cards[%d].innerText;" % idx
        )
        print("卡片文本:", repr(txt))
finally:
    dp.quit()
