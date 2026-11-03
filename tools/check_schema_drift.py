#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""比对 Java 实体字段与 MySQL 实际列，发现「实体有、表里没有」的漂移。"""
import os
import re
import subprocess
import sys

ENTITY_DIR = os.path.join("dy-backend", "src", "main", "java",
                          "com", "coolskyai", "coolpilot", "insight", "entity")


def camel_to_snake(name):
    s1 = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", name)
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s1).lower()


def parse_entities():
    out = {}
    for fn in sorted(os.listdir(ENTITY_DIR)):
        if not fn.endswith(".java"):
            continue
        text = open(os.path.join(ENTITY_DIR, fn), encoding="utf-8").read()
        m = re.search(r'@TableName\("([^"]+)"\)', text)
        if not m:
            continue
        table = m.group(1)
        fields = re.findall(r"^\s*private\s+[\w<>\[\],\s\.]+\s+(\w+)\s*;", text, re.M)
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
