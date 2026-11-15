# 04 · 采集能力核查

审计方式：静态阅读 `Py_Data/` 源码 + 引用同一 commit 运行实例的实测记录
（`AIProjects/DataAnalysis/douyin_opinion_analysis` 与本仓库同为 commit `415027f`）

## 1. 采集脚本清单

| 文件 | 大小 | 职责 |
|---|---|---|
| `Py_Data/douyin_comment.py` | 12.3 KB | 抖音评论采集（DrissionPage 驱动真实 Chrome） |
| `Py_Data/douyin_search.py` | 4.6 KB | 抖音关键词搜索（**未验证**） |
| `Py_Data/config_browser.py` | 3.8 KB | 浏览器连接配置 |
| `Py_Data/verify_region_map.py` | 1.8 KB | 地图着色验证工具（非业务） |
| `Py_Data/clean/clean_main.py` | 11.1 KB | 清洗主流程 |
| `Py_Data/clean/wordcloud_service.py` | 24.7 KB | Flask 服务：分词、话题、AI 分析、报告渲染 |
| `Py_Data/clean/analysis_engine.py` | 4.6 KB | 分析引擎 |
| `Py_Data/clean/wordcloud_gen.py` | 2.3 KB | 词云生成 |

## 2. 抖音评论采集实现（已实现，有实测）

**技术路径**：DrissionPage → `ChromiumPage` → 真实浏览器 → 监听接口响应 → 提取评论。
不使用 `a_bogus` 签名（全项目 0 命中），依靠真实浏览器自动完成签名。

关键函数：

| 函数 | 作用 |
|---|---|
| `extract_video_id(raw)` | 从分享口令 / 短链 / 完整 URL 中提取 aweme_id |
| `preflight_check(dp)` | 采集前检查评论区滚动容器是否就绪（有明确成功提示） |
| `scroll_comment_panel(dp)` | 滚动评论区触发加载（每次 800px） |
| `capture_video_meta(dp, video_id)` | 抓取视频元数据 |
| `to_row(c, parent_cid, is_reply)` | 单条评论转行，保留 `cid` 与父评论关系 |
| `fingerprint(nickname, dt, content)` | 无 cid 时的本地指纹兜底 |
| `load_existing_cids(csv_path)` | 读历史 cid，避免重复采集 |
| `save_progress(...)` | 断点续采 |

**分页**：靠滚动触发，`MAX_COMMENTS` 默认 300（可由命令行第二参数覆盖），
滚动容器 `scrollHeight` 逼近 `clientHeight` 时停止。

**楼中楼**：`reply_comment` 在 1 个文件中出现，`cid`/`cursor` 各 3 个文件——结构上支持，但覆盖面有限。

## 3. 实测结论（引用运行实例，非本次代码静态推断）

| 项 | 结论 | 强度 |
|---|---|---|
| 评论 cid | 抖音接口**确实返回 cid**，19/19 命中，去重策略落 `CID` | 实测 |
| 二次采集幂等 | 靠 `uk_platform_cid` + `uk_platform_dedup` 双键，未产生重复 | 实测 |
| 匿名态产量 | 约 **3 页 / 15–20 条**，之后不再加载 | 实测 |
| 楼中楼 | 有效，可抓到 `reply_comment` 子评论 | 实测 |
| 登录态可复用 | 容器内 Chrome 用户目录（`chrome-data` 卷）保留登录状态 | 实测 |
| AI 链路 | 评论级 19/19 成功、视频洞察 26 条证据、报告 PDF/XLSX 正常 | 实测 |

## 4. 未验证 / 有风险的能力

| 能力 | 状态 | 说明 |
|---|---|---|
| **关键词搜索**（`douyin_search.py`） | **未验证** | 代码存在（`extract_from_response` + `fallback_dom` 双策略），但从未跑通；抖音搜索风控强度未知。**不能计入已实现能力。** |
| 全量评论采集 | **受限** | 匿名态 15–20 条即止；登录态上限未知（需实测） |
| 搜索分页 | 未验证 | 同上 |
| 图片/图集内容 | 未验证 | 现有链路面向视频评论 |
| 采集频率耐受度 | 未知 | 无长期运行数据，无法判断是否会触发风控 |

## 5. 与目标架构的差距

目标架构要求每个平台实现统一 Adapter 契约：

```
validate_config / discover / collect_content / collect_comments / normalize / health_check
```

现状：

- **没有任何 Adapter 抽象层**。`douyin_comment.py` 是独立脚本，参数通过命令行传入，
  由 Java 侧以子进程方式调用。
- **`normalize` 缺失**：清洗结果为 CSV，由 Java 侧 `CleanService` 再入库，
  转换逻辑分散在 Python 与 Java 两侧。
- **无 `health_check`**：Flask 只有 `/health`（进程存活），不代表平台可用。
- **无能力状态机**（Available / Limited / Manual / Disabled / Pending）。

## 6. 合规边界（必须写入实现约束）

- 不绕过登录限制、验证码、反爬措施
- 不采集手机号、私人联系方式等敏感个人信息
- 关键词搜索在验证合规性之前**不得对外宣称「支持全量搜索」**
- 采集数据必须保留来源 URL、采集时间、采集方式

## 7. 结论

**抖音评论采集是本项目最成熟、最有复用价值的部分**（唯一有实测数据支撑的链路）。
改造时应把它封装成 `DouyinAdapter`，而不是重写。

关键词搜索是**空白区**，不能作为 P1 的既定交付物排期——需要先做一次独立的可行性验证，
把结果单列成报告，再决定是否纳入路线图。
