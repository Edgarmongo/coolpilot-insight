"""平台适配器契约。

每个数据源（抖音 / 小红书 / 快手 / Web）实现本接口，采集引擎只依赖这里的方法，
不直接依赖任何平台 SDK。这样新增平台时不需要改动分析侧与业务侧。

设计约定：
  * 能力必须显式声明（见 ``capabilities``）——「能跑」不等于「合规可用」；
    未验证的能力应声明为 ``False``，上层据此降级为人工导入或直接禁用。
  * 所有产出统一为 ``RawContent`` / ``RawComment``，由 normalizer 转成库表结构；
    适配器不直接写数据库。

当前状态（2026-11-16）：
  * ``StubAdapter`` —— 链路验证替身，不声明任何真实能力。
  * ``DouyinPublicAdapter``（``adapters/douyin/douyin_adapter.py``）——
    首个真实接入实现，能力绑定到 ``collector.py`` / ``search.py`` 里的真实函数。
  本文件先确立契约；小红书 / 快手 / Web 按同一契约接入。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Iterable

if TYPE_CHECKING:  # 避免运行期循环导入
    from app.registry.capability import CapabilitySpec


@dataclass
class Capability:
    """能力声明：决定上层能否对该平台开放对应功能。"""

    content_detail: bool = False
    comments: bool = False
    author_profile: bool = False
    keyword_search: bool = False
    pagination: bool = False
    #: Available / Limited / Manual / Disabled / Pending
    status: str = "Pending"
    note: str = ""


@dataclass
class RawContent:
    """一份内容的原始形态，字段允许为空，由 normalizer 补齐默认值。"""

    external_id: str
    content_type: str = "VIDEO"          # VIDEO / NOTE / ARTICLE / POST
    title: str | None = None
    text: str | None = None
    url: str | None = None
    author_external_id: str | None = None
    published_at: str | None = None      # ISO8601，拿不到则为 None
    metrics: dict[str, Any] = field(default_factory=dict)
    raw: dict[str, Any] = field(default_factory=dict)   # 原始响应，供溯源


@dataclass
class RawComment:
    external_id: str | None = None       # 平台评论 ID，可能为空
    parent_external_id: str | None = None
    content_id: str | None = None
    text: str = ""
    nickname: str | None = None
    published_at: str | None = None
    likes: int = 0
    ip_region: str | None = None
    raw: dict[str, Any] = field(default_factory=dict)


class PlatformAdapter(ABC):
    """平台适配器基类。"""

    #: 平台唯一标识，如 DOUYIN / XHS / KUAISHOU / WEB
    source_code: str = ""
    #: 接入实现标识（SOURCE-02 §02-04）。同一平台可有多个接入实现。
    connector_code: str = ""
    #: 接入实现版本（semver）。接口行为变化时递增。
    connector_version: str = "0.0.0"

    @property
    @abstractmethod
    def capabilities(self) -> Capability:
        """返回当前**已验证**的能力集合（旧契约，供兼容期使用）。

        新代码请用 :meth:`capability_specs` —— 它能表达逐项的访问模式、
        实现状态与函数绑定。
        """

    def capability_specs(self) -> list["CapabilitySpec"]:
        """返回 v1 契约下的能力声明列表。

        默认空：没有真实实现就没有能力声明。
        每条声明都必须带 ``adapterFunction``，指向真实可调用函数。
        """
        return []

    def legacy_status(self) -> str | None:
        """旧端点使用的状态词覆盖值。返回 ``None`` 表示按能力状态自动推导。"""
        return None

    def legacy_note(self) -> str | None:
        """旧端点使用的说明文本覆盖值。"""
        return None

    @abstractmethod
    def validate_config(self) -> None:
        """校验配置与运行时依赖（浏览器、登录态、令牌等）。

        不具备条件时抛 ``AdapterUnavailable``，由上层决定降级或跳过。
        """

    @abstractmethod
    def discover(self, query: dict[str, Any]) -> Iterable[RawContent]:
        """按关键词 / 话题 / URL 发现候选内容。不支持的平台应抛 NotImplementedError。"""

    @abstractmethod
    def collect_content(self, external_id: str) -> RawContent:
        """采集单条内容详情。"""

    @abstractmethod
    def collect_comments(self, external_id: str, limit: int = 300) -> Iterable[RawComment]:
        """采集允许访问的评论。不可用的平台返回空集合并声明能力为 False。"""

    def health_check(self) -> bool:
        """连接与可用性检查。默认认为通过，子类可覆盖。

        **不得**为了健康检查发起真实平台采集请求——
        L4/L5 健康检查需显式触发且受速率限制（SOURCE-02 §02-05）。
        """
        return True


class AdapterUnavailable(RuntimeError):
    """适配器当前不可用（未登录、依赖缺失、平台限制等）。"""
