# 三项收尾：关键词搜索验证 / 分层健康检查 / 空内容回复

> 基线：`5fe7709`
> 顺序按裁决："先做第三个（关键词搜索），再做其它两个"

---

## 一、关键词搜索（第三个）：验证尝试**失败**，功能保持关闭

### 1.1 做了什么

`KEYWORD_SEARCH` 一直停在 `UNVERIFIED`，根因之一是**它根本没有验证器**——
`tools/verify_capability.py` 的 `VERIFIERS` 里只有 COMMENT / COMMENT_REPLY / CONTENT_DETAIL，
没有验证器的能力永远无法被提升。

本轮补上了验证器（`_verify_keyword_search`，命令行加 `--keyword`），
并在容器内真实跑了一次：

```
[verify] 验证 DOUYIN_PUBLIC/KEYWORD_SEARCH …
[search] 已打开搜索页: 足球
[search] 共发现 0 个视频
[verify]   结果：未通过 —— 关键词「足球」未搜到任何带 awemeId 的结果
```

### 1.2 为什么没通过（不是代码问题）

单独打开搜索页做了诊断：

```
URL   : https://www.douyin.com/search/%E8%B6%B3%E7%90%83?type=video
TITLE : 验证码中间页
HTML  : 8628 chars
命中: 验证 / captcha
video links: 0
```

**平台返回的是验证码中间页**，匿名访问被风控拦下，页面里连一个 `/video/` 链接都没有。
`run_search` 的接口监听与 DOM 兜底都拿到 0 条，属于预期结果。

### 1.3 结论

- 关键词语义**无法在匿名态验证**；要验证得用登录态（真实账号），本轮不做；
- 能力**保持 `UNVERIFIED`**，准入闸门继续拒绝 → **关键词发现功能仍然不可用**；
- 没有为了让功能"看起来能用"而降低验证标准（例如"有响应即通过"）；
- 验证器与 CLI 参数已留下，等有登录态/风控窗口时可重跑。

**也就是说：这一项不是"做了"，是"确认了现在做不了，并留下了可复跑的入口"。**

---

## 二、分层健康检查（SOURCE-05）

### 2.1 交付

| 位置 | 内容 |
|---|---|
| `system/application/LayeredHealthService` | 逐层探测：backend / MySQL / collection(Registry 快照) / AI |
| `system/controller/SystemHealthController` | `GET /api/system/health/layers`（`@RequireRole`，需登录） |
| `frontend/views/OverviewView.vue` | 总览页新增「系统状态」卡片：状态灯 + 耗时 + 原因，可手动刷新 |
| `frontend/api` | 新增 `systemApi.healthLayers()` |

### 2.2 设计取舍

- **与 `/api/health` 分开**：容器探活不能带 Token，只回"进程 + DB"；分层结果包含内部服务名与错误细节，必须登录才能看；
- **AI 失败判 `DEGRADED` 而不是 `DOWN`**：没有 AI 时分析/报告降级，但采集与浏览仍可用，判 DOWN 会让人以为整个平台挂了；
- **DB / collection 失败判 `DOWN`**，总体状态随之降级；
- 每层都返回 `latencyMs` 与 `detail`——"UP" 不带耗时等于没信息，排查时看不出是快还是勉强。

### 2.3 运行时实测

```
overall: UP
  backend      UP   0ms   进程存活
  db           UP   1ms   SELECT 1 通过
  collection   UP 143ms   快照可用，接入 1 个，能力声明 4 条
  ai           UP   4ms   健康端点返回 200
```

前端构建通过，产物中确认包含「系统状态」卡片。

---

## 三、空内容回复（第三个难题的根）

### 3.1 根因（两层过滤，不只一层）

| 位置 | 旧行为 |
|---|---|
| `clean.py` 过滤 | `昵称 and 内容` 都非空才保留 |
| `clean.py` 返回前 | 再做一次 `if r['内容']` 过滤 |

平台会返回**只有表情/图片的楼中楼回复**：文本为空、但 `cid` 存在、回复数 ≥1。
被两层过滤丢掉后 → 永远不入库 → 采集侧每次都把它当"新增"重新抓一遍 →
**U1 的分页去重永远收敛不到 `collected=0`**。

### 3.2 修复

- 抽出 `ai-service/app/rows.py` 的 `keep_row()`：昵称必填；**内容为空但有 cid 就保留**
  （证明是平台实体，不是解析脏行）；无 cid 又无内容的仍然丢弃；
- `clean_data` 去掉二次过滤，改为同一规则；
- 空内容的情感基线直接记 `neutral`，不把空串送进分词器。

### 3.3 验证（核心验收终于达成）

```
第一次采集：collected=1 → 清洗入库 → DB 20 → 21
第二次采集：status=SUCCEEDED  collected=0  pages=3  dedupSeen=21
          log: 去重记忆（接口）：total=21 pages=1 cid=21 指纹=0
          DB 仍为 21，item SUCCESS
```

**"第二次采集 collected == 0" 这个判据，在修掉空内容过滤后第一次真正成立。**
此前它不成立不是去重算法的问题，而是"采到却入不了库"——两件事被混在一起了。

---

## 四、测试与回归

| 套件 | 结果 |
|---|---|
| Java 全量 | **353 / 353** |
| Python collection-service | **121 passed** |
| Python ai-service | **9 passed**（新增 `test_rows.py` 5 条） |
| 回归脚本 | **12 / 12** |
| 前端构建 | 通过（19.2s） |

新增测试：
- `test_rows.py`：正常行 / 空内容带 cid / 空内容无 cid / 缺昵称 / None 值；
- 关键词验证器无单测（它必须打真实平台，给不了稳定的断言）——这是刻意的，避免写一条假装验过的测试。

---

## 五、变更范围

未触碰：SOURCE-02 契约与枚举、`EffectiveCapability` 推导、Task/Run 状态机、Flyway/DB Schema、
`collector.py`（证据绑定，未改）、U2/U4 的实现。

数据：本轮真实采集 3 次，评论数 20 → 21（新增的正是那条空内容楼中楼）；
临时管理员账号已停用（未删除，受外键约束）。

---

## 六、遗留

| 项 | 说明 |
|---|---|
| 关键词搜索 | 需登录态或风控窗口才能验证；在此之前功能不可用（预期） |
| 空内容评论的展示 | 入库后评论列表会出现一条空白内容行；是否需要前端特殊标记，属产品决策 |
| SOURCE-05 的健康历史 | 目前只有"当前状态"，没有趋势/告警；要做需另立卡 |
| U2 清洗结构化摘要 | 未做（`output` 仍回传原文，F6 遗留） |
