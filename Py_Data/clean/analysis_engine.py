"""情感分析引擎。

策略（P0-7）：
1. SnowNLP 给出基础分
2. 灰区（默认 0.4~0.6）用词典二次判定 —— 启用此前完全未使用的 529 词词典
3. 阈值可通过环境变量配置，无需改代码
4. 输出 sentiment 标签 + 置信度，供 comment_analysis 记录

短评论、反讽、纯表情等仍可能误判，这属于规则法的固有上限；
真正的需求/痛点识别由 LLM 结构化分析承担（见 wordcloud_service）。
"""
import os
import re

from snownlp import SnowNLP

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DICT_DIR = os.path.join(BASE_DIR, 'sentiment_dict')

POS_THRESHOLD = float(os.environ.get('SENTIMENT_POS', '0.6'))
NEG_THRESHOLD = float(os.environ.get('SENTIMENT_NEG', '0.4'))

# 否定词：出现在情感词前会翻转极性
NEGATIONS = set('不 没 没有 别 无 非 未 莫 勿 差 缺乏 谈不上'.split())

BRACKET_PATTERN = re.compile(r'\[[一-龥\w]*\]')
NON_WORD = re.compile(r'[^\w\u4e00-\u9fff]+')


def _load_words(filename):
    path = os.path.join(DICT_DIR, filename)
    if not os.path.exists(path):
        return set()
    with open(path, encoding='utf-8', errors='ignore') as f:
        return {line.strip() for line in f if line.strip()}


class SentimentAnalyzer:

    def __init__(self):
        self.positive = _load_words('positive_words.txt')
        self.negative = _load_words('negative_words.txt')

    # ---------------------------------------------------------- 词典

    def dict_score(self, text):
        """返回 (正向命中数, 负向命中数)，否定词翻转极性。"""
        pos = neg = 0
        # 按 2~6 字滑窗匹配词典，避免中文分词依赖
        for i in range(len(text)):
            for size in range(2, 7):
                w = text[i:i + size]
                if len(w) < 2:
                    continue
                if w in self.positive:
                    if i > 0 and text[i - 1] in NEGATIONS:
                        neg += 1
                    else:
                        pos += 1
                elif w in self.negative:
                    if i > 0 and text[i - 1] in NEGATIONS:
                        pos += 1
                    else:
                        neg += 1
        return pos, neg

    # ---------------------------------------------------------- 主入口

    def analyze_with_score(self, text):
        """返回 (标签, 置信度)。标签：1 积极 / 0 中性 / -1 消极。"""
        if not text or not text.strip():
            return 0, 0.0

        cleaned = BRACKET_PATTERN.sub('', text).strip()
        cleaned = NON_WORD.sub('', cleaned)
        if not cleaned:
            return 0, 0.0

        try:
            base = SnowNLP(cleaned).sentiments
        except Exception:
            base = 0.5

        pos_hits, neg_hits = self.dict_score(cleaned)
        dict_bias = 0.0
        if pos_hits + neg_hits > 0:
            dict_bias = (pos_hits - neg_hits) / (pos_hits + neg_hits)

        # 灰区才用词典，避免覆盖 SnowNLP 的明确判断
        in_gray = NEG_THRESHOLD <= base <= POS_THRESHOLD
        score = base
        if in_gray and (pos_hits or neg_hits):
            # 词典权重 0.35，最多把分数推到阈值外
            score = base + dict_bias * 0.35
        score = max(0.0, min(1.0, score))

        confidence = abs(score - 0.5) * 2
        if score > POS_THRESHOLD:
            return 1, round(confidence, 3)
        if score < NEG_THRESHOLD:
            return -1, round(confidence, 3)
        return 0, round(confidence, 3)

    def analyze(self, text):
        return self.analyze_with_score(text)[0]

    def analyze_batch(self, texts):
        return [self.analyze(t) for t in texts]


_analyzer = None


def get_analyzer():
    global _analyzer
    if _analyzer is None:
        _analyzer = SentimentAnalyzer()
    return _analyzer


if __name__ == '__main__':
    analyzer = get_analyzer()
    tests = [
        ("好可爱的小猫咪！！！", 1),
        ("真的太难看了想吐", -1),
        ("今天天气不错", 0),
        ("笑死我了哈哈哈哈太搞笑了", 1),
        ("不好看", -1),
        ("超级好看", 1),
        ("非常难看", -1),
        ("[赞][赞]好看", 1),
        ("？？？", 0),
    ]
    for text, expected in tests:
        label, conf = analyzer.analyze_with_score(text)
        status = "PASS" if label == expected else f"FAIL(exp:{expected})"
        print(f"  {status} [{label}] conf={conf} {text}")
