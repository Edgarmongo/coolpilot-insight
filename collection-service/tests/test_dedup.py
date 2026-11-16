"""去重记忆客户端（U1 · A-API）。

这里验证的是**失败必须显式**：拿不到、拿不全、被拒，都要抛异常让作业失败，
而不是悄悄退化成"没有历史"继续采——那会把同一批评论再抓一遍。
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

import pytest

from app.jobs import dedup


class _Handler(BaseHTTPRequestHandler):
    """按路径返回预设响应；记录收到的请求头，供令牌断言。"""

    routes: dict = {}
    seen_tokens: list = []

    def do_GET(self):  # noqa: N802
        _Handler.seen_tokens.append(self.headers.get("X-Internal-Token"))
        path = urlparse(self.path).path
        handler = _Handler.routes.get(path)
        if handler is None:
            self.send_response(404)
            self.end_headers()
            return
        status, body = handler(parse_qs(urlparse(self.path).query))
        payload = body.encode("utf-8") if isinstance(body, str) else json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):  # 静音
        pass


@pytest.fixture
def server(monkeypatch):
    httpd = HTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    _Handler.routes = {}
    _Handler.seen_tokens = []
    monkeypatch.setenv("BACKEND_API_URL", f"http://127.0.0.1:{httpd.server_address[1]}")
    monkeypatch.setenv("INTERNAL_TOKEN", "tok-test")
    monkeypatch.setenv("COLLECT_DEDUP_SOURCE", "api")
    try:
        yield _Handler
    finally:
        httpd.shutdown()
        httpd.server_close()


def page(cids=(), fps=(), next_cursor=None, total=0, truncated=False):
    return 200, {"code": 200, "data": {
        "cids": list(cids), "fingerprints": list(fps),
        "nextCursor": next_cursor, "total": total, "truncated": truncated}}


def test_single_page(server):
    server.routes["/internal/collection/dedup"] = lambda q: page(
        cids=["c1", "c2"], fps=["f" * 40], total=3)

    seen = dedup.fetch_seen("7703721523349239951")

    assert seen.cids == {"c1", "c2"}
    assert seen.fingerprints == {"f" * 40}
    assert seen.total == 3
    assert seen.pages == 1


def test_follows_cursor(server):
    def handler(q):
        cursor = int(q.get("cursor", ["0"])[0])
        if cursor == 0:
            return page(cids=["c1"], next_cursor=1, total=2)
        return page(cids=["c2"], next_cursor=None, total=2)

    server.routes["/internal/collection/dedup"] = handler

    seen = dedup.fetch_seen("123")

    assert seen.cids == {"c1", "c2"}
    assert seen.pages == 2


def test_sends_internal_token(server):
    server.routes["/internal/collection/dedup"] = lambda q: page(total=0)

    dedup.fetch_seen("123")

    assert server.seen_tokens == ["tok-test"]


def test_unauthorized_is_unavailable(server):
    server.routes["/internal/collection/dedup"] = lambda q: (401, {"code": 40100})

    with pytest.raises(dedup.DedupUnavailable):
        dedup.fetch_seen("123")


def test_truncated_fails_closed(server):
    server.routes["/internal/collection/dedup"] = lambda q: page(
        cids=["c1"], total=99999, truncated=True)

    with pytest.raises(dedup.DedupTooLarge):
        dedup.fetch_seen("123")


def test_unreachable_is_unavailable(monkeypatch):
    monkeypatch.setenv("BACKEND_API_URL", "http://127.0.0.1:1")
    monkeypatch.setenv("COLLECT_DEDUP_SOURCE", "api")

    with pytest.raises(dedup.DedupUnavailable):
        dedup.fetch_seen("123")


def test_bad_json_is_unavailable(server):
    server.routes["/internal/collection/dedup"] = lambda q: (200, "not-json")

    with pytest.raises(dedup.DedupUnavailable):
        dedup.fetch_seen("123")


def test_empty_content_id_skips_call(server):
    seen = dedup.fetch_seen("")

    assert seen.cids == set()
    assert server.seen_tokens == []


def test_source_mode_default_and_override(monkeypatch):
    monkeypatch.delenv("COLLECT_DEDUP_SOURCE", raising=False)
    assert dedup.source_mode() == "api"
    monkeypatch.setenv("COLLECT_DEDUP_SOURCE", "CSV")
    assert dedup.source_mode() == "csv"
