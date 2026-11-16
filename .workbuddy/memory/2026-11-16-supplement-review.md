# 2026-11-16 工作日志

## SOURCE-04-03-FIX-03：契约统一 + 非法实现状态 fail-closed（36eeb87）

独立复核判 PASS WITH FINDINGS（3 MEDIUM）。本轮只做 P1 三项，FIX-04 待评审通过后再动。

### F1-2 非法 implementationStatus（最重要）
原实现只读不校验，Python 拼错或新增状态会被当成正常值 → 可能判可执行。
修成两层：解析层 `CapabilityStatus.isKnown()` 缺失/null/非法一律 BROKEN_CAPABILITY
拒绝整份快照（不设默认值、不做大小写归一、不跳过单条）；核对层白名单兜底。
报告里写明"拒绝整份而非跳过单条"的理由：跳过等于能力静默消失，更难发现。

### F1-1 UNVERIFIED + 已接受证据被放行
契约写"按不可执行处理"，实现只有 if(IMPLEMENTED) 一个分支。
改成白名单：只有 VERIFIED/DEGRADED 能继续。**改结构而不补值**——
F1-1 与 F1-2 是"没被点名就放行"的两次发作，点名式守卫必然随枚举增长而漏。
新增 unverifiedDetail 区分三种语境（本地测试/数据自相矛盾/不在枚举内）。

### F2-1 §2.7 旧 Reason
04-04 §2.7 与 §3.2 第 4 行冲突，而 §2.7 正是 04-06 迁移最可能读的章节。
已改为 DESIRED_UNPARSEABLE，并连带同步 §3.2.1 的格/新增行/铁律 4、EFFECTIVE-REASON 第 16 行。

测试 Java 259/259（+6）、Python 76/76、5 容器 healthy、真实链路无回归。

**仍未做**：F1-3（ADR 示例 / DEGRADED 忽略 reason）、F1-4（注释步号）、
F2-3 与 O1 的诊断测试 —— 全属 FIX-04；L1~L5 单独核查。


## SOURCE-04-03 修复：FIX-01（H1）+ FIX-02（M1/M2）

评审 edcb034 判 FAIL（H1 HIGH）。按用户裁决分两个独立任务修复，方案 A。

### FIX-01（d6610f9）H1 证据闸门
根因两层：04-04 §3.2 原第 19 行把 {VERIFIED, IMPLEMENTED} 并列判 AVAILABLE（契约错），
代码又把证据校验限定在 UNVERIFIED/VERIFIED（实现错）。
- 契约：§3.2 改 14/15/16/18/19 行；新增 §3.2.1「实现状态 × 证据矩阵」（六值 × 三态 18 格）；
  SOURCE-04-TASKCARD 同步 + 注意 4；ADR-008 §4.3.1 记录"白名单解决结论能否执行、
  证据闸门解决结论怎么得出"。
- 代码：去掉外层 if，改为对所有状态无条件生效；证据有效但 impl==IMPLEMENTED 仍判
  VERIFICATION_REQUIRED；IMPLEMENTED 判定放在证据校验**之后**（否则"证据被篡改"
  会被误报成"从未验证"）。
- 测试 34→41；新增 rejectedEvidenceNeverExecutable 含与 CapabilityStatus.ALL 的
  结构性自检（防"测试清单自己也会漏"）。
- 探针实证：同样输入，修复前全放行、修复后全拒绝。

### FIX-02（d7a4077）M1 + M2
- M1：新增 reason DESIRED_UNPARSEABLE（20→21），位置从链首下移到第 4 位
  （服务不可达是更前置的未知）；新增 DataSourceView.desiredError ——
  因为"两边都坏"时候选为空、返回空列表，调用方看不到任何原因。
  发现：Desired 不支持 + Registry 不可达时无行可标注，这是 L4 的加强版证据。
- M2：parseV2 按 connectorCode+code 检测重复，整份文档拒绝；四类组合测试。

### 教训（值得沉淀）
对"枚举允许的取值"做准入推导必须**穷举全部取值**。只处理常见两值、其余默认落到
最后一步，等于给未列举的值开了一条通往 AVAILABLE 的暗门。这与"禁止 != 反转判断"
是同一错误的两个面：黑名单漏新状态，穷举不全漏旧状态。

Java 253/253、Python 76/76、5 容器 healthy；FRESH/STALE/UNAVAILABLE 与恢复全验证。
零改动：库/Flyway V30/Schema 八值/账号/状态机/前端。


## SOURCE-04-03 代码评审（只读，结论 FAIL）

评审 4e534a8（基线 4288924），仅新增 SOURCE-04-03-CODE-REVIEW.md。

**H1 潜伏型 fail-open**：CapabilityReconciler:222 的证据校验守卫只覆盖
UNVERIFIED/VERIFIED，IMPLEMENTED 与 DEGRADED 直接跳过第 14~16 步落入 AVAILABLE。
用真实 RegistrySnapshotParser + 临时探针实测：IMPLEMENTED+证据被拒 → AVAILABLE、
executable=true。当前未触发（四个声明全 UNVERIFIED），但 Schema 允许 IMPLEMENTED，
一旦声明即误放行。根因是 04-04 冻结表没有 IMPLEMENTED 这一行（契约空白），
但实现不应在沉默处选 fail-open。给了 A/B/C 三方案，建议先定契约再改码。

**M1** Desired 不可解析借用 SCHEMA_UNSUPPORTED 且排在快照判定之前（冻结顺序 2<3），
故障域指错方向。**M2** v2 重复能力静默 first-wins，无诊断。
LOW：contractVersion 缺失跳过校验、STALE 的 CONTRACT/INCONSISTENT 合并为 SCHEMA、
跨 Connector 无测试、sourceCode 不校验、账号粒度 Source 级。

做对：三态隔离靠类型系统、白名单准入 + 构造期校验 executable、候选取并集
（运行时 COMMENT_REPLY→NOT_DECLARED 证明有效）、9/10 步守卫保留"声明过"事实。

测试：Java 241/241、Python 76/76、五容器 healthy；独立复现停服→全 UNKNOWN、
恢复→自动 FRESH+AVAILABLE。Flyway 仍 V30，capability_json 原值未变。


## SOURCE-04-03 实施：Desired / Observed / Effective 核对

FIX-01 通过后启动 04-03。实现三态核对并接入 `/api/v1/source/sources`。

### 新增模型
- `EffectiveStatus`：冻结八值 + **白名单**准入（AVAILABLE+NONE / DEGRADED）
- `EffectiveReason`：20 值，`REACHABLE` 标出 19 个可达（FUNCTION_NOT_CALLABLE 预留）
- `EffectiveCapability`：compact constructor 强制 executable 与 (status,reason) 一致，
  非法组合在构造时即抛，不靠调用方自觉
- `DesiredCapability` / `DesiredCapabilitySet`：**不含任何实现状态**
- `DesiredCapabilityParser`：v1/v2 双读。v1 用确定性映射
  （comments/contentDetail/keywordSearch → 三条 v2 形状），
  connectorCode 由 sourceCode 显式映射（不用 adapter_code 拼，避免大小写误判）

### 核心：CapabilityReconciler
19 步短路，顺序严格按 EFFECTIVE-REASON §4。候选集合取 **Desired ∪ Observed**：
只看 Desired 会漏 NOT_DECLARED，只看 Observed 会漏"声明了但没加载"。

### 两处必要的连带改动
1. `SnapshotCache` 的 `lastError(String)` → `failure(SnapshotFailure)`。
   原因：04-03 要区分 REGISTRY_UNREACHABLE 与 SCHEMA_UNSUPPORTED，
   原来只有字符串，要区分只能 contains —— 正是第一轮 H1 否决过的做法。
2. `SourceAccountMapper` 加 `selectBySourceId`（原只有全表 selectAll）。

### 运行时验证（真实容器，三种状态）
- FRESH：COMMENT/CONTENT_DETAIL = AVAILABLE，KEYWORD_SEARCH/COMMENT_REPLY = NOT_DECLARED
- UNAVAILABLE（停 collection + 重启 backend）：全部 UNKNOWN/REGISTRY_UNREACHABLE，
  全部不可执行 —— N7-4 的运行时证据
- 恢复：自动回到 FRESH + AVAILABLE，无需手工干预

### 真实发现（不是缺陷）
COMMENT_REPLY 在 Python 侧已 VERIFIED，但 Effective 判 NOT_DECLARED ——
因为库中 capability_json 是 v1，没有这个字段。这恰恰证明核对工作正常：
它把"Python 有、Java 没登记"显式暴露出来，而不是被旧布尔值掩盖。
迁移到 v2（04-06）后补声明即可。

### 结果
Java **241/241**（原 197 + Reconciler 34 + Parser 10）；Python 76/76；5 容器 healthy。
零改动：库/Flyway(V30)/Schema/前端/账号状态机全部未动。


## SOURCE-04-02-FIX-01 关闭评审遗留（N1 / N2 + N7 冻结）

04-02 验收通过，但保留 N1、N2 待处理；本轮按用户要求只做前置修复，
不启动 04-03 核心实现。

### N2：platformScoped 改失败关闭（优先项）
原实现"字段缺失 → null → isPlatformScoped() 按 true"= 失败开放，
而 platformScoped=false 正是排除 STUB 的唯一结构化依据。
- Python: AdapterMetadata.__post_init__ 拒绝非 bool（不做事后 bool() 转换，
  bool("false")==True 会把配置错误变成语义相反的快照）
- Java: Parser 新增 Failure.BROKEN_ADAPTER；platformScoped 缺失/null/非布尔
  一律拒绝整个快照；用 psNode.isBoolean() 严格判类型，不用 asBoolean() 容错
- DTO: platformScoped 从 Boolean 改为原始 boolean，null→true 这条路径在
  类型层面消失
- 测试：Java 4 条负向（缺失/null/字符串"false"/数字 0 + 缺 connectorCode）,
  Python 3 条（非 bool 四种取值、true/false 均可、真实快照全为 bool）

### N1：Error 路径也要完成 Future
原 catch(RuntimeException) 不捕获 Error：finally 清了 inFlight 但 future
永不 complete → 等待者永久阻塞。
- 改为 catch (RuntimeException | Error e) → completeExceptionally →
  if (e instanceof Error) throw error（不吞 JVM 级错误）→ finally 清 inFlight
- 关键区分：清 inFlight 解决"后续能重发"，不解决"当前等待者醒来"，两件事
- 测试 ownerErrorStillCompletesWaiters：provider 阻塞在 gate → 确认 owner
  已进入 → waiter 进入 join → 放行抛 StackOverflowError → 断言 waiter 退出、
  Error 被重抛、后续 refresh 能恢复。单独耗时 1.486s（若阻塞会多 3s）

### N7 冻结进 04-03 任务卡
六条硬约束：只遍历 platformScopedAdapters()、platformScoped 不等于已验证、
VERIFIED 不等于可执行、只有 FRESH 可参与准入（STALE/UNAVAILABLE 判 UNKNOWN）、
白名单准入禁止 != 排除法、19 个 reason 正向 + FUNCTION_NOT_CALLABLE 不可达测试。
验收标准同步改为 19+1，并补 N7-1/3/4/5 四条断言。

### 结果
Java 197/197（+5）；Python 76/76（+3）；5 容器 healthy。
真实快照实测：所有 adapter 的 platformScoped 均为 bool，
STUB_PUBLIC=False / DOUYIN_PUBLIC=True。

零改动：库/Flyway(V30)/Desired Schema/账号/状态机/前端全部未动；
未创建 CapabilityReconciler，未实现 Effective，未启动 04-03。


## SOURCE-04-02 第二轮修复（评审 H1/H2 + M1~M8）

第一轮评审不通过（HIGH 2），本轮按四阶段修复。

### F1 结构化错误（H1）
`RegistrySnapshotParser.Failure` 八值枚举 + `Error(Failure, message)`；
`CollectionServiceClient.mapFailure()` 用**穷举 switch**（新增值编译报错），
删除全部 `message.contains`。测试只断言 Failure，不断言文本。

### F2 AdapterView 完整性（H2）
从 5 字段扩到 10 字段（补 platformScoped/module/errorCode/errorMessage/capabilities）；
新增 `platformScopedAdapters()` —— 04-03 排除链路验证替身的唯一结构化入口。

### F3 Single-flight（M1 升级 HIGH / M2 / M6）
`synchronized refresh()` 不构成请求合并（第二线程拿到锁不复查）。
改为 CompletableFuture 协调：锁内只决定 owner，锁外发请求，等待者 join 共享结果。
退避在 `performRefresh()` 开头二次检查。
**明确 `refresh()` 同样受退避约束**——否则主动刷新成了绕过后门。
7 条并发测试：20 线程首次/过期刷新/失败一致性均断言 HTTP 恰好 1 次。

### F4 三态 + 版本独立（M3/M4/M5）
`SnapshotCacheState { FRESH, STALE, UNAVAILABLE }`，usableForExecution 由其派生；
compact constructor 强制三条不变量（非 FRESH 不得标记可执行等）。
Python `SNAPSHOT_CONTRACT_VERSION = "1.0"` 与 `SCHEMA_VERSION = "1.1"` 解绑；
Java `SUPPORTED_CONTRACT_MAJOR` 独立校验。
真实快照现为 contractVersion=1.0 / schemaVersion=1.1，独立性可观测。

### F5 其余
M7 token 测试改本地 HttpServer 验证请求头（确定性）；
M8 Python snapshot() 写明运行期只读前提；
L1 删 Entry 死字段；L3 文档化数组顺序语义。
L2（default=str）/ L4（frozen+可变list）未修，已记录理由。

### 结果
Java **192/192**（原 174 + 解析器 5 + 缓存 8 + 客户端 5）
Python **73/73**
5 容器 healthy；backend→collection 实测 contractVersion=1.0 / schemaVersion=1.1。

零改动：库/Flyway(V30)/Schema 枚举/前端/账号/状态机全部未动；未启动 04-03。


## SOURCE-04-02 实施完成（方案 A：统一 Registry 快照）

用户批准方案 A，进入代码实施。范围严格限定：Snapshot 获取/校验/缓存/Observed 转换，
**不做 Effective 推导**（属 04-03）。

### Python 侧

- `CapabilityRegistry.evaluate_all()`：**一次遍历**同时产出 observed + diagnostics。
  为什么不复用 list_capabilities()+diagnostics()——它们各遍历一次，"一致"靠缓存命中这个
  隐含前提；统一快照要求显式只遍历一次。
- `CollectionRegistry.snapshot()`：一次评估派生 adapters/capabilities/diagnostics，
  `snapshotId = "sha256:" + sha256(canonical_json(body))`，**body 不含 generatedAt**
  （否则每次请求 ID 都变）。
- 新增 `GET /v1/registry/snapshot`；原 `/v1/capabilities`、`/v1/adapters` 完全保留。
- `tests/test_snapshot.py` 17 条。

### Java 侧

新增 `RegistrySnapshot` / `RegistrySnapshotParser` / `CollectionServiceClient` /
`SnapshotProvider` / `SnapshotCache` + 3 个测试类。

**解析器六道拒绝**，最关键的是第 6 道「内部不自洽」，且两条规则**方向相反**：
- `INVALID_IMPLEMENTATION` → 该能力**不应**在 Observed 清单里
- `EVIDENCE_*` → 该能力**应当**在 Observed 清单里（能力兑现了，只是证据无效）
所以不能简单写"两集合不能有交集"——会误杀合法的"已兑现但证据过期"。

**缓存状态机**：单位是完整快照（结构上不可能分开缓存）；
`usableForExecution == fresh`，STALE 与超 TTL 都不可用于执行准入。

### 两个由测试逼出来的真实缺陷

1. **TTL 用 `getSeconds()` 会截断**：TTL=1s 时 1.2s 后被算成 1s，仍判"在 TTL 内"。
   改用 `Duration.compareTo`。
2. **失败时间不能只放 Entry 里**："失败且无历史"时 entry==null，无处记录，
   导致 Registry 不可用期间每次查询都打网络请求。改为独立 `lastFailureAt` 字段
   + 5 秒最小重试间隔。

### 结果

Python **73/73**（23+17+16+17）；Java **174/174**（原 147 + 27）；
含 5 条**真实 HTTP 往返**测试（对 cpi-collection，刻意不用 assume 跳过）；
5 容器 healthy；backend→collection:5001 快照端点连通。

零改动：未改库（capability_json 仍 v1）、Flyway 仍 V30、未改 Schema 枚举、
未改前端/账号/状态机、未启动 04-03、Observed 未回写 Desired。

## SOURCE-04-04 二次评审闭环（纯文档，仍不改代码）

评审裁决三项，全部落地：

**裁决 1：`effectiveReason` 20 值暂不精简，但须补定义表 + 重复审查**
新增 `SOURCE-04-04-EFFECTIVE-REASON.md`：
- 20 值逐一定义（含义/判定条件/数据来源/effectiveStatus 映射）
- 逐对交叉语义审查（12 组对比 + 6 组边界说明）
- 判定顺序 19 步（顺序即优先级）
- 每值至少 1 个正向测试构造方式
- 扩展规则 E1~E5 防语义漂移

**审查发现 1 处真实缺陷（不是语义重复，是数据源不可区分）**：
`FUNCTION_UNRESOLVED` 与 `FUNCTION_NOT_CALLABLE` 在 Python 侧共用同一诊断码
`INVALID_IMPLEMENTATION`（capability_registry.py:150），只靠 `detail` 自由文本区分
（"属性不存在或模块无法导入" vs "引用指向的对象不可调用"）。而 SOURCE-03 已定
「禁止用自由文本做机器判据」→ `FUNCTION_NOT_CALLABLE` **当前不可达**，违反
"每值至少一正向测试"要求。
处置：保留两值（语义确实不同），标注 NOT_CALLABLE 为预留，短期统一映射为
FUNCTION_UNRESOLVED，记 Q9（需 Python 拆子码）。

**裁决 2：Q5 快照一致性必须纳入 04-02**
04-02 任务卡新增 §A（必须同时消费 capabilities + diagnostics）与 §B（快照一致性）：
- 方案 A（统一快照接口 /v1/registry/snapshot）为首选
- 方案 B（双端点 + registrySnapshotId 比对）为退路
- 方案 C（读了就合并）**明确不接受**
- **禁止用 generatedAt 判一致性**，必须用内容哈希或单调版本号
- id 不一致 → 重试 1 次 → 仍不同判 UNKNOWN + OBSERVED_STALE，**禁止合并数据**
- 缓存单位改为**快照**，不是每个端点一份

**裁决 3：UNKNOWN 绝不允许执行**
ADR-008 新增 §4.3：准入用**白名单**（AVAILABLE + NONE / DEGRADED），
禁止写 `!"UNREGISTERED".equals(status)` 这类反转形式（会把 UNKNOWN 和未来新状态默认放行）。
§4.4：API 与前端**必须同时返回/展示** status + reason，不得只给一个 UNKNOWN。

04-03 验收标准同步：20 个 reason 逐条正向用例、判定顺序不得调整、
断言 UNKNOWN 不可执行、断言只用单一快照。

零改动验证：代码/库/Flyway/Schema 全部未动（Flyway 仍 V30，
`capability_json` 仍是 v1 原值）。未启用 04-02/04-03。

## SOURCE-04-04 v2 修订（评审修订指令，仍是纯设计）

评审裁决四项全部采纳：迁移方案 A；Schema/Contract 主版本兼容（Adapter 锁定时精确匹配）；
`connectorCode` 放进**每个能力对象**（支撑多 Connector）；`desired` → `desiredStatus`。

**D5 状态语义修订（最关键）**：
原设计把「已声明 + Observed=UNVERIFIED」折叠为 `UNREGISTERED`，是**事实错误**。
核对 `effectiveStatus` 八值后确认：**没有任何值能表达「已注册但未验证」**——
这是 SOURCE-02 的既存契约缺口（其"核对结果映射"表本身就缺这一行）。

处置：**不加枚举**，改为两层正交表达：
- `effectiveStatus` = 准入分类（冻结八值，Schema 零改动）
- `effectiveReason` = 精确原因（20 值 Java 内部模型，不进 Python 输出）

关键映射修正：
| 事实 | 修前 | 修后 |
|---|---|---|
| 已注册未验证 | `UNREGISTERED` ❌ | `UNKNOWN` + `VERIFICATION_REQUIRED` |
| 证据过期 | `UNREGISTERED` ❌ | `UNKNOWN` + `EVIDENCE_EXPIRED` |
| 证据无效 | `UNREGISTERED` ❌ | `UNKNOWN` + `EVIDENCE_INVALID` |
| 能力未注册 | `UNREGISTERED` ✅ | `UNREGISTERED` + `CAPABILITY_NOT_REGISTERED` |
| 函数解析不到 | `UNREGISTERED` ⚠️ | `UNREGISTERED` + `FUNCTION_UNRESOLVED` |
| 服务不可达 | `UNKNOWN` ✅ | `UNKNOWN` + `REGISTRY_UNREACHABLE` |

**铁律**：`effectiveStatus` 相同不代表事实相同，必须同时看 reason。
展示层只显示 effectiveStatus 会误导（04-07 强制两者都显示）。

**04-02 的接口要求（新发现的约束）**：
要区分"能力未注册"与"函数解析不到"，Java **必须同时读** `/v1/capabilities`
（Observed 清单）和 `/v1/adapters`（diagnostics 含 INVALID_IMPLEMENTATION）。
只读前者会在两者之间**失明**——它们都表现为"没有这条能力"。

产出：
- 新增 `ADR-008-effective-status-reason-separation.md`
- 重写 `SOURCE-04-04-TASKCARD.md`（v2）
- 同步 `SOURCE-04-TASKCARD.md` 的 04-03 推导规则表（16 条，含 reason）
- `SOURCE-02-CONTRACT.md` 补记录契约缺口（**枚举未改**）

零改动验证：Schema 文件未动、代码未动、库数据未动（`capability_json` 仍是 v1 原值）、
Flyway 仍停 V30。未启动 04-02 / 04-03。

## SOURCE-04-04 初版设计冻结（已修订，见上）

SOURCE-03 及其补充审查正式标记完成，方案 A 作为部署基线。
本轮执行 SOURCE-04-04：Desired 数据结构与状态语义定稿，**只审计+设计+冻结**。

产出：`docs/architecture/collection/SOURCE-04-04-TASKCARD.md`，含六份交付物：
1. `capability_json` 真实样本：`{"comments":true,"commentStatus":"AVAILABLE",
   "contentDetail":true,"keywordSearch":false,"keywordStatus":"PENDING"}`（库中唯一一行）。
2. SOURCE-02 v1.1 字段映射：Desired 只存 code/category/accessModes/desired，
   implementationStatus/adapterFunction/evidence/contractVersion **一律不写回 Desired**。
3. 三态转换表：Effective 九条短路规则 + UNKNOWN/UNAVAILABLE 区别。
4. PENDING 裁决：否决"加进枚举"（C），推荐 v2 结构迁移（A）；PENDING 被拆解为
   "声明 + UNVERIFIED → UNREGISTERED"。
5. 版本规则：Schema 主版本 + Contract 主版本 + 可选 Adapter 锁版本；变更即失效缓存。
6. 八场景 + 4 补充分支的测试设计。

关键红线（用户强调）：
- **不把 Python Observed 写回 capability_json**（三态会塌成两态，探测失败会静默清空声明）。
- Effective 是**派生结论**，不落库、不作为第三份事实来源。
- Collection Service 不可达 → UNKNOWN，**不代表所有平台能力失效**。

待评审确认 4 项（阻塞 04-02/04-03）：迁移方案 A/B、版本匹配深度、
connectorCode 是否进 Desired 顶层、desired 字段命名。

## SOURCE-03 补充审查（承接 a0ea22a）

用户在阶段验收时提出五个追问，本轮逐项审查并修复。
审查报告：`docs/architecture/collection/SOURCE-03-SUPPLEMENT-REVIEW.md`。

### 审查结论与处置

| # | 追问 | 结论 | 处置 |
|---|---|---|---|
| 1 | `verification_evidence.json` 是否可信 | **不合格**——原实现只有"约定禁止手改"，无任何机制 | 新增 `app/registry/evidence.py`：证据升级 schema 2.0 |
| 2 | `COMMENT_REPLY` 语义歧义 | 歧义真实存在，但根因是**契约缺一个维度** | Schema 加 `semantics`（枚举仅 `READ`），升 1.1 |
| 3 | `VERIFIED` 适用范围与有效期 | **缺失**——原证据只键入 connector+capability | `scope` 六项绑定 + `implementationHash` + TTL 30 天 |
| 4 | HTTP 服务未部署 | 代码存在、运行时不存在 | 方案 A 独立容器 `cpi-collection`（ADR-007） |
| 5 | `AccountVerificationService` 探活错配 | **缺陷确认**——探的是 ai-service | `ServiceHealthProbe` 显式绑定服务名，两服务独立判断 |

### 关键实现

**证据完整性**（`collection-service/app/registry/evidence.py`，新）
- `contentHash`：记录本体 SHA-256，改动即被发现；
- `scope.implementationHash`：能力声明 + 目标函数源码指纹 → 改了实现旧证据自动失效；
- `EvidenceVerifier` 八项校验：`TAMPERED / STALE / EXPIRED / VERSION_MISMATCH /
  SCOPE_MISMATCH / MALFORMED`，任一不过即拒绝并产生诊断；
- **诚实边界**：`contentHash` 算法公开，能防误改，**不能防恶意伪造**（签名属 SOURCE-09）。

**只读语义**（Schema 1.1）
- 新增 `capabilities[].semantics`，枚举**仅 `READ`** → 写能力在 Schema 里没有表达位置；
- 不改 `COMMENT_REPLY` 这个 code（破坏 1.x 兼容承诺且治标），改契约维度；
- `COMMENT_REPLY` 中文名改「楼中楼回复读取」。

**独立容器**（方案 A）
- `deploy/docker/collection.Dockerfile`（Python 3.12 + Flask + DrissionPage + Chrome）；
- CSV 目录与 backend 共享同一宿主目录 → 现有生产 CSV 链路未被打断；
- Chrome profile 共享 `chrome-data` 卷，**约束：两容器不得并发启动 Chrome**；
- 查询端点在 `INTERNAL_TOKEN` 非空时要求 `X-Internal-Token`；`/health` 保持公开。

**探活修正**
- 新增 `integration/health/ServiceHealthProbe.java`（服务名 + URL 显式绑定）；
- `COLLECTION_SERVICE` 不可达 → `SERVICE_UNAVAILABLE`，**不改 `auth_status`**；
  `AI_SERVICE` 不可达 → 不影响账号判定，仅在 `serviceChecks` 如实报告；
- `VerifyResult` 增加 `serviceChecks`；配置键拆成
  `app.collection-service-url` / `app.ai-service-url`；
- Java 侧同步解析 Schema 1.1 新字段（`semantics` / `evidence`），
  契约样本从**真实运行的容器**重新导出。

### 实测证据

- Python **56/56**（registry 23 + server 17 + evidence 16）
- Java **147/147**（原 138 + 探活 5 + 契约 4）
- 证据四类失效实测：篡改 / 改实现 / 改版本 / 超期 → 全部被拒
- 容器拓扑 5 个，全部 healthy；`cpi-backend` → `collection:5001` 互通
- 账号验证运行时实测：
  - 两服务都可达 → VALID，`serviceChecks` 两项均 reachable=true
  - 停掉 `cpi-collection` → `SERVICE_UNAVAILABLE`，`auth_status` 仍为 VALID，
    `last_error_code` 仍为 NULL（**未被污染**），AI_SERVICE 仍 reachable=true

### 产出文档

- `SOURCE-03-SUPPLEMENT-REVIEW.md`（审查报告）
- `ADR-007-collection-service-deployment.md`（部署架构评审）
- `SOURCE-04-TASKCARD.md`（04-01~04-08 冻结任务卡，04-01/04-05 已完成）

### 仍然遗留（已在任务卡中登记）

- Java 消费 Python `/v1/capabilities` → SOURCE-04-02
- Desired/Observed/Effective 核对 → SOURCE-04-03
- `ds_source.capability_json` 的 `PENDING` 不在 `effectiveStatus` 枚举内 → SOURCE-04-04
- `/v1/jobs` 仍是 stub → SOURCE-06
- 验证证据数字签名 → SOURCE-09
