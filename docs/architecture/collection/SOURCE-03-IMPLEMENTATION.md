# SOURCE-03 · 实施说明（03-02 ~ 03-06）

日期：2026-11-16　状态：**已实施并通过验收**
上游：`SOURCE-03-AUDIT.md`（03-01 现状审计与迁移映射）、`SOURCE-02-CONTRACT.md`（契约）

---

## 一、交付物清单

| 编号 | 交付物 | 位置 |
|---|---|---|
| 03-01 | 现状审计与旧字段→v1 Schema 映射表 | `SOURCE-03-AUDIT.md` |
| 03-02 | Adapter Registry | `collection-service/app/registry/adapter_registry.py` |
| 03-03 | Capability Registry | `collection-service/app/registry/capability_registry.py`、`capability.py`、`function_resolver.py` |
| 03-04 | 能力接口 | `collection-service/app/server.py` |
| 03-05 | 错误分类 | `collection-service/app/registry/errors.py` |
| 03-06 | 测试与验收 | `collection-service/tests/test_registry.py`、`test_server.py`、`schema_check.py`；`backend/src/test/java/.../source/CapabilityContractTest.java` |
| 真实接入实现 | 抖音公开网页接入 | `collection-service/app/adapters/douyin/douyin_adapter.py` |
| 真实函数 | 评论/详情/搜索采集 | `collection-service/app/adapters/douyin/collector.py`、`search.py` |
| 验证证据 | 真实验证记录 | `collection-service/app/registry/verification_evidence.json` |
| 验证工具 | 产出证据的脚本 | `collection-service/tools/verify_capability.py` |
| Java 消费端 | 契约解析 | `backend/.../integration/capability/`（5 个类） |

---

## 二、03-02 Adapter Registry

`app/registry/adapter_registry.py`

| 规则 | 实现 |
|---|---|
| 统一注册接口 | `register(adapter, capabilities, ...)` 与 `register_loader(code, loader, ...)` |
| 重复注册拒绝 | `register*` 遇已存在的 `connectorCode` 抛 `CollectorError(DUPLICATE_ADAPTER)`，**不覆盖** |
| 单平台失败不影响其它 | `register_loader` 捕获加载异常 → 该接入标 `FAILED`（`errorCode=ADAPTER_LOAD_FAILED`），其余照常 |
| 注册信息完整 | `connectorCode` / `sourceCode` / `connectorVersion` / `module` / `loadStatus` / `platformScoped` |
| 防无效注册 | 缺 `connector_code` 直接拒绝；`platformScoped` 由 `sourceCode` 是否在 Schema 平台枚举内自动判定 |

**接入口径**：`Connector` = 平台的一种接入实现。抖音首个接入为 `DOUYIN_PUBLIC`；
`STUB_PUBLIC` 是链路验证替身，**不是平台接入**（`platformScoped=false`，无 `sourceCode`）。

---

## 三、03-03 Capability Registry

### 3.1 能力 = 声明 + 函数绑定

`CapabilityRegistry._evaluate()` 对每条声明做两件事：

1. **函数解析**：`adapterFunction` → `importlib` 解析 → 必须是**可调用对象**。
   解析不到 / 不可调用 → 该能力**不进入 Observed 清单**，并产生诊断记录。
2. **声明合规**：入参契约必须是 object 型 JSON Schema；`inputSchema.required`
   必须能被函数签名接住（`videoId` ↔ `video_id` 归一化后比对）；出参契约必须是对象；
   `retryPolicy.retryableErrors` 必须是既有七类 `failure_type` 之一。

### 3.2 三层"存在性"必须分清

| 层次 | 由谁证明 | 本任务的落点 |
|---|---|---|
| 函数**存在** | `importlib` 能解析到属性 | `resolve_reference()` |
| 函数**可调用** | 解析到的对象 `callable()` 为真 | `resolve_callable()` |
| 函数**执行成功** | 真实平台跑通并留下证据 | `verification_evidence.json` |

**Registry 只能证明前两层。** 第三层由 `tools/verify_capability.py` 在真实平台上跑通后写出证据，
代码里的能力声明**一律从 `UNVERIFIED` 起步**，只有证据存在才被 `apply_evidence()` 提升为 `VERIFIED`。

### 3.3 枚举纪律

`implementationStatus` 严格取 SOURCE-02 的六值：
`UNVERIFIED / IMPLEMENTED / VERIFIED / DEGRADED / DISABLED / UNSUPPORTED`。

任务卡示意表里的 `NOT_LOADED`、`INVALID_IMPLEMENTATION` **不是状态**：
- `NOT_LOADED` → 适配器加载状态 `LoadStatus.FAILED`；
- `INVALID_IMPLEMENTATION` → **诊断原因码**（`DiagnosticReason.FUNCTION_UNRESOLVED`），
  出现在 `diagnostics` 区，不进入任何状态枚举。

**正式枚举一字未扩。**

---

## 四、03-04 能力接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 返回真实 Registry 状态（`registry` 段）+ 旧字段（`code`/`msg`/`adapters`）+ 统一信封 |
| GET | `/v1/capabilities` | 旧 `items` **原样保留** + 新 `connectors`（v1 Schema 文档）+ `schemaVersion` + 信封 |
| GET | `/v1/adapters` | Observed 接入清单（含 `loadStatus`、`capabilityCount`、逐项能力）+ `diagnostics` |
| GET | `/v1/adapters/{connectorCode}` | 单个接入详情 + 它的能力 + 诊断 |
| GET | `/v1/adapters/{connectorCode}/capabilities` | 该接入的 Observed 能力（v1 Schema） |

**统一信封**：`{"code":0,"msg":"ok","data":{...},"error":null,"meta":{"traceId":"..."}}`
（`meta.traceId` 沿用调用方传入的 `X-Trace-Id` / `X-Request-Id`，没有就生成）。

**健康检查不触发平台请求**：只读注册表。已有测试
`test_health_does_not_trigger_platform_requests` 用 spy 断言
`/health`、`/v1/capabilities`、`/v1/adapters` 三者都不会调到 `collect_comments`。

**兼容期**：旧字段一个没删，语义也没改。旧 `items[].status` 仍取大写词表
（`AVAILABLE` / `LIMITED` / `PENDING` / `DISABLED`），
布尔位（`comments` / `keywordSearch`）沿用旧口径——**只有兑现且可用的能力才为 true**，
与 `ds_source.capability_json` 里 `"keywordSearch":false,"keywordStatus":"PENDING"` 一致。

---

## 五、03-05 错误分类

`app/registry/errors.py`

- **技术错误码**（稳定契约，19 个）：`CREDENTIAL_MISSING` / `CREDENTIAL_NOT_FOUND` /
  `CREDENTIAL_EXPIRED` / `AUTH_REQUIRED` / `AUTHORIZATION_DENIED` / `RATE_LIMITED` /
  `CONNECT_TIMEOUT` / `UPSTREAM_UNREACHABLE` / `PLATFORM_UNAVAILABLE` / `VIDEO_MISSING` /
  `PARSE_FAILED` / `ADAPTER_ERROR` / `SERVICE_UNAVAILABLE` / `SECRET_STORAGE_UNAVAILABLE` /
  `ADAPTER_LOAD_FAILED` / `FUNCTION_UNRESOLVED` / `DUPLICATE_ADAPTER` / `ADAPTER_NOT_FOUND` /
  `CAPABILITY_DECLARATION_INVALID`
- **映射七类 failure_type**：`AUTHENTICATION` / `AUTHORIZATION` / `RATE_LIMIT` /
  `NETWORK` / `PLATFORM` / `DATA` / `SYSTEM`
- **`retryable` 由 failure_type 决定**：`is_retryable(code)` 直接查表，
  调用方**不允许读 errorMessage 猜**。测试 `test_retryable_derives_from_failure_type` 固住这条。
- **异常 → 错误码**：`classify_exception()` 按异常**类型**（不是文案）翻译，
  未知码兜底到 `ADAPTER_ERROR`。
- 用户取消 → `CANCELLED`，不设 `failure_type`，不计入 `failed_count`（沿用既有约定）。

---

## 六、03-06 测试与验收

### 6.1 Python（容器内）

```bash
cd /app/collection-service && /opt/venv/bin/python tests/test_registry.py   # 23/23
cd /app/collection-service && /opt/venv/bin/python tests/test_server.py    # 17/17
```

| 验收项 | 测试 |
|---|---|
| 正常注册 | `test_adapter_registry_normal_registration` |
| 重复注册被拒 | `test_adapter_registry_rejects_duplicate` |
| 函数缺失 | `test_capability_requires_real_function` |
| 函数不可调用 | `test_capability_rejects_non_callable` |
| 单平台加载失败，其它正常 | `test_adapter_registry_loader_failure_isolated` |
| 无效注册被拒 | `test_adapter_registry_invalid_registration_rejected` |
| 声明与函数签名不匹配 | `test_capability_input_contract_mismatch` |
| UNVERIFIED 不进 AVAILABLE | `test_unverified_capability_never_available` |
| Schema 输出通过校验 | `test_v1_documents_pass_json_schema` |
| 旧接口兼容 | `test_legacy_items_keep_old_shape`、`test_capabilities_legacy_items_preserved` |
| 错误码映射七类 | `test_error_codes_map_to_seven_failure_types` |
| retryable 由 failure_type 决定 | `test_retryable_derives_from_failure_type` |
| 健康检查不触发平台请求 | `test_health_does_not_trigger_platform_requests` |
| 随包 Schema 不漂移 | `test_schema_copy_in_sync` |

**Schema 校验器**：`tests/schema_check.py` 是零依赖的 Draft 2020-12 子集实现
（`$ref`/`type`/`enum`/`const`/`required`/`properties`/`items`/`pattern`/`format` 等）。
环境里若恰好装了 `jsonschema`，会自动优先使用官方实现——两条路径都必须过。

### 6.2 Java 消费端契约测试

`backend/src/test/java/com/coolpilot/insight/source/CapabilityContractTest.java`（9 项）

样本 `backend/src/test/resources/capabilities-sample.json` 是**从真实采集服务导出的响应**，
不是手写假数据——Python 侧契约漂移会直接让这个测试变红。

覆盖：真实响应解析 / 每条能力都带函数引用 / VERIFIED 才可执行 /
UNVERIFIED 不可执行 / **函数缺失 ⇒ 不可执行**（硬规则）/ 旧 `items` 仍可读 /
未知主版本 → `isUnknown()` / 空响应 → UNKNOWN 而非伪造不可用 / 限额与重试策略解析。

### 6.3 真实验证证据

```bash
cd /app/collection-service && /opt/venv/bin/python tools/verify_capability.py \
    --video 7703721523349239951 --max 30 --out /tmp/evidence.json
```

2026-11-16 实测结果（真实抖音页面，单视频、未做批量）：

| 能力 | 结果 | 证据 |
|---|---|---|
| `COMMENT` | ✅ VERIFIED | 采到 19 条，cid 覆盖 **19/19**，3 页 |
| `COMMENT_REPLY` | ✅ VERIFIED | **读取**楼中楼回复 **6** 条（只读能力，不含任何写操作） |
| `CONTENT_DETAIL` | ✅ VERIFIED | 取到标题与描述 |
| `KEYWORD_SEARCH` | ⛔ 保持 UNVERIFIED | **没有验证器**——风控行为未知，无法验证 |

`KEYWORD_SEARCH` 保持 UNVERIFIED 是**机制保证**而不是人工约定：
`tools/verify_capability.py` 的 `VERIFIERS` 表里没有它，
没有验证器的能力**在机制上无法被提升为 VERIFIED**。

> **2026-11-16 补充审查后**：证据文件升级为 schema 2.0，每条记录带
> `contentHash`（记录本体哈希）+ `scope`（平台 / 接入 / 版本 / 访问模式 / 契约版本）
> + `scope.implementationHash`（声明 + 目标函数指纹），并有 30 天有效期。
> 篡改记录、改实现、改版本、超期——四类都会被拒绝并产生诊断。
> 详见 `SOURCE-03-SUPPLEMENT-REVIEW.md` 审查项 1 与 3。

---

## 七、未越界声明

本任务**没有**做：

- ❌ 改 MySQL Schema / 加 Flyway Migration（V30 之后无新增）
- ❌ 改 `ds_account` 与 Collection Task/Run 状态机
- ❌ 新增 Python 任务调度体系（`/v1/jobs` 原样保留，仍为 stub）
- ❌ 引入签名复刻 / 指纹伪装 / 身份铸造 / 代理轮换
- ❌ 把"函数注册成功"当作"平台真实采集验证成功"
- ❌ 降低测试断言换兼容（旧断言全部保留，新断言只增不减）
- ❌ 删除旧接口或旧 Adapter（`/health`、`/v1/capabilities`、`comment.py`、`search.py` 全在）
- ❌ 扩充 `implementationStatus` / `effectiveStatus` 正式枚举

本任务**做了**（明确在范围内）：

- ✅ `comment.py` 的核心逻辑抽到 `collector.py`，`comment.py` 变薄壳，
  **CLI 参数、CSV 文件名/列名/BOM、增量落盘、跨次去重、进度文件全部不变**
- ✅ `search.py` 的 `from browser import build_options` 改为包内/脚本双模式可导入，
  新增可调用 `run_search()`，**stdout `VIDEO|...` 协议不变**

---

## 八、遗留（交给 SOURCE-04）

见 `SOURCE-03-AUDIT.md` §03-01-F：

1. collection-service 进程化（独立容器 vs 并入 backend）
2. `AccountVerificationService` 探活目标错配（现在探的是 ai-service）
3. Java `ds_source.capability_json` 与 `effectiveStatus` 枚举口径统一（`PENDING` 不在枚举内）
4. `/v1/jobs` 改由 Registry 派发
