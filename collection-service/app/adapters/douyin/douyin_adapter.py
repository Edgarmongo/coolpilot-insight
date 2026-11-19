"""抖音公开网页接入（Connector: ``DOUYIN_PUBLIC``）。

这是 SOURCE-03 之后**第一个真正实现 ``PlatformAdapter`` 契约的接入**：

* 每条能力都绑定到真实可调用函数（``collector.py`` / ``search.py``）；
* 能力声明里的 ``implementationStatus`` **默认是 UNVERIFIED**——
  代码存在不等于平台验证通过。只有当 ``registry/verification_evidence.json``
  里存在真实运行证据时，才会被提升为 ``VERIFIED``；
* 不伪造签名、不模拟设备指纹、不做身份轮换，只读公开页面。
"""

from __future__ import annotations

from typing import Any, Iterable

from app.adapters.base import (
    AdapterUnavailable,
    PlatformAdapter,
    RawComment,
    RawContent,
)
from app.registry.capability import (
    AccessMode,
    CapabilityCategory,
    CapabilityCode,
    CapabilitySpec,
    ImplementationStatus,
)

#: 能力声明里的函数引用。**必须**是真实可调用函数的模块路径。
FN_COLLECT_COMMENTS = "app.adapters.douyin.collector.collect_comments"
FN_COLLECT_DETAIL = "app.adapters.douyin.collector.collect_content_detail"
FN_SEARCH = "app.adapters.douyin.search.run_search"

#: 抖音公开评论接口的实测约束（见 SOURCE-01 / C-03 E2E 报告）。
MAX_COMMENTS = 300
MAX_PAGE_SIZE = 20


def _raw_comment(row: dict[str, Any]) -> RawComment:
    """采集行 → :class:`RawComment`。"""
    return RawComment(
        external_id=(row.get("CID") or None),
        parent_external_id=(row.get("PARENT_CID") or None),
        content_id=None,
        text=row.get("内容") or "",
        nickname=row.get("昵称") or None,
        published_at=row.get("日期") or None,
        likes=int(row.get("点赞") or 0),
        ip_region=row.get("地区") or None,
        raw=row,
    )


class DouyinPublicAdapter(PlatformAdapter):
    """抖音公开网页接入实现。"""

    source_code = "DOUYIN"
    connector_code = "DOUYIN_PUBLIC"
    connector_version = "1.0.0"

    # ------------------------------------------------------------------
    # 能力声明
    # ------------------------------------------------------------------
    def capability_specs(self) -> list[CapabilitySpec]:
        return [
            CapabilitySpec(
                code=CapabilityCode.COMMENT,
                category=CapabilityCategory.COMMENT,
                name="视频评论采集",
                access_modes=(AccessMode.PUBLIC, AccessMode.AUTHENTICATED),
                implementation_status=ImplementationStatus.UNVERIFIED,
                contract_version="1.0",
                adapter_function=FN_COLLECT_COMMENTS,
                input_schema={
                    "type": "object",
                    "properties": {
                        "videoId": {"type": "string"},
                        "maxComments": {"type": "integer", "minimum": 0},
                        "useLogin": {"type": "boolean"},
                    },
                    "required": ["videoId"],
                },
                output_schema={
                    "type": "object",
                    "properties": {
                        "videoId": {"type": "string"},
                        "comments": {"type": "array"},
                        "finishedReason": {"type": "string"},
                    },
                    "required": ["videoId", "comments"],
                },
                limits={
                    "maxComments": MAX_COMMENTS,
                    "maxPageSize": MAX_PAGE_SIZE,
                    "supportsPagination": True,
                },
                retry_policy={
                    "maxAttempts": 3,
                    "retryableErrors": ["NETWORK", "RATE_LIMIT", "DATA", "SYSTEM"],
                },
                note="匿名态受平台限制，页数与条数有上限；登录态可提高上限。",
            ),
            CapabilitySpec(
                code=CapabilityCode.COMMENT_REPLY,
                category=CapabilityCategory.COMMENT,
                name="楼中楼回复读取",
                access_modes=(AccessMode.PUBLIC, AccessMode.AUTHENTICATED),
                implementation_status=ImplementationStatus.UNVERIFIED,
                contract_version="1.0",
                adapter_function=FN_COLLECT_COMMENTS,
                input_schema={
                    "type": "object",
                    "properties": {
                        "videoId": {"type": "string"},
                        "maxComments": {"type": "integer", "minimum": 0},
                    },
                    "required": ["videoId"],
                },
                output_schema={
                    "type": "object",
                    "properties": {
                        "replies": {"type": "array"},
                    },
                },
                limits={
                    "maxComments": MAX_COMMENTS,
                    "maxPageSize": MAX_PAGE_SIZE,
                    "supportsPagination": False,
                },
                retry_policy={
                    "maxAttempts": 3,
                    "retryableErrors": ["NETWORK", "RATE_LIMIT", "DATA", "SYSTEM"],
                },
                note="只读取评论下的楼中楼回复，由评论接口的 reply_comment 字段随主评论一并返回，不单独翻页。本能力不含任何写操作。",
            ),
            CapabilitySpec(
                code=CapabilityCode.CONTENT_DETAIL,
                category=CapabilityCategory.CONTENT,
                name="视频详情采集",
                access_modes=(AccessMode.PUBLIC,),
                implementation_status=ImplementationStatus.UNVERIFIED,
                contract_version="1.0",
                adapter_function=FN_COLLECT_DETAIL,
                input_schema={
                    "type": "object",
                    "properties": {"videoId": {"type": "string"}},
                    "required": ["videoId"],
                },
                output_schema={
                    "type": "object",
                    "properties": {
                        "videoId": {"type": "string"},
                        "desc": {"type": "string"},
                        "author": {"type": "string"},
                    },
                },
                retry_policy={
                    "maxAttempts": 2,
                    "retryableErrors": ["NETWORK", "PLATFORM", "SYSTEM"],
                },
                note="只读取公开页面上的标题、作者与点赞文案。",
            ),
            CapabilitySpec(
                code=CapabilityCode.KEYWORD_SEARCH,
                category=CapabilityCategory.SEARCH,
                name="关键词搜索发现",
                access_modes=(AccessMode.PUBLIC,),
                implementation_status=ImplementationStatus.UNVERIFIED,
                contract_version="1.0",
                adapter_function=FN_SEARCH,
                input_schema={
                    "type": "object",
                    "properties": {
                        "keyword": {"type": "string"},
                        "limit": {"type": "integer", "minimum": 1},
                    },
                    "required": ["keyword"],
                },
                output_schema={
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "awemeId": {"type": "string"},
                            "title": {"type": "string"},
                            "url": {"type": "string"},
                        },
                    },
                },
                limits={"maxPageSize": 20, "supportsPagination": True},
                retry_policy={
                    "maxAttempts": 2,
                    "retryableErrors": ["NETWORK", "RATE_LIMIT", "SYSTEM"],
                },
                note="从未在真实平台验证过（风控行为未知），固定保持 UNVERIFIED。",
            ),
        ]

    # ------------------------------------------------------------------
    # 旧契约（兼容期）
    # ------------------------------------------------------------------
    @property
    def capabilities(self):
        from app.adapters.base import Capability

        return Capability(
            content_detail=True,
            comments=True,
            author_profile=False,
            keyword_search=False,
            pagination=True,
            status="PENDING",
            note="抖音公开网页接入；评论采集已实测，关键词搜索未验证",
        )

    def legacy_note(self) -> str:
        return "抖音公开网页接入；评论采集已实测，关键词搜索未验证"

    # ------------------------------------------------------------------
    # 运行时
    # ------------------------------------------------------------------
    def validate_config(self) -> None:
        """只校验本地依赖，**不访问平台**（健康检查不得触发真实请求）。"""
        try:
            import DrissionPage  # noqa: F401
        except Exception as e:
            raise AdapterUnavailable("DrissionPage 未安装，抖音接入不可用") from e

    def discover(self, query: dict[str, Any]) -> Iterable[RawContent]:
        from app.adapters.douyin.search import run_search

        keyword = (query.get("keyword") or "").strip()
        if not keyword:
            raise AdapterUnavailable("关键词不能为空")
        limit = int(query.get("limit") or 50)
        for item in run_search(keyword, limit):
            yield RawContent(
                external_id=item.get("awemeId") or "",
                content_type="VIDEO",
                title=item.get("title") or None,
                url=item.get("url") or None,
                author_external_id=None,
                metrics={"likes": item.get("likes", 0)},
                raw=item,
            )

    def collect_content(self, external_id: str) -> RawContent:
        from app.adapters.douyin.collector import collect_content_detail

        meta = collect_content_detail(external_id) or {}
        return RawContent(
            external_id=external_id,
            content_type="VIDEO",
            title=meta.get("desc") or None,
            text=meta.get("desc") or None,
            url=f"https://www.douyin.com/video/{external_id}",
            metrics={"likeText": meta.get("likeText")},
            raw=meta,
        )

    def collect_comments(self, external_id: str, limit: int = 300) -> Iterable[RawComment]:
        from app.adapters.douyin.collector import collect_comments as _collect

        outcome = _collect(external_id, limit, use_login=False, write_csv=False)
        for row in outcome.comments:
            yield _raw_comment(row)

    def health_check(self) -> bool:
        """只检查本地依赖是否就绪，不发起平台请求。"""
        try:
            self.validate_config()
            return True
        except AdapterUnavailable:
            return False
