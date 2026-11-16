# 独立只读评审（第二轮）· A-1 / B-1 / C-4 / A-2 / U1

> 评审对象：`223670d`（A-1）、`7514695` + `1553798`（B-1）、`7b20ef6`（C-4）、`78a34b5`（U1 设计 + 实施报告）
> 参照（非证据）：`0bb5c84` 自评审报告
> 评审日期：2026-11-17
> 基线：`1666d84`

---

## 0. 评审声明与证据分级

**本轮仍是自评审**（三个修复提交与 U1 设计均由我实施）。上轮已声明过一次；
这次的处理方式是：把"我认为它对"与"证据显示它对"分开写，凡证据不足处一律降级或标未定性。

证据分三级，全文标注：

- **【源】** 源码 / SQL / 配置，带路径与行号；
- **【测】** 单元测试断言（注意：断言通过 ≠ 分支被执行，见 A1-3）；
- **【运】** 运行时查询或日志（本轮未重启、未采集，只读历史与既有查询）。

未做：未改源码/测试/配置/文档、未改库与 Flyway、未回填、未采集、未重启容器、未启动 U2/U4/SOURCE-05。

---

## 1. A-1 · 指标异常隔离

### 1.1 通过项

| 检查 | 结论 | 证据 |
|---|---|---|
| `countFailure` 整体隔离 | **通过** | 【源】`AuditService.java:167-186`：`getIfAvailable()` 在 `try` 内（第 169 行），`Counter.builder(...).register(...).increment()` 同块 |
| `getIfAvailable` 异常不再逸出 | **通过** | 【源】同上，`catch (RuntimeException e)`（第 181 行）覆盖 `BeansException` |
| RuntimeException / Error 边界 | **通过** | 【源】只捕 `RuntimeException`；`Error`（如 `NoClassDefFoundError`）仍会逸出——这是刻意的，符合"不吞掉程序错误" |
| 原始异常未被二次异常覆盖 | **通过** | 【源】两处 catch 均为 `log.error(...)` **在前**、`countFailure(...)` 在后（第 71→73、121→123 行）。即使计数再抛，原始错误已落日志 |
| 失败路径不影响业务 | **通过** | 【源】`persist()` 的 catch 不重抛；`countFailure` 全包 |
| 测试确实跑 | **通过** | 【测】`AuditServiceTest` 10/10；【运】`mvn test 339/339` |

### 1.2 A1-1（P3）"三种 failure stage"的表述与实际计数不一致

【源】实际只有两个 stage tag：`"serialize"`（第 73、95 行）与 `"write"`（第 123 行）。
指标组件自身的失败**无法自我计数**（自指），只能打日志（第 184 行 `[METRICS_FAILED]`、第 171 行 `[METRICS_UNAVAILABLE]`）。

实施报告 §1.2 用"三类边界"描述处理方式——作为**边界划分**是对的，
但容易被读成"三个阶段都有 `audit.failures` 计数"。建议在文档中明确：
**计数覆盖 2 个 stage，metrics 失败只记日志**。

### 1.3 A1-2（P3）顶层没有兜底，`Error` 仍可能穿透到业务

`audit()` 本身没有最外层 guard。若运行期缺 micrometer（`NoClassDefFoundError`，
属 `Error` 而非 `RuntimeException`），异常会穿透 `countFailure` →
穿过 `audit()` 的 catch → 落到业务。

- 当前不成立：micrometer 是编译期依赖，随 jar 打包（【运】容器内 `jar` 含 `BOOT-INF/lib`）；
- 但"审计绝不破坏业务"这句承诺在**极端条件下**不严格成立。要么接受并写清边界，要么加一层
  `catch (Throwable)` 只为**记录后继续**——后者会违反"不吞 Error"，需显式裁决。

### 1.4 A1-3（P3）测试未证明 `countFailure` 真被调用

【测】`metricProviderFailureDoesNotBreakBusiness` 只断言"不抛"。
若将来 `persist()` 改成不调 `countFailure`，该测试仍然通过。
同组另一条 `metricRegistrationFailureDoesNotBreakBusiness` 做得对——它断言了
`registry.find("audit.failures").counter() == null`，证明走到了抛异常分支。
建议 provider 那条也加一个等价锚点（例如断言 provider 的 `getIfAvailable` 被调用过）。

### 1.5 A-1 结论

**修复有效，可以接受**；三条 P3 属表述与测试强度问题，不阻塞。

---

## 2. B-1 · 日志去敏

### 2.1 通过项

| 检查 | 结论 | 证据 |
|---|---|---|
| `logTail` 输出路径清除 | **通过** | 【源】全仓库 `logTail` 仅剩两处：`HttpCollectionClient.java:220`（注释）、`:242`（`logs.size()` 只取行数） |
| `summarize` 不含原文 | **通过** | 【源】第 236-249 行：仅 status/collected/pages/errorCode/message/logLines |
| debug 不再打原文 | **通过** | 【源】`CollectServiceImpl.java:356-359`：`exitCode` + `outputChars` |
| 清洗输出不再进 INFO 日志 | **通过** | 【源】`CollectServiceImpl.java:371-375`：`success` + `outputChars` |
| 敏感内容测试 | **通过** | 【测】`logTailIsNotCopiedIntoOutput`：断言含 `collected/pages/logLines`，且 `doesNotContain` 昵称/抖音号/属地/正文四片段 |
| 旧断言随契约更新 | **通过** | 【测】`failedJobCarriesLogTail` 改为断言 `logLines=1` 且 `doesNotContain("Preflight 失败")` |

### 2.2 B1-1（P2）**仍有一条路径把清洗脚本原文写进数据库**——未修

```
CollectServiceImpl.java:379
    error = "清洗入库失败：" + cleanResult.get("output");
  → markItem(item, "FAILED", "COLLECT_FAILED", error)      // :444
  → patch.setErrorMessage(message.substring(0, 2000))      // :449
  → collection_task_item.error_message（列为 TEXT，Java 截断到 2000 字符）
  → writeLog(... message ...) → :493 截断到 500 字符 → collection_task_log.message（varchar(512)）
```

而 `ai-service/app/clean.py:214` 在逐行入库失败时打印：

```python
print(f"  ⚠️ 单条入库失败（跳过）: {e}")
```

`e` 是 pymysql 异常，`Duplicate entry '...' for key` 这类消息**会带出 cid 或字段值**；
数据截断类异常会带出被截断的内容。也就是说：**清洗失败时，第三方原文仍会落库**。

- 与 B-1 要求"errorMessage、item.error_message、task_log 的传播路径"直接冲突；
- 只在清洗失败路径触发（正常路径输出为空，见上轮 U2），所以定 **P2** 而非 P1；
- 修法（不在本轮）：`error` 只写固定文案 + `outputChars`，或落一个 `clean_failed` 错误码，
  详情由运维按需查。

### 2.3 B1-2（P3）`AuditService` 在序列化失败时把原始 detail 打进 ERROR 日志

【源】`AuditService.java:71`、`:93`：`log.error(... detail={} ..., abbreviate(detail))`。
`audit_log.detail_json` 的列注释明确写着"禁止写密码/Token/完整手机号"（【源】备份 SQL `deploy/backup/*.sql:273`）。
**日志同样应受这条约束**。当前 23 个调用点都不传敏感值，属潜在风险，建议把 `abbreviate(detail)` 换成
`abbreviate(detail)` 的长度或哈希。

### 2.4 B1-3（P2）`KEYWORD_SEARCH` **仍可执行**——只是不经闸门

这是本节最需要澄清的一条。评审任务问"未完成安全审查前是否仍不可执行"，答案是分层的：

| 路径 | 是否可执行 | 证据 |
|---|---|---|
| 作业派发 `POST /v1/jobs` + `capabilityCode=KEYWORD_SEARCH` | **不可执行** | 【运】`/v1/adapters/DOUYIN_PUBLIC` 返回 `implementationStatus=UNVERIFIED`、`evidence=None`；【源】`app/jobs/dispatch.py` 的 `NOT_EXECUTABLE` 判定 |
| `CollectionClient.searchByKeyword` → `ProcessCollectionClient` 子进程 | **可执行** | 【源】`HttpCollectionClient.searchByKeyword` 显式委派给 `processFallback`；`DiscoveryService.invokeSearch` 调用它 |

也就是说：**搜索能力仍在跑，只是绕过了能力准入**。这是我在 C 轮自陈的"已知不一致"，
本轮未改（超出授权），但它意味着"未验证能力不可执行"这条原则在搜索路径上**并不成立**。

- 定级 **P2**：不是新引入，但涉及"未验证能力"的执行面，安全含义比日志问题更重；
- 建议：U2 一并处理，或在 `DiscoveryService` 上加显式开关并在启动日志声明。

### 2.5 B1-4（P3）`DiscoveryService` 遗留已被明确记录，但**未做隔离**

【源】`DiscoveryService.java:268-284`：把搜索脚本非 `VIDEO|` 行拼进
`BizException("搜索脚本执行失败: " + tail)`。报告 §2.3.2 已记录为"未改"。
"已记录"不等于"已隔离"——该异常消息会进 API 响应。低风险（搜索脚本输出不含评论正文），但应登记在案。

### 2.6 B-1 结论

**主线目标达成**（logTail 与 debug 原文已清除、有测试锚点），
但 **B1-1 是一条真实残留**（清洗失败时原文入库），且 **B1-3 澄清了"搜索仍可执行"**。
不能判定为"去敏完成"。

---

## 3. C-4 · 单副本约束

### 3.1 通过项

| 检查 | 结论 | 证据 |
|---|---|---|
| JobQueue 是进程内队列 | **通过** | 【源】`app/jobs/queue.py`：`_queue.Queue` + 单个 `threading.Thread(target=self._loop)`；`_pending.get()` 串行消费 |
| 队列单例 | **通过** | 【源】`server.py`：`_QUEUE` 全局 + `_QUEUE_LOCK` 双检 |
| 进程模型 | **通过** | 【源】`collection.Dockerfile:97` `CMD ["/opt/venv/bin/python", "collection-service/app/server.py"]`；Flask 默认 `debug=False` → 无 reloader，单进程 |
| Chrome profile 共享 | **通过** | 【源】`docker-compose.yml:99`（collection）与 `:162`（backend）同为 `CHROME_USER_DATA_DIR=/app/chrome-data`，且挂同一 volume（`docker inspect` 实测） |
| ADR §4.2.1 | **通过** | 【源】`ADR-007:97` 起，覆盖：硬约束表述、队列限制、锁冲突表现、两个约束的区别、扩容四件套 |
| Compose 标注 | **通过** | 【源】compose collection 服务上方注释块；`docker compose config` 通过 |
| 其他文档冲突 | **无** | 【源】grep `scale|副本|replica` 于 `docs/`、`README`，仅命中"两份证据副本"等无关表述 |

### 3.2 C4-1（P3）"Chrome 锁"在仓库内没有可验证证据

ADR 断言多副本会因 profile 锁失败，但仓库里**没有**任何代码检测 `SingletonLock`，
也没有失败样例日志。这属于 Chrome 的**外部行为常识**，不是本项目可复现的证据。

建议在 §4.2.1 补一句"此结论来自 Chromium user-data-dir 单实例约束，非本项目实测"，
避免后续把它当成实测结论引用。

### 3.3 C4-2（P3）用 Flask 开发服务器承载生产流量（与单副本无关，但同属部署风险）

【源】`CMD ["/opt/venv/bin/python", "collection-service/app/server.py"]`，末尾 `app.run(...)`。
dev server 的并发与超时行为不适合生产；当前靠单副本 + 短作业掩盖。
登记，不属本轮范围。

### 3.4 C-4 结论

**文档目标达成，可以接受**；两条 P3 属证据强度与相邻风险登记。

---

## 4. A-2 · 消费者兼容性

### 4.1 通过项

| 检查 | 结论 | 证据 |
|---|---|---|
| 后端是否解析 detail | **否** | 【源】`AuditLog.detailJson` / `AuditLogView.detailJson` 均为 `String`；`AuditQueryService` 中无 `detailJson` 引用（grep 0 命中） |
| 动态 SQL | **无** | 【源】`AuditLogMapper.xml` 只有 CRUD 的列引用；全仓库 mapper 无 `JSON_EXTRACT`/`JSON_UNQUOTE` 作用于 `detail_json` |
| 前端 | 裸渲染 | 【源】`AuditLogView.vue:21` `prop="detailJson"`，无 `JSON.parse`、无按 action 分支 |
| 报表 / 导出 | 无消费者 | 【源】grep `detail_json|audit_log` 于 `tools/`、`ai-service/`、`collection-service/`：0 命中；`ReportService` 只处理分析报告 |
| 外部调用方 | 无 | 【源】无对外 API 契约文档引用该字段 |

### 4.2 A2-1（P2）混合形态是既成事实，且没有版本标识

【运】`SELECT detail_json, JSON_TYPE(detail_json) ... WHERE action='GENERATE_VIDEO_INSIGHT'`
→ 3 条 `UNSIGNED INTEGER`（历史），修复后同 action 为 `{"text":"…"}`。

没有人消费，所以**现在不构成破坏**；但一旦将来有按 action 聚合/解析的 SQL，
它会同时遇到数字与对象两种类型。**建议冻结一条规则并写进文档**：
`detail_json` 是**不透明 JSON**，任何消费方必须先判 `JSON_TYPE`，禁止假设标量或对象。

### 4.3 A2-2（P3）是否需要格式版本

结论：**不需要**为 detail 增加 `v` 字段——它会改变 23 个调用点的输出形态，
而收益仅服务于不存在的消费者。替代方案是上一条的"不透明"约定。

### 4.4 A-2 结论

**调查覆盖足够、结论成立**（无类型依赖、无需通知外部）。
唯一需要跟进的是把"混合形态 + 不透明约定"固化，属文档动作，**P2 但可延后**。

---

## 5. U1 · 去重架构设计（**不通过，需修订**）

### 5.1 设计的事实基础（复核后成立）

| 项 | 结论 | 证据 |
|---|---|---|
| `comment.cid` 可空、平台内唯一 | **成立** | 【源】`cid varchar(64) DEFAULT NULL`；`UNIQUE KEY uk_platform_cid (platform,cid)`；【运】20 行、0 NULL、20 distinct、长度均 19 |
| `dedup_key` 与 `dedup_strategy` | **成立** | 【源】`dedup_key varchar(64) NOT NULL` + `uk_platform_dedup(platform,dedup_key)`；`clean.py:87 dedup_key_for`：有 cid 时 `key=cid`，否则 sha256 指纹 |
| 按 `video_id` 可检索 | **成立** | 【源】`KEY idx_video_time (video_id, publish_time)` → `video_id` 前缀可用 |
| cid 是否跨视频重复 | **成立（当前 0 例）** | 【运】`GROUP BY cid HAVING COUNT(DISTINCT video_id)>1` → 0 |
| CSV 删除路径 | **成立** | 【源】`clean.py:252 os.remove(filepath)`；`load_existing_cids`（`collector.py`）仅读 CSV |
| 历史 CSV 不可恢复 | **成立** | 【运】`datas` 目录仅剩 `45752568906.csv`（108 字节、1 行 = 表头） |

### 5.2 U1-1（P1）方案 A **扩大了信任边界**——不能按"沿用既有做法"放过

任务卡问"是否违反 Python 不直连 Java 业务数据库的架构边界"。分三层回答：

1. **书面契约**：仓库中**不存在**"Python 不得直连业务库"的明文规定（【源】grep `直连`/`数据库凭据`/`只通过 HTTP` 于 ADR 与架构文档：0 条相关约束）。所以**不算违反成文契约**；
2. **事实现状**：`ai-service/app/clean.py:31-49` 已用 pymysql 直连（`DB_USER`/`DB_PASSWORD`），但它运行在 **backend 容器内**，凭据与 backend 同域（【源】`ProcessAiClient` 以子进程方式在 backend 内执行）；
3. **本次改动**：collection 是**独立容器**，当前**没有任何 DB 环境变量**（【源】`docker-compose.yml` collection 段 grep `DB_|mysql` → 无）。方案 A 会给它新增库凭据 = 凭据面从 1 个容器扩到 2 个。

**判定：不违反成文契约，但改变了既有信任边界，且未在设计里论证这一点。**
我的设计把它当成"只是少一跳"，这是**论证不足**——必须补：谁批准、用什么最小权限、
凭据如何轮换、collection 被拿下时的爆炸半径。

### 5.3 U1-2（P1）应优先采用 A-API，并补齐六项设计（当前缺失）

任务卡要求给出 A-API 方案的批量查询/认证/并发/幂等/失败恢复/回滚。设计文档里**一项都没有**——
这是本次设计的**实质缺口**，不能因为"方案 A 更简单"就跳过。

修订要求（按此逐条补）：

| 维度 | 必须写清的内容 |
|---|---|
| 批量查询 | 接口形态：`GET /internal/collection/dedup?source=&videoId=&cursor=&limit=`；返回 `cids[]`、`fingerprints[]`、`nextCursor`、`truncated`。**禁止一次性返回全量**（单视频万级会打爆内存与 HTTP 响应） |
| 认证 | 复用现有 `X-Internal-Token`（与 `/v1/registry/snapshot` 同一条链），**不新增凭据类型**；接口仅内网可达 |
| 并发 | 读接口幂等；同一视频的采集串行（已由 `countActiveByVideo` + `uk_task_video` 保证）；接口需支持并发读（只读查询，无锁） |
| 幂等 | 读无副作用；重复调用返回同一结果集（可按 `updatedAt` 快照或直接 DB 实时） |
| 失败恢复 | backend 不可达 → **fail-closed**（`DEDUP_SOURCE_UNAVAILABLE`），禁止盲采；部分页失败 → 整次失败（不得用半份集合去采） |
| 回滚 | 开关 `COLLECT_DEDUP_SOURCE=api|csv`；`csv` 为旧行为；api 失效时人工切回 |

**结论：方案 A（直连 DB）降级为"备选"，需架构批准；方案 A-API 为默认推荐。**

### 5.4 U1-3（P2）截断策略未量化

设计 §5.1 写"返回行数异常大（> 阈值）只取最近 N 条 + 告警"，但：
- 阈值、N 都未定；
- "最近"缺排序依据（`publish_time` 可为空，`id` 更稳）；
- 截断会**降低去重能力**（漏判为"新"），需明确"截断即告警 + 记录在 job 状态里"。

建议：阈值按实测分布定（当前单视频 ≤ 20），排序用 `id DESC`，截断时在 job 里写 `dedupTruncated=true`。

### 5.5 U1-4（P2）`video_id ↔ cid` 的反向关系未讨论

`uk_platform_cid` 是**平台内**唯一，若同一 cid 因历史原因挂在别的 `video_id` 下，
按 `video_id` 查询会漏判 → 重复采集。当前实测 0 例（【运】§5.1），但设计必须写明：

- 要么接受（并给出检测 SQL，定期跑）；
- 要么按 `(platform, cid)` 全表判重（成本高，需索引评估）。

**不能默认"不会发生"。**

### 5.6 U1-5（P2）指纹集合的映射只写了一半

`load_existing_cids` 返回 `(cids, fingerprints)` 两个集合。设计只给出 cid 侧 SQL，
未给 `dedup_strategy='FINGERPRINT'` 行的映射（这些行 `cid` 为空、`dedup_key` 是哈希）。
若只回填 cid，**指纹类历史会全部失效** → 这部分评论会被重复采集。

修订：SQL 必须同时返回 `dedup_strategy` 与 `dedup_key`，并写明"指纹行不参与 cid 集合、
参与指纹集合"，且补测试。

### 5.7 U1-6（P3）TOCTOU：窗口存在但当前可控，需写清前提

链路是「采集前读集合 → 采集 → clean 写入」。读与写之间理论上有窗口：

- 同一视频：被 `countActiveByVideo` + `uk_task_video` + 单 worker 串行，窗口不产生实际竞态；
- 不同视频：cid 全局唯一（实测 0 例跨视频），互不影响；
- **真正的风险点是"读集合后、clean 写入前，另一条写入路径插入该视频的评论"**——
  当前只有 clean 会写，而 clean 在采集之后执行，所以同视频不会重叠。

**结论**：按当前并发模型无实际竞态，但这是**依赖"单 worker + 同视频串行"的前提**。
设计里必须显式写出这个前提，并注明"多副本/并行采集时该前提失效"（与 C-4 呼应）。

### 5.8 U1 结论

**不通过，需修订。** 三点必须改：

1. **默认方案改为 A-API**，并补齐六项设计（§5.3）；方案 A 仅作备选且需架构批准；
2. **补指纹集合映射**（§5.6）与**截断策略量化**（§5.4）；
3. **显式写出并发前提与反向关系处理**（§5.5、§5.7）。

原设计的事实核查部分（§5.1）**成立**，硬判据（第二次采集 `collected=0`）**成立且应保留**。

---

## 6. 问题等级汇总

| ID | 事项 | 等级 | 阻断性 |
|---|---|---|---|
| B1-1 | 清洗失败时脚本原文写入 `item.error_message` / `task_log` | **P2** | 需修，不阻断本轮 |
| B1-3 | `KEYWORD_SEARCH` 仍可经子进程执行（绕过准入） | **P2** | 需登记/决策 |
| U1-1 | 方案 A 扩大信任边界，未论证 | **P1** | 阻断 U1 实施 |
| U1-2 | 缺 A-API 六项设计 | **P1** | 阻断 U1 实施 |
| U1-3 | 截断策略未量化 | P2 | 需补 |
| U1-4 | `video_id↔cid` 反向关系未讨论 | P2 | 需补 |
| U1-5 | 指纹集合映射缺失 | P2 | 需补 |
| A2-1 | detail_json 混合形态无书面约定 | P2 | 文档动作 |
| A1-1/A1-2/A1-3 | 表述与测试强度 | P3 | 不阻断 |
| B1-2 | 审计 ERROR 日志打印原始 detail | P3 | 不阻断 |
| B1-4 | DiscoveryService 遗留已记录未隔离 | P3 | 登记 |
| C4-1/C4-2 | 锁结论无实测证据 / dev server | P3 | 登记 |
| U1-6 | TOCTOU 前提未写明 | P3 | 随 P1 一起补 |

---

## 7. 结论

| 工作 | 评审结论 |
|---|---|
| A-1 | **通过**（3 条 P3 建议） |
| B-1 | **未完全通过**：主线达成，但 B1-1 残留（清洗失败原文入库）、B1-3 需明确（搜索仍可执行） |
| C-4 | **通过**（2 条 P3 证据/登记建议） |
| A-2 | **通过**（1 条 P2 文档约定待补） |
| U1 | **不通过，需修订**：默认改 A-API、补六项设计、补指纹与截断、写清并发前提 |

**不能判定"去重方案已可实施"。** 事实核查与硬判据是可用的，但架构方案的选择权
不该由"实现简单"决定——它把库凭据给了一个现在没有库凭据的容器。
