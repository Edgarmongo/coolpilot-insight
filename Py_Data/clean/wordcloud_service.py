"""Flask 常驻分词服务 + AI Agent 话题分析"""
from flask import Flask, request, jsonify
import pymysql
import jieba
import os
import json

from openai import OpenAI
from dotenv import load_dotenv

# 加载 .env 文件（如果有的话）
load_dotenv()

app = Flask(__name__)

DB_CONFIG = {
    'host': os.environ.get('DB_HOST', 'localhost'),
    'port': int(os.environ.get('DB_PORT', 3306)),
    'user': os.environ.get('DB_USER', 'root'),
    'password': os.environ.get('DB_PASSWORD', 'root'),
    'database': os.environ.get('DB_NAME', 'dy_comment'),
    'charset': 'utf8mb4',
}

# ---------- DeepSeek Agent ----------
_DEEPSEEK_CLIENT = None

def get_deepseek_client():
    global _DEEPSEEK_CLIENT
    if _DEEPSEEK_CLIENT is not None:
        return _DEEPSEEK_CLIENT
    api_key = os.environ.get("DEEPSEEK_API_KEY") or ""
    if not api_key:
        print("[topic_agent] WARN: DEEPSEEK_API_KEY 未设置，AI 分析不可用")
        return None
    _DEEPSEEK_CLIENT = OpenAI(api_key=api_key, base_url="https://api.deepseek.com")
    return _DEEPSEEK_CLIENT


def build_topic_prompt(data):
    """将结构化数据组装成给 DeepSeek 的分析 Prompt"""
    # 关键词
    keywords = data.get("keywords", [])
    kw_str = "、".join(k["name"] for k in keywords[:15]) if keywords else "暂无"

    # 句子结构
    ss = data.get("sentenceStructure", {})
    sent_str = f"提问{ss.get('question',0)}条 / 感叹{ss.get('exclamation',0)}条 / 陈述{ss.get('statement',0)}条 / 建议{ss.get('imperative',0)}条"

    # 情感分布（数组：[{sentiment, count, percentage}]）
    sents = data.get("sentimentDistribution", [])
    if sents and isinstance(sents, list):
        sent_dist = " / ".join(
            f"{s.get('sentiment','?')} {s.get('percentage',0)}%({s.get('count',0)}条)"
            for s in sents
        )
    else:
        sent_dist = "暂无"

    # 话题共现
    topics = data.get("topics", [])
    if topics and isinstance(topics, list):
        topics_str = "\n".join(
            f"- {t.get('topic','?')}（{t.get('total',0)}条，正面{t.get('positive',0)} / "
            f"负面{t.get('negative',0)} / 中性{t.get('neutral',0)}）"
            for t in topics
        )
    else:
        topics_str = "暂无"

    # 动机
    motivations = data.get("motivations", {})
    if motivations and isinstance(motivations, dict):
        mot_str = "、".join(f"{k}（{v}条）" for k, v in sorted(motivations.items(), key=lambda x: -x[1]))
    else:
        mot_str = "暂无"

    # 地区
    regions = data.get("topRegions", [])
    region_str = "、".join(regions[:5]) if regions else "暂无"

    # 情感趋势
    trend = data.get("sentimentTrendDesc", "")

    return f"""你是一位专业的抖音评论区数据分析师。请根据以下结构化数据生成一份深入、易读的「核心话题分析报告」。

【数据概况】共 {data.get('totalComments',0)} 条评论，{data.get('totalUsers',0)} 位用户参与，总点赞 {data.get('totalLikes',0)}
【高频关键词】{kw_str}
【情感分布】{sent_dist}
【评论句式】{sent_str}
【讨论地区分布】{region_str}
【用户动机】{mot_str}
【情感趋势】{trend}
【话题共现详情】
{topics_str}

请严格按照以下模板输出报告（不要增减结构层级）：

📊 数据概览
- 整体评论量、参与度、情感基调概括
- 主要情感倾向与热度趋势

💡 核心话题分析
1. 话题名称
- 讨论量级与正负面占比
- 用户关注点解读
- ⚠️ 风险提示（仅关键风险加粗）
- ✅ 亮点说明
2. 话题名称
...（同上结构，一个话题一项）

📌 总结与运营建议
1. 核心关注点
- 具体解释
2. 核心关注点
- 具体解释与建议文字

格式强制规则：
- 不要使用 # 标题：一级用 **粗标题 + Emoji**，二级用 1. 2. 数字编号
- 各模块之间仅空一行，模块内紧凑排版不留空行
- **加粗**仅用于关键结论或风险提示标签，禁止整段加粗
- 不要使用 --- 分隔线
- 不要罗列原始 JSON 数据"""


@app.route("/analyze_topic", methods=["POST"])
def analyze_topic():
    data = request.get_json(silent=True)
    if not data or not data.get("totalComments", 0):
        return jsonify({"code": 1, "data": None, "msg": "no data"})

    client = get_deepseek_client()
    if not client:
        return jsonify({"code": 1, "data": None, "msg": "no api key"})

    prompt = build_topic_prompt(data)
    try:
        resp = client.chat.completions.create(
            model="deepseek-chat",
            messages=[
                {"role": "system", "content": "你是一个专业的抖音评论区数据分析师。输出格式强制规则：不要使用 # 标题，一级标题用 **粗标题 + Emoji**，二级用 1. 2. 数字编号；用 - 列表组织细分内容；**加粗**仅用于关键结论或风险标签，禁止整段加粗；各模块之间仅空一行，模块内紧凑无空行；不要使用 --- 分隔线；语言简洁自然，不罗列 JSON 原始数据。"},
                {"role": "user", "content": prompt}
            ],
            temperature=0.7,
            max_tokens=2000,
            timeout=30
        )
        text = resp.choices[0].message.content
        return jsonify({"code": 0, "data": text, "msg": "ok"})
    except Exception as e:
        print(f"[topic_agent] DeepSeek 调用失败: {e}")
        return jsonify({"code": 1, "data": None, "msg": str(e)})

STOP_WORDS = set("""
的 了 在 是 我 有 和 就 不 人 都 一 一个 上 也 很 到 说 要 去 你
会 着 没有 看 好 自己 这 他 她 它 们 那 些 所 以 之 与 及 但 或
被 从 而 且 使 让 向 对 于 把 将 能 可以 因为 所以 如果 虽然
什么 怎么 哪 为什么 吗 吧 呢 啊 哦 嗯 哈 呀 啦 哇 嘛 呗 噢
太 真 多 少 大 小 来 去 出 做 只 个 还 又 再 才 已经 正在
不是 就是 还是 只是 可是 但是 然后 所以 因为 而且 或者
如果 虽然 不过 非常 比较 特别 真的 觉得 感觉 应该 可以 需要
知道 喜欢 想 要 会 能 可能 好像 大概 也许 一定 必须 希望
""".split())

# 服务启动时预加载 jieba
jieba.lcut("预加载")
print("jieba 已就绪")

@app.route("/wordcloud")
def wordcloud():
    video_id = request.args.get("video_id", "")
    user_id = request.args.get("user_id")
    if not video_id:
        return jsonify([])

    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()
    if user_id:
        cursor.execute("SELECT content FROM comment WHERE video_id = %s AND user_id = %s", (video_id, user_id))
    else:
        cursor.execute("SELECT content FROM comment WHERE video_id = %s", (video_id,))
    rows = cursor.fetchall()
    conn.close()

    if not rows:
        return jsonify([])

    import re
    bracket_pat = re.compile(r'\[[一-龥\w]*\]')
    text = ' '.join(bracket_pat.sub('', r[0]) for r in rows)
    words = jieba.lcut(text)

    freq = {}
    for w in words:
        w = w.strip()
        if len(w) < 2 or w in STOP_WORDS:
            continue
        if w.isdigit() or all(not c.isalnum() for c in w):
            continue
        freq[w] = freq.get(w, 0) + 1

    result = [{"name": k, "value": v} for k, v in sorted(freq.items(), key=lambda x: -x[1])[:100]]
    return jsonify(result)


if __name__ == "__main__":
    port = int(os.environ.get("FLASK_PORT", 5000))
    host = os.environ.get("FLASK_HOST", "127.0.0.1")
    app.run(host=host, port=port, debug=False)
