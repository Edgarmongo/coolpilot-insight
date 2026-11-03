# 16 · DEVELOPMENT_TASKS

> 后续开发任务卡（可直接转为需求单 / Issue）
> 每张卡包含 12 项要素：编号、名称、优先级、现状、问题、涉及文件、数据表、实现方案、前置依赖、验收标准、风险、工作量

---

## 任务卡索引

| 编号 | 名称 | 优先级 | 依赖 | 工作量 |
|------|------|-------|------|-------|
| [T-01](#t-01) | 采集数据流式落盘 | P0 | — | 0.5~1d |
| [T-02](#t-02) | 路径穿越与越权修复 | P0 | — | 1d |
| [T-03](#t-03) | 评论唯一 ID 与唯一索引 | P0 | — | 1~2d |
| [T-04](#t-04) | 采集任务表与状态持久化 | P0 | — | 2~3d |
| [T-05](#t-05) | 任务去重与并发管控 | P0 | T-04 | 1~2d |
| [T-06](#t-06) | video 表与回复字段 | P0 | T-03 | 3~4d |
| [T-07](#t-07) | 情感分析准确率提升 | P0 | — | 2~3d |
| [T-08](#t-08) | 采集增量与断点续采 | P0 | T-03,T-04 | 2~3d |
| [T-09](#t-09) | 评论级结构化分析 | P1 | T-03 | 4~6d |
| [T-10](#t-10) | 视频级结构化洞察 | P1 | T-09 | 5~8d |
| [T-11](#t-11) | 多视频批量采集 | P1 | T-04,T-05 | 3~4d |
| [T-12](#t-12) | 多视频联合分析 | P1 | T-06,T-10 | 4~5d |
| [T-13](#t-13) | AI 成本与并发管控 | P1 | T-10 | 2d |
| [T-14](#t-14) | 关键词管理与视频发现 | P2 | T-04,T-06 | 6~8d |
| [T-15](#t-15) | 采集任务中心前端 | P2 | T-04,T-05 | 4~5d |
| [T-16](#t-16) | 行业热点与趋势分析 | P2 | T-12,T-14 | 5~7d |
| [T-17](#t-17) | 竞品分析 | P2 | T-09,T-12 | 6~8d |
| [T-18](#t-18) | 报告中心与导出 | P2 | T-10 | 4~5d |

---

## T-01

### 采集数据流式落盘

| 项 | 内容 |
|----|------|
| **编号** | T-01 |
| **名称** | 采集数据流式落盘（消除中断全丢） |
| **优先级** | P0 |
| **现状** | `douyin_comment.py` 将全部评论累积在内存 `data_list`，循环结束后一次性 `open(csv_path,"w")` 写入 |
| **存在的问题** | 实测采集中 `kill -9` 后，已采集的 10 条评论**全部丢失**，CSV 为空 |
| **涉及文件** | `Py_Data/douyin_comment.py`（`data_list` 累积逻辑 + 写文件段） |
| **涉及数据表** | 无 |
| **技术实现方案** | ① 采集循环内每页结束即 `open(csv_path,'a',encoding='utf-8-sig')` 追加 ② 首行写表头（判断文件是否已存在且非空）③ 写前用集合过滤已写内容，防重复 ④ 追加 `.progress` 记录已采页数与 cursor，供断点续采（T-08 使用） |
| **前置依赖** | 无 |
| **验收标准** | ① 采集中 kill -9，CSV 中保留已采页的数据（≥1 页 = 5 条）② 重跑同一视频时 CSV 内无重复行 ③ 正常结束时 CSV 内容与原实现一致（条数、字段、顺序）④ 连续两次采集不产生 `UnicodeDecodeError`（utf-8-sig 追加场景） |
| **风险评估** | **低**。改动局限在文件写入逻辑，不涉及架构。需注意 `utf-8-sig` 追加写时 BOM 只应在首行 |
| **工作量估算** | **0.5~1 人日** |

> **建议第一个实施**：改动最小、收益最直接，直接消除"采集中断数据全丢"。

---

## T-02

### 路径穿越与越权修复

| 项 | 内容 |
|----|------|
| **编号** | T-02 |
| **名称** | 文件路径穿越与跨用户数据越权修复 |
| **优先级** | P0 |
| **现状** | `CollectController.upload()` 用 `new File(datasDir, name)`（name 来自前端）；`CleanServiceImpl.exportFiles()` 同理；`getPendingFiles()` 不做用户过滤 |
| **存在的问题** | ① 文件名含 `../` 可读写容器内 `Datas` 目录外的任意文件 ② 任何登录用户可导出**其他用户**采集的原始 CSV（含他人昵称/抖音号/地区）③ `/api/user/info` 返回 `password` 字段（BCrypt 哈希） |
| **涉及文件** | `dy-backend/.../controller/CollectController.java`、`.../service/impl/CleanServiceImpl.java`、`.../controller/UserController.java` |
| **涉及数据表** | 无（`getPendingFiles` 需引入 user_id 过滤，依赖 `comment.user_id` 已有的语义；CSV 文件本身无归属信息，见风险） |
| **技术实现方案** | ① 新增工具方法 `resolveSafePath(File base, String name)`：`getCanonicalPath()` 后校验 `startsWith(base.getCanonicalPath()+File.separator)` ② upload 与 export 全部走此方法 ③ `exportFiles` 增加 `ownerId` 参数，只导出该用户采集的文件（CSV 命名加 user 前缀或建立 `csv_file` 台账表）④ `UserController.info()` 剔除 password 字段 |
| **前置依赖** | 无 |
| **验收标准** | ① `../../etc/passwd`、`..\windows\system32` 等文件名被拒绝且返回明确错误 ② 用户 A 无法导出用户 B 的 CSV ③ `/api/user/info` 响应中无 `password` ④ 正常文件名不受影响 |
| **风险评估** | **低**。但需注意 CSV 文件的**归属追踪**：当前 CSV 文件名仅为 `{videoId}.csv`，无用户维度。要精确实现②，需在文件名加 userId 或建台账表（**建议建 `csv_file` 表或改名为 `{userId}_{videoId}.csv`**） |
| **工作量估算** | **1 人日**（若含归属改造则 1.5 人日） |

---

## T-03

### 评论唯一 ID 与唯一索引

| 项 | 内容 |
|----|------|
| **编号** | T-03 |
| **名称** | 评论唯一 ID（cid）与数据库唯一索引 |
| **优先级** | P0 |
| **现状** | `comment` 表无 `cid`；去重依赖 `(昵称, 内容)` 内容比对；接口返回的 `cid` 字段被丢弃 |
| **存在的问题** | ① 无法精确去重 ② 无法识别评论更新与新增 ③ 「先查后插」非原子，两进程并发清洗同一视频存在竞态插入重复 ④ 无法做增量采集 ⑤ 未来做评论级分析时无法溯源到具体评论 |
| **涉及文件** | `Py_Data/douyin_comment.py`（采集）、`Py_Data/clean/clean_main.py`（入库）、`dy-backend/.../entity/Comment.java`、`.../service/impl/AnalysisServiceImpl.java`（TopComments 若要带 cid） |
| **涉及数据表** | `comment`：`ADD COLUMN cid VARCHAR(32) NULL`、`ADD UNIQUE KEY uk_cid (cid)` |
| **技术实现方案** | ① 采集时读取接口 `comment["cid"]` 写入 CSV 新列 ② 入库改 `INSERT IGNORE INTO comment ...`（MySQL 唯一索引冲突时忽略）③ 去重键升级：`cid` 非空时以 cid 为准，否则回退 `(昵称, 内容)` ④ 后端实体加 `cid` 字段 ⑤ 旧数据 cid 为 NULL 不影响唯一索引（MySQL 允许多个 NULL） |
| **前置依赖** | 无。**建议先做一次抽验**：用真实抖音数据确认 `cid` 字段稳定唯一（不同评论不重复、同一评论多次请求一致） |
| **验收标准** | ① 采集 CSV 含 `cid` 列，非空率 > 99% ② `comment` 表存在 `uk_cid` ③ 重复采集同一视频入库条数不增加 ④ 两进程并发清洗同一视频，最终无重复行 ⑤ 旧数据（cid=NULL）不受影响 |
| **风险评估** | **中**。① `ALTER TABLE` 需停机窗口或在线 DDL ② 需确认 cid 稳定性（抖音可能变更）③ 若 cid 存在重复（极端情况），唯一索引创建会失败 |
| **工作量估算** | **1~2 人日** |

---

## T-04

### 采集任务表与状态持久化

| 项 | 内容 |
|----|------|
| **编号** | T-04 |
| **名称** | 采集任务表（collect_task）与状态持久化 |
| **优先级** | P0 |
| **现状** | `CollectServiceImpl` 用 `static final ConcurrentHashMap<String,String> STATUS_MAP` 保存状态；key 为 videoId，value 为状态字符串 |
| **存在的问题** | ① 容器重启/重新部署 → 全部状态丢失 ② key 为 videoId → 同一视频多次任务状态互相覆盖，无法区分任务实例 ③ 无时间戳、无进度、无失败原因分类 ④ 无历史可查，无法统计成功率与耗时 ⑤ 不支持暂停/恢复/重试/取消 |
| **涉及文件** | 新建 `entity/CollectTask.java`、`mapper/CollectTaskMapper.java`、`service/CollectTaskService.java`、`service/impl/CollectTaskServiceImpl.java`；改 `CollectServiceImpl`、`CollectController`、`CollectView.vue` |
| **涉及数据表** | 新建 `collect_task`（DDL 见 `04_TASK_CENTER_AUDIT.md` 5.1，含 status/task_no/collected/cursor/started_at/finished_at/error_msg/retry_count） |
| **技术实现方案** | ① 任务创建时落库并生成 `task_no` ② 状态流转时更新表，`MemoryCache` 降级为纯加速层 ③ `getStatus` 改为查表，兼容旧的 videoId 查询（查该视频最近任务）④ 新增任务列表/详情 API ⑤ 采集脚本每完成一页回调上报进度（或 Java 侧解析 stdout 提取） |
| **前置依赖** | 无 |
| **验收标准** | ① 创建任务后重启容器，任务及状态仍可查 ② 任务列表含 video_id/type/status/开始时间/完成时间/失败原因/已采条数 ③ 可按状态、用户、时间筛选 ④ 同一视频多次任务互不覆盖 ⑤ 旧 `/api/collect/status` 接口保持兼容 |
| **风险评估** | **低**。纯新增表与新接口。需注意 `MemoryCache` 与 DB 的一致性策略（建议 DB 为准） |
| **工作量估算** | **2~3 人日** |

---

## T-05

### 任务去重与并发管控

| 项 | 内容 |
|----|------|
| **编号** | T-05 |
| **名称** | 采集任务去重与并发上限管控 |
| **优先级** | P0 |
| **现状** | `startCollect` 直接 `STATUS_MAP.put(videoId,"RUNNING")` 后 `new Thread().start()`；无状态检查、无线程池 |
| **存在的问题** | 实测连续 3 次 POST 同一视频 → 全部返回 200 → 启动 3 个 Chrome → **2 个失败**（`local variable 'fail' referenced before assignment`，DrissionPage 内部缺陷，由 Chrome profile 争抢触发）；同时 3 个进程写同一 CSV 互相覆盖 |
| **涉及文件** | `dy-backend/.../service/impl/CollectServiceImpl.java`、`.../controller/CollectController.java` |
| **涉及数据表** | `collect_task`（T-04） |
| **技术实现方案** | ① 提交前查 `collect_task` 中同 video_id 的 PENDING/RUNNING 任务 → 存在则返回"任务进行中"（含 task_no）② 用 `ThreadPoolExecutor(core=1, max=2, queue=100)` 替代裸线程，队列满则任务保持 PENDING ③ 若需真正并发：每任务独立 profile 目录 `chrome-data/task-{taskNo}`，并在任务结束时清理 ④ 容器停止时优雅停机（`@PreDestroy` 等待任务完成或标记中断） |
| **前置依赖** | T-04 |
| **验收标准** | ① 重复提交同一视频返回明确提示且不启动第二个任务 ② 并发提交不同视频不出现 profile 冲突失败 ③ 任务线程数有上限 ④ 容器停止时任务被标记为可识别的状态而非永久 RUNNING |
| **风险评估** | **低**。③ 独立 profile 会增加磁盘占用（每个 Chrome profile 约 100MB），需评估清理策略 |
| **工作量估算** | **1~2 人日** |

---

## T-06

### video 表与评论回复字段

| 项 | 内容 |
|----|------|
| **编号** | T-06 |
| **名称** | 视频元数据表与评论回复（楼中楼）字段 |
| **优先级** | P0 |
| **现状** | 3 张表；视频仅作 `comment.video_id` 字符串列，无元数据；无任何回复数据 |
| **存在的问题** | ① 无法存视频标题/作者/点赞/时长 → 无法做视频维度分析 ② 无法采集楼中楼回复 → 用户需求识别不完整（追问、纠错常在回复里）、评论数被低估 ③ `comment.user_id` 无索引 → 普通用户查询全表扫描 ④ 无软删除 → 误删不可恢复 |
| **涉及文件** | `dy_comment.sql`、`Py_Data/douyin_comment.py`（采集回复与视频元数据）、`Py_Data/clean/clean_main.py`、`dy-backend/.../entity/Comment.java` |
| **涉及数据表** | 新建 `video`（DDL 见 `14_TARGET_ARCHITECTURE.md` 4.1）；`comment` 加 `reply_id/parent_cid/reply_count/is_reply`；加 `idx_user_id` |
| **技术实现方案** | ① 新建 `video` 表，`(platform, aweme_id)` 唯一 ② 采集时一并读取视频元数据（接口 `aweme_list`/`aweme_detail`）③ 采集接口的 `reply_comment` 与 `reply_comment_total` 字段，一级评论与回复分层入库（`parent_cid` 指向一级评论）④ 补 `idx_user_id` ⑤ `comment` 表加 `deleted` 软删除列 |
| **前置依赖** | T-03（cid 作为 parent_cid 的引用基础） |
| **验收标准** | ① 视频元数据入库且可通过 video_id 查到 ② 楼中楼回复被采集入库，可按 parent_cid 聚合 ③ `EXPLAIN` 验证普通用户查询走 `idx_user_id` ④ 视频重复采集不产生重复 video 行 |
| **风险评估** | **中**。① 表结构变更需迁移 ② 回复采集会增加请求量，可能提升风控风险 ③ 抖音接口的回复数据结构需实测确认 |
| **工作量估算** | **3~4 人日** |

---

## T-07

### 情感分析准确率提升

| 项 | 内容 |
|----|------|
| **编号** | T-07 |
| **名称** | 情感分析准确率提升与词典启用 |
| **优先级** | P0 |
| **现状** | `analysis_engine.py` 用 SnowNLP 默认模型，阈值硬编码 0.4/0.6；`sentiment_dict/` 下 529 词词典**完全未被引用** |
| **存在的问题** | 抽样 14 条人工核对约 9 条可疑：纯 @提及被判积极、疑问句被判积极、负面诉求被判积极；短评论/emoji/方言/反讽结构性不适用；准确率不可度量、不可回归 |
| **涉及文件** | `Py_Data/clean/analysis_engine.py`、`Py_Data/clean/sentiment_dict/*.txt` |
| **涉及数据表** | `comment` 可加 `sentiment_score DECIMAL(5,4)`（便于复算与调参） |
| **技术实现方案** | ① 启用词典：SnowNLP 分数落在灰区 [0.4,0.6] 时用词典二次判定（词典命中计分）② 阈值移入 `application.yml` 配置 ③ 建立 200~500 条人工标注集（覆盖短评论/emoji/反讽/方言/球赛黑话）④ 新增 `sentiment_score` 列保存原始分 ⑤ 补一个离线评估脚本，用标注集输出准确率报告 |
| **前置依赖** | 无 |
| **验收标准** | ① 标注集准确率 ≥ 80%（当前抽样约 60~70%）② 阈值可通过配置调整无需改代码 ③ 词典参与判定的比例可统计 ④ 评估脚本可重复运行输出准确率 |
| **风险评估** | **低**。主要是标注集制作的工作量（需人工）。若追求更高准确率可考虑中文情感专用模型，但需 GPU 或量化部署（**当前阶段不建议**） |
| **工作量估算** | **2~3 人日**（含标注集） |

---

## T-08

### 采集增量与断点续采

| 项 | 内容 |
|----|------|
| **编号** | T-08 |
| **名称** | 采集增量与断点续采 |
| **优先级** | P0 |
| **现状** | 每次采集从第 1 页重拉 3 页；未使用抖音接口返回的 `cursor`；`analysis_result`/`comment` 无"最后采集时间" |
| **存在的问题** | ① 风控暴露高（每次都全量请求）② **抖音按热度排序，新评论未必出现在前 3 页 → 增量实际不完整** ③ 无法知道上次采到哪 ④ 中断后只能从头再来 |
| **涉及文件** | `Py_Data/douyin_comment.py`、`Py_Data/clean/clean_main.py` |
| **涉及数据表** | `comment`（cid）、`collect_task`（cursor 字段） |
| **技术实现方案** | ① 采集时捕获接口响应的 `cursor` 与 `has_more`，持久化到 `collect_task.cursor` ② 重跑时从该 cursor 继续（需处理 cursor 过期：捕获 4xx/空响应时回退到全量）③ 或按 `publish_time` 过滤已入库评论（需处理时间排序问题）④ 增量结果与全量结果合并入库（靠 cid 去重） |
| **前置依赖** | T-03、T-04、T-01 |
| **验收标准** | ① 同一视频二次采集能拿到上次之后的新评论 ② 二次采集的抖音请求数低于首次 ③ 中断后重跑从断点继续而非从头 ④ cursor 失效时能自动回退且不报错 |
| **风险评估** | **中**。① 抖音 cursor 有效期未知，需实测 ② 若抖音排序变化，基于 cursor 的"增量"可能漏数据，需与 `publish_time` 过滤交叉验证 |
| **工作量估算** | **2~3 人日** |

---

## T-09

### 评论级结构化分析

| 项 | 内容 |
|----|------|
| **编号** | T-09 |
| **名称** | 评论级结构化分析（需求/痛点/意向/主题） |
| **优先级** | P1（**AI 能力分水岭**） |
| **现状** | 仅 SnowNLP 三分类情感；AI 层只消费聚合统计特征，LLM 从未看到评论原文 |
| **存在的问题** | 无法识别用户需求、痛点、购买意向；无法做评论级溯源 |
| **涉及文件** | `Py_Data/clean/wordcloud_service.py`（新增 `/analyze_comments`）、`dy-backend/.../service/impl/AnalysisServiceImpl.java`（新增编排与持久化）、`.../entity/Comment.java` |
| **涉及数据表** | `comment` 加 `analysis_json MEDIUMTEXT`（结构化结果）、`sentiment_score` |
| **技术实现方案** | ① Flask 新端点接收评论数组（10~20 条/批）② 调用 DeepSeek，`temperature=0.1`、`response_format={"type":"json_object"}` 强制 JSON ③ 输出结构：sentiment / intent[] / topics[] / pain_points[] / purchase_intent{level,signals} / user_need / mentioned_products[] ④ Python 侧 `json.loads` + 字段校验，失败重试 1 次 ⑤ Java 侧解析并落 `comment.analysis_json` ⑥ SnowNLP 预筛：仅对灰区样本调 LLM（省 60~70% 成本） |
| **前置依赖** | T-03（cid 用于溯源与去重） |
| **验收标准** | ① 单条评论输出结构化标签且 JSON 解析成功率 ≥ 95% ② 结果可按 topic / intent / pain_point 聚合查询 ③ 每条结果可溯源到具体评论 ④ Token 消耗有统计 ⑤ 有成本护栏（超预算降级） |
| **风险评估** | **中**。① 成本上升（估 150~300 tokens/条，**需按 DeepSeek 实际定价核算**）② LLM 输出格式不稳定 ③ 短文本分类难度高（需与 T-07 的词典互补） |
| **工作量估算** | **4~6 人日** |

---

## T-10

### 视频级结构化洞察

| 项 | 内容 |
|----|------|
| **编号** | T-10 |
| **名称** | 视频级结构化洞察（JSON 输出 + 持久化） |
| **优先级** | P1 |
| **现状** | `/analyze_topic` 输出 Markdown 文本，仅含内存缓存 30 分钟；输入只有聚合统计特征 |
| **存在的问题** | ① LLM 看不到原文 → 无法产出需求/痛点/意向 ② 输出不可程序消费（无法聚合、对比、统计）③ 重启后丢失 → 重复付费 ④ 现有 prompt 模板固定，无法产出新维度 |
| **涉及文件** | `Py_Data/clean/wordcloud_service.py`（新增 `/analyze_insights`）、`dy-backend/.../service/impl/AnalysisServiceImpl.java`、`.../entity/VideoInsight.java` |
| **涉及数据表** | 新建 `video_insight`（DDL 见 14 号文档 4.1，含 content JSON / prompt_version / model / token_used） |
| **技术实现方案** | ① 分批传评论原文（50~100 条/批）② LLM 输出结构化 JSON：`summary` / `user_needs[]`（含 evidence 评论 ID 数组）/ `pain_points[]` / `purchase_intent` / `product_pros[]` / `product_cons[]` / `topics[]` / `risks[]` / `suggestions[]` ③ 多批结果由 Java 侧聚合频次与去重 ④ 结果持久化 `video_insight`，二次查看直接读库 ⑤ 保留现有 Markdown 端点不动（双输出） |
| **前置依赖** | T-09 |
| **验收标准** | ① 输出含需求/痛点/购买意向三类结论 ② 每条结论可溯源到具体评论 ID ③ 二次查看同一视频不重复调用 DeepSeek ④ 结果持久化后容器重启仍可读 ⑤ 单视频分析耗时可接受（**需实测**） |
| **风险评估** | **中**。成本显著上升（估 320 条评论 ≈ 4.8 万 tokens，**需按实际定价核算**）；长上下文可能截断；需分批与结果合并 |
| **工作量估算** | **5~8 人日** |

---

## T-11

### 多视频批量采集

| 项 | 内容 |
|----|------|
| **编号** | T-11 |
| **名称** | 多视频批量采集 |
| **优先级** | P1 |
| **现状** | `CollectRequest.videoId` 为 `String`；实测传数组返回 400（`Cannot deserialize value of type String from Array value`），传逗号分隔返回"无法识别视频编号"；README 却写有"支持批量多视频同时采集" |
| **存在的问题** | 一次任务只能处理一个视频；README 描述与实现不符 |
| **涉及文件** | `dy-backend/.../dto/CollectRequest.java`、`.../controller/CollectController.java`、`.../service/impl/CollectServiceImpl.java`、`dy-frontend/src/views/CollectView.vue`、`dy-frontend/src/api/index.js` |
| **涉及数据表** | `collect_task`（新增 `video_ids` TEXT JSON 字段） |
| **技术实现方案** | ① DTO 增加 `List<String> videoIds`，保留 `videoId` 兼容旧调用 ② Controller 逐个 `extractVideoId` 校验 ③ 任务内**串行**执行各视频（**先不做并发**）④ 单视频失败记入失败列表，继续下一个，最终状态 PARTIAL_SUCCESS ⑤ 前端支持多行输入（每行一个链接） |
| **前置依赖** | T-04、T-05 |
| **验收标准** | ① 一次提交 N 个视频可完成全部采集 ② 部分失败时状态为 PARTIAL_SUCCESS 且响应列出失败项与原因 ③ 单视频失败不中断整批 ④ 兼容旧的单视频调用 ⑤ README 描述与实现一致 |
| **风险评估** | **低**。串行执行规避了并发 profile 冲突 |
| **工作量估算** | **3~4 人日** |

---

## T-12

### 多视频联合分析

| 项 | 内容 |
|----|------|
| **编号** | T-12 |
| **名称** | 多视频联合分析与关键词级聚合 |
| **优先级** | P1 |
| **现状** | 全部 analysis 接口按单 `videoId` 查询；无对比、无聚合 |
| **存在的问题** | 无法做竞品对比、无法做关键词级横向分析、无法发现共性痛点 |
| **涉及文件** | `dy-backend/.../service/impl/AnalysisServiceImpl.java`、`.../controller/AnalysisController.java`、前端新增对比页 |
| **涉及数据表** | `video`、`video_insight`、`keyword` |
| **技术实现方案** | ① 新增 `/api/analysis/compare?videoIds=a,b,c`：拉取各视频 `video_insight` 做维度对比矩阵 ② 新增 `/api/analysis/keyword/{keywordId}`：按关键词聚合全部视频的 `video_insight` → 共性痛点、差异点、趋势 ③ 前端新增并排对比页（表格 + 雷达图） |
| **前置依赖** | T-06、T-10 |
| **验收标准** | ① 可并排对比 N 个视频的需求/痛点/情感/意向 ② 关键词级可聚合出共性痛点与差异点 ③ 对比结果可导出 |
| **风险评估** | **低**。纯聚合逻辑，不涉及新模型 |
| **工作量估算** | **4~5 人日** |

---

## T-13

### AI 成本与并发管控

| 项 | 内容 |
|----|------|
| **编号** | T-13 |
| **名称** | AI 调用并发限流、Token 预算与结果复用 |
| **优先级** | P1 |
| **现状** | Flask 无并发限制；无 Token 统计；结果仅内存 30 分钟；Java `readTimeout=30000` 与 Flask `timeout=30` **相同无余量** |
| **存在的问题** | ① N 个用户 = N 个并发 DeepSeek 调用 ② 成本不可见、不可控 ③ DeepSeek 慢响应时 Java 先超时而 Flask 后成功 → **费用已产生但结果被丢弃** ④ 重启后重复付费 |
| **涉及文件** | `Py_Data/clean/wordcloud_service.py`、`dy-backend/.../service/impl/AnalysisServiceImpl.java` |
| **涉及数据表** | `video_insight`（token_used） |
| **技术实现方案** | ① `threading.Semaphore(3)` 限制并发 DeepSeek 调用 ② 全局 Token 计数器 + 每日预算上限，超限自动降级为统计报告 ③ 结果持久化复用（T-10）④ Java 侧 readTimeout 改 45s（> Flask 30s + 网络余量）⑤ 请求/响应日志记录耗时与 token（**不记录 Key**） |
| **前置依赖** | T-10 |
| **验收标准** | ① 并发调用有上限 ② 每日 Token 消耗可查 ③ 超预算自动降级不报错 ④ Java 侧不再出现"超时但已扣费" ⑤ 单次调用的耗时与 token 可追溯 |
| **风险评估** | **低** |
| **工作量估算** | **2 人日** |

---

## T-14

### 关键词管理与视频发现

| 项 | 内容 |
|----|------|
| **编号** | T-14 |
| **名称** | 关键词管理、视频发现与定时采集 |
| **优先级** | P2 |
| **现状** | **完全没有**。全项目检索无抖音搜索页解析代码；`douyin_comment.py` 只能处理单个已知视频 |
| **存在的问题** | 用户必须人工去抖音搜索并逐个复制链接，无法规模化 |
| **涉及文件** | 新建 `Py_Data/douyin_search.py`、新建 `keyword` 相关 Java 类与前端页 `KeywordView.vue`、改 `CollectServiceImpl` 支持任务类型分发 |
| **涉及数据表** | 新建 `keyword`；`video`（发现台账）；`collect_task`（type=KEYWORD_SEARCH） |
| **技术实现方案** | ① 新建 `douyin_search.py`：打开 `/search/{keyword}` → `listen.start("aweme/v1/web/general/search/single/")` → 滚动翻页 → 提取 `aweme_info` 的 aweme_id/desc/author/statistics → 过滤条件（时间范围、最低点赞、最小时长）→ 输出 JSON ② 复用 `build_options()` 与 `preflight_check` ③ 关键词 CRUD + 过滤条件 UI ④ 定时发现用 `@Scheduled` + `collect_task` ⑤ 视频去重靠 `video` 表 `(platform, aweme_id)` 唯一键 |
| **前置依赖** | T-04、T-06 |
| **验收标准** | ① 输入关键词能发现视频列表 ② 过滤条件生效 ③ 定时任务按 cron 触发 ④ 已发现视频不重复入库 ⑤ 搜索页改版时 `preflight_check` 能报错 |
| **风险评估** | **中**。① 抖音搜索接口风控强度**需实测验证**，匿名态可能结果有限 ② 搜索页结构变动频率高于视频页 ③ 定时采集会显著增加请求量，需配合限流 |
| **工作量估算** | **6~8 人日** |

---

## T-15

### 采集任务中心前端页面

| 项 | 内容 |
|----|------|
| **编号** | T-15 |
| **名称** | 采集任务中心前端 |
| **优先级** | P2 |
| **现状** | 仅 `CollectView` 单次任务的状态提示（"采集中…"），无任务列表、无进度、无日志 |
| **存在的问题** | 用户无法查看历史任务、无法知道整体进度、无法查看失败原因、无法重试 |
| **涉及文件** | 新建 `dy-frontend/src/views/CollectTaskView.vue`、改 `dy-frontend/src/api/index.js`、`router/index.js`、`components/AppLayout.vue` |
| **涉及数据表** | `collect_task`（+ 可选 `collect_task_log`） |
| **技术实现方案** | ① 任务列表页（表格：任务号/类型/视频/状态/进度/开始/结束/耗时）② 任务详情抽屉（步骤时间线 + 实时日志流 + 错误信息）③ 操作：重试、取消、查看结果 ④ 5 秒轮询运行中任务 ⑤ 顶部导航加"任务中心"入口 |
| **前置依赖** | T-04、T-05 |
| **验收标准** | ① 可见任务列表并按状态筛选 ② 可见实时进度与日志 ③ 失败任务可一键重试 ④ 运行中任务可取消 ⑤ 页面在任务多时不卡顿（分页） |
| **风险评估** | **低** |
| **工作量估算** | **4~5 人日** |

---

## T-16

### 行业热点与趋势分析

| 项 | 内容 |
|----|------|
| **编号** | T-16 |
| **名称** | 行业热点、趋势与需求演化分析 |
| **优先级** | P2 |
| **现状** | 无。仅单视频、当前时点的静态统计 |
| **存在的问题** | 无法回答"这个行业的热点在变什么""用户需求在如何演化" |
| **涉及文件** | `dy-backend/.../service/impl/AnalysisServiceImpl.java`、前端新增趋势页 |
| **涉及数据表** | `video_insight`（历史快照）、`video`（published_at）、`keyword` |
| **技术实现方案** | ① 按主题/关键词统计评论热度时间序列 ② 对比不同时期的需求/痛点分布 ③ 需求演化（新增主题、消失主题）④ 行业级聚合报告（复用 `video_insight` 聚合） |
| **前置依赖** | T-12、T-14 |
| **验收标准** | ① 可看某主题的热度时间曲线 ② 可看出需求随时间的新增与消退 ③ 行业报告可导出 |
| **风险评估** | **中**。需要长期积累数据才能看出趋势，短期效果有限 |
| **工作量估算** | **5~7 人日** |

---

## T-17

### 竞品分析

| 项 | 内容 |
|----|------|
| **编号** | T-17 |
| **名称** | 竞品识别与优缺点对比分析 |
| **优先级** | P2 |
| **现状** | 无 |
| **存在的问题** | 无法回答"竞品A和B在用户口碑上的差异" |
| **涉及文件** | `Py_Data/clean/wordcloud_service.py`（评论级抽取 mentioned_products）、`dy-backend/.../service/impl/AnalysisServiceImpl.java` |
| **涉及数据表** | 新建 `product` 表（名称/别名/所属品牌）、`video_insight` |
| **技术实现方案** | ① 评论级抽取提及的产品/品牌（LLM + 别名表归一）② 聚合到产品维度：正面/负面要点、提及量 ③ 竞品对比矩阵（产品 × 维度 × 口碑）④ 结合 `video` 元数据识别竞品账号与自号 ⑤ 竞品对比报告 |
| **前置依赖** | T-09、T-12 |
| **验收标准** | ① 可输出竞品对比矩阵 ② 每个产品维度可溯源到评论 ③ 能区分自家产品与竞品 |
| **风险评估** | **中**。产品名识别准确率需评估（谐音、简称、错别字） |
| **工作量估算** | **6~8 人日** |

---

## T-18

### 报告中心与导出

| 项 | 内容 |
|----|------|
| **编号** | T-18 |
| **名称** | 分析报告中心与导出 |
| **优先级** | P2 |
| **现状** | 报告仅在内存 30 分钟；无历史列表、无检索、无导出（`clean/export` 导的是原始 CSV 不是分析报告） |
| **存在的问题** | 历史分析结果无法回溯；无法批量导出报告 |
| **涉及文件** | 新建 `ReportController.java` 与 `ReportView.vue`，改 `AnalysisServiceImpl` 写入报告 |
| **涉及数据表** | `video_insight`（Markdown 版本可另存一列或另建 `analysis_report` 表） |
| **技术实现方案** | ① 报告列表（按视频/类型/时间筛选）② 报告详情（Markdown 渲染 + 结构化 JSON）③ 导出 Markdown / PDF / Excel ④ 定时快照（可选） |
| **前置依赖** | T-10 |
| **验收标准** | ① 可查看历史报告 ② 可按视频与时间检索 ③ 可导出 ④ 导出后可脱离系统阅读 |
| **风险评估** | **低**。PDF 导出可选引入依赖（**建议先用 Markdown/HTML 规避**） |
| **工作量估算** | **4~5 人日** |

---

## 任务卡使用说明

1. **本轮不实施任何任务**，等待批准
2. 建议先做 **T-01**（0.5~1 人日，消除数据丢失）
3. **T-03 是关键路径起点**——cid 缺失阻塞了增量采集与全部 AI 升级能力
4. 每张卡的验收标准均可自动化验证（建议补集成测试脚本）
5. 标注为「**未实测**」「**需实测验证**」的项，在实施前应先做小规模验证
