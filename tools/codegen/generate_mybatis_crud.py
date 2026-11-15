#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从实体类生成标准 MyBatis CRUD 映射（替代 MyBatis-Plus BaseMapper）。

迁移背景：MyBatis-Plus 3.5.x 依赖 javax 版 mybatis-spring，与 Spring Boot 4 不兼容，
必须替换为原生 MyBatis。本脚本只生成"机械部分"（单表 CRUD），业务条件查询仍手写。

生成的语义与原 MyBatis-Plus 保持一致：
  - insert / updateById 跳过 null 字段（MP 默认 NOT_NULL 策略）
  - id 使用数据库自增并回填（useGeneratedKeys）

用法：python tools/codegen/generate_mybatis_crud.py [--dry-run]
"""

from __future__ import annotations

import argparse
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "dy-backend"))
ENTITY_DIR = os.path.join(ROOT, "src/main/java/com/coolskyai/coolpilot/insight/entity")
MAPPER_DIR = os.path.join(ROOT, "src/main/java/com/coolskyai/coolpilot/insight/mapper")
XML_DIR = os.path.join(ROOT, "src/main/resources/mapper")
MAPPER_PKG = "com.coolskyai.coolpilot.insight.mapper"
ENTITY_PKG = "com.coolskyai.coolpilot.insight.entity"


def camel_to_snake(name: str) -> str:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def parse_entity(path: str):
    src = open(path, encoding="utf-8").read()
    cls = re.search(r"public\s+class\s+(\w+)", src)
    table = re.search(r'@TableName\(\s*"([^"]+)"\s*\)', src)
    if not cls or not table:
        return None
    fields = []
    for m in re.finditer(r"^\s*private\s+(?!static)([\w<>\[\], .]+?)\s+(\w+)\s*;", src, re.M):
        ftype, fname = m.group(1).strip(), m.group(2)
        fields.append((ftype, fname))
    if not fields:
        return None
    return {"class": cls.group(1), "table": table.group(1), "fields": fields, "src": src}


def build_xml(ent) -> str:
    cls, table, fields = ent["class"], ent["table"], ent["fields"]
    ns = f"{MAPPER_PKG}.{cls}Mapper"
    cols = [(f, camel_to_snake(f)) for _, f in fields]

    result_map = "\n".join(
        f'        <result column="{col}" property="{prop}"/>' for prop, col in cols if prop != "id"
    )
    column_list = ", ".join(col for _, col in cols)

    insert_cols = "\n".join(
        f'            <if test="{prop} != null">{col},</if>' for prop, col in cols if prop != "id"
    )
    insert_vals = "\n".join(
        f'            <if test="{prop} != null">#{{{prop}}},</if>' for prop, col in cols if prop != "id"
    )
    update_sets = "\n".join(
        f'            <if test="{prop} != null">{col} = #{{{prop}}},</if>' for prop, col in cols if prop != "id"
    )

    return f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<!-- 由 tools/codegen/generate_mybatis_crud.py 生成，替代 MyBatis-Plus BaseMapper。
     语义与 MP 一致：insert/updateById 跳过 null 字段，id 自增回填。手工业务 SQL 请追加在本文件末尾。 -->
<mapper namespace="{ns}">

    <resultMap id="BaseResultMap" type="{ENTITY_PKG}.{cls}">
        <id column="id" property="id"/>
{result_map}
    </resultMap>

    <sql id="Base_Column_List">{column_list}</sql>

    <select id="selectById" resultMap="BaseResultMap">
        SELECT <include refid="Base_Column_List"/> FROM {table} WHERE id = #{{id}}
    </select>

    <select id="selectBatchIds" resultMap="BaseResultMap">
        SELECT <include refid="Base_Column_List"/> FROM {table}
        WHERE id IN
        <foreach collection="collection" item="it" open="(" separator="," close=")">#{{it}}</foreach>
    </select>

    <insert id="insert" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO {table}
        <trim prefix="(" suffix=")" suffixOverrides=",">
{insert_cols}
        </trim>
        <trim prefix="VALUES (" suffix=")" suffixOverrides=",">
{insert_vals}
        </trim>
    </insert>

    <update id="updateById">
        UPDATE {table}
        <set>
{update_sets}
        </set>
        WHERE id = #{{id}}
    </update>

    <delete id="deleteById">
        DELETE FROM {table} WHERE id = #{{id}}
    </delete>
</mapper>
'''


def build_interface(cls: str, existing: str) -> str:
    """把 extends BaseMapper<T> 换成 extends BaseCrudMapper<T>，保留原有自定义方法。"""
    body = existing
    body = body.replace("import com.baomidou.mybatisplus.core.mapper.BaseMapper;\n", "")
    body = re.sub(r"extends\s+BaseMapper<(\w+)>", r"extends BaseCrudMapper<\1>", body)
    return body


def clean_entity(src: str) -> str:
    src = src.replace("import com.baomidou.mybatisplus.annotation.IdType;\n", "")
    src = src.replace("import com.baomidou.mybatisplus.annotation.TableId;\n", "")
    src = src.replace("import com.baomidou.mybatisplus.annotation.TableName;\n", "")
    src = re.sub(r'^@TableName\([^)]*\)\n', "", src, flags=re.M)
    src = re.sub(r"^\s*@TableId\([^)]*\)\n", "", src, flags=re.M)
    return src


BASE_INTERFACE = '''package {pkg};

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * 单表 CRUD 契约，替代 MyBatis-Plus 的 BaseMapper。
 *
 * 只声明方法签名，具体 SQL 在各 Mapper XML 中实现（见 resources/mapper）。
 * 之所以保留同名方法，是为了让既有 Service 调用点无需改动。
 */
public interface BaseCrudMapper<T> {{

    T selectById(Serializable id);

    List<T> selectBatchIds(Collection<? extends Serializable> ids);

    int insert(T entity);

    int updateById(T entity);

    int deleteById(Serializable id);
}}
'''


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    os.makedirs(XML_DIR, exist_ok=True)
    generated, skipped = [], []

    for name in sorted(os.listdir(ENTITY_DIR)):
        if not name.endswith(".java"):
            continue
        path = os.path.join(ENTITY_DIR, name)
        ent = parse_entity(path)
        if not ent:
            skipped.append(name)
            continue
        cls = ent["class"]
        xml_path = os.path.join(XML_DIR, f"{cls}Mapper.xml")
        if not args.dry_run:
            open(xml_path, "w", encoding="utf-8", newline="\n").write(build_xml(ent))
            open(path, "w", encoding="utf-8", newline="\n").write(clean_entity(ent["src"]))
            mapper_path = os.path.join(MAPPER_DIR, f"{cls}Mapper.java")
            if os.path.exists(mapper_path):
                cur = open(mapper_path, encoding="utf-8").read()
                open(mapper_path, "w", encoding="utf-8", newline="\n").write(build_interface(cls, cur))
        generated.append((cls, ent["table"], len(ent["fields"])))

    if not args.dry_run:
        base = os.path.join(MAPPER_DIR, "BaseCrudMapper.java")
        open(base, "w", encoding="utf-8", newline="\n").write(BASE_INTERFACE.format(pkg=MAPPER_PKG))

    for cls, table, n in generated:
        print(f"  {cls:<26} -> {table:<24} {n} 字段")
    if skipped:
        print(f"跳过（无 @TableName）: {', '.join(skipped)}")
    print(f"\n共生成 {len(generated)} 个 Mapper XML -> {XML_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
