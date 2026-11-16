# SOURCE-04-FOLLOWUP 实施报告 · A-1 / B-1 / C-4 修复 + U1 方案设计

> 状态：**A-1、B-1、C-4 已实施**；**U1 只出设计**；A-2 只做消费者调查
> 授权范围：`A-1` `B-1` `C-4` `U1 设计`；**未授权**：U1 实施/回填、U2、U4、SOURCE-05、任何 DDL
> 基线：`0bb5c84`

---

## 一、A-1 · 指标异常隔离

### 1.1 问题

`countFailure()` 的第一行 `meterRegistry.getIfAvailable()` 在 `try` 之外。
该方法只在**审计已经失败之后**被调用，它抛出的 `BeansException` 会从 `audit()` 的
catch 块向外穿透（catch 块内的异常不会被自身捕获），直接落到业务调用方 ——
"审计的旁路故障反向破坏已经成功的业务"。

### 1.2 修复

把 **整个方法体**（含 `getIfAvailable()`）放进 try，并按三类边界分别记录：

| 边界 | 触发 | 处理 |
|---|---|---|
| 序列化 | `toJsonDocument` 抛错 | `log.error [SERIALIZE_FAILED]` + `count(serialize)`；审计行照写、详情置空 |
| 数据库写入 | `auditMapper.insert` 抛错 | `log.error [WRITE_FAILED]` + `count(write)`；不抛出 |
| 指标组件 | provider 抛错 / 注册失败 | `log.warn [METRICS_FAILED]`（带异常类型）/ `debug [METRICS_UNAVAILABLE]`；**不抛出** |

刻意**不写 `catch (Throwable)`**：`Error`（OOM 等）不该被审计旁路吞掉——
要求里"不允许通过吞掉所有异常掩盖其他程序错误"落在这里。

### 1.3 测试（3 个指定场景）

| 场景 | 测试 | 断言 |
|---|---|---|
| 指标组件不可用 | `metricProviderFailureDoesNotBreakBusiness` | `audit()` 不抛 |
| 指标注册失败 | `metricRegistrationFailureDoesNotBreakBusiness` | 不抛，且 `registry.find("audit.failures")` 为空（证明真的走到抛错分支） |
| 正常计数 | `countsBothStages` | `audit.failures{stage=write,action=LOGIN} == 1.0` |

原有 7 条（纯文本/对象/空值/标量/结构化/写失败/无 Registry）保持通过，合计 **10 条**。

---

## 二、B-1 · 日志去敏

### 2.1 问题

`HttpCollectionClient.summarize()` 把 `logTail` **原文**拼进返回值，而采集器日志行是：

```
昵称｜抖音号｜IP属地｜赞数｜cid｜评论正文
```

这条字符串会顺着 `CommandResult.output()` 流进**三个**地方：
应用日志（我新加的 debug）、`collection_task_item.error_message`、
`collection_task_log.message`。三处都不该有个人信息。

### 2.2 修复（改在源头，而不是改打印语句）

| 位置 | 改动 |
|---|---|
| `HttpCollectionClient.summarize()` | **删除 logTail 原文**，改为 `logLines=<行数>` |
| `CollectServiceImpl` 的 debug 日志 | 不再打印 `collected.tail(800)`，改为 `exitCode` + `outputChars` |

为什么改源头：如果只删掉那句 debug，`output()` 里的原文仍会经 `errorMessage` 落进数据库——
治标不治本。源头去掉后，任何下游打印都不会再带出个人信息。

需要看内容时：`GET /v1/jobs/{id}`（服务端有令牌边界）或按 cid 到库里查（有数据权限）。

### 2.3 允许 / 禁止记录的内容

| 允许 | 禁止 |
|---|---|
| `jobId` / `status` / `collected` / `pages` / `finishedReason` / `errorCode` / `durationMs` / `logLines` | 昵称、抖音号、UID、IP 属地、评论正文、Cookie/Token/凭据、原始 logTail |

### 2.4 测试

`logTailIsNotCopiedIntoOutput`：构造含 `绝望的大鼻嘎｜1083262777｜黑龙江｜赞13｜cid=…｜把这视频给俱乐部发过去`
的 logTail，断言输出包含 `collected=5 / pages=1 / logLines=2`，
且**不含**昵称、抖音号、属地、正文四个片段。

> 顺带修正了一条旧断言：`failedJobCarriesLogTail` 原本断言"输出里要有 Preflight 失败"，
> 那正是被本轮禁止的行为。现改为断言保留 `COLLECT_INCOMPLETE` + `logLines=1`，
> 并显式 `doesNotContain` 原文。这是**契约变更导致的断言更新**，不是放宽。

---

## 三、C-4 · 单副本部署约束（文档）

写入 `ADR-007` 新增 **§4.2.1**，同时在 `docker-compose.yml` 的 collection 服务上方加醒目注释。
内容覆盖五项要求：

1. **硬约束表述**（不是"建议"）：只允许 1 个副本，不要 `--scale`；
2. **进程内队列的限制**：`app/jobs/queue.py` 是两个进程内对象（`queue.Queue` + 单 worker 线程），
   不跨进程、无分布式锁；
3. **Chrome Profile 锁冲突**：多副本各起 Chrome 抢同一 `chrome-data` volume，
   表现为**间歇性**启动失败，作业记为 `FAILED/BROWSER_ERROR`，极易误判成平台风控；
4. **两个约束要分开看**：backend 多副本**允许**（无状态、靠 DB 租约），collection 多副本**禁止**；
5. **未来扩容四件套**（缺一不可）：跨进程协调、profile 按副本隔离、作业状态持久化、
   并发控制——任一缺失时扩容只会把"串行"变成"随机失败"。

未修改副本数、未改部署结构、未扩容。

---

## 四、A-2 · 下游消费者调查（只读，未改兼容策略）

| 消费者 | 是否依赖 `detail_json` 的类型 | 证据 |
|---|---|---|
| 后端领域模型 | 否 | `AuditLog.detailJson` / `AuditLogView.detailJson` 都是 `String`，仅透传 |
| 后端查询接口 | 否 | `GET /api/v1/system/audit-logs` 返回分页 DTO，无解析、无按 action 分支 |
| 前端审计页 | 否 | `AuditLogView.vue:21` `prop="detailJson"` 直接渲染文本，无 `JSON.parse` |
| 报表 / 导出 | **无此消费者** | 全仓库无审计导出端点；`ReportService` 只处理分析报告 |
| 外部脚本 / 工具 | 无 | `tools/`、`collection-service/`、`ai-service/` 均不读 `audit_log` |
| 备份 | 是 SQL dump，仅含 DDL | `deploy/backup/*.sql` 不含消费逻辑 |

**结论**：现有消费者全部把该字段当**不透明字符串**，类型变化只影响**展示形态**
（页面从 `7703721523349239951` 变为 `{"text":"7703721523349239951"}`），不构成兼容性破坏。

**建议**：不引入兼容层；若在意观感，可在前端做一次"是 JSON 就格式化、否则原样"的展示优化
（未实施，属 A-2 后续）。

**仍需注意**：库存量数据里同一 action 存在两种形态（历史 `UNSIGNED INTEGER` vs 新 `{"text":…}`），
按 action 做聚合统计的**未来**消费方必须处理这种混合。

---

## 五、U1 · 方案设计（未实施）

见 `SOURCE-04-U1-CID-STORE-DESIGN.md`。核心结论：

1. **去重记忆本来就在库里**（`comment.cid`），CSV 只是它的易失缓存；被删导致"记忆丢失"，
   本质是**拿缓存当权威**；
2. 推荐 **方案 A：采集前直接查 `comment` 表**（只读账号），不新增存储、无需回填、并发天然安全；
3. 回填对方案 A **不必要**；若走 B/C/D 已给出 SQL 与四个必查项（cid 可空 / 唯一 / NULL 参与唯一键 / 指纹行）；
4. 失败必须 **fail-closed**（`DEDUP_SOURCE_UNAVAILABLE`），不允许盲采；
5. **唯一硬判据**：同一视频连续采集两次，第二次 `job.collected == 0`。

未执行回填、未写新 Store、未改 `clean.py`、未新增表、未改 Flyway。

---

## 六、变更范围审计

| 文件 | 类型 | 授权依据 |
|---|---|---|
| `system/application/AuditService.java` | 实现 | A-1 |
| `system/application/AuditServiceTest.java` | 测试 | A-1 |
| `integration/collection/HttpCollectionClient.java` | 实现 | B-1 |
| `collection/application/impl/CollectServiceImpl.java` | 实现（仅日志语句） | B-1 |
| `integration/collection/HttpCollectionClientTest.java` | 测试 | B-1 |
| `deploy/compose/docker-compose.yml` | 注释（无功能变更，`compose config` 校验通过） | C-4 |
| `docs/.../ADR-007-collection-service-deployment.md` | 文档 | C-4 |
| `docs/.../SOURCE-04-U1-CID-STORE-DESIGN.md` | 文档（新增） | U1 设计 |
| `docs/.../SOURCE-04-FOLLOWUP-FIX-REPORT.md` | 文档（新增） | 交付 |

**未触碰**（逐项确认）：

- `SOURCE-02` 正式契约与枚举：无改动；
- `EffectiveCapability` 状态推导：无改动；
- Task / Run 状态机：无改动；
- 数据库 Schema / Flyway：**未新增迁移**；
- `clean.py`、`collector.py`（U2/U1 实施范围）：**未改动**；
- U2（`ProcessRunner` 异常处理）：未改；
- U4（状态模型）：未改；
- SOURCE-05：未启动；
- 任何数据：未删、未改、未采集。

---

## 七、测试结果

| 类别 | 结果 |
|---|---|
| Java 全量 | **339 / 339**（上一轮 335，本轮 +4） |
| 新增 | A-1 三条（provider 抛错 / 注册失败 / 正常计数）、B-1 一条（敏感内容不落日志） |
| 修改 | `failedJobCarriesLogTail` 断言随契约变更更新（见 §2.4） |
| Python | 未变更代码，未重跑（本轮不动 Python 侧） |
| Compose | `docker compose config` 通过 |

---

## 八、遗留

| 项 | 状态 |
|---|---|
| A-2 下游兼容 | 调查完成：无类型依赖；混合形态需未来消费方注意 |
| U1 实施与回填 | 待单独授权（设计已给，含前置条件清单） |
| U2 `ProcessRunner` 吞异常 | 待专项（本轮未改） |
| U4 状态模型 | 暂缓（同意：U1 之前实施会建立在不可靠数据上） |
| A/B/C 第三方独立复核 | 待进行（`SOURCE-04-FOLLOWUP-REVIEW.md` §9.1 为复核清单） |
| SOURCE-04-03 M1/L1 | 仍待单独修复与复核，与本次无关 |
