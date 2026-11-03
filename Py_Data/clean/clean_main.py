"""
抖音评论数据清洗入库脚本
1. 扫描 Py_Data/Datas/ 目录下的 CSV 文件
2. 从文件名称提取 video_id
3. 空值剔除、去重、情感分析
4. 批量写入 MySQL
5. 入库成功后删除本地 CSV 文件
"""
import os
import sys
import csv
import pymysql
from datetime import datetime

sys.path.insert(0, os.path.dirname(__file__))
from analysis_engine import get_analyzer

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATAS_DIR = os.environ.get('PY_DATAS_DIR') or os.path.join(BASE_DIR, 'Datas')

DB_CONFIG = {
    'host': os.environ.get('DB_HOST', 'localhost'),
    'port': int(os.environ.get('DB_PORT', 3306)),
    'user': os.environ.get('DB_USER', 'root'),
    'password': os.environ.get('DB_PASSWORD', 'root'),
    'database': os.environ.get('DB_NAME', 'dy_comment'),
    'charset': 'utf8mb4',
}

BATCH_SIZE = 500


def get_connection():
    return pymysql.connect(**DB_CONFIG)


def parse_csv(filepath):
    """读取CSV文件，返回评论列表 [{}, {}]"""
    rows = []
    with open(filepath, 'r', encoding='utf-8-sig') as f:
        reader = csv.DictReader(f)
        for row in reader:
            rows.append(row)
    print(f"  读取 {len(rows)} 条原始记录")
    return rows


def clean_data(rows):
    """清洗：空值剔除、去重、格式标准化"""
    # 1. 剔除昵称或内容为空的行
    filtered = [r for r in rows if r.get('昵称', '').strip() and r.get('内容', '').strip()]

    # 2. 按 (昵称, 内容, 日期) 去重，保留首次出现
    seen = set()
    unique = []
    for r in filtered:
        key = (r.get('昵称', '').strip(), r.get('内容', '').strip(), r.get('日期', '').strip())
        if key not in seen:
            seen.add(key)
            unique.append(r)

    # 3. 过滤抖音方括号表情标签 [哭哭] [赞] [黑脸] 等
    import re
    bracket_pat = re.compile(r'\[[一-龥\w]*\]')
    for r in unique:
        r['内容'] = bracket_pat.sub('', r.get('内容', '')).strip()

    # 4. 日期格式标准化：补全秒
    for r in unique:
        dt = r.get('日期', '').strip()
        if len(dt) == 16:  # "2026-03-04 12:35" -> 补 :00
            dt += ':00'
        r['日期'] = dt

    print(f"  清洗后: {len(unique)} 条 (剔除空值 {len(rows) - len(filtered)}, 去重 {len(filtered) - len(unique)})")
    return unique


USER_ID = os.environ.get('CLEAN_USER_ID')


def insert_to_db(video_id, rows):
    """批量插入评论数据到 MySQL（跨批次去重，重复采集同一视频不会产生重复数据）"""
    analyzer = get_analyzer()
    conn = get_connection()
    cursor = conn.cursor()

    # 先查该视频已入库的 (昵称, 内容) 组合，用于跨批次去重
    cursor.execute(
        "SELECT nickname, content FROM comment WHERE video_id = %s",
        (video_id,),
    )
    existing = set(cursor.fetchall())
    if existing:
        print(f"  该视频已有 {len(existing)} 条入库记录，将跳过重复")

    sql = """INSERT INTO comment (user_id, video_id, nickname, douyin_id, douyin_uid, sec_uid,
                                region, publish_time, content, likes, sentiment, created_at)
             VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)"""

    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    batch = []
    success = 0
    skipped = 0

    for row in rows:
        nickname = row.get('昵称', '').strip()
        region = row.get('地区', '').strip()
        publish_time = row.get('日期', '').strip()
        content = row.get('内容', '').strip()
        likes_str = row.get('点赞', '0').strip()
        likes = int(likes_str) if likes_str.isdigit() else 0
        # 用户标识字段（旧版 CSV 无这些列时为空）
        douyin_id = row.get('抖音号', '').strip()
        douyin_uid = row.get('UID', '').strip()
        sec_uid = row.get('SEC_UID', '').strip()

        # 跨批次去重：该 (昵称, 内容) 已入库则跳过
        if (nickname, content) in existing:
            skipped += 1
            continue
        existing.add((nickname, content))

        sentiment = analyzer.analyze(content)
        batch.append((USER_ID, video_id, nickname, douyin_id, douyin_uid, sec_uid,
                      region, publish_time, content, likes, sentiment, now))

        if len(batch) >= BATCH_SIZE:
            cursor.executemany(sql, batch)
            conn.commit()
            success += len(batch)
            batch = []

    if batch:
        cursor.executemany(sql, batch)
        conn.commit()
        success += len(batch)

    cursor.close()
    conn.close()
    print(f"  入库成功: {success} 条" + (f"，跳过重复 {skipped} 条" if skipped else ""))
    return success


def process_file(filepath):
    """处理单个CSV文件：读取→清洗→入库→删除"""
    filename = os.path.basename(filepath)
    video_id = os.path.splitext(filename)[0]
    print(f"\n[{video_id}] 开始处理: {filename}")

    rows = parse_csv(filepath)
    if not rows:
        print(f"  文件为空，跳过")
        return

    clean_rows = clean_data(rows)
    if not clean_rows:
        print(f"  清洗后无有效数据，跳过")
        return

    insert_to_db(video_id, clean_rows)
    os.remove(filepath)
    print(f"  本地文件已删除: {filepath}")


def process_all():
    """扫描 Datas 目录，处理所有 CSV 文件"""
    if not os.path.exists(DATAS_DIR):
        print(f"数据目录不存在: {DATAS_DIR}")
        return

    csv_files = sorted([
        os.path.join(DATAS_DIR, f) for f in os.listdir(DATAS_DIR) if f.endswith('.csv')
    ])
    if not csv_files:
        print("没有待处理的 CSV 文件")
        return

    print(f"发现 {len(csv_files)} 个待处理文件")
    for fp in csv_files:
        try:
            process_file(fp)
        except Exception as e:
            print(f"  处理失败: {e}")

    print("\n全部处理完成")


def process_by_video(video_id):
    """处理指定视频编号的 CSV 文件"""
    filepath = os.path.join(DATAS_DIR, f'{video_id}.csv')
    if not os.path.exists(filepath):
        print(f"文件不存在: {filepath}")
        return
    process_file(filepath)


def process_by_names(names):
    """处理指定的文件名列表"""
    for name in names:
        fp = os.path.join(DATAS_DIR, name)
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
