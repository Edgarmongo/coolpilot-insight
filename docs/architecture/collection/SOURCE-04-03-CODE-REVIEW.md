# SOURCE-04-03 代码与契约评审（Desired / Observed / Effective 核对器）

> 评审性质：**只读独立评审**。未修改任何实现、测试、数据库、Flyway、Schema、前端，
> 未创建/修改 `CapabilityReconciler` 之外的业务代码，未启动 04-06 / 04-07 / 04-08，
> 未改变分支与提交历史。
>
> 评审基线：`4288924`（04-03 实施前的实际父提交）
> 目标提交：`4e534a8`
> 分支：`feat/boot4-jdk21-mybatis`

---

## 一、Git 差异范围

```
git status --short                     → 空（工作区干净）
git diff 4288924 4e534a8 --stat        → 18 files, +2220 / -13
git diff 4288924 4e534a8 -- frontend/ collection-service/ deploy/ → 空
```

| 分类 | 文件 | 授权 |
|---|---|---|
| 新增（10） | `EffectiveStatus` / `EffectiveReason` / `EffectiveCapability` / `DesiredCapability` / `DesiredCapabilitySet` / `DesiredCapabilityParser` / `CapabilityReconciler` / `AccountAvailability` / `DaoAccountAvailability` / `SnapshotFailure` | ✅ 属于 04-03 范围 |
| 修改（4） | `DataSourceView` / `SourceQueryService` / `SourceAccountMapper` / `SnapshotCache` | ✅ 见下方说明 |
| 测试（2） | `CapabilityReconcilerTest`（691 行）/ `DesiredCapabilityParserTest`（149 行） | ✅ |
| 文档（2） | `SOURCE-04-03-IMPLEMENTATION.md` / 工作记忆 | ✅ |

**两处「计划外但必要」的改动，评审确认合理**：

1. `SnapshotCache` 的 `lastError(String)` → `failure(SnapshotFailure)`。
   04-03 必须区分 `REGISTRY_UNREACHABLE` 与 `SCHEMA_UNSUPPORTED`，而字符串只能靠
   `contains` 区分——那正是首轮评审 H1 已否决的做法。改结构化是**同一条原则的延续**，
   不是新引入设计。
2. `SourceAccountMapper.selectBySourceId`（+1 方法）。原 Mapper 只有 `selectAll` 与
   `selectByExternal`，`ACCOUNT_REQUIRED` 判定需要按 source 聚合。

**禁改项核对（实测）**：

| 项目 | 结果 |
|---|---|
| Flyway 版本 | `30`（未新增迁移） |
| `ds_source.capability_json` | `{"comments": true, "commentStatus": "AVAILABLE", "contentDetail": true, "keywordSearch": false, "keywordStatus": "PENDING"}` — **原值未变** |
| `frontend/` `db/migration/` `SOURCE-02-capability-schema.json` | 无改动 |
| Effective 是否落库 | 否（`EffectiveCapability` 无 Mapper / 无表） |
| `collection-service/` | 无改动（本轮纯 Java） |

---

## 二、冻结契约核对

| 冻结依据 | 核对结果 |
|---|---|
| `SOURCE-02` `effectiveStatus` 八值 | ✅ `EffectiveStatus` 八值逐字一致，无增删改（`:36-38`） |
| `ADR-008` status × reason 两层 | ✅ 20 个 reason 独立建模，未混入 status |
| `04-04 §4` 19 步短路顺序 | ⚠️ 见 §三（第 1 项有偏差，其余 18 项一致） |
| `04-04 §2.2` v1→v2 映射 | ✅ 逐条一致，见 §四 |
| `04-04 §2.5` accessModes 保守默认 | ✅ 一致（COMMENT 取 PUBLIC+AUTHENTICATED，其余 PUBLIC） |
| `04-04 §4.4` adapterVersion 可选锁定 | ✅ 声明即精确匹配，未声明的用实际版本 |
| `N7-1` 只遍历 `platformScopedAdapters()` | ✅ `:278` / `:294`，全文无 `snapshot.adapters()` 遍历 |
| `N7-4` 只有 FRESH 可准入 | ✅ `:110-118` |
| `N7-5` 白名单准入 | ✅ `EffectiveStatus.allowsExecution` 为正向白名单，无 `!=` 反转 |

---

## 三、19 步短路顺序对照表

> 冻结顺序来自 `SOURCE-04-04-EFFECTIVE-REASON.md` §4（表格 1~19 行）。

| 冻结序 | Reason | 源码位置 | 触发条件 | 对应测试 | 结论 |
|---|---|---|---|---|---|
| 1 | `SOURCE_DISABLED` | `:100-103` | `source.status != ENABLED` | `reasonSourceDisabled` | ✅ |
| 2 | `REGISTRY_UNREACHABLE` | `:110-114` | `state == UNAVAILABLE` 或 `read == null` | `reasonRegistryUnreachable` | ✅ |
| 3 | `SCHEMA_UNSUPPORTED` | `:115-118` + `:131-133` | STALE 且 failure 属协议问题 | `reasonSchemaUnsupported` | ⚠️ 见 M1（另有 `:105-108` 借用此 reason） |
| 4 | `OBSERVED_STALE` | `:115-118` + `:124-126`、`:134` | STALE 且非协议/非不可达 | `reasonObservedStale` | ✅ |
| 5 | `NOT_DECLARED` | `:146-153` | 找不到声明，或 `desiredStatus=DISABLED` | `reasonNotDeclared` / `reasonNotDeclaredBecauseDisabled` | ✅ |
| 6 | `ADAPTER_NOT_LOADED` | `:157-167` | 非真实平台接入，或 `loadStatus != LOADED` | `reasonAdapterNotLoaded` / `reasonAdapterNotPlatformScoped` | ✅ |
| 7 | `CONNECTOR_VERSION_MISMATCH` | `:170-175` | 锁定版本 ≠ 实际版本 | `reasonConnectorVersionMismatch` | ✅ |
| 8 | `CONTRACT_VERSION_MISMATCH` | `:179-184` | `majorOf(contractVersion) != 1 且 > 0` | `reasonContractVersionMismatch` | ⚠️ 见 L1（`> 0` 使缺失版本放行） |
| 9 | `CAPABILITY_NOT_REGISTERED` | `:187-198` | `observed == null` 且无函数类诊断 | `reasonCapabilityNotRegistered` | ✅ 守卫含"无诊断"，与冻结条件一致 |
| 10 | `FUNCTION_UNRESOLVED` | `:188-194` | `observed == null` 且 `diag.isFunctionProblem()` | `reasonFunctionUnresolved` | ✅ |
| 11 | `CAPABILITY_DISABLED` | `:202-206` | `impl == DISABLED` | `reasonCapabilityDisabled` | ✅ |
| 12 | `PLATFORM_UNSUPPORTED` | `:207-211` | `impl == UNSUPPORTED` | `reasonPlatformUnsupported` | ✅ |
| 13 | `ACCESS_MODE_UNSUPPORTED` | `:214-219` | `intersect(desired, observed)` 为空 | `reasonAccessModeUnsupported` | ✅ |
| 14 | `VERIFICATION_REQUIRED` | `:224-228` | `impl ∈ {UNVERIFIED, VERIFIED}` 且 `evidence == null` | `reasonVerificationRequired` | ❌ 见 **H1**（条件范围不含 IMPLEMENTED） |
| 15 | `EVIDENCE_EXPIRED` | `:229-232` | 同上且 `rejectionReason == EVIDENCE_EXPIRED` | `reasonEvidenceExpired` | ❌ 见 **H1** |
| 16 | `EVIDENCE_INVALID` | `:229-232` | 同上且其它拒绝原因 | `reasonEvidenceInvalid` | ❌ 见 **H1** |
| 17 | `ACCOUNT_REQUIRED` | `:240-245` | 仅 AUTHENTICATED 可用 且 无可用账号 | `reasonAccountRequired` | ✅ |
| 18 | `DEGRADED` | `:248-252` | `impl == DEGRADED` | `reasonDegraded` | ⚠️ 见 H1（未查证据） |
| 19 | `NONE` | `:255-256` | 前面全部通过 | `reasonNone` | ⚠️ 受 H1 影响 |

**顺序保真度**：第 9/10 两步在源码中合并在 `:187-198` 一个块内，但第 9 步的进入条件
显式包含「无函数类诊断」，因此**逻辑顺序等价**于冻结表（先判"确实没注册"，再判"函数不成立"）。
这一点写得对——若把函数诊断问题早早放进"未注册"，就会丢失"它其实声明过"这个事实。

**全局短路的位置**：第 1~4 步被提取为 `globalBlock`（`:97-120`），在逐条判定前统一执行。
这与冻结表的"自上而下短路"语义一致，且实测行为相符——停掉采集服务时，连
`desiredStatus=DISABLED` 的能力也返回 `UNKNOWN / REGISTRY_UNREACHABLE` 而不是
`NOT_DECLARED`，符合"第 2 步优先于第 5 步"。

### 3.1 白名单准入与 executable 一致性

| 检查项 | 结论 |
|---|---|
| 准入是否白名单 | ✅ `:53-60`，仅 `AVAILABLE+NONE` 与 `DEGRADED` 返回 true |
| 是否存在 `!UNREGISTERED.equals(...)` 之类反转 | ✅ 全文无 |
| `executable` 是否由 `(status, reason)` 派生 | ✅ `EffectiveCapability.of` 派生；主构造器 `:35-49` 在不一致时抛异常 |
| 是否存在绕过构造器的路径 | ✅ `record` 无 setter，主构造器为唯一入口；`UNKNOWN` 无法被标成可执行（测试 `effectiveInvariantEnforced`） |
| `UNKNOWN` 是否可能可执行 | ✅ 不可能：`allowsExecution` 对 `UNKNOWN` 返回 false，且构造器拒绝不一致组合 |

---

## 四、Desired v1 / v2 双读审查

### 4.1 v1 映射（`DesiredCapabilityParser:117-136`）

逐条对照 `04-04 §2.2`：

| v1 字段 | 冻结规则 | 实现 | 结论 |
|---|---|---|---|
| `comments:true` | `COMMENT` / `ENABLED` | 同 | ✅ |
| `comments:false` | `COMMENT` / `DISABLED` | 同（`:130-133`） | ✅ |
| `contentDetail` | `CONTENT_DETAIL` / `CONTENT` | 同 | ✅ |
| `keywordSearch` | `KEYWORD_SEARCH` / `SEARCH` | 同 | ✅ |
| `commentStatus` / `keywordStatus` | **丢弃** | 未读取（全文无引用） | ✅ 与 §2.4 一致 |
| `PENDING` | 由 `DISABLED + Observed=UNVERIFIED` 派生 | 运行时实测 `KEYWORD_SEARCH → NOT_DECLARED`，PENDING 未泄漏 | ✅ |
| 字段缺失 | 视为未声明 | `:126-129` 跳过 | ✅ 未猜测 |
| 字段类型非布尔 | 视为未声明 | `!node.isBoolean()` → 跳过 | ✅ |
| `connectorCode` | 显式映射表（§2.3） | `:64-68` 四平台表；查不到则 `unsupported` | ✅ 未用 `adapter_code` 拼接 |
| `accessModes` | 保守默认（§2.5） | `:49-55` 与表一致 | ✅ |

**信息丢失核查**：`commentStatus` 的 `AVAILABLE` 被丢弃，是否丢信息？
不丢——它本就是 Observed 侧事实，运行时由 `implementationStatus` 提供。
实测 `COMMENT` 判 `AVAILABLE / NONE`，与 v1 里 `commentStatus: AVAILABLE` 一致，说明没丢。

### 4.2 v2 严格性（`:141-180`）

| 场景 | 期望（评审要求） | 实现 | 结论 |
|---|---|---|---|
| 缺 `capabilities` 数组 | 失败关闭 | `:143-145` → `unsupported` | ✅ |
| 缺 `code` | 失败关闭 | `:151-153` | ✅ |
| 缺 `connectorCode` | 失败关闭 | `:154-157`，且报错文案点明"多 Connector 必须逐条声明" | ✅ |
| `desiredStatus` 非法 | 失败关闭 | `:158-162`，仅接受 ENABLED/DISABLED | ✅ |
| `accessModes` 为空 | 失败关闭 | `:170-173` | ✅ |
| 未知主版本（≥3） | 失败关闭 | `:103-106` | ✅ |
| `schemaVersion` 格式非法 | 失败关闭 | `:99-102` | ✅ |
| **重复能力（同 connector+code）** | 失败关闭 | **未检查**，`findFirst` 取第一条 | ❌ 见 **M2** |
| `sourceCode` 与 `ds_source.code` 不一致 | 未明确要求 | 不校验（仅原样存入） | ⚠️ 见 L4 |

**v2 整体评价**：除"重复"外，异常输入均 fail-close，且报错文案包含能力码，
可定位到具体条目——这比"整个 JSON 解析失败"有用得多。

---

## 五、候选集合与匹配键

**构造**（`:268-286`）：先放 Desired，再放 Observed（仅 `platformScopedAdapters()`），
`putIfAbsent` 去重，`LinkedHashMap` 保序。

**匹配键**（`:321-323`）：`connectorCode + "/" + code`，**不是**仅用 capability code。

| 场景 | 行为 | 结论 |
|---|---|---|
| Desired + Observed 都有 | 一条候选，正常判定 | ✅（`candidateUnionCoversBothSides`） |
| Desired 有、Observed 无 | 候选保留 → `CAPABILITY_NOT_REGISTERED` | ✅（`reasonCapabilityNotRegistered`） |
| Observed 有、Desired 无 | 候选保留 → `NOT_DECLARED` | ✅（运行时 `COMMENT_REPLY`） |
| 两侧都无 | 空列表 | ✅ 合理 |
| 同名能力不同 Connector | 键含 connectorCode，不会错配 | ✅ 代码正确，但**无测试**（L3） |
| 同一能力不同 accessMode | 不影响候选，只影响第 13 步 | ✅ |
| `platformScoped=false` 的 STUB | 不进候选，也不进 adapter map | ✅（`n71StubProducesNoEffective`） |
| 同一 Source 多 Connector | 键区分，互不干扰 | ✅ 代码正确，但**无测试**（L3） |

> **为什么必须取并集**：只看 Desired 会漏 `NOT_DECLARED`（Python 有、Java 没登记）；
> 只看 Observed 会漏"声明了但没加载"。实现注释把这个理由写清楚了，且运行时
> `COMMENT_REPLY` 恰好证明了前一种情况真实存在。

---

## 六、账号准入

**判定位置**：`:240-245`，条件为
`usableModes 含 AUTHENTICATED && 不含 PUBLIC && !hasUsableAccount`。

`usableModes` 是 Desired 与 Observed 的**交集**（`:214`），不是 Desired 单独的值。
这一点很关键：若某能力 Desired 要 PUBLIC+AUTHENTICATED，而 Observed 只给 AUTHENTICATED，
则 `usableModes = [AUTHENTICATED]`，仍需账号——不会因为"Desired 里写了 PUBLIC"就放行。

| 场景 | 期望 | 实际 | 结论 |
|---|---|---|---|
| PUBLIC + 无账号 | 不要求账号 | `usableModes` 含 PUBLIC → 跳过该步 | ✅ |
| AUTHENTICATED + 无账号 | `ACCOUNT_REQUIRED` | 同 | ✅（`reasonAccountRequired`） |
| AUTHENTICATED + 有效账号 | 继续后续判定 | `hasUsableAccount=true` → 跳过 | ✅ |
| 账号属于其他 Source | 不生效 | `selectBySourceId(sourceId)` 按 source 过滤 | ✅ |
| 账号 `status != ACTIVE` / `auth_status != VALID` / 已过期 | 视为无账号 | `DaoAccountAvailability:31-46` 三项全查 | ✅ |
| 账号状态无法确认（字段 null） | 保守视为不可用 | `!"ACTIVE".equals(null)` → true → continue | ✅ 方向正确 |
| 能力支持多个 accessModes 且有 PUBLIC | 不应要求账号 | `contains(PUBLIC)` → 跳过 | ✅ |

**未把"账号 VALID"等同于"能力 VERIFIED"**：账号只影响第 17 步，证据校验在第 14~16 步
且优先执行——顺序正确，不会因为"有账号"就跳过验证检查。

`hasUsableAccount` 的粒度是 **Source 级**，不是 Connector 级或能力级。这是简化：
若将来同一 Source 下两个 Connector 需要不同账号，会判得过宽。当前只有单 Connector，
不构成缺陷，但值得在文档中记明（见 L5）。

---

## 七、SnapshotFailure 与缓存状态

| 检查项 | 结论 |
|---|---|
| 类型是否完整 | ✅ `kind`（结构化）+ `detail`（人读），无自由文本判断 |
| 错误映射 | ✅ `isUnreachable()` = UNREACHABLE/HTTP_ERROR；`isProtocolProblem()` = MALFORMED/SCHEMA/CONTRACT/INCONSISTENT |
| 是否仍存在 `message.contains` 分类 | ✅ 新代码全文无（唯一 `contains("` 是路径穿越检查，与分类无关） |
| 非法快照是否覆盖合法快照 | ✅ `applyResult` 失败分支只在 `prev != null` 时保留旧快照并标 STALE，不覆盖 |
| FRESH/STALE/UNAVAILABLE 不变量 | ✅ `SnapshotRead` compact constructor 强制三条不变量（未改动，04-02 已审） |
| 只有 FRESH 可参与准入 | ✅ `:110-118` 显式判 state，未用 `snapshot != null` |
| 恢复后自愈 | ✅ 实测：停 collection → 全 UNKNOWN；启 collection → 自动回 FRESH+AVAILABLE |
| 单次请求是否只读一份快照 | ✅ `SourceQueryService.list():57` 取一次，循环内复用同一 `read` |
| 是否会混用不同 snapshotId | ✅ 结构上不可能：`reconcile` 接收的 `read` 内含单个不可变 `RegistrySnapshot`，`adapters` map 与 `capabilities` 都从它派生（`:84-85`） |

**一处保留意见（L2）**：STALE 且 failure 为 `CONTRACT_UNSUPPORTED` 时统一映射为
`SCHEMA_UNSUPPORTED`（`:131-133`）。`SCHEMA_UNSUPPORTED` 的 javadoc 已覆盖
"非法 JSON / 主版本不支持 / 内部不一致"，未提契约；语义上略粗，但不影响准入结论（都是 UNKNOWN）。

---

## 八、API 兼容性

实测 `GET /api/v1/source/sources`（真实容器，FRESH 状态）：

```
source: DOUYIN | status: ENABLED | snapshotState: FRESH
  旧 capability 字段仍在: {'comments': True, 'commentStatus': 'AVAILABLE',
                          'contentDetail': True, 'keywordSearch': False, 'keywordStatus': 'PENDING'}
  desired: [('COMMENT','ENABLED',['PUBLIC','AUTHENTICATED']),
            ('CONTENT_DETAIL','ENABLED',['PUBLIC']),
            ('KEYWORD_SEARCH','DISABLED',['PUBLIC'])]
  effective: COMMENT        -> AVAILABLE   / NONE          | executable: True
  effective: CONTENT_DETAIL -> AVAILABLE   / NONE          | executable: True
  effective: KEYWORD_SEARCH -> UNREGISTERED/ NOT_DECLARED  | executable: False
  effective: COMMENT_REPLY  -> UNREGISTERED/ NOT_DECLARED  | executable: False
```

| 检查项 | 结论 |
|---|---|
| 旧 `capability` 字段 | ✅ 原样保留，老前端零改动 |
| 新字段是否权威 | ✅ `effective` 来自实时核对；`capability` 仅为兼容 |
| status 与 reason 是否成对返回 | ✅ 同一 record 的相邻字段，结构上必然成对 |
| `executable` 由后端产生 | ✅ 派生 + 构造期校验，客户端无法伪造 |
| `UNKNOWN` 是否绝不放行 | ✅ 实测停服时全部 `UNKNOWN` + `executable=false` |
| 旧 API 行为回归 | ✅ 字段只增不改；`list()` 的返回值结构向后兼容（见 O1 关于延迟的说明） |

**额外暴露的信息**：`snapshotState` + `snapshotDetail`（`:46-49`）。这是必要的——
没有它，前端看到 `UNKNOWN` 无法区分"服务挂了"和"没验证"。这与 ADR-008 §4.4
"必须成对展示"的要求一致。

---

## 九、测试执行证据（实际运行）

```
Java 全量      241/241  （Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS）
Python 全量     76/76   （registry 23 + server 17 + evidence 16 + snapshot 20）
容器            5/5 healthy
```

**专项执行**：

| 项目 | 结果 |
|---|---|
| Desired v1/v2 解析器 | `DesiredCapabilityParserTest` 10 条全通过 |
| 19 个可达 reason 正向用例 | 逐条存在（`reasonNone` … `reasonDegraded`），每条的 `@DisplayName` 标注编号 |
| `FUNCTION_NOT_CALLABLE` 不可达 | `functionNotCallableIsUnreachable` ✅（含三种不同 detail 文本都只能产出 `FUNCTION_UNRESOLVED` 的行为断言） |
| N7-1 | `n71StubProducesNoEffective` / `n71DeclaringStubIsAdapterMissing` ✅ |
| N7-2(a)(b) | `n72aVerifiedPlatformButUnverifiedCapability` / `n72bVerifiedDoesNotBypassAccessMode` / `n72bVerifiedDoesNotBypassDesired` ✅ |
| N7-3 | `n73VerifiedButStaleIsNotExecutable` ✅ |
| N7-4 | `n74NonFreshNeverYieldsAvailable` ✅ |
| N7-5 | `n75WhitelistOnly` ✅（含"AVAILABLE 但 reason=DEGRADED 不可执行"这条边界） |
| 账号准入 | `reasonAccountRequired`（AUTHENTICATED 无账号）；DaoAccountAvailability 三条件由代码静态核对 |
| 三种缓存状态 | FRESH/STALE/UNAVAILABLE 各有运行时与单测证据 |
| 真实 HTTP 往返 | `CollectionSnapshotClientTest` 直连容器，不用 assume 跳过 |
| 服务不可达与恢复 | **本轮独立复现**（见下） |

**测试辨别力评价**：

- `n74NonFreshNeverYieldsAvailable` 用 `allSatisfy` 断言**每一条**都是 UNKNOWN 且不可执行，
  不是只看第一条；并且对 STALE 与 UNAVAILABLE 分别构造。
- `n75WhitelistOnly` 覆盖了 `AVAILABLE + 非 NONE 原因` 这个容易漏的反例。
- `effectiveInvariantEnforced` 用 `assertThatThrownBy` 验证构造器确实拒绝非法组合，
  而不是只测正常路径。
- `endToEndWithRealData` 用**真实快照样本** + 库中真实 v1 字符串，且断言了
  `COMMENT_REPLY → NOT_DECLARED` 这个"看起来像缺陷、实际是正确暴露"的结果。

**本轮独立执行的运行时验证（未修改项目文件）**：

```
# 停采集服务 + 重启后端（清缓存）
snapshotState: UNAVAILABLE | 无可用快照
  COMMENT        -> UNKNOWN / REGISTRY_UNREACHABLE | exec: False
  CONTENT_DETAIL -> UNKNOWN / REGISTRY_UNREACHABLE | exec: False
  KEYWORD_SEARCH -> UNKNOWN / REGISTRY_UNREACHABLE | exec: False

# 启动采集服务后（无需干预）
snapshotState: FRESH
  COMMENT        -> AVAILABLE / NONE         | exec: True
  CONTENT_DETAIL -> AVAILABLE / NONE         | exec: True
  KEYWORD_SEARCH -> UNREGISTERED / NOT_DECLARED | exec: False
  COMMENT_REPLY  -> UNREGISTERED / NOT_DECLARED | exec: False
```

注意第一段里 `KEYWORD_SEARCH`（Desired DISABLED）返回 `UNKNOWN` 而不是 `NOT_DECLARED`
——这是**正确**的，符合"第 2 步 REGISTRY_UNREACHABLE 优先于第 5 步 NOT_DECLARED"。

---

## 十、缺陷清单

### H1（HIGH · 代码缺陷）证据校验对 `IMPLEMENTED` / `DEGRADED` 完全不生效

**位置**：`backend/src/main/java/com/coolpilot/insight/source/application/CapabilityReconciler.java:222`

```java
if ("UNVERIFIED".equals(impl) || "VERIFIED".equals(impl)) {
    CapabilityItem.Evidence ev = observed.evidence();
    ...
}
```

**问题**：第 14~16 步只在 `implementationStatus ∈ {UNVERIFIED, VERIFIED}` 时执行。
`IMPLEMENTED`、`DEGRADED` 直接跳过整个证据校验，落入第 19 步判 `AVAILABLE`。

**触发条件**：某能力声明为 `IMPLEMENTED`（Schema 合法值）或 `DEGRADED`。

**实测（本轮的独立探针，用真实 `RegistrySnapshotParser` 解析构造的快照）**：

```
IMPLEMENTED + 无证据        -> AVAILABLE  / NONE              executable=true
IMPLEMENTED + 证据被拒      -> AVAILABLE  / NONE              executable=true
DEGRADED    + 证据被拒      -> DEGRADED   / DEGRADED           executable=true
UNVERIFIED  + 证据被拒(对照) -> UNKNOWN   / EVIDENCE_INVALID   executable=false
```

**为什么这是缺陷，而不是"设计选择"**：

1. **违背已冻结的语义**。`04-04 §4` 第 14 步 `VERIFICATION_REQUIRED` 的定义是
   "能力已注册，但**从未获得有效验证证据**"；`IMPLEMENTED` 的 Schema 语义是
   "已实现且**本地测试通过**"，明确区别于 `VERIFIED`（"已通过**真实平台验证**"）。
   把"本地测试通过"当成"可执行"，等于让未上过真实平台的实现直接放行。
2. **`evidence.accepted=false` 被静默忽略**。这比第 1 点更严重：
   `accepted=false` 的含义是"证据存在但校验失败（篡改/过期/版本不符）"。
   此时仍标 `executable=true`，是**失败开放**（fail-open）——正是 04-02 阶段
   专门修掉的那类问题（`platformScoped` 缺失默认 true）。
3. **04-02 的防线只挡住了 `VERIFIED`**。`apply_evidence` 保证证据被拒时不会变成
   `VERIFIED`，于是 04-02 的断言成立；但 04-03 在另一侧又把 `IMPLEMENTED` 放了过去，
   防线在这里断了。

**影响范围**：当前**尚未实际发生**——现有四个能力声明全是 `UNVERIFIED`，
证据合格后升为 `VERIFIED`，`IMPLEMENTED` / `DEGRADED` 目前无人使用
（`grep implementation_status= collection-service/app/adapters/` 只有 UNVERIFIED）。
因此这是**潜伏型 HIGH**：一旦某个 adapter 将来声明 `IMPLEMENTED`（这是 Schema 允许的），
不需要改任何代码就会把未验证能力标成可执行。

**契约侧的连带问题**：冻结的 16 行推导表**没有 `IMPLEMENTED` 这一行**。
这不是实现的错，但实现不应该在契约沉默时选择 fail-open。

**修复建议**（三选一，需先定契约再改码）：

| 方案 | 做法 |
|---|---|
| A（推荐） | 证据校验对**所有** `impl` 生效（`DISABLED` / `UNSUPPORTED` 除外，它们在第 11/12 步已返回）；`IMPLEMENTED` 显式映射为 `UNKNOWN + VERIFICATION_REQUIRED`，并在 04-04 表补一行 |
| B | 保留 `IMPLEMENTED → AVAILABLE`，但必须要求 `evidence.accepted == true`；同时在 04-04 表补一行说明它与 `VERIFIED` 的区别 |
| C | 若判定 `IMPLEMENTED` 不应出现，在 04-02 解析层直接拒绝该值（但会与 Schema 枚举冲突，不推荐） |

无论哪个方案，`DEGRADED` 都应要求 `evidence.accepted == true`——"降级"是性能/限流语义，
不是"证据无效"的借口。

---

### M1（MEDIUM · 代码/诊断缺陷）Desired 不可解析借用 `SCHEMA_UNSUPPORTED`，且优先级高于 `REGISTRY_UNREACHABLE`

**位置**：`CapabilityReconciler.java:105-108`（位于 `:110` 的快照判定之前）

```java
if (!desired.supported()) {
    return new Blocked(EffectiveStatus.UNKNOWN,
            EffectiveReason.SCHEMA_UNSUPPORTED, "Desired 不可解析: " + desired.error());
}
```

**问题一：原因码借用**。`SCHEMA_UNSUPPORTED` 在 `EffectiveReason:33-34` 的注释里定义为
"快照结构不可用（非法 JSON / 缺 schemaVersion / 主版本不支持 / 内部不一致）"，
指的是 **Observed 快照**。Desired 是 Java 自己的库内配置，两者是完全不同的故障域。
下游看到 `SCHEMA_UNSUPPORTED` 会去排查 Python，实际该查 `ds_source.capability_json`。

**问题二：优先级偏离冻结顺序**。冻结表第 2 步是 `REGISTRY_UNREACHABLE`、第 3 步才是
`SCHEMA_UNSUPPORTED`；而这里 Desired 判定排在快照判定**之前**。当"配置坏了 + 服务挂了"
同时发生时，返回 `SCHEMA_UNSUPPORTED`，与冻结顺序相反。

**影响**：只影响诊断准确性，不影响准入结论（两者都是 `UNKNOWN`、均不可执行）。
但 04-03 的核心价值就是"说清为什么不可用"，原因码指错方向会直接误导排查。

**修复建议**：把该判定移到快照判定之后；并在 04-04 增加一个 Desired 专属 reason
（如 `DESIRED_UNPARSEABLE`，`effectiveStatus=UNKNOWN`），避免与快照语义混用。

---

### M2（MEDIUM · 代码缺陷）v2 重复能力静默接受（first-wins），未失败关闭

**位置**：`DesiredCapabilityParser.parseV2:141-180`（无重复检查）；
`DesiredCapabilitySet.find:46-50`（`findFirst`）

**问题**：v2 对缺字段、非法枚举、空数组都 fail-close，唯独对"同一
`connectorCode + code` 出现两次"不做检查，由 `findFirst` 按数组顺序取第一条。
`collectCandidates` 的 `putIfAbsent` 也按同样顺序静默去重。

**触发条件**：配置里同一条能力被写了两遍，例如：

```json
{"connectorCode":"DOUYIN_PUBLIC","code":"COMMENT","desiredStatus":"DISABLED", ...},
{"connectorCode":"DOUYIN_PUBLIC","code":"COMMENT","desiredStatus":"ENABLED",  ...}
```

**影响**：结果取决于数组顺序，且**没有任何诊断**。对"配置即权威"的 Desired 来说，
两条互相矛盾的声明应当被拒绝，而不是让最后编辑的人猜。这与项目一贯的
"不静默不猜测"原则不一致。

**修复建议**：`parseV2` 内用 `Set` 检测重复键，命中即 `unsupported("v2 存在重复能力: X/Y")`。
成本约 5 行。

---

### L1（LOW · 代码缺陷）`contractVersion` 缺失时跳过契约校验

**位置**：`CapabilityReconciler.java:179-180`

```java
if (observed != null && majorOf(observed.contractVersion()) != SUPPORTED_CONTRACT_MAJOR
        && majorOf(observed.contractVersion()) > 0) {
```

`majorOf` 对 null/空/非数字返回 0，`> 0` 使这些情况直接跳过校验。
而 `RegistrySnapshotParser` 只拒绝"缺 code / 缺 adapterFunction"，不拒绝缺 `contractVersion`。
结果：一条没有契约版本的能力会被当作契约兼容。
当前真实快照里 `contractVersion` 恒为 `"1.0"`，故未实际触发。

**建议**：缺失视为不兼容（fail-close），或在解析层把 `contractVersion` 列为必需字段。

---

### L2（LOW · 语义精度）STALE 的 `CONTRACT_UNSUPPORTED` / `INCONSISTENT` 统一映射为 `SCHEMA_UNSUPPORTED`

**位置**：`CapabilityReconciler.java:131-133`

`isProtocolProblem()` 把四种失败合并成一个 reason。`SCHEMA_UNSUPPORTED` 的 javadoc
覆盖了"非法 JSON / 主版本不支持 / 内部不一致"，但没覆盖"契约主版本不兼容"。
不影响准入，仅损失一点区分度。

---

### L3（LOW · 测试缺陷）跨 Connector 同名能力无测试

**位置**：`CapabilityReconcilerTest` 全文

匹配键 `connectorCode + "/" + code` 在代码上是正确的（`:321-323`），
但没有任何用例构造"两个 Connector 下都有 `COMMENT`"的场景。
`candidateUnionCoversBothSides` 只覆盖了"同一 Connector 不同 code"。
评审要求里明确列了"同名 capability 不同 connector"与"同一 Source 多 Connector"两项，
当前仅靠代码审读保证，缺少回归保护。

**建议**：补一条用例，断言两个 Connector 的 `COMMENT` 各自独立判定，且
`ADAPTER_NOT_LOADED` 只影响缺失的那个 Connector。

---

### L4（LOW · 一致性）v2 不校验 `sourceCode` 与 `ds_source.code` 一致

**位置**：`DesiredCapabilityParser:175`

v2 条目里的 `sourceCode` 被原样存入 `DesiredCapability`，从不与 `ds_source.code` 比对。
若配置写错（如 DOUYIN 的配置里写了 `XIAOHONGSHU`），核对仍按 `connectorCode` 进行，
不会报错，但 `desired` 输出里会带着一个错误平台码，前端展示会自相矛盾。

**建议**：解析时校验 `sourceCode == ds_source.code`（不等即 `unsupported`），或在 v2 里
把它降为可选字段并在缺省时填充。

---

### L5（LOW · 设计说明缺失）账号可用性是 Source 级而非 Connector 级

**位置**：`SourceQueryService:104` → `accountAvailability.hasUsableAccount(ds.getId())`

判定粒度是"该 Source 下是否存在任一可用账号"，不区分 Connector，也没有"用哪个账号"的概念。
当前只有单 Connector，不构成缺陷；但 04-06/后续多 Connector 时会出现"有账号但账号不属于该
Connector"的误判为可用。

**建议**：在 `SOURCE-04-03-IMPLEMENTATION.md` 的边界章节记明这一简化，
或把接口签名改为 `hasUsableAccount(sourceId, connectorCode)`（现在改成本很低）。

---

### O1（观察，非缺陷）`list()` 在读接口里触发一次可能的网络刷新

**位置**：`SourceQueryService:57`

`GET /api/v1/source/sources` 现在会调用 `snapshotCache.get()`，缓存未命中或超 TTL 时
在**请求线程**上发起一次 HTTP（超时 2s/5s）。单飞（single-flight）保证并发请求只打一次，
退避保证失败后 5 秒内不重试，所以最坏情况是"重启后首个请求多等 ≤5s"。

这是 04-02 设计（快照为单位缓存）的自然结果，不是缺陷。但需要知道：
**读接口的可用性从此依赖采集服务的可达性延迟**（虽然不会因此报错）。

**建议**：在实施文档中记明，并在 04-08 集成测试里覆盖"采集服务超时下列表接口的响应时间"。

---

## 十一、缺陷汇总

| 编号 | 级别 | 分类 | 位置 | 当前是否实际触发 |
|---|---|---|---|---|
| **H1** | **HIGH** | 代码缺陷（fail-open） | `CapabilityReconciler:222` | ❌ 潜伏（无 adapter 声明 IMPLEMENTED/DEGRADED） |
| M1 | MEDIUM | 代码/诊断缺陷 | `CapabilityReconciler:105-108` | ✅ 仅在 Desired 非法时 |
| M2 | MEDIUM | 代码缺陷（静默接受） | `DesiredCapabilityParser:141-180` | ❌ 潜伏（当前配置无重复） |
| L1 | LOW | 代码缺陷（fail-open） | `CapabilityReconciler:179-180` | ❌ 潜伏 |
| L2 | LOW | 语义精度 | `CapabilityReconciler:131-133` | ✅ 仅在 STALE 时 |
| L3 | LOW | 测试缺口 | `CapabilityReconcilerTest` | — |
| L4 | LOW | 一致性 | `DesiredCapabilityParser:175` | ❌ 潜伏 |
| L5 | LOW | 文档缺失 | `SourceQueryService:104` | — |
| O1 | 观察 | 设计后果 | `SourceQueryService:57` | ✅ 每次缓存未命中 |

---

## 十二、评审结论

**FAIL（不通过）** — BLOCKER 0 / **HIGH 1** / MEDIUM 2 / LOW 5 / 观察 1。

按既定门禁（BLOCKER = 0 且 HIGH = 0 才通过），存在 1 个 HIGH，不满足通过条件。

### 但需要区分"做得怎么样"和"能不能过"

**做得对的部分（应保留，不要在修复时改坏）**：

1. **三态隔离是结构性的，不是约定性的**。`DesiredCapability` 里没有任何实现态字段，
   `EffectiveCapability` 是不可变派生对象且无 Mapper——"不落库"靠类型系统保证，
   不靠人记得。
2. **准入是正向白名单**，且 `executable` 在构造期校验。这堵住了"未来新增状态被默认放行"
   这条最难发现的漏洞。
3. **候选集合取并集并写清了理由**，运行时 `COMMENT_REPLY → NOT_DECLARED` 证明这个设计
   真的抓到了"Python 有、Java 没登记"这种信息不对称，而不是被旧布尔值掩盖。
4. **第 9/10 步的守卫写法**（"未注册"要求"无函数类诊断"）保留了"它其实声明过"这个事实，
   没有把两种情况糊成一类。
5. **v1 映射严格照 §2.2**，状态词按 §2.4 丢弃且实测证明信息没丢。
6. **测试有辨别力**：`allSatisfy` 断言全部而非首条、`assertThatThrownBy` 验证构造器拒绝、
   真实样本端到端且断言了"看起来像缺陷其实正确"的那一条。

**为什么 H1 必须修**：它不是"当前会出事"，而是"当前不出事靠的是巧合"——
四个能力声明全为 `UNVERIFIED` 只是因为还没人用 `IMPLEMENTED`。
这类"潜伏的 fail-open"恰恰是 04-02 阶段已经付过代价的问题（`platformScoped` 默认 true），
同一个模式不该在 04-03 再出现一次。

**M1 建议同批修**：它只影响诊断方向，但 04-03 的全部价值就是"说清为什么不可用"；
把 Desired 故障报成 Python Schema 故障，会在真实排障时浪费最多的时间。

**修复顺序建议**：

1. 先在 `04-04` 表里明确 `IMPLEMENTED` 的归属（方案 A 或 B）——这是契约问题，不能由实现单方面决定；
2. 按裁定改 `:222` 的条件（预计 ≤10 行）；
3. 补 `IMPLEMENTED` / `DEGRADED` + 拒绝证据的负向用例（这是 H1 的回归保护）；
4. M2 的重复检测（≤5 行）+ 用例；
5. M1 的顺序调整与 reason 命名。

**本轮未做的事（按授权边界）**：未修改任何实现与测试、未改数据库/Flyway/Schema/前端、
未启动 04-06/04-07/04-08、未改变分支与提交历史。
运行时验证用的探针类编译在临时目录（`%TEMP%\probe`），未写入项目目录，未进版本控制。
