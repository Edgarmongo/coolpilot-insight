# 独立复核 · FIX-02（P1 准入装饰器 / P2 错误脱敏）

> 复核对象：`77c9ee8`（P1）、`eaa075b`（P2）
> 基线：`344bfb5`
> 复核日期：2026-11-17
> 结论：**P1 = PASS WITH FINDINGS；P2 = PASS WITH FINDINGS；总体 PASS WITH FINDINGS**

---

## 0. 复核声明

**这是自评审**（两个提交均由我实施）。处理方式与上一轮一致：
把结论按证据强度分层，凡不能证明的一律归入"无法验证"。

证据标签：

- **【源】** 源码/SQL/配置，附路径与行号——可被任何人复核；
- **【测】** 本次**实际执行**的测试命令与输出（不接受引用报告中的数字）；
- **【运】** 对已部署产物的只读查询（未重启、未触发任务）；
- **【报】** 仅见于实施者报告，本次未能独立验证。

未做：未改源码/测试/配置/文档（仅新增本报告）、未改库与 Flyway、未采集、未重启容器、
未启动 U1/DOC-FIX/U2/U4/SOURCE-05。

---

## 1. P1 · CollectionClient 统一准入装饰器

### 1.1 接口与实现（【源】）

```
CollectionClient（integration/collection/CollectionClient.java:11-22）
├── CommandResult collectComments(...)      ← 2 个抽象方法，无 default 方法
└── CommandResult searchByKeyword(...)

实现（grep "implements CollectionClient" → 3 个）
├── AdmissionGatedCollectionClient   （装饰器，collection/application）
├── HttpCollectionClient             （HTTP 作业；内部持有 ProcessCollectionClient 作 search 回退）
└── ProcessCollectionClient          （子进程，@Deprecated）
```

### 1.2 调用路径图（【源】）

```
                       ┌──────────────────────────────┐
CollectServiceImpl ───▶│                              │
  (:81 注入)            │  AdmissionGatedCollectionClient
                       │    collectComments → guard(COMMENT)
DiscoveryService  ────▶│    searchByKeyword → guard(KEYWORD_SEARCH)
  (:54 构造注入)         │                              │
                       └──────────────┬───────────────┘
                                      │ 仅当 guard 放行
                                      ▼
                        ┌─────────────┴──────────────┐
                        │                            │
                HttpCollectionClient        ProcessCollectionClient
                  （executor=http）            （executor=process）
                        │
                        └── searchByKeyword → processFallback（当前不可达，见 1.5）
```

**注入点核对**：全仓库只有两处按类型注入（`CollectServiceImpl:81` 字段、
`DiscoveryService:54` 构造）。未发现 `getBeansOfType`、`getBean(CollectionClient.class)`、
`@Resource(name=...)` 或按实现类注入（【源】grep 0 命中）。

### 1.3 Bean 装配（【源】）

| Bean | 位置 | 说明 |
|---|---|---|
| `rawCollectionClient` | `integration/collection/CollectionClientConfig.java:31` | 显式命名，避免被按类型抢注 |
| `collectionClient`（`@Primary`） | `collection/application/CollectionClientGateConfig.java:26-29` | 依赖 `@Qualifier("rawCollectionClient")` + 守卫 |

`HttpCollectionClient` / `ProcessCollectionClient` **均无** `@Component`/`@Service`（【源】）。
因此按类型注入只能拿到 `@Primary` 的那个。

**【运】装配证据**：已部署容器启动日志

```
[collection] 采集执行已统一经过能力准入：实现=HttpCollectionClient
```

jar 内确认含 `AdmissionGatedCollectionClient.class` 与 `CollectionClientGateConfig.class`（【运】）。

### 1.4 准入覆盖矩阵（【源】）

| 执行方法 | 闸门检查的能力 | 拒绝时委托调用 | 平台请求 | 子进程 |
|---|---|---|---|---|
| `collectComments` | `COMMENT`（`AdmissionGated:48`） | 0 次 | 无 | 无 |
| `searchByKeyword` | `KEYWORD_SEARCH`（`AdmissionGated:57`） | 0 次 | 无 | 无 |

守卫判定链（【源】）：`CollectionAdmissionGuard.requireExecutable`
→ `SourceQueryService.findByCode`（`:64`，读 DB + `snapshotCache.get()`）
→ `CapabilityReconciler` 产出的 `EffectiveCapability`
→ 命中 `UNREGISTERED` / `NOT_EXECUTABLE`（`implementationStatus != VERIFIED` 或
`evidence.accepted != true`）/ `UNKNOWN`（快照不可达）即抛 `BizException(STATE_CONFLICT)`。

### 1.5 未验证能力的拒绝证据

**【运】能力状态**（只读查询 `GET /v1/adapters/DOUYIN_PUBLIC`）：

| 能力 | implementationStatus | evidence.accepted |
|---|---|---|
| COMMENT | VERIFIED | true |
| COMMENT_REPLY | VERIFIED | true |
| KEYWORD_SEARCH | **UNVERIFIED** | **None** |

即：`KEYWORD_SEARCH` 必然命中 `NOT_EXECUTABLE` 分支 → 搜索被拒。
同时 `COMMENT` 仍为 VERIFIED → **正常采集不会被误伤**（行为不变）。

`HttpCollectionClient.searchByKeyword` 内的子进程委派在当前状态下**不可达**：
调用必然先在装饰器被拒（【源】`AdmissionGated:57` 在委托之前）。

> **未做的运行时验证**：实际触发一次关键词任务、观察拒绝响应。
> 原因：触发器会写入任务/日志行（DML），本轮禁止；且禁止真实采集。
> 已在 §5 给出可复现方案。

### 1.6 委托零调用测试证据（【测】）

本次实际执行：

```
mvn -o test -Dtest='AdmissionGatedCollectionClientTest,CollectionAdmissionTest'
→ AdmissionGatedCollectionClientTest  Tests run: 4, Failures: 0, Errors: 0
→ CollectionAdmissionTest             Tests run: 6, Failures: 0, Errors: 0
```

`AdmissionGatedCollectionClientTest` 用"记录调用次数的假实现"充当委托，
断言 `delegate.searchCalls == 0` / `collectCalls == 0`（【源】测试文件 90、103、131 行）。
"零调用"等价于"不起子进程、不发 HTTP"。

### 1.7 P1 发现

#### F1（P2）"拒绝时零副作用"的表述不准确：仍有内部 HTTP 与 DB 读

- **位置**：`AdmissionGatedCollectionClient:48/57` → `CollectionAdmissionGuard:43` → `SourceQueryService:64-69`
- **事实**：拒绝前必然发生 ① 一次 `dataSourceMapper.selectByCode`（DB 读）
  ② 一次 `snapshotCache.get()`（可能触发对 collection-service 的 `/v1/registry/snapshot` 请求，【源】）。
- **影响**：任务卡要求确认"拒绝时是否发生 HTTP 请求"。准确答案是
  **会向采集服务发一次内部快照请求，但不会向平台发任何请求、不会起子进程**。
  这符合原约束的实质（禁止平台请求），但"零副作用"的说法会误导后续评审。
- **复现**：断网/停 collection 容器 → 搜索请求 → guard 抛 `UNKNOWN/REGISTRY_UNREACHABLE`，
  期间有快照请求与 DB 查询。
- **建议**：在 `AdmissionGatedCollectionClient` 类注释与报告中改写为
  "拒绝时不产生平台请求、不起子进程；仍会做一次准入判定（DB + 内部快照）"。

#### F2（P2）回滚开关不能绕过准入：Registry 故障时 `executor=process` 也会停摆

- **位置**：`CollectionClientGateConfig:26-31`（装饰器包住了**两种**实现）
- **事实**：`process` 回滚路径同样经过 guard。若故障点是 collection-service 的 Registry
  不可达，则 guard 判 `UNKNOWN` → 采集整体停摆，**回滚开关救不回来**。
- **影响**：这是 fail-closed 的必然结果（安全上正确），但运维预案里"切回 process 即可恢复"
  这句话不再成立。属于**运行约束变更**，必须写进部署/回滚文档。
- **复现**：停 `cpi-collection` → 任意采集提交 → 拒绝，且切 `executor=process` 后仍拒绝。
- **建议**：记为已知运行约束；若要保留"Registry 故障时仍可采集"，需要一次**显式**的
  降级决策（例如独立的 `allow-unverified` 开关 + 审计记录），不能靠回滚开关顺带实现。

#### F3（P3）新增 `default` 方法会静默绕过闸门

- **事实**：接口当前 2 个抽象方法；新增**抽象**方法会让装饰器编译失败（这是保护），
  但新增 **`default`** 方法会直接被装饰器继承 → 不经过 guard。
- **建议**：在接口上写明"禁止添加 default 方法；新增能力必须同时改装饰器"。

#### F4（P3）`SOURCE_DOUYIN` 硬编码在装饰器

- **位置**：`AdmissionGatedCollectionClient:32-34`
- **影响**：多平台接入后，所有 Source 都会按抖音的能力判定。
- **建议**：随多平台需求改为按请求携带 source（当前 `CollectionClient` 方法签名不带 source）。

#### F5（P3）装饰器层未直接用**真实** guard 做集成测试

- **事实**：`AdmissionGatedCollectionClientTest` 用的是 `AllowAllGuard` / `DenyGuard` 桩；
  真实 guard 的行为由 `CollectionAdmissionTest`（6 条）单独覆盖。
- **影响**：两者拼起来的链路（装饰器 → 真实 guard → Effective）没有单一测试覆盖。
- **建议**：补一条"真实 guard + 失败快照"的装饰器测试（低优先级）。

---

## 2. P2 · 清洗失败信息脱敏

### 2.1 脱敏函数与调用点（【源】）

```
ai-service/app/redact.py
    error_summary(exc) → "类名"  或  "类名(整数错误码)"
    规则：只放行 ① type(exc).__name__ ② args[0] 若为 int

调用点（clean.py）
    :221  单条入库失败（跳过）: error_summary(e)
    :277  处理失败: error_summary(e)        （process_all）
    :288  处理失败: error_summary(e)        （process_by_names）
```

### 2.2 清洗脚本全部输出点盘点（【源】18 处 print）

| 类别 | 行 | 内容 | 是否含数据 |
|---|---|---|---|
| 计数 | 60、88、230 | 读取/清洗/入库条数 | 否 |
| 标识 | 148、241、244、260 | video.id、文件名、aweme_id、路径 | 否（非评论数据） |
| 分支 | 248、253、265、270、272、278、291 | "文件为空/无有效数据/目录不存在"等 | 否 |
| **异常** | **221、277、288** | `error_summary(e)` | **否（本次修复点）** |

### 2.3 Java 侧错误处理（【源】）

| 位置 | 内容 | 是否含原文 |
|---|---|---|
| `CollectServiceImpl:373-375` | 只取 `cleanResult.get("output").length()` 记日志 | 否 |
| `CollectServiceImpl:377-381` | `error = "清洗入库失败（详见服务端清洗诊断日志）"` | 否 |
| `CollectServiceImpl:358` | `collected.output().length()` | 否 |
| `DiscoveryService:283-289` | `exitCode` + 固定文案；行数/字符数只进 WARN | 否 |
| `CleanServiceImpl:109` | `result.put("output", cmdResult.output())` | **是**（见 F6） |

### 2.4 敏感信息传播矩阵（模拟异常 `FakeDbError(1062, "Duplicate entry 'SIMULATED_CID_123' for key 'uk_test'")`）

| 通道 | 是否可能出现 `SIMULATED_CID_123` / `uk_test` | 依据 |
|---|---|---|
| clean.py **stdout** | **否** | 【测】实跑 `error_summary` → `FakeDbError(1062)`；三处打印均已替换 |
| clean.py **stderr** | **否**（常规路径） | 【源】脚本不向 stderr 写；异常被 `except Exception` 捕获 |
| 应用日志（Java） | **否** | 【源】`CollectServiceImpl` 只记长度；`DiscoveryService` 只记行数 |
| `collection_task_item.error_message` | **否** | 【源】`:379` 固定文案，无拼接 |
| `collection_task_log.message` | **否** | 【源】同上写入路径 |
| **API 响应**（`POST /api/clean/run`） | **否**（正常异常路径）/ **可能**（未捕获 traceback，见 F7） | 【源】`CleanController:31` 原样返回 stdout |
| **API 响应**（发现任务失败） | **否** | 【源】`DiscoveryService:288-289` 固定文案 |

### 2.5 脱敏实测（【测】/【运】）

宿主机：

```
error_summary(FakeDbError(1062, "Duplicate entry 'SIMULATED_CID_123' for key 'uk_test'"))
  → 'FakeDbError(1062)'
  contains SIMULATED_CID_123: False
  contains uk_test: False
  contains Duplicate: False
error_summary(FakeDbError("SIMULATED_CID_123"))   → 'FakeDbError'   （字符串首参不放行）
```

已部署容器内（只读执行，确认镜像内含新模块）：

```
/app/ai-service/app/redact.py  存在（1612 字节）
grep error_summary /app/ai-service/app/clean.py → 5 处（2 import + 3 调用）
E(1062, "Duplicate entry 'SIMULATED_CID_123' for key 'uk_test'") → E(1062)
```

### 2.6 错误码与重试语义未变（【源】）

| 项 | 状态 |
|---|---|
| 失败时 `errorCode` | 仍为 `COLLECT_FAILED`（`:408`），未新增/改名 |
| 失败时 `item.status` | 仍为 `FAILED`（`markItem` 参数未变） |
| 重试判定 | `retry()`（`:566-580`）只看 `retryCount` / `maxRetry` 计数，**不解析 message/errorCode** |
| 重试副作用 | 仍置 `PENDING`、清空 `errorCode`/`errorMessage`、计数 +1 |
| 正常清洗成功路径 | 未改动（仍写「入库成功: N 条」等计数） |

### 2.7 P2 发现

#### F6（P2）`POST /api/clean/run` 仍把脚本 stdout 原样返回前端

- **位置**：`controller/CleanController.java:31` → `CleanServiceImpl:109`
- **事实**：脱敏后 output 里不再有 CID/正文，但仍有**服务器绝对路径**
  （`本地文件已删除: /app/collection-service/datas/xxx.csv`）、video.id、文件名。
- **影响**：属剩余信息暴露（内部路径/目录结构），且它是 F7 的放大器——
  一旦出现未被捕获的 traceback，原始 message 会**直接进 API 响应**。
- **复现**：调用清洗接口，查看响应 `data.output`。
- **建议**：接口只返回结构化摘要（成功/失败、inputRows、insertedRows 等），
  原始 stdout 仅落服务端日志。

#### F7（P3）未捕获异常的 traceback 仍会带 message

- **事实**：`process_file` 与 `process_by_names` 都用 `except Exception` 包裹，
  常规异常已被脱敏；但 `MemoryError` / `KeyboardInterrupt` / 解释器级错误不属于 `Exception`，
  会由 Python 打印完整 traceback（含 message）到 stderr → ProcessRunner 合并进 output。
- **影响**：在 F6 未修之前，该 output 会进 API 响应；Java 侧已不落库。
- **建议**：随 F6 一起修（接口只返回摘要即可消解）。

#### F8（P3）`error_summary` 对 `bool` 首参会打印 `(True)`

- **事实**：`bool` 是 `int` 子类，`isinstance(True, int)` 为真 → 输出 `X(True)`。
- **影响**：无数据泄露，仅可读性。
- **建议**：`type(args[0]) is int` 替代 `isinstance`。

#### F9（P3）Java 侧缺"error_message 不含原文"的自动化断言

- **事实**：Java 的"不拼原文"目前只有源码可证（`:379`），没有测试锁死。
- **建议**：若后续重构该分支，需要一条断言（可用字面量扫描或抽取消息构造函数）。

#### F10（P3）`DiscoveryService` 其它 `e.getMessage()`

- **位置**：`DiscoveryService:232` `log.error("[discovery] 任务 {} 失败: {}", taskId, e.getMessage())`
- **影响**：来源是上游异常（含已脱敏的 BizException），但仍属"异常消息进日志"的同类模式。
- **建议**：随 U2 的异常治理统一处理。

---

## 3. 测试执行记录（本次实际运行，非引用报告）

| 命令 | 结果 |
|---|---|
| `cd backend && mvn -o test` | **343 / 343**，Failures 0，Errors 0，Skipped 0，BUILD SUCCESS |
| `mvn -o test -Dtest='AdmissionGatedCollectionClientTest,CollectionAdmissionTest'` | 4 / 4 + 6 / 6 |
| `cd collection-service && python -m pytest tests -q` | **110 passed** |
| `cd ai-service && python -m pytest tests -q` | **4 passed** |
| 模拟异常（宿主机） | `FakeDbError(1062)`，三段敏感串均不含 |
| 模拟异常（容器内） | `E(1062)` |

任务卡要求逐项对应：

| 要求 | 状态 |
|---|---|
| 准入拒绝路径委托次数为零 | ✅ 测试断言 `searchCalls/collectCalls == 0` |
| `KEYWORD_SEARCH` 未验证时不可执行 | ✅ 【源】+【运】能力状态；端到端触发未做（见 §5） |
| 清洗异常不泄露原始 message | ✅ 【测】宿主机与容器内双处实测 |
| 正常清洗结果不受影响 | ✅ 【源】成功路径 print 与返回值未改动 |
| 错误码与重试语义不变 | ✅ 【源】`COLLECT_FAILED` 与计数式重试未变 |
| Java / Python / 回归均通过 | ✅ 见上表 |

---

## 4. 结论

| 项 | 结论 | 理由 |
|---|---|---|
| **P1** | **PASS WITH FINDINGS** | 闸门位置正确、覆盖两个执行入口、零调用有测试；但 F1（副作用表述）、F2（回滚开关失效）是需要在文档/预案里说清的真实约束 |
| **P2** | **PASS WITH FINDINGS** | 源码级脱敏到位、三通道实测无泄露、错误码与重试未变；但 F6（清洗接口回传 stdout）是残留暴露面，且会放大 F7 |
| **总体** | **PASS WITH FINDINGS** | 两个阻断项已实质修复，无 FAIL 级问题 |

**未达"完全通过"的原因**：F1/F2 是运行约束表述问题、F6 是残留暴露面。
三者都不否定修复本身，但都会在真实故障或审计时造成误判。

---

## 5. 当前无法验证的事项（含可复现方案）

| 事项 | 为什么不能做 | 可复现方案（待授权） |
|---|---|---|
| 关键词搜索被拒的端到端表现 | 需触发发现任务 → 写 `ds_keyword_task`/日志行；本轮禁止 DML 与真实采集 | 隔离库环境下：新建关键词 → `POST /api/keywords/{id}/run` → 断言 HTTP 4xx/5xx 文案含 `KEYWORD_SEARCH` 与 `VERIFICATION_REQUIRED`，且 collection 容器日志无 `/v1/jobs` 新增、无 Chrome 启动 |
| 清洗失败时 `error_message` 实际落库内容 | 需构造非法 CSV 并触发清洗（写 comment 表） | 隔离库：投一份必失败的 CSV（如 cid 超 64 字符）→ 跑采集清洗 → `SELECT error_message FROM collection_task_item` 断言等于固定文案 |
| `executor=process` 回滚路径的实际行为 | 需改配置并重启容器 | 隔离环境：设 `COLLECTION_EXECUTOR=process` 重启 → 提交采集 → 观察仍是"准入拒绝"而非"子进程执行" |
| F6 的实际响应体 | 需调用清洗接口（对生产目录有副作用） | 隔离库 + 单独 datas 目录 |
