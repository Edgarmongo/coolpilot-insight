# 独立评审 · A/B/C 三个提交（只读）

> 评审对象：`a6f8594`（A 审计）、`fb29996`（B 日志）、`140d244`（C 诊断/复跑报告）
> 评审基线：`9102f47`
> 评审日期：2026-11-17
> 关联文档：`SOURCE-04-FOLLOWUP-AUDIT-AND-DIAGNOSIS.md`

---

## 0. 评审声明（先说不成立的假设）

**本次是自评审，不是独立评审。** 这三个提交由我本人实施，我对其设计意图有过强先验，
容易把"我打算做的"读成"代码实际做的"。因此：

- 本报告的价值在于**把每条结论都落到可复核的证据**（行号 / SQL / 日志），而不是"我确认没问题"；
- 下文凡是我自己代码的缺陷，一律按同一标准列问题等级，不降格；
- **建议由第三人按本报告 §9 的复核清单再走一遍**，尤其 A-2 与 B-1 这两条是我在写代码时没意识到的。

未做的事（遵守只读约束）：未改任何实现代码、未改数据库与 Flyway、未删任何数据、
未重启容器、未再次执行真实采集、未启动 SOURCE-05。本次新增文件**只有本报告**。

---

## 1. 范围与方法

| 提交 | 改动 |
|---|---|
| `a6f8594` | `AuditService`（+130 行）、`CollectController`（10 行）、`AuditServiceTest`（新增 193 行）、`SourceCapabilityServiceTest`（2 行） |
| `fb29996` | `application.yml`（4 行）、`docker-compose.yml`（2 行） |
| `140d244` | `CollectServiceImpl`（诊断日志 + `invokeCollector` 返回值）、诊断报告（225 行） |

证据来源分三类，不混用：
- **静态**：源码 grep / 行号；
- **运行时只读**：`information_schema`、`audit_log` 聚合查询、容器日志（历史，不产生新事件）；
- **推导**：明确标注"推导"，并给出推翻它所需的证据。

---

## 2. A · 审计 JSON（结论：方向正确，有 1 个实质缺陷 + 3 处需修正的表述）

### 2.1 通过项

| 检查点 | 结论 | 证据 |
|---|---|---|
| 所有写入都经规范化入口 | **通过** | `grep auditMapper.insert` 仅命中 `AuditService:118` 一处；无旁路 |
| 调用点覆盖 | **通过** | 全仓库 23 处：`audit(...)` 22 处 + `auditStructured(...)` 1 处，逐一核对均落到同一 `persist()` |
| 纯文本包装不丢内容 | **通过** | `{"text": 原文}`，`writeValueAsString` 负责转义；单测 `wrapsPlainText` 回读验证 |
| 合法 JSON 保持语义 | **基本通过** | 对象/数组/标量均原样重序列化；**例外见 A-3** |
| 失败计数覆盖两个阶段 | **通过** | `stage=serialize`（`audit()` 与 `auditStructured()` 各一处）与 `stage=write` 均有计数 |
| 写失败不改变业务结果 | **通过** | `persist()` 内 catch 全包；单测 `writeFailureIsObservableNotFatal` |
| 运行时落库 | **通过** | `SUBMIT_COLLECTION` 1 条：`{"taskNo": "CT20261117014049167", "totalItems": 1}`，`JSON_VALID=1`；全表非法 JSON 计数 0 |

### 2.2 A-1（P2）`countFailure` 有一行在 try 之外，可能把异常抛出 `audit()`

```
AuditService.countFailure():
    MeterRegistry registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();   ← try 之外
    if (registry == null) return;
    try { Counter.builder(...)... } catch (Exception e) { log.warn(...); }
```

`ObjectProvider.getIfAvailable()` 在容器未就绪 / 依赖解析失败时**可以抛 `BeansException`**。
一旦抛出，它会从 `countFailure` 逸出，穿过 `audit()` 的 catch 块（catch 块内的异常不会再被自身捕获），
最终抛给业务调用方 —— 正好违反本类自己声明的"审计绝不影响业务结果"。

- 触发概率：低（正常容器内 `getIfAvailable` 不会抛）；
- 影响：一旦触发，`SUBMIT_COLLECTION` 这类调用会让已成功的采集提交**报错**；
- 建议：把 `getIfAvailable()` 一起挪进 try，或整体 `try { ... } catch (Throwable)`。

### 2.3 A-2（P2）修复说明里"6 个调用点必定失败"不准确——纯数字文本是**侥幸成功**的

`SOURCE-04-FOLLOWUP-AUDIT-AND-DIAGNOSIS.md` §2.1 写"6 个传纯文本 → 必定写入失败"。
实测推翻了这个全称判断：

```
SELECT detail_json, JSON_TYPE(detail_json) FROM audit_log WHERE action='GENERATE_VIDEO_INSIGHT';
→ 7703721523349239951   UNSIGNED INTEGER   （3 条）
```

`GENERATE_VIDEO_INSIGHT` 传的是视频 ID，**纯数字串本身就是合法 JSON（数字）**，MySQL 照收。
所以失败的只是"含字母/符号"的那些（`items=1`、`markdown`、`7703….csv`、`pdf`、`APPROVED`），
纯数字的那一类是"侥幸通过但语义被改写"（字符串 → UNSIGNED INTEGER）。

影响与意义：
- 报告的结论方向（"审计在丢"）不受影响，且被 `audit_log` 的 action 分布进一步证实：
  历史只出现过 7 种 action，`UPLOAD_CSV` / `EXPORT_REPORT` / `CREATE_REPORT` /
  `REVIEW_ANALYSIS` / `DICT_*` / `ROLE_CREATE…` **均为 0 条**；
- 但**修复带来的形态变化要写进交付说明**：同一 action 历史上是 `UNSIGNED INTEGER`，
  修复后是 `{"text":"7703…"}`。下游若按数字取值会拿不到。

### 2.4 A-3（P3）重复键在重序列化时被静默合并

`{"a":1,"a":2}` 是合法 JSON，`readTree` 保留后者，`writeValueAsString` 得到 `{"a":2}` —— 丢了一个键。
现有 23 个调用点都不传重复键，属理论风险；但"规范化"不该改变内容，建议在文档里写明这条边界。

### 2.5 A-4（P3）调用点数量表述

报告写"22 个调用点"，实际 **23**（22 × `audit` + 1 × `auditStructured`）。§2.4 的表格也只列了 4 组而不是逐条。
建议按"23 处、其中 6 处传字符串"修正口径。

### 2.6 A-5（P3）前端展示形态变化

`AuditLogView.vue:21` 直接把 `detailJson` 当字符串渲染。修复后纯文本类事件从"空白/数字"
变成 `{"text":"markdown"}`。功能无碍，但页面观感变了，评审时应确认产品侧接受。

---

## 3. B · 日志配置（结论：正确，有 1 个实质缺陷需处理）

### 3.1 通过项

| 检查点 | 结论 | 证据 |
|---|---|---|
| 包名正确 | 通过 | `com.coolpilot.insight`；旧值 `com.dy.comment` 已不存在 |
| 默认 INFO | 通过 | `${APP_LOG_LEVEL:INFO}`；`.env` 无该键（grep 计数 0）；compose `${APP_LOG_LEVEL:-INFO}` 双保险 |
| 生产无遗留 DEBUG | 通过 | 运行时 `printenv APP_LOG_LEVEL = INFO`，启动后 30s 内 DEBUG 行数 0 |
| 无全局级别覆盖 | 通过 | `logging.level` 下只有这一条，无 `root` 覆盖 |
| 凭据类日志 | 通过 | `log.debug` 中无 token / password / secret / credential / cookie 命中 |

### 3.2 B-1（P2）新增的 DEBUG 日志包含评论正文与昵称 —— 违反"DEBUG 不含敏感数据"

`CollectServiceImpl` 新增：

```java
log.debug("[collect] item={} 执行器原文: {}", item.getId(), collected.tail(800));
```

`collected` 是采集服务返回的 `logTail`，实际内容（本轮真实日志）：

```
绝望的大鼻嘎｜1083262777｜黑龙江｜赞13｜cid=770374991205｜把这视频给俱乐部发过去…
Queen｜884011144｜辽宁｜赞4｜cid=770383765035｜砸到咱们第一排一个小孩了…
```

即 **昵称 + 抖音号 + IP 属地 + 评论正文**。这属于个人信息，不是"调试用的技术细节"。
虽然 DEBUG 默认关闭，但要求是"DEBUG 日志不包含凭据和敏感数据"，按字面这条不成立。

- 影响：开启 DEBUG 期间，个人信息会落进容器日志（可能被采集、外传、长期留存）；
- 建议：只保留**结构性摘要**（`status/collected/pages/finishedReason/errorCode` + 日志行数），
  不打印 logTail 原文；需要正文时按 cid 到库里查（有权限边界）。

> 这一条与 C 的诊断需求冲突：诊断靠的正是这段原文。建议的解法是"摘要进日志、原文进作业对象"
> （`GET /v1/jobs/{id}` 已经返回 `logTail`，Java 侧不必再抄一份）。

---

## 4. C · 受控复跑（结论：证据链成立，判定口径有 2 处不稳）

### 4.1 通过项

| 检查点 | 结论 |
|---|---|
| HTTP 是实际执行路径 | 通过：`172.21.0.2 → POST /v1/jobs 202`，后端 `ProcessRunner/python/comment.py` 命中 0 |
| Job 生命周期可追踪 | 通过：`jobId=task-7-item-8`，状态 `SUCCEEDED / collected=5 / pages=1 / MAX_LIMIT` |
| 与采集器自记一致 | 通过：`progress.json {page:1, collected:5}` 与 job 完全一致 |
| `collected` 语义 | 通过：= 本次写入 CSV 的条数（`executor: job.collected = len(items)`） |
| `collected_count` 语义 | 通过：= 该视频库内累计（`CommentMapper.summaryRow`: `COUNT(*) FROM comment WHERE video_id=?`，已核对 SQL） |
| 5 条为何不入库 | 通过：cid 命中既有记录（`7703837650353177401` → `comment.id=6`），`ON DUPLICATE KEY UPDATE` 只更新 |

### 4.2 C-1（P1）Item SUCCESS 的判定依赖一个"会被别处删除的文件"

`runItem` 的成功条件是：执行器 success **且** CSV `exists && length>0` **且** clean 退出 0。
而 CSV 会被 `clean.py:252 os.remove(filepath)` 删除。于是：

- 同一视频第二次采集 → 产物存在性取决于**上一轮是否已被清洗**，而不是本轮结果；
- 本轮实测就出现了 `collected=5` 但库内 0 新增、Item 仍 SUCCESS —— 说明"成功"这个结论
  对产物的依赖是隐式的、跨轮的。

这不是本轮新引入的问题，但它是 U1 的**判定侧**表现，需与 U1 一起处理。

### 4.3 C-2（P2）header-only CSV 判 SUCCESS（即 U4）

`length>0` 对"仅表头（约 108 字节）"成立 → 进入清洗 → 清洗 0 行但退出 0 → SUCCESS。
判定把"文件里有表头"当成"采集到了东西"。现状契约见 §7。

### 4.4 C-3（P3）`collected` 与 `collected_count` 命名近似、语义不同

一个是"本次写入 CSV 条数"，一个是"库内累计"。日志里加了提醒，但字段名本身仍会误读。
建议后续在 DTO 层改名（`collectedThisRun` / `totalInDb`），本阶段不动契约。

### 4.5 C-4（P2）串行假设只对单副本成立

- 当前：collection 单 worker 线程 + backend `AtomicBoolean busy` + `countActiveByVideo` + `uk_task_video`
  → 同一视频不会并行、Chrome 不会并发；
- 但 `JobQueue` 是**进程内**队列。一旦 collection 扩容到 2 副本，两个队列各自起 Chrome，
  共享 `chrome-data` 卷 → profile 锁冲突。当前是单副本，属**部署约束**而非代码保证，
  应在部署文档里写死"collection 必须单副本"。

---

## 5. U1（P1）CSV 同时承担"交付产物"与"跨次去重状态"

### 5.1 风险链（已由代码与复跑双向确认）

```
collector.collect_comments → load_existing_cids(csv_path)   ← 唯一的历史来源
                          → append_rows(csv_path)           ← 本次结果
clean.py process_file     → insert_to_db(...) → os.remove(csv_path)  ← 历史被删
下一轮                    → load_existing_cids 读到空 → 全部视为"新"
```

后果不是"数据重复"（DB 有 `(video_id, dedup_key)` 唯一键兜底），而是：

1. **重复抓取**：每轮都把同一批评论从平台重拉一遍 —— 风控暴露面随采集次数线性放大；
2. **`collected` 失去意义**：它统计"写入 CSV 的条数"，在历史被删后恒等于"本页条数"；
3. **判定不稳**：见 C-1。

### 5.2 评审认为需要先确认的 5 件事（当前只能给出部分答案）

| # | 问题 | 现有答案 | 依据 |
|---|---|---|---|
| 1 | 多 Job 是否可能并发处理同一视频 | **不会**（单副本前提下） | `countActiveByVideo` + `uk_task_video` + collection 单 worker |
| 2 | 去重记录是否有原子写入保障 | **没有** | `append_rows` 是普通 `open(...,'a')` 追加；`ensure_header` 先写表头。无临时文件+rename，无 fsync |
| 3 | 写入失败是否会丢历史 | **会**（且更严重：历史已被删除） | 历史随 clean 删除而消失，与本轮写入是否成功无关 |
| 4 | 去重记录是否按平台/视频/CID 隔离 | **按视频隔离，未按平台** | 文件名 `<aweme_id>.csv`，若未来多平台 ID 空间重叠会互相污染 |
| 5 | 历史 CSV 能否安全迁移为新的去重数据 | **当前无法迁移** | 现存 CSV 已全部被 clean 删除；`datas` 目录实测只剩 1 个 108 字节的空表头文件（45752568906.csv），无历史可迁 |

第 5 条尤其重要：**"把删除动作去掉"并不能恢复历史**，只能防止未来继续丢；
真正要做的是新机制上线后从**数据库**回填一次 cid 集合（`SELECT cid FROM comment WHERE video_id=?`）。

### 5.3 评审结论

维持"最高优先级"，且**同意"不要直接把 `os.remove` 去掉当最终修复"**：
那只是让临时产物无限堆积，职责混杂并未解决。方向应是拆成两个存储：

| 数据 | 职责 | 生命周期 | 候选载体 |
|---|---|---|---|
| `<aweme_id>.csv` | 本次交付产物 | 清洗后即可清理 | 现状不变 |
| cid 去重集合 | 跨次状态 | 长期 | DB 回填 + 独立 `seen.jsonl`/SQLite；按 `(platform, video_id, cid)` 命名空间 |

本轮**不批准实施**，需先出独立方案（含并发写入原子性、失败降级、回填脚本）。

---

## 6. U2（P2）clean 的标准输出为空 —— 未定性，但有一条具体可疑点

### 6.1 已确认与已排除

| 项 | 结论 |
|---|---|
| clean 是否执行 | **执行了**（CSV 被删、退出码 0、`result.success=true`） |
| python 能否正常打印 | **能**（容器内手动执行 `clean.py __not_exist__.csv` 有正常输出） |
| clean.py 是否派生子进程 | **没有**（无 `subprocess` / `multiprocessing` / `fork`） |
| 是否是超时被杀 | **不是**（超时会抛 `IntegrationException`，item 会 FAILED） |

### 6.2 具体可疑点（推导，未证实）

`ProcessRunner` 的读取线程：

```java
try (BufferedReader r = ...) { while ((line = r.readLine()) != null) output.append(...); }
catch (Exception ignored) { }   // 注释说"进程被强杀时流会关闭，属预期"
```

这个 `catch` 吞掉**所有**异常，包括 `MalformedInputException`（解码失败）。
一旦发生，`output` 保持为空、进程退出码仍是 0 —— 与观测到的"exit 0 + 空输出"完全吻合。
注释与实际行为不一致：它声称只兜"强杀"，实际兜了一切。

**推翻它需要什么**：在 `catch` 里临时记录异常类型（或记录读取到的字节数）。这属于改实现，本轮不做。

### 6.3 目标诊断契约（对齐你的建议，不含"已实现"含义）

清洗结果应分三块记录，互不替代：

```
{
  "exitCode": 0,
  "stdoutBytes": 1234,      // 读取到多少字节（0 就能立刻定位"没读到"）
  "stderrText": "...",      // 单独保留，不合并
  "inputRows": 5,
  "validRows": 5,
  "duplicateRows": 5,
  "insertedRows": 0,
  "updatedRows": 5,
  "failedRows": 0,
  "deletedArtifact": true
}
```

当前程序**一项都没有**（flush 之前连 exit/stdout 都是合并的）。这份契约应随 U1 的修复一起做，
因为去重统计与入库统计本来就该是同一份数据。

---

## 7. U3（结论：确认不可追溯，不再反推）

| 判断 | 依据 |
|---|---|
| 19→20 的增量无法归因 | 该次运行的 CSV 已被 clean 删除；当时审计正在丢失（`SUBMIT_COLLECTION` 历史 0 条）；无 job 级留存 |
| 不应做的事 | 用当前库内 20 条反推"哪一条是新增" —— `comment` 表无 `created_at`（实测：该列不存在），无法按时间定位 |

同意你的裁决：**保留为历史不可追溯事件**。新的关联链（jobId ↔ itemId ↔ cid ↔ 审计）建起来之后，
这类问题在新数据上可查即可。

---

## 8. U4（契约评估，不改状态机）

现状：`exists && length>0` → header-only 也算"有产物" → SUCCESS。

评审认为你给的目标模型方向正确：**执行状态与数据结果应分离**，否则"没有新增"会被读成"采集失败"。
但需要补一条约束，否则新的枚举会退化成另一个笼统结论：

| 执行结果 | 数据结果 | 建议表达 | 备注 |
|---|---|---|---|
| 正常 | 有新增 | `SUCCESS` + `INSERTED` | 需给出 insertedRows>0 |
| 正常 | 全重复 | `SUCCESS` + `DUPLICATE_ONLY` | 与 U1 修复后才有意义（否则"全重复"不可信） |
| 正常 | 无可用数据 | `SUCCESS` + `EMPTY` | 需区分"平台确实空"与"被风控"（`finishedReason` 已有 NO_RESPONSE） |
| 清洗失败 | — | `FAILED` | |
| 入库失败 | — | `FAILED` / `PARTIAL_SUCCESS` | 取决于事务边界，当前 clean.py **无事务**（逐行 execute），应明确为 `PARTIAL_SUCCESS` |
| 执行异常 | 无产物 | `FAILED` | |

**不建议**在 U1 修好之前先改这套状态：那时 `DUPLICATE_ONLY` 本身就是不可信的（因为历史被删，
"重复"是相对的）。顺序应是 U1 → U2 结构化 → U4 状态模型。

---

## 9. 问题等级汇总

| ID | 结论 | 等级 | 处置建议 |
|---|---|---|---|
| A-1 | `countFailure` 中 `getIfAvailable()` 在 try 外，可能把异常抛给业务 | **P2** | 下个补丁内修（3 行） |
| A-2 | 报告"6 个调用点必定失败"不准；纯数字文本被存成 JSON 数字，修复后形态变化 | **P2** | 修正文档 + 通知下游 |
| A-3 | 重复键在重序列化时被合并 | P3 | 文档写明边界 |
| A-4 | 调用点数 22 → 实际 23 | P3 | 修正文档 |
| A-5 | 前端审计详情展示形态变化 | P3 | 产品确认 |
| B-1 | 新增 DEBUG 日志含昵称/抖音号/IP/评论正文 | **P2** | 改为结构化摘要，不打印 logTail 原文 |
| B-2 | `APP_LOG_LEVEL` 若置空，`${...:INFO}` 会得到空级别 | P3 | compose 已用 `:-INFO` 兜底；文档写明"只能设为 DEBUG/INFO" |
| C-1 | Item SUCCESS 依赖可被外部删除的产物 | **P1** | 与 U1 一起改 |
| C-2 / U4 | header-only CSV 判 SUCCESS | **P2** | 契约决策（§8） |
| C-3 | `collected` 与 `collected_count` 命名近似 | P3 | 后续改 DTO 字段名 |
| C-4 | 串行假设仅对单副本成立 | **P2** | 部署文档写死"collection 单副本" |
| U1 | CSV 兼作交付产物与去重状态，清洗即毁历史 | **P1** | 独立方案（§5.3） |
| U2 | clean stdout 为空，根因未定性 | **P2** | §6.2 的可疑点优先验证 |
| U3 | 19→20 不可追溯 | — | 确认终止，不再反推 |

### 9.1 供第三人的复核清单（按性价比排序）

1. 复现 A-2：查 `JSON_TYPE(detail_json)` 是否仍有历史 `UNSIGNED INTEGER` 行，确认形态切换影响面；
2. 看 A-1 那两行，判断是否真的会逸出（可用一个抛异常的 `ObjectProvider` 单测，但需改测试代码）；
3. 看 B-1 那段 debug 日志，决定"摘要 vs 原文"边界；
4. 用 §6.2 的假设验证 U2（需改 `ProcessRunner` 的 catch 打日志，属改代码，需另开提交）；
5. U1 方案评审（本轮不实施）。

---

## 10. 结论

| 工作 | 评审结论 |
|---|---|
| A 审计 JSON | **修复方向正确、实现基本到位**；有 1 个 P2 缺陷（A-1）与 1 处报告失准（A-2），不构成回退，建议随下个补丁收掉 |
| B 日志配置 | **正确**；但新增 DEBUG 日志引入了个人信息（B-1），应在开启 DEBUG 之前处理 |
| C 受控复跑 | **证据链成立**，语义已分清；判定口径仍有 C-1/C-2 两处不稳，本质是 U1/U4 |
| U1 | **维持 P1、最高优先级**；同意不把"去掉删除"当修复；需独立方案（含从 DB 回填历史 cid） |
| U2 | 未定性；已锁定一个具体可疑点（`ProcessRunner` 吞异常），需改代码才能证实 |
| U3 | 证据不足，确认终止 |
| U4 | 契约建议已给（§8），**不建议在 U1 之前实施** |

**维持"未闭环"判断**：HTTP 派发能真实执行这一点已经证明，但"跨次去重记忆"不可靠之前，
这条链路不具备生产级稳定性。下一步应先出 U1 的独立方案，再谈状态模型。
