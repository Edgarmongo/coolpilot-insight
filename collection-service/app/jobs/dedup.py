"""跨次去重记忆的客户端（U1 · A-API）。

采集前向 backend 拉取"这个视频已经入库过哪些评论"，再开始采集。

**为什么不直连数据库**：给采集容器发库凭据会把数据库访问主体从 1 个变成 2 个
（凭据分发、审计范围、爆炸半径全都扩大）。backend 已有权威数据，走内部接口即可。

**为什么拿不到就必须失败**：去重记忆是"少打平台"的手段。拿不到还继续采，
就会把同一批评论反复抓一遍——风控暴露面随采集次数线性放大。
所以这里所有失败都抛异常，由调用方把作业判为失败，**不做静默降级**。
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

#: 默认单页条数（与 backend 的 MAX_LIMIT=5000 对齐，取 1000 控制单次响应体）
DEFAULT_PAGE_SIZE = 1000
#: 硬上限：超过即视为"拿不到完整集合"，直接失败（正常视频远小于此）
MAX_TOTAL = 50_000
DEFAULT_TIMEOUT = 10


class DedupUnavailable(Exception):
    """去重记忆拿不到（未配置 / 不可达 / 非 200 / 响应非法）。"""

    code = "DEDUP_SOURCE_UNAVAILABLE"


class DedupTooLarge(Exception):
    """去重集合超过硬上限，或 backend 声明 truncated。"""

    code = "DEDUP_SOURCE_TOO_LARGE"


@dataclass
class SeenSet:
    """已入库评论的两类键。"""

    cids: set[str] = field(default_factory=set)
    fingerprints: set[str] = field(default_factory=set)
    total: int = 0
    pages: int = 0
    truncated: bool = False


def source_mode() -> str:
    """去重记忆来源：``api``（默认）或 ``csv``（旧行为，仅回滚用）。"""
    return (os.environ.get("COLLECT_DEDUP_SOURCE") or "api").strip().lower()


def _base_url() -> str:
    return (os.environ.get("BACKEND_API_URL") or "http://backend:8080").rstrip("/")


def _token() -> str:
    return os.environ.get("INTERNAL_TOKEN") or ""


def _fetch_page(external_content_id: str, cursor: int, limit: int, timeout: int) -> dict:
    query = urllib.parse.urlencode({
        "externalContentId": external_content_id,
        "cursor": cursor,
        "limit": limit,
    })
    url = f"{_base_url()}/internal/collection/dedup?{query}"
    req = urllib.request.Request(url, method="GET")
    token = _token()
    if token:
        req.add_header("X-Internal-Token", token)

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                raise DedupUnavailable(f"去重接口返回 HTTP {resp.status}")
            body = json.loads(resp.read().decode("utf-8"))
    except DedupUnavailable:
        raise
    except urllib.error.HTTPError as e:
        # 401 也要当成"拿不到"：继续采会把重复数据再抓一遍
        raise DedupUnavailable(f"去重接口返回 HTTP {e.code}") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise DedupUnavailable(f"去重接口不可达: {e}") from e
    except json.JSONDecodeError as e:
        raise DedupUnavailable(f"去重接口响应不是合法 JSON: {e}") from e

    data = body.get("data") if isinstance(body, dict) else None
    if not isinstance(data, dict):
        raise DedupUnavailable("去重接口响应缺少 data")
    return data


def fetch_seen(external_content_id: str, *, page_size: int = DEFAULT_PAGE_SIZE,
               max_total: int = MAX_TOTAL, timeout: int = DEFAULT_TIMEOUT) -> SeenSet:
    """分页拉全该视频的去重集合。

    :raises DedupUnavailable: 拿不到（调用方应判作业失败）
    :raises DedupTooLarge: 集合过大或 backend 声明 truncated
    """
    if not external_content_id:
        return SeenSet()

    seen = SeenSet()
    cursor = 0
    while True:
        data = _fetch_page(external_content_id, cursor, page_size, timeout)
        seen.pages += 1

        for cid in data.get("cids") or []:
            if cid:
                seen.cids.add(str(cid))
        for fp in data.get("fingerprints") or []:
            if fp:
                seen.fingerprints.add(str(fp))

        seen.total = int(data.get("total") or 0)
        if data.get("truncated"):
            seen.truncated = True
            raise DedupTooLarge(f"去重集合过大（total={seen.total}），拒绝在不完整集合上采集")

        nxt = data.get("nextCursor")
        if nxt is None:
            break
        cursor = int(nxt)
        if cursor <= 0:
            raise DedupUnavailable(f"去重接口返回非法游标: {nxt}")
        if len(seen.cids) + len(seen.fingerprints) > max_total:
            raise DedupTooLarge(f"去重集合超过上限 {max_total}")

    return seen
