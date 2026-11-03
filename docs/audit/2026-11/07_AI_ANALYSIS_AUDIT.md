# 07 · AI_ANALYSIS_AUDIT

> AI 分析模块专项审计（情感分析 + 核心话题 + DeepSeek 接入）

---

## 1. 情感分析（7.1）

### 1.1 实现方式

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 使用什么算法 | **SnowNLP**（贝叶斯分类器） | A |
| 是否使用情感词典 | ❌ **未使用**（词典文件存在但无任何引用） | D |
| 是否使用 jieba | ❌ 情感分析不用 jieba（词云用） | A |
| 是否调用大语言模型 | ❌ 纯本地模型 | A |
| 分类规则 | `score > 0.6 → 1`；`score < 0.4 → -1`；否则 0 | A |
| 得分如何计算 | `SnowNLP(text).sentiments`，内置贝叶斯模型对文本做分词后查词频表 | A |
| 结果如何存储 | `comment.sentiment` tinyint（-1/0/1） | A |
| 是否支持重新分析 | ⚠️ 只能整体重跑清洗（会重算 sentiment）；**无单条重判接口** | C |
| 是否存在分析版本 | ❌ 无（算法变更后无法区分历史数据由哪版算法产出） | D |

### 1.2 核心代码

```python
# Py_Data/clean/analysis_engine.py
from snownlp import SnowNLP

class SentimentAnalyzer:
    def analyze(self, text):
        if not text or not text.strip(): return 0
        try: score = SnowNLP(text.strip()).sentiments
        except Exception: return 0
        if score > 0.6:   return 1     # 积极
        elif score < 0.4: return -1    # 消极
        return 0                        # 中性
```

### 1.3 词典资产未被使用（重要发现）

```
Py_Data/clean/sentiment_dict/positive_words.txt   254 词
Py_Data/clean/sentiment_dict/negative_words.txt   275 词
```

**全项目检索确认：`sentiment_dict/` 目录无任何代码引用。**

而 README 写有「**情感分析引擎：基于情感词典对每条评论做积极/中性/消极判定**」——
**与实现不符**。实际算法是 SnowNLP，词典是死代码。

### 1.4 准确性评估

**抽样人工核对 14 条 → 约 9 条存在可疑标注**（详见 `06_DATA_QUALITY_AUDIT.md` 第 3 节）。

| 问题类型 | 示例 | 根因 |
|---------|------|------|
| 纯 @提及 → 积极 | `@中国足协` | 短文本无情感词时 SnowNLP 输出偏正 |
| 疑问句 → 积极 | `明年还来大连吗？` | 无疑问句专门处理 |
| 负面诉求 → 积极 | `必须重罚` | 无反讽/意图识别 |

**根因**：
1. SnowNLP 默认模型基于**购物评论**语料训练，非抖音语料（游戏、球赛、网络梗）
2. 阈值 0.4/0.6 硬编码，未用本项目数据调优
3. 已有 529 词领域词典未被利用
4. 无标注集 → 准确率不可度量、不可回归

**等级：C（部分实现，存在明显限制）**

### 1.5 短视频与网络用语场景的先天不足

SnowNLP 对以下场景**结构性地**不适用：

| 场景 | 问题 |
|------|------|
| 超短评论（<5 字） | 分词后词频表命中稀疏，输出接近随机 |
| emoji / 颜文字 | 未剥离即分词，噪声大 |
| 谐音梗、缩写（"yyds"、"6"） | 词典未覆盖 |
| 方言（"靓""得劲"） | 词典未覆盖 |
| 反讽（"真棒啊，输得漂亮"） | 贝叶斯模型无情感极性反转识别 |

> 注：`clean_main.py:66` 会剥离 `[哭哭]` 这类**方括号**标签，但**不剥离 Unicode emoji**（`😂`），
> 而抖音评论中 emoji 占比很高。

---

## 2. 核心话题分析（7.2）

### 2.1 结论总览

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 输入数据来源 | `computeTopicDims()` 计算的 5 维度结构化数据 | A |
| 评论数量限制 | ❌ **无限制**（传入全部评论的聚合统计，非原文） | A |
| 评论预处理 | 文本经 `clean_main` 剥离方括号标签 | A |
| Prompt 设计 | 见 2.3，质量较高 | A |
| DeepSeek 调用方式 | OpenAI SDK 兼容模式，`base_url=https://api.deepseek.com` | A |
| 模型 | `deepseek-chat` | A |
| 输出数据结构 | 纯 Markdown 文本（`jsonify({"code":0,"data":text,"msg":"ok"})`） | A |
| 是否进行 JSON 解析 | ❌ **不做**，直接返回 Markdown 字符串 | A |
| 是否有格式校验 | ❌ 无 | D |
| 是否支持失败重试 | ❌ 无 | D |
| 是否有结果缓存 | ✅ 有（Java 侧 `MemoryCache` 30 分钟 + `analysis_result` 持久化） | A |

### 2.2 输入的 5 个维度

`AnalysisServiceImpl.computeTopicDims()`（`:384-460`）：

| 维度 | 计算方式 | 等级 |
|------|---------|------|
| 关键词 | 调 `getWordCloud()`（经 Flask jieba 分词），取 Top 15 | A |
| 句子结构 | 正则/关键词匹配统计疑问/感叹/陈述/祈使句 | A |
| 情感语境 | 调 `getSentimentStats()`，含主导情感 + 前后半段积极率趋势 | A |
| 话题共现 | 基于关键词的共现组合统计 | A |
| 用户动机 | 基于评论内容的启发式模式匹配 | A |
| 地区分布 | `getRegionStats()` | A |
| 汇总指标 | totalComments / totalUsers / totalLikes | A |

**重要设计特征**：传给 LLM 的**不是评论原文**，而是**聚合后的统计特征**。
这降低了 token 成本与隐私风险，但也意味着：
- LLM 无法发现统计特征之外的细节（如具体的产品诉求、具体竞品名）
- 无法做逐条评论级的需求提取

### 2.3 Prompt 设计评估

**System 角色**（`wordcloud_service.py:141`）：
```
你是一个抖音评论区数据分析师。输出格式强制规则：
- 不要使用 # 标题，一级标题用 **粗标题 + Emoji**
- 二级用 1. 2. 数字编号
- 用 - 列表组织细分内容
- **加粗**仅用于关键结论或风险标签，禁止整段加粗
- 各模块之间仅空一行，模块内紧凑不空行
- 不要使用 --- 分隔线
- 语言简洁自然，不罗列 JSON 原始数据
```

**User 角色**：结构化数据 + 强制输出模板（数据概览 / 核心话题分析 / 总结与运营建议）

**评估**：

| 维度 | 评价 |
|------|------|
| 角色设定 | ✅ 清晰 |
| 输出格式约束 | ✅ 详细，可减少后处理 |
| 数据注入 | ✅ 结构化，token 可控 |
| 缺陷 | ❌ **无少样本示例（few-shot）**，格式遵从率依赖模型自身能力 |
| 缺陷 | ❌ **无 JSON Schema 约束**，只能靠自然语言描述格式 |
| 缺陷 | ⚠️ 模板固定为"数据概览/核心话题/建议"，**无法产出"用户痛点""购买意向"等新维度** |

### 2.4 输出处理与容错

**无 JSON 解析、无格式校验**——这是**合理的选择**（当前目标就是 Markdown 报告）。

**降级机制**（三级，设计良好）：

```java
// AnalysisServiceImpl:332-341
String agentReport = callTopicAgent(dims);
if (agentReport != null) { return agentReport; }
// 降级：AI 不可用时用旧版逻辑
String fallback = buildFallbackReport(dims);
```

| 层级 | 触发条件 | 处理 |
|------|---------|------|
| 1. 正常 | DeepSeek 返回 | Markdown 报告 |
| 2. 降级 | API 异常/无 Key/超时 | `buildFallbackReport()` 统计报告 |
| 3. 空 | 评论数为 0 | 返回空字符串 |

**实测**：本次会话中 AI 报告成功生成（"共14条评论，好评率50%…"），降级路径未触发（未做故障注入测试）。

### 2.5 缓存策略

| 层 | 位置 | TTL | 问题 |
|----|------|-----|------|
| Java 内存 | `MemoryCache` | 30 分钟 | 重启丢失 |
| 持久化 | `analysis_result` 表 | 永久 | 但 `getTopic` 未从该表读，只读内存缓存 |

**问题**：`refreshAnalysis` 会把统计写入 `analysis_result` 表，但**不写 topic 文本**（表无该列）。
AI 报告只有 30 分钟内存缓存，**容器重启后需重新付费调用 DeepSeek**。

---

## 3. AI 服务架构（7.3）

### 3.1 调用链

```
浏览器 GET /api/analysis/topic?videoId=X
   │
   ▼
AnalysisServiceImpl.getTopic(videoId)            :307
   │  ├─ MemoryCache 命中？→ 直接返回
   │  └─ 未命中 ↓
   │
   ├─ commentMapper.selectList()                  查评论
   ├─ computeTopicDims(all, videoId)              :384  算 5 维度
   │    └─ getWordCloud() → HTTP → Flask /wordcloud → jieba
   │    └─ getSentimentStats() → 本地计算
   │
   ├─ callTopicAgent(dims)                        :349
   │    └─ HttpURLConnection POST http://flask:5000/analyze_topic
   │         connectTimeout=5000ms, readTimeout=30000ms
   │              │
   │              ▼
   │         Flask: analyze_topic()
   │              ├─ get_deepseek_client()  (OpenAI SDK)
   │              ├─ build_topic_prompt(data)  组装 prompt
   │              └─ client.chat.completions.create(
   │                    model="deepseek-chat", temperature=0.7,
   │                    max_tokens=2000, timeout=30)
   │                       │
   │                       ▼ HTTPS api.deepseek.com
   │                   DeepSeek API
   │
   └─ cache.put(cacheKey, report, 30min)
```

### 3.2 关键参数

| 参数 | 值 | 位置 | 评价 |
|------|-----|------|------|
| Java→Flask connectTimeout | 5000 ms | `AnalysisServiceImpl:355` | 合理 |
| Java→Flask readTimeout | 30000 ms | `:356` | ⚠️ 与 Flask 侧 DeepSeek timeout=30s **相同**，无余量 |
| Flask→DeepSeek timeout | 30 s | `wordcloud_service.py:146` | 合理 |
| model | deepseek-chat | `:139` | 通用 |
| temperature | 0.7 | `:144` | 偏 creativity，报告类任务建议 0.3~0.5 |
| max_tokens | 2000 | `:145` | 约够 1500 中文字 |

### 3.3 API Key 管理

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 是否硬编码 | ❌ 不硬编码 | A |
| 存储位置 | 环境变量 `DEEPSEEK_API_KEY`（`docker-compose.yml:61`） | A |
| 本地开发回退 | `load_dotenv()` 读 `Py_Data/clean/.env` | A |
| 缺失时行为 | 打印 WARN，AI 不可用 → 走降级报告 | A |
| 前端是否暴露 | ❌ 不暴露 | A |
| 日志是否打印 | ✅ 只打印「未设置」提示，不打印 Key 本身 | A |

**风险**：`.env` 文件若被提交到 Git 会泄露 Key。
**现状**：`.gitignore` 已包含 `.env`（本次审计前新增），仓库中 `Py_Data/clean/.env` 已被删除（提交 `777e6c3`）。

### 3.4 异常处理

```java
// AnalysisServiceImpl:349-380
try {
    HttpURLConnection conn = ...;
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(30000);
    ...
    if (conn.getResponseCode() == 200) { ...返回报告... }
    return null;                       // 非 200 → null → 降级
} catch (Exception e) {
    log.warn(...);
    return null;                       // 异常 → null → 降级
}
```

| 异常 | 处理 | 等级 |
|------|------|------|
| 连接失败 | catch → 返回 null → 降级 | A |
| 非 200 | 返回 null → 降级 | A |
| 读取超时 | catch → null → 降级 | A |
| 响应体解析失败 | catch → null → 降级 | A |
| DeepSeek 侧异常 | Flask catch → `{"code":1,"msg":...}` → Java 视为非成功 → 降级 | A |
| 无 API Key | Flask 返回 code:1 → 降级 | A |

**容错设计完整**：任何环节失败都不会导致 500，均平滑降级。

### 3.5 关键缺口

| 缺口 | 影响 | 等级 |
|------|------|------|
| **无 Token 消耗统计** | 无法知道成本，无法做预算 | D |
| **无请求日志**（只记 warn） | 无法排查 DeepSeek 侧问题（限流/欠费） | C |
| **无响应持久化** | 报告只在内存 30 分钟；重启丢失需重新付费 | D |
| **无重试** | 网络抖动即降级，不重试 | D |
| **无并发控制** | 多个用户同时请求 topic → 并发调 DeepSeek | C |
| **无熔断** | DeepSeek 挂掉时每个请求都等 30 秒超时 | D |
| **阻塞 Tomcat 线程** | 同步 HttpURLConnection 占用工作线程 30 秒 | C |
| **无 prompt 版本管理** | 改 prompt 后无法对比效果 | D |

### 3.6 并发与成本风险

**并发场景**：N 个用户同时点开不同视频的「核心话题」

- 每个请求 → 一次 Java→Flask HTTP → 一次 DeepSeek 调用
- Flask 的 `get_deepseek_client()` 是**全局单例**（`global _DEEPSEEK_CLIENT`），客户端复用 ✅
- 但 **DeepSeek 调用本身无并发限制**，N 个请求 = N 个并发 API 调用
- 每请求最多 2000 tokens；若 100 个并发 → 200K tokens

**建议**：Flask 侧加 `threading.Semaphore(2~3)` 限流 + 缓存层（Redis 或本地 TTL 缓存）。

---

## 4. 当前 AI 能力的准确定位

### 4.1 现有能力边界

| 能力 | 现状 | 可支撑的下阶段目标 |
|------|------|--------------|
| 情感三分类 | ✅ 有（准确率约 60~70%，需优化） | 情感分布、情感趋势 |
| 关键词提取 | ✅ 有（jieba，Top 100） | 主题聚类的输入 |
| 统计报告生成 | ✅ 有（DeepSeek，5 维度） | 视频级分析报告 |
| 用户需求识别 | ❌ 无（当前 prompt 无此维度） | P1 目标 |
| 用户痛点提取 | ❌ 无 | P1 目标 |
| 购买意向识别 | ❌ 无 | P1 目标 |
| 产品优缺点分析 | ❌ 无 | P1 目标 |
| 竞品对比 | ❌ 无（无视频元数据，无法识别竞品） | P2 目标 |
| 行业热点 | ❌ 无（无关键词/时间序列聚合） | P2 目标 |
| 主题聚类 | ❌ 无（仅关键词频次，非聚类） | P1 目标 |
| 多视频联合分析 | ❌ 无（接口全部按单 videoId） | P1 目标 |

### 4.2 与目标定位的差距

目标定位是「**AI 驱动的社交媒体评论采集与用户需求分析平台**」。

当前 AI 层实际是「**统计特征 → LLM → 统计报告**」，本质是**数据摘要生成器**，
距离「用户需求分析」还差：

| 差距 | 性质 |
|------|------|
| LLM 没看到评论原文 | **根本性限制**：只能看到聚合统计，无法做需求/痛点提取 |
| 输出是 Markdown 报告 | 无法被程序消费（无法做二次分析、聚合、对比） |
| 单视频视角 | 无法做跨视频对比与行业聚合 |
| 无关键词/视频实体 | 无法做竞品识别 |

**这是本次审计对下一阶段最重要的判断**：
要支撑「用户需求识别 / 痛点提取 / 购买意向识别」，
**必须让 LLM 看到评论原文（或至少原文片段）**，并把输出从 Markdown 改为结构化 JSON。

---

## 5. 证据索引

| 关注点 | 位置 |
|-------|------|
| 情感算法 | `Py_Data/clean/analysis_engine.py` |
| 未使用的词典 | `Py_Data/clean/sentiment_dict/*.txt`（无引用） |
| 5 维度计算 | `AnalysisServiceImpl.computeTopicDims()` :384 |
| Prompt 构建 | `wordcloud_service.py:build_topic_prompt()` :36 |
| DeepSeek 调用 | `wordcloud_service.py:138` |
| Java→Flask 调用 | `AnalysisServiceImpl.callTopicAgent()` :349 |
| 降级逻辑 | `AnalysisServiceImpl:332-341` + `buildFallbackReport()` |
| 缓存 | `MemoryCache`（30min）、`analysis_result` 表 |
