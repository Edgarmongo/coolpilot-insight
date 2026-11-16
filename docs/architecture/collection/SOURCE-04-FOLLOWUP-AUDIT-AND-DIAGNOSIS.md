# SOURCE-04 后续 · 审计修复 + 日志配置修复 + 端到端定性复跑

> 状态：A、B 已修复并验证；C 已完成受控复跑，**仍有 4 项未定性**（§5）
> 关联：`SOURCE-06-IMPLEMENTATION.md` §8.4（本轮要解决的就是它标出的疑点）
> 基线：`df4c492`（本轮的起点）

---

## 一、实施前置核对（以源码与运行时为准）

| 项 | 结果 |
|---|---|
| 分支 / HEAD | `feat/boot4-jdk21-mybatis` / `df4c492`；工作区与暂存区**均干净** |
| D1/D2/D3 落点 | `b11a096`（Java 切换）、`9ad810b`（Python 侧）；报告 `SOURCE-06-IMPLEMENTATION.md` §6/§8 |
| `app.collection.executor` | `application.yml` 默认 `http`；容器内启动日志确认 `executor=http`；`CollectionClientConfig` 按该值选实现 |
| `detail_json` 类型 | MySQL `json`（`information_schema` 实测，非推断） |
| 审计调用方 | **22 处**，见 §2.2 |
| Job 响应字段 | `jobId/status/collected/total/pages/errorCode/errorMessage/finishedReason/queuePosition/startedAt/finishedAt/logTail` |
| CSV 契约 | `<aweme_id>.csv`，`utf-8-sig`（BOM）+ 13 列 |
| Item SUCCESS 判定 | `CollectServiceImpl.runItem`：执行器 success **且** CSV 存在且 `length>0` **且** clean 成功 |

---

## 二、A：AuditService 的 JSON 写入缺陷

### 2.1 根因

`audit_log.detail_json` 是 **JSON 列**，而 `AuditService` 把调用方传的字符串**原样**写入。
22 个调用点里大半传的是纯文本：

```
"items=1"      ← SUBMIT_COLLECTION（本轮要验的那个）
"markdown"     ← EXPORT_REPORT
视频 ID / 状态 / 文件名 …
```

MySQL 对非 JSON 文本直接报 3140，异常被 `catch` 成一行 `log.warn`（且 `e.getMessage()` 为空），
于是这些事件**必定写入失败**——不是"偶发"，是每一条都失败。实测：修复前
`action='SUBMIT_COLLECTION'` 在 `audit_log` 里 **0 条**。

### 2.2 修复

写入前统一规范化（`AuditService.toJsonDocument`）：

| 输入 | 落库 |
|---|---|
| `null` / 空白 | `NULL`（不是字符串 `"null"`） |
| 合法 JSON（对象/数组/标量） | 解析后重新序列化（顺带校验与压缩） |
| 其它纯文本 | `{"text": "<原文>"}`，内容一字不改 |

另加 `auditStructured(action, type, id, Map)`：结构化语义由调用方给出、序列化归 AuditService，
避免"同样是提交任务，一处是对象、一处是裸字符串"。**不重载 `audit`**——与 `detail=null`
的调用点会编译期歧义（实测踩到，已改名规避）。

`SUBMIT_COLLECTION` 改为传 `Map.of("taskNo", …, "totalItems", …)`。

### 2.3 失败语义

- 序列化失败：`log.error("[audit][SERIALIZE_FAILED] …")` + 计数；**审计行照写、详情置空**——
  丢详情可以，丢"谁做了什么"不行；
- 写入失败：`log.error("[audit][WRITE_FAILED] …")` + 计数；不抛出，业务结果不受影响；
- 计数：`audit.failures{stage=serialize|write, action=…}`（Micrometer；无 Registry 时自动跳过）。

### 2.4 22 个调用点的落库形态

| 传入形态 | 调用点 | 落库 |
|---|---|---|
| `null` | LOGIN / CHANGE_PASSWORD / CREATE_KEYWORD / DISABLE_KEYWORD / DELETE_REPORT / 采集取消与重试 | `NULL` |
| 已是 JSON 对象 | DICT_TYPE_*、ROLE_*、ROLE_MENU_GRANT、CONFIG_UPDATE、SOURCE_CAPABILITY_UPDATE | 原样对象 |
| **纯文本** | GENERATE_VIDEO_INSIGHT（视频 ID）、UPLOAD_CSV（文件名）、EXPORT_REPORT（`markdown`）、EXPORT_REPORT_FILE（格式）、CREATE_REPORT（类型）、REVIEW_ANALYSIS（状态） | `{"text": "…"}` |
| 结构化（新增） | SUBMIT_COLLECTION | `{"taskNo":…, "totalItems":…}` |

> 纯文本那几个没有逐个改成结构化：它们的信息量本身就是"一个值"，
> 改调用点属于无收益的扩散。统一规范化后它们的落库形态是合法的、可反序列化的。

### 2.5 验证

| 证据 | 结果 |
|---|---|
| 单测 `AuditServiceTest`（7 条） | 纯文本/对象/空值/数组标量/结构化/写失败可观测/无 Registry 不炸 |
| 运行时（真实容器） | `SUBMIT_COLLECTION` 落库 1 条：`{"taskNo": "CT20261117014049167", "totalItems": 1}` |
| 全表 JSON 合法性 | `SUM(JSON_VALID(detail_json)=0) = 0`（非空 detail 共 8 条） |
| 修复前对照 | 同 action 记录数 **0** |

---

## 三、B：日志包名配置

| 项 | 内容 |
|---|---|
| 原值 | `logging.level.com.dy.comment: debug` —— 该包名**已不存在**，等于一条日志级别都没配上；新代码的 debug 全不输出 |
| 改为 | `com.coolpilot.insight: ${APP_LOG_LEVEL:INFO}` |
| 运行时开关 | compose 增加 `APP_LOG_LEVEL`（默认 `INFO`），无需重建镜像即可临时开 DEBUG |
| 敏感信息 | 全仓库 `log.debug` 中无 token/password/secret/credential/cookie 命中（grep 实测） |
| 恢复 | 诊断期以 `APP_LOG_LEVEL=DEBUG` 启动，诊断完成后重启回 `INFO`（`docker exec printenv` 已确认 `INFO`，且 30s 内 DEBUG 行数 0） |

---

## 四、C：端到端定性复跑（HTTP 执行路径）

### 4.1 受控设置

- 执行器：`http`（启动日志确认；后端日志中 `ProcessRunner` / `python` / `comment.py` 命中 **0**）
- 对象：`aweme_id=7703721523349239951`，`maxComments=5`
- 关联标识：`taskId=7` → `itemId=8` → `jobId=task-7-item-8`
- 复跑前基线：该视频库内评论 **20** 条；datas 目录内**无**该视频 CSV

### 4.2 证据链

```
① Backend → Collection
   172.21.0.2 - - "POST /v1/jobs HTTP/1.1" 202

② Job（task-7-item-8）
   status=SUCCEEDED  collected=5  pages=1  finishedReason=MAX_LIMIT  errorCode=None
   logTail: 浏览器启动成功 → 已滚动到评论区 → 第 1 页
            （￣︶￣）↗ 本页新增 5 条，本次累计 5 条（已落盘）
            ( •̀ ω •́ )✧ 已达到 5 条上限，结束采集
            φ(゜▽゜*)♪ 采集完成：本次新增 5 条，文件 /app/collection-service/datas/7703721523349239951.csv

③ progress.json（采集器自记）
   {"page":1,"collected":5,"lastCid":"7703837650353177401","finished":true}

④ Java 侧（新增诊断日志，DEBUG 已开）
   [collect] item=8 执行器 success=true 耗时=16039ms
   [collect] item=8 产物检查 path=/app/…/7703721523349239951.csv exists=true size=1313
   [collect] item=8 清洗结果=            ← 空字符串（见 §5·U2）
   [collect] item=8 判定 SUCCESS：库内累计 20 条（不是本次新增数）

⑤ 产物消失
   清洗后立即 ls：7703721523349239951.csv 不存在（仅剩 meta/progress json）

⑥ 数据库
   comment 总数 20 → 20（未变）
   本次 5 条 cid 中的 7703837650353177401 → 已存在（comment.id=6，dedup_strategy=CID）

⑦ Item / Run
   item.id=8 status=SUCCESS collected_count=20 skipped_count=0
   task.id=7 status=IDLE（终态由 Run 承载）success_items=1
   run.id=11 status=COMPLETED
```

### 4.3 六个语义的准确定义（以源码为准）

| 字段 | 定义 | 出处 |
|---|---|---|
| `collected` | **本次写入 CSV 的条数**，不是"平台上新增的评论数" | `executor._collect_comments` → `job.collected = len(items)` |
| `pages` | 采集器**实际处理到的页序号**（达到上限即停） | `collector.collect_comments` → `outcome.pages = page` |
| Job `SUCCEEDED` | 产出条目 > 0；或收到过数据页但全部重复（0 新增） | `executor.build_runner` |
| Item `SUCCESS` | 执行器成功 **且** CSV 存在且 `length>0` **且** clean 退出码 0 | `CollectServiceImpl.runItem` |
| `collected_count` | **该视频在库中的累计评论数**，与本次无关 | `commentMapper.summaryRow(videoId).total` |
| 19→20 | 见 §5·U3（未定性） | — |

### 4.4 三类产物的处理（现状契约）

| 产物 | Java 判定 |
|---|---|
| **无 CSV** | 失败：「采集未产出数据文件（可能触发风控或该视频无评论）」 |
| **空 CSV（0 字节）** | 同上（`length==0`） |
| **只有表头（约 108 字节）** | `exists && length>0` → 进入清洗 → 清洗 0 行但退出 0 → **判 SUCCESS**，`collected_count` 为库内累计 |

最后一行是**契约漏洞**：header-only 会被当成"采集成功"。

### 4.5 本次复跑能确定与不能确定的

**能确定**：
1. HTTP 是实际执行路径，未回退子进程；
2. `collected` 与 `pages` 的含义如上，且与采集器自记的 `progress.json` 一致（5/1）；
3. `collected=5` 而库内 0 新增，原因是这 5 条 cid **本就在库**，`ON DUPLICATE KEY UPDATE` 只更新不插入；
4. Item SUCCESS **不代表有新数据入库**，只代表"流程跑完且产物存在"；
5. CSV 在清洗后被 `clean.py` 删除（`ai-service/app/clean.py:252 os.remove(filepath)`），
   这是它"消失"的唯一原因，与容器挂载无关（backend 与 collection 共用同一宿主目录）。

**不能确定**：见 §5。

---

## 五、未解决项（不得当作已闭环）

| # | 事项 | 现有证据 | 为什么未定性 |
|---|---|---|---|
| **U1** | `clean.py` **无条件删除 CSV**，而 CSV 是采集器跨次去重的唯一记忆（`load_existing_cids` 只读 CSV） | 每次运行后 CSV 必消失；本轮 5 条全是库内已有评论，证明"历史"没起作用 | 这会让每次采集都重复抓同一批评论（平台风控风险 + `collected` 数字被误读为"新增"）。修它要动去重记忆的存放位置，属于独立变更，本轮禁止（不删数据、不改状态映射） |
| **U2** | clean 的**标准输出为空** | 三次运行 `清洗结果=` 均为空串；手动在容器内跑 `clean.py __not_exist__.csv` 有正常输出 | 无法看到逐行入库异常。可能是 `ProcessRunner` 输出抓取的时序问题，也可能是调用路径不同；未继续定位 |
| **U3** | 上一轮 **19→20** 的增量无法归因 | 该次运行早于受控实验；其 CSV 已被 clean 删除，无审计留存（审计当时正在丢失） | 现有证据不足以区分"1 条真实新增"与"其它写入路径"。按任务要求，不做推断 |
| **U4** | 只有表头的 CSV 被判 SUCCESS（§4.4） | 代码路径推导 + 45752568906.csv 实测为 108 字节表头文件 | 是否要改成"清洗 0 行即失败"属于契约决策，不在本轮范围 |

> 另：`logs` 里 `clean` 的返回为 success=true、output 为空，故 §4.2 第 ④ 项只能证明"清洗被调用且退出码 0"，
> 不能证明它处理了几行——**入库 0 条这一结论是靠 SQL 反查（cid 已在库、总数未变）得出的**，不是靠 clean 自报。

---

## 六、测试与回归

| 类别 | 结果 |
|---|---|
| Java 全量 | **335 / 335**（原 328 + `AuditServiceTest` 7） |
| Python 全量 | **110 / 110** |
| 新增测试 | `AuditServiceTest`：7 条（纯文本/对象/空值/标量/结构化/写失败可观测/无 Registry） |
| 修改测试 | `SourceCapabilityServiceTest.RecordingAudit` 构造函数补第三参（`AuditService` 新增 MeterRegistry 依赖） |
| 运行时审计 | `SUBMIT_COLLECTION` 落库且 `JSON_VALID=1`；全表非法 JSON 计数 0 |

---

## 七、根因 · 影响范围 · 建议

### 7.1 审计丢失

- **根因**：把"详情"当作字符串直写 JSON 列，22 个调用点里 6 个传纯文本 → 必定失败；失败被降级成 warn。
- **影响**：这 6 类操作（视频洞察生成、CSV 上传、报告导出×2、报告创建、分析审核、采集提交）
  的审计**长期缺失**；其余传 `null` 或合法 JSON 的不受影响。
- **建议**：告警规则挂 `audit.failures{stage="write"}`；后续把纯文本调用点逐步改成结构化
  （本轮只改了 SUBMIT_COLLECTION，因为它是有测试目标的那个）。

### 7.2 日志配置

- **根因**：包名重命名（`com.dy.comment` → `com.coolpilot.insight`）时漏改日志配置，静默失效。
- **影响**：新代码所有 debug 日志从未输出，排障一直只能看 info。
- **建议**：把 `APP_LOG_LEVEL` 写进运维手册；长期可加一条启动自检（配置里的 logger 前缀在 classpath 中不存在则 WARN）。

### 7.3 CSV 作为去重记忆被清洗删除（U1）

- **根因**：`clean.py` 入库后 `os.remove`，而采集器把 CSV 当历史索引。
- **影响**：重复抓取、`collected` 语义被误读；平台风控风险随重复次数上升。
- **建议**（需单独立卡）：清洗后把 cid 摘要落成独立索引文件，或采集侧改为按 DB 判重；
  删除前至少保留 `<aweme_id>.seen.json`。**本轮未改**。
