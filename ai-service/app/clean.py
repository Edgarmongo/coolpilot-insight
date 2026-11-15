"""评论清洗入库（目标模型 V1.1）。

与旧版的差异：
1. video 独立建模：CSV 文件名的 aweme_id → `video.id`（ADR-003）
2. 评论双唯一键：`uk_platform_cid`（cid 可空）+ `uk_platform_dedup`（恒非空本地指纹）
3. 幂等写入：`ON DUPLICATE KEY UPDATE`，重复采集不会产生重复行
4. 情感基线写入 `comment_analysis`（profile=ap_ruleset_v1），不再写在 comment 上
5. 不再使用 comment.user_id（ADR-004），归属由采集任务与授权表表达

用法：
    python clean_main.py                      # 处理 Datas 下全部 CSV
    python clean_main.py 7703721523349239951.csv   # 处理指定文件
"""
import csv
import hashlib
import json
import os
import re
import sys
from datetime import datetime

import pymysql

sys.path.insert(0, os.path.dirname(__file__))
from analysis_engine import get_analyzer

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATAS_DIR = os.environ.get('PY_DATAS_DIR') or os.path.join(BASE_DIR, 'Datas')

DB_CONFIG = {
    'host': os.environ.get('DB_HOST', 'localhost'),
    'port': int(os.environ.get('DB_PORT', 3306)),
    'user': os.environ.get('DB_USER', 'root'),
    'password': os.environ.get('DB_PASSWORD', 'root'),
    'database': os.environ.get('DB_NAME', 'coolpilot_insight'),
    'charset': 'utf8mb4',
}

PLATFORM = 'douyin'
BATCH_SIZE = 500
AWEME_PATTERN = re.compile(r'^(\d{6,})\.csv$')
BRACKET_PATTERN = re.compile(r'\[[一-龥\w]*\]')

SENTIMENT_MAP = {1: 'positive', 0: 'neutral', -1: 'negative'}
PROFILE_KEY = 'ap_ruleset_v1'


def get_connection():
    return pymysql.connect(**DB_CONFIG)


def parse_csv(filepath):
    with open(filepath, 'r', encoding='utf-8-sig') as f:
        rows = list(csv.DictReader(f))
    print(f"  读取 {len(rows)} 条原始记录")
    return rows


def clean_data(rows):
    """空值剔除、批内去重、表情标签清理、时间标准化。"""
    filtered = [r for r in rows if (r.get('昵称') or '').strip() and (r.get('内容') or '').strip()]

    seen = set()
    unique = []
    for r in filtered:
        cid = (r.get('CID') or '').strip()
        key = ('cid', cid) if cid else (
            'fp', (r.get('昵称') or '').strip(),
            (r.get('内容') or '').strip(),
            (r.get('日期') or '').strip())
        if key in seen:
            continue
        seen.add(key)
        unique.append(r)

    for r in unique:
        r['内容'] = BRACKET_PATTERN.sub('', r.get('内容') or '').strip()
        dt = (r.get('日期') or '').strip()
        if len(dt) == 16:
            dt += ':00'
        r['日期'] = dt

    print(f"  清洗后: {len(unique)} 条 (剔除空值 {len(rows) - len(filtered)}, 去重 {len(filtered) - len(unique)})")
    return [r for r in unique if r['内容']]


def dedup_key_for(aweme_id, row):
    """生成幂等键：优先平台 cid，否则本地指纹（明确不是平台 ID）。"""
    cid = (row.get('CID') or '').strip()
    if cid:
        return cid, 'CID'
    raw = "|".join([
        PLATFORM,
        aweme_id,
        (row.get('UID') or '').strip(),
        (row.get('日期') or '').strip(),
        (row.get('内容') or '')[:120],
    ])
    return hashlib.sha256(raw.encode('utf-8')).hexdigest()[:40], 'FINGERPRINT'


def upsert_video(cursor, aweme_id):
    cursor.execute(
        "SELECT id FROM video WHERE platform=%s AND aweme_id=%s",
        (PLATFORM, aweme_id))
    row = cursor.fetchone()
    if row:
        return row[0], False

    meta = {}
    meta_path = os.path.join(DATAS_DIR, f"{aweme_id}.meta.json")
    if os.path.exists(meta_path):
        try:
            with open(meta_path, encoding='utf-8') as f:
                meta = json.load(f)
        except Exception:
            meta = {}

    title = (meta.get('desc') or meta.get('title') or '')[:500] or None
    author = (meta.get('author') or '')[:128] or None

    cursor.execute(
        """INSERT INTO video (platform, aweme_id, title, author_name, digg_count,
                              comment_count, share_count, status, last_collected_at)
           VALUES (%s, %s, %s, %s, 0, 0, 0, 1, NOW())""",
        (PLATFORM, aweme_id, title, author))
    return cursor.lastrowid, True


def get_profile_id(cursor):
    cursor.execute("SELECT id FROM analysis_profile WHERE profile_key=%s", (PROFILE_KEY,))
    row = cursor.fetchone()
    return row[0] if row else None


def insert_to_db(aweme_id, rows, source_item_id=None):
    analyzer = get_analyzer()
    conn = get_connection()
    cursor = conn.cursor()
    try:
        video_pk, created = upsert_video(cursor, aweme_id)
        if created:
            print(f"  新建视频记录 video.id={video_pk}")
        profile_id = get_profile_id(cursor)

        sql = """INSERT INTO comment
                 (platform, cid, dedup_key, dedup_strategy, video_id, parent_cid, root_cid,
                  is_reply, reply_count, nickname, douyin_id, douyin_uid, sec_uid,
                  ip_region, content, likes, publish_time, source_item_id)
                 VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                 ON DUPLICATE KEY UPDATE
                   likes = GREATEST(likes, VALUES(likes)),
                   reply_count = GREATEST(reply_count, VALUES(reply_count)),
                   content = VALUES(content),
                   updated_at = NOW()"""

        sentiment_sql = """INSERT INTO comment_analysis
                           (comment_id, profile_id, sentiment, confidence, is_uncertain)
                           VALUES (%s,%s,%s,%s,%s)
                           ON DUPLICATE KEY UPDATE
                             sentiment = VALUES(sentiment),
                             confidence = VALUES(confidence),
                             is_uncertain = VALUES(is_uncertain)"""

        success = 0
        text_only = 0
        for row in rows:
            key, strategy = dedup_key_for(aweme_id, row)
            cid = (row.get('CID') or '').strip() or None
            likes_raw = (row.get('点赞') or '0').strip()
            likes = int(likes_raw) if likes_raw.isdigit() else 0
            reply_raw = (row.get('回复数') or '0').strip()
            reply_count = int(reply_raw) if reply_raw.isdigit() else 0
            is_reply = 1 if (row.get('是否回复') or '0').strip() in ('1', 'true', 'True') else 0
            publish_time = row.get('日期') or datetime.now().strftime('%Y-%m-%d %H:%M:%S')

            try:
                cursor.execute(sql, (
                    PLATFORM, cid, key, strategy, video_pk,
                    (row.get('PARENT_CID') or '').strip() or None,
                    (row.get('ROOT_CID') or '').strip() or None,
                    is_reply, reply_count,
                    (row.get('昵称') or '').strip()[:100],
                    (row.get('抖音号') or '').strip()[:64] or None,
                    (row.get('UID') or '').strip()[:32] or None,
                    (row.get('SEC_UID') or '').strip()[:128] or None,
                    (row.get('地区') or '未知').strip()[:50] or '未知',
                    row['内容'], likes, publish_time, source_item_id,
                ))
                if cursor.rowcount > 0:
                    success += 1
                # 规则情感基线：让看板在 AI 分析前也能展示情感分布
                if profile_id:
                    score = analyzer.analyze_with_score(row['内容']) \
                        if hasattr(analyzer, 'analyze_with_score') else None
                    if score is None:
                        label = analyzer.analyze(row['内容'])
                        confidence = None
                    else:
                        label, confidence = score
                    comment_id = cursor.lastrowid
                    if not comment_id:
                        cursor.execute(
                            "SELECT id FROM comment WHERE platform=%s AND dedup_key=%s",
                            (PLATFORM, key))
                        found = cursor.fetchone()
                        comment_id = found[0] if found else None
                    if comment_id:
                        cursor.execute(sentiment_sql, (
                            comment_id, profile_id, SENTIMENT_MAP.get(label, 'neutral'),
                            confidence, 1 if confidence is not None and confidence < 0.5 else 0))
                        text_only += 1
            except Exception as e:
                print(f"  ⚠️ 单条入库失败（跳过）: {e}")
                continue

            if success % BATCH_SIZE == 0:
                conn.commit()

        conn.commit()
        cursor.execute("UPDATE video SET last_collected_at=NOW() WHERE id=%s", (video_pk,))
        conn.commit()
        print(f"  入库成功: {success} 条（含更新），情感基线: {text_only} 条")
        return success
    finally:
        cursor.close()
        conn.close()


def process_file(filepath):
    filename = os.path.basename(filepath)
    m = AWEME_PATTERN.match(filename)
    if not m:
        print(f"  文件名不是 <作品ID>.csv，跳过: {filename}")
        return
    aweme_id = m.group(1)
    print(f"\n[{aweme_id}] 开始处理: {filename}")

    rows = parse_csv(filepath)
    if not rows:
        print("  文件为空，跳过")
        return

    clean_rows = clean_data(rows)
    if not clean_rows:
        print("  清洗后无有效数据，跳过")
        return

    source_item_id = os.environ.get('COLLECT_ITEM_ID')
    insert_to_db(aweme_id, clean_rows,
                 int(source_item_id) if source_item_id and source_item_id.isdigit() else None)
    os.remove(filepath)
    print(f"  本地文件已删除: {filepath}")


def process_all():
    if not os.path.exists(DATAS_DIR):
        print(f"数据目录不存在: {DATAS_DIR}")
        return
    files = sorted(os.path.join(DATAS_DIR, f) for f in os.listdir(DATAS_DIR)
                   if f.endswith('.csv'))
    if not files:
        print("没有待处理的 CSV 文件")
        return
    print(f"发现 {len(files)} 个待处理文件")
    for fp in files:
        try:
            process_file(fp)
        except Exception as e:
            print(f"  处理失败: {e}")
    print("\n全部处理完成")


def process_by_names(names):
    for name in names:
        fp = os.path.join(DATAS_DIR, os.path.basename(name))
        if os.path.exists(fp):
            try:
                process_file(fp)
            except Exception as e:
                print(f"  处理失败: {e}")
        else:
            print(f"  文件不存在: {name}")
    print("\n全部处理完成")


if __name__ == '__main__':
    if len(sys.argv) > 1:
        process_by_names(sys.argv[1:])
    else:
        process_all()
