"""Flask AI 服务：分词词云 + 结构化需求洞察。

端点：
  GET  /wordcloud?video_id=<内部id>      词云
  POST /analyze_topic                    统计报告（Markdown，兼容旧链路）
  POST /analyze/comments                 评论级结构化分析（强制 JSON）
  POST /analyze/insight                  视频级洞察（强制 JSON）

安全：配置 FLASK_INTERNAL_TOKEN 后，所有业务端点必须带 X-Internal-Token。
"""
from flask import Flask, request, jsonify
import pymysql
import jieba
import os
import json
import re
import threading

from openai import OpenAI
from dotenv import load_dotenv

load_dotenv()

app = Flask(__name__)

DB_CONFIG = {
    'host': os.environ.get('DB_HOST', 'localhost'),
    'port': int(os.environ.get('DB_PORT', 3306)),
    'user': os.environ.get('DB_USER', 'root'),
    'password': os.environ.get('DB_PASSWORD', 'root'),
    'database': os.environ.get('DB_NAME', 'coolpilot_insight'),
    'charset': 'utf8mb4',
}

INTERNAL_TOKEN = os.environ.get('FLASK_INTERNAL_TOKEN', '').strip()
MODEL_NAME = os.environ.get('AI_MODEL', 'deepseek-chat')
# 进程内并发上限；跨实例限流需网关（文档 6.6 已说明该边界）
SEMAPHORE = threading.Semaphore(int(os.environ.get('AI_MAX_CONCURRENCY', '3')))


@app.before_request
def guard():
    """服务间认证：未配置令牌时跳过（仅本地开发），配置后强制校验。"""
    if not INTERNAL_TOKEN:
        return None
    if request.path in ('/', '/health'):
        return None
    if request.headers.get('X-Internal-Token', '') != INTERNAL_TOKEN:
        return jsonify({"code": 403, "msg": "forbidden"}), 403
    return None


# ---------------------------------------------------------------- DeepSeek

_DEEPSEEK_CLIENT = None
_CLIENT_LOCK = threading.Lock()


def get_client():
    global _DEEPSEEK_CLIENT
    if _DEEPSEEK_CLIENT is not None:
        return _DEEPSEEK_CLIENT
    with _CLIENT_LOCK:
        if _DEEPSEEK_CLIENT is not None:
            return _DEEPSEEK_CLIENT
        api_key = os.environ.get("DEEPSEEK_API_KEY") or ""
        if not api_key:
            print("[ai] WARN: DEEPSEEK_API_KEY 未设置，AI 能力不可用")
            return None
        _DEEPSEEK_CLIENT = OpenAI(api_key=api_key, base_url="https://api.deepseek.com")
        return _DEEPSEEK_CLIENT


def usage_of(resp):
    try:
        u = resp.usage
        return {"input_tokens": int(u.prompt_tokens or 0),
                "output_tokens": int(u.completion_tokens or 0)}
    except Exception:
        return {"input_tokens": 0, "output_tokens": 0}


def chat_json(system_prompt, user_prompt, max_tokens=2048, temperature=0.2):
    """强制 JSON 输出；失败返回 None（调用方降级）。"""
    client = get_client()
    if not client:
        return None, {"input_tokens": 0, "output_tokens": 0}
    acquired = SEMAPHORE.acquire(timeout=60)
    if not acquired:
        raise RuntimeError("AI 并发已达上限，请稍后重试")
    try:
        resp = client.chat.completions.create(
            model=MODEL_NAME,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=temperature,
            max_tokens=max_tokens,
            response_format={"type": "json_object"},
            timeout=150,
        )
        text = resp.choices[0].message.content
        return json.loads(text), usage_of(resp)
    finally:
        SEMAPHORE.release()


# ---------------------------------------------------------------- 词云

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

jieba.lcut("预加载")
print("jieba 已就绪")


@app.route("/health")
def health():
    return jsonify({"code": 0, "msg": "ok"})


@app.route("/wordcloud")
def wordcloud():
    video_id = request.args.get("video_id", "")
    if not video_id:
        return jsonify({"code": 0, "data": []})
    if video_id == "__health__":
        return jsonify({"code": 0, "data": []})

    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor() as cursor:
            # video_id 现在是内部 video.id（ADR-003）；鉴权在 Java 侧完成
            cursor.execute("SELECT content FROM comment WHERE video_id = %s", (video_id,))
            rows = cursor.fetchall()
    finally:
        conn.close()

    if not rows:
        return jsonify({"code": 0, "data": []})

    bracket_pat = re.compile(r'\[[一-龥\w]*\]')
    text = ' '.join(bracket_pat.sub('', r[0]) for r in rows)
    freq = {}
    for w in jieba.lcut(text):
        w = w.strip()
        if len(w) < 2 or w in STOP_WORDS:
            continue
        if w.isdigit() or all(not c.isalnum() for c in w):
            continue
        freq[w] = freq.get(w, 0) + 1

    result = [{"name": k, "value": v} for k, v in sorted(freq.items(), key=lambda x: -x[1])[:100]]
    return jsonify({"code": 0, "data": result})


# ---------------------------------------------------------------- 旧链路：统计报告

def build_topic_prompt(data):
    keywords = data.get("keywords", [])
    kw_str = "、".join(k["name"] for k in keywords[:15]) if keywords else "暂无"

    ss = data.get("sentenceStructure", {})
    sent_str = (f"提问{ss.get('question',0)}条 / 感叹{ss.get('exclamation',0)}条 / "
                f"陈述{ss.get('statement',0)}条 / 建议{ss.get('imperative',0)}条")

    sents = data.get("sentimentDistribution", [])
    if sents and isinstance(sents, list):
        sent_dist = " / ".join(
            f"{s.get('sentiment','?')} {s.get('percentage',0)}%({s.get('count',0)}条)"
            for s in sents)
    else:
        sent_dist = "暂无"

    topics = data.get("topics", [])
    if topics and isinstance(topics, list):
        topics_str = "\n".join(
            f"- {t.get('topic','?')}（{t.get('total',0)}条，正面{t.get('positive',0)} / "
            f"负面{t.get('negative',0)} / 中性{t.get('neutral',0)}）"
            for t in topics)
    else:
        topics_str = "暂无"

    motivations = data.get("motivations", {})
    if motivations and isinstance(motivations, dict):
        mot_str = "、".join(f"{k}（{v}条）" for k, v in sorted(motivations.items(), key=lambda x: -x[1]))
    else:
        mot_str = "暂无"

    regions = data.get("regionDistribution", [])
    if regions and isinstance(regions, list):
        region_str = "、".join(
            f"{r.get('region','?')}({r.get('count',0)})" for r in regions[:6]) \
            if isinstance(regions[0], dict) else "、".join(map(str, regions[:5]))
    else:
        region_str = "暂无"

    top_comments = data.get("topComments", [])
    rep_str = "\n".join(f"- [{c.get('likes',0)}赞] {c.get('content','')[:80]}"
                        for c in top_comments[:8]) if top_comments else "暂无"

    return f"""你是一位专业的内容数据分析师。请根据以下结构化数据生成一份「核心话题分析报告」。

【数据概况】共 {data.get('totalComments',0)} 条评论，{data.get('totalUsers',0)} 位用户参与，总点赞 {data.get('totalLikes',0)}
【高频关键词】{kw_str}
【情感分布】{sent_dist}
【评论句式】{sent_str}
【讨论地区分布】{region_str}
【用户动机】{mot_str}
【情感趋势】{data.get('sentimentTrendDesc','')}
【高赞代表评论】
{rep_str}
【话题共现详情】
{topics_str}

请严格按模板输出：

📊 数据概览
- 整体评论量、参与度、情感基调概括
- 主要情感倾向与热度趋势

💡 核心话题分析
1. 话题名称
- 讨论量级与正负面占比
- 用户关注点解读
- ⚠️ 风险提示（仅关键风险加粗）
- ✅ 亮点说明

📌 总结与运营建议
1. 核心关注点
- 具体解释与建议文字

格式规则：不要使用 # 标题；一级用 **粗标题 + Emoji**；二级用 1. 2. 编号；
用 - 列表；加粗仅用于关键结论；不要 --- 分隔线；不要罗列原始 JSON。"""


@app.route("/analyze_topic", methods=["POST"])
def analyze_topic():
    data = request.get_json(silent=True)
    if not data or not data.get("totalComments", 0):
        return jsonify({"code": 1, "data": None, "msg": "no data"})

    client = get_client()
    if not client:
        return jsonify({"code": 1, "data": None, "msg": "no api key"})

    prompt = build_topic_prompt(data)
    try:
        resp = client.chat.completions.create(
            model=MODEL_NAME,
            messages=[
                {"role": "system", "content": "你是专业的内容数据分析师。输出格式：不要 # 标题；一级用 **粗标题+Emoji**；二级用 1. 2. 编号；- 列表；加粗仅用于结论；不要 --- 分隔线；不罗列 JSON。"},
                {"role": "user", "content": prompt},
            ],
            temperature=0.7,
            max_tokens=2000,
            timeout=60,
        )
        return jsonify({"code": 0, "data": resp.choices[0].message.content,
                        "usage": usage_of(resp), "msg": "ok"})
    except Exception as e:
        print(f"[topic] DeepSeek 调用失败: {e}")
        return jsonify({"code": 1, "data": None, "msg": str(e)})


# ---------------------------------------------------------------- 评论级结构化分析

COMMENT_SYSTEM = """你是资深的社交媒体用户研究分析师。你的任务是把抖音评论转成结构化标签。

必须只输出一个 JSON 对象，格式严格如下：
{"results":[{
  "comment_id": 数字,
  "sentiment": "positive|neutral|negative|unknown",
  "intent": "complaint|praise|question|purchase|other",
  "topics": ["简短主题词", ...],
  "pain_points": ["具体痛点", ...],
  "user_need": "一句话概括用户真实需求，无则空字符串",
  "purchase_intent": "none|low|medium|high",
  "scenario_tags": ["使用场景", ...],
  "product_suggestions": ["用户提出的产品建议", ...],
  "competitor_mentions": ["提到的竞品/品牌", ...],
  "confidence": 0到1之间的小数
}]}

规则：
1. 每条输入评论必须对应一个结果，comment_id 原样返回。
2. 只能依据评论原文判断，不要臆测。
3. 无相关信息时数组返回 []，字符串返回 ""。
4. 反讽、纯表情、纯@提及等无法判断的，sentiment 用 unknown 且 confidence < 0.5。
5. 不输出任何 JSON 之外的解释文字。"""


@app.route("/analyze/comments", methods=["POST"])
def analyze_comments():
    payload = request.get_json(silent=True) or {}
    comments = payload.get("comments") or []
    if not comments:
        return jsonify({"code": 1, "msg": "no comments"})

    lines = []
    for c in comments:
        text = (c.get("content") or "").replace("\n", " ")[:300]
        lines.append(f'{{"comment_id":{int(c.get("id"))},"content":{json.dumps(text, ensure_ascii=False)}}}')

    user_prompt = ("以下是待分析评论，每行一个 JSON：\n" + "\n".join(lines) +
                   f"\n\n共 {len(comments)} 条，请逐条输出结构化结果。")

    try:
        data, usage = chat_json(COMMENT_SYSTEM, user_prompt,
                                max_tokens=min(4096, 400 * len(comments) + 500))
        if data is None:
            return jsonify({"code": 1, "msg": "AI 服务不可用（未配置 API Key）"})
        results = data.get("results") if isinstance(data, dict) else None
        if not isinstance(results, list):
            return jsonify({"code": 1, "msg": "AI 返回格式不符合 Schema"})
        return jsonify({"code": 0, "data": {"results": results}, "usage": usage})
    except json.JSONDecodeError as e:
        return jsonify({"code": 1, "msg": f"JSON 解析失败: {e}"})
    except Exception as e:
        print(f"[analyze/comments] 失败: {e}")
        return jsonify({"code": 1, "msg": str(e)})


# ---------------------------------------------------------------- 视频级洞察

INSIGHT_SYSTEM = """你是资深用户研究与产品策略分析师。基于给定视频的评论样本和统计，产出结构化需求洞察。

只输出一个 JSON 对象：
{
  "summary": "200字以内的整体洞察总结",
  "needs": [{"title":"需求名","description":"解释","evidence_comment_ids":[评论id],"confidence":0.0-1.0}],
  "pain_points": [{"title":"痛点名","description":"解释","evidence_comment_ids":[评论id],"confidence":0.0-1.0}],
  "product_opportunities": [{"title":"机会点","description":"解释","evidence_comment_ids":[评论id],"confidence":0.0-1.0}],
  "highlights": [{"title":"正面亮点","description":"解释","evidence_comment_ids":[评论id],"confidence":0.0-1.0}],
  "purchase_intent": {"level":"none|low|medium|high","reason":"依据"}
}

规则：
1. 每个结论必须给出 evidence_comment_ids，只能是输入中出现过的评论 id。
2. 不超过 5 条 needs、5 条 pain_points、3 条 product_opportunities、3 条 highlights。
3. 不得虚构原文没有的信息；证据不足时宁可少给结论。
4. 不输出 JSON 之外任何文字。"""


@app.route("/analyze/insight", methods=["POST"])
def analyze_insight():
    payload = request.get_json(silent=True) or {}
    comments = payload.get("comments") or []
    stats = payload.get("stats") or {}
    if not comments:
        return jsonify({"code": 1, "msg": "no comments"})

    lines = []
    for c in comments:
        text = (c.get("content") or "").replace("\n", " ")[:250]
        lines.append(f'{{"id":{int(c.get("id"))},"likes":{int(c.get("likes") or 0)},'
                     f'"content":{json.dumps(text, ensure_ascii=False)}}}')

    user_prompt = (
        f"【统计】{json.dumps(stats, ensure_ascii=False)}\n\n"
        "【评论样本】每行一个 JSON：\n" + "\n".join(lines) +
        f"\n\n共 {len(comments)} 条样本，请输出结构化洞察。")

    try:
        data, usage = chat_json(INSIGHT_SYSTEM, user_prompt, max_tokens=3500)
        if data is None:
            return jsonify({"code": 1, "msg": "AI 服务不可用（未配置 API Key）"})
        return jsonify({"code": 0, "data": data, "usage": usage})
    except json.JSONDecodeError as e:
        return jsonify({"code": 1, "msg": f"JSON 解析失败: {e}"})
    except Exception as e:
        print(f"[analyze/insight] 失败: {e}")
        return jsonify({"code": 1, "msg": str(e)})


if __name__ == "__main__":
    port = int(os.environ.get("FLASK_PORT", 5000))
    host = os.environ.get("FLASK_HOST", "127.0.0.1")
    app.run(host=host, port=port, debug=False, threaded=True)
