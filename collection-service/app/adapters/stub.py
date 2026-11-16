"""Stub 适配器：不访问任何平台，返回确定性数据。

用途有两个，都很明确：
1. 端到端验证「Java → 采集服务 → 结果」这条链路，不依赖 Chrome 与真实平台；
2. 作为新平台接入时的契约参考——实现 PlatformAdapter 需要做什么，这里是最小样例。

它**不代表任何真实平台能力**。能力声明里 status=PENDING、各项为 False，
上层据此应认定「该源不可用于生产采集」。
"""

from __future__ import annotations

from typing import Iterable

from app.adapters.base import (
    Capability,
    PlatformAdapter,
    RawComment,
    RawContent,
)


class StubAdapter(PlatformAdapter):
    """返回固定内容的假适配器。"""

    source_code = "STUB"
    connector_code = "STUB_PUBLIC"
    connector_version = "1.0.0"

    def __init__(self, content_id: str = "stub-content", comment_count: int = 3):
        self._content_id = content_id
        self._comment_count = max(0, comment_count)

    @property
    def capabilities(self) -> Capability:
        return Capability(
            content_detail=False,
            comments=False,
            keyword_search=False,
            status="PENDING",
            note="stub 适配器，仅用于链路验证，不代表真实平台能力",
        )

    def capability_specs(self) -> list:
        """**不声明任何能力。**

        stub 只是链路验证替身：它没有平台、没有真实实现，
        声明能力等于骗人说"这个平台能采"。
        """
        return []

    def legacy_status(self) -> str:
        return "PENDING"

    def legacy_note(self) -> str:
        return "stub 适配器，仅用于链路验证，不代表真实平台能力"

    def validate_config(self) -> None:
        # stub 无外部依赖，永远可用
        return None

    def discover(self, query: dict) -> Iterable[RawContent]:
        # 明确不支持发现：不要为了「看起来完整」返回假数据
        raise NotImplementedError("stub 适配器不支持发现")

    def collect_content(self, external_id: str) -> RawContent:
        return RawContent(
            external_id=external_id,
            content_type="VIDEO",
            title="Stub 内容",
            text="仅用于链路验证",
            url=f"https://example.invalid/{external_id}",
        )

    def collect_comments(self, external_id: str, limit: int = 300) -> Iterable[RawComment]:
        n = min(self._comment_count, max(0, limit))
        for i in range(1, n + 1):
            yield RawComment(
                external_id=f"{external_id}-c{i}",
                content_id=external_id,
                text=f"stub 评论 #{i}",
                nickname=f"stub-user-{i}",
                likes=i,
                ip_region="未知",
            )
