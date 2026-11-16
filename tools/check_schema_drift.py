#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""比对 Java 实体字段与 MySQL 实际列，发现「实体有、表里没有」的漂移。

⚠️ **本脚本目前不可用（2026-11-17 核实）**，不可作为验收依据：

1. 它扫描的包路径 `com/coolskyai/coolpilot/insight/entity` 已不存在
   （项目包名改为 `com.coolpilot.insight`，实体散落在 `source/domain`、
   `system/domain`、`content/domain` 等目录）；
2. 全仓库已 **0 处** 使用 `@TableName`——持久层是注解 SQL + 普通 POJO，
   脚本的识别依据本身就不成立了。

也就是说：即便把路径改对，它也只会输出「实体数 0」然后打印"一致"，
这是一种**假通过**。与其留下这个陷阱，不如让它显式失败。

真正需要的漂移检查（POJO 字段 ←→ 列名）要按 mapper 的 SQL 反推，属于独立工具，
未在本轮实现。迁移是纯数据变更时（如 V31~V33）不涉及表结构，本就没有漂移可言。
"""
import os
import re
import subprocess
import sys

ROOT = os.path.join("backend", "src", "main", "java")


def camel_to_snake(name):
    s1 = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", name)
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s1).lower()


def parse_entities():
    out = {}
    for dirpath, _dirs, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            text = open(path, encoding="utf-8").read()
            m = re.search(r'@TableName\("([^"]+)"\)', text)
            if not m:
                continue
            table = m.group(1)
            # 只取实例字段：static / final 的常量不是列
            fields = re.findall(
                r"^\s*private\s+(?!static)(?!final)[\w<>\[\],\s\.]+\s+(\w+)\s*;", text, re.M)
            out[table] = [camel_to_snake(f) for f in fields]
    return out


def db_columns():
    sql = ("SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
           "WHERE TABLE_SCHEMA='coolpilot_insight';")
    p = subprocess.run(
        ["docker", "exec", "cpi-mysql", "mysql", "-uroot", "-proot",
         "--default-character-set=utf8mb4", "-N", "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    cols = {}
    for line in p.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) == 2:
            cols.setdefault(parts[0].strip(), set()).add(parts[1].strip())
    return cols


def main():
    ents, cols = parse_entities(), db_columns()
    if not ents:
        # 空集合不能算"一致"——那是假通过。见文件头说明。
        print("❌ 未找到任何 @TableName 实体：本脚本的识别依据已失效。", file=sys.stderr)
        print("   请勿把这条输出当作「表结构一致」的证据。", file=sys.stderr)
        return 2
    missing_tables, drift = [], []
    for table, fields in ents.items():
        if table not in cols:
            missing_tables.append(table)
            continue
        for f in fields:
            if f not in cols[table]:
                drift.append((table, f))
    print("实体数:", len(ents), " 数据库表数:", len(cols))
    if missing_tables:
        print("\n[缺表]")
        for t in missing_tables:
            print("  -", t)
    if drift:
        print("\n[实体有字段、表中无列] 共 %d 处" % len(drift))
        for t, f in drift:
            print("  - %-24s %s" % (t, f))
    if not missing_tables and not drift:
        print("\n✅ 实体与表结构一致")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
