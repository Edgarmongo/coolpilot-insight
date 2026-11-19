"""
词云数据生成脚本
从 MySQL 读取评论，jieba 分词，停用词过滤，输出词频 JSON
用法: python wordcloud_gen.py <video_id>
"""
import os
import sys
import json
import pymysql
import jieba

DB_CONFIG = {
    'host': 'localhost', 'port': 3306, 'user': 'root', 'password': 'root',
    'database': 'dy_comment', 'charset': 'utf8mb4',
}

STOP_WORDS = set("""
的 了 在 是 我 有 和 就 不 人 都 一 一个 上 也 很 到 说 要 去 你
会 着 没有 看 好 自己 这 他 她 它 们 那 些 所 以 之 与 及 但 或
被 从 而 且 使 让 向 对 于 把 将 能 可以 因为 所以 如果 虽然
什么 怎么 哪 为什么 吗 吧 呢 啊 哦 嗯 哈 呀 啦 哇 嘛 呗 噢
太 真 多 少 大 小 来 去 出 做 只 个 还 又 再 才 已经 正在
不是 就是 还是 只是 可是 但是 然后 所以 因为 而且 或者
如果 虽然 不过 非常 比较 特别 真的 觉得 感觉 应该 可以 需要
知道 喜欢 想 要 会 能 可能 好像 大概 也许 一定 必须 希望
[ ] 【 】 《 》 （ ） ( ) 我 你 他 她 它 我们 你们 他们 她们
""".split())

def get_comments(video_id):
    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()
    cursor.execute("SELECT content FROM comment WHERE video_id = %s", (video_id,))
    rows = cursor.fetchall()
    conn.close()
    return [r[0] for r in rows]

def gen_wordcloud(video_id):
    comments = get_comments(video_id)
    if not comments:
        print(json.dumps([]))
        return

    import re
    bracket_pat = re.compile(r'\[[一-龥\w]*\]')
    text = ' '.join(bracket_pat.sub('', c) for c in comments)
    words = jieba.lcut(text)

    freq = {}
    for w in words:
        w = w.strip()
        if len(w) < 2 or w in STOP_WORDS:
            continue
        # 过滤纯数字、纯符号
        if w.isdigit() or all(not c.isalnum() for c in w):
            continue
        freq[w] = freq.get(w, 0) + 1

    result = [{"name": k, "value": v} for k, v in sorted(freq.items(), key=lambda x: -x[1])[:100]]
    print(json.dumps(result, ensure_ascii=False))

if __name__ == '__main__':
    if len(sys.argv) > 1:
        gen_wordcloud(sys.argv[1])
    else:
        gen_wordcloud("7613237683142223214")
