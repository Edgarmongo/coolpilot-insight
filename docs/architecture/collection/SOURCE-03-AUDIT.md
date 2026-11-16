# SOURCE-03 · 03-01 现状审计与迁移映射

日期：2026-11-16　范围：`collection-service/`、Java 采集调用链、前端消费端
依据：SOURCE-00 外部审计 + SOURCE-01 现状基线 + SOURCE-02 契约冻结
状态：**已执行**，结论用于 03-02 ~ 03-06 的实施

> 本文件只做**审计与映射**，不定义新契约。新契约见 SOURCE-02。

---

## 03-01-A 既有接口的真实实现

### `/health`

旧实现（`collection-service/app/server.py`）：

```python
@app.get("/health")
def health():
    return jsonify({"code": 0, "msg": "ok", "adapters": ["stub"]})
```

**`adapters` 是硬编码字面量**，与运行时实际加载了什么无关。抖音接入早已存在
（`adapters/douyin/comment.py`），但这个接口永远只报 `stub`。

### `/v1/capabilities`

旧实现每次请求都 `StubAdapter()` 化一个假适配器，只输出它自己的声明：

```json
{"items": [{"sourceCode": "STUB", "comments": false, "keywordSearch": false,
            "status": "PENDING", "note": "stub 适配器，仅用于链路验证，不代表真实平台能力"}]}
```

与 v1 Schema 的差距（SOURCE-02 §02-03 已列，这里补齐**字段级**对照）：

| v1 要求 | 旧输出 | 差距 |
|---|---|---|
| `schemaVersion` | 无 | 缺 |
| `connectorCode` / `connectorVersion` | 无 | 缺（无接入实现维度） |
| `capabilities[]` 逐项声明 | 扁平布尔字段 | 粒度不同 |
| `accessModes` | 无 | 缺（无法表达"要不要账号"） |
| `implementationStatus` | `status`（Available/Limited/Pending/Disabled） | 语义不同：旧的是"平台可用性"，新的是"实现状态" |
| `adapterFunction` | 无 | 缺（**最关键**：无法证明能力有实现） |
| `contractVersion` | 无 | 缺 |
| `verifiedAt` | 无 | 缺 |
| `limits` / `retryPolicy` | 无 | 缺 |

---

## 03-01-B 调用方盘点（关键结论）

> 这一节回答任务卡里"确认没有遗漏旧调用方"。

| # | 调用方 | 实际访问的目标 | 结论 |
|---|---|---|---|
| 1 | Java `ProcessCollectionClient` | **不走 HTTP**，用 `ProcessBuilder` 调 `comment.py` / `search.py` | 不受接口变更影响；但 **脚本本身不能删** |
| 2 | Java `CollectServiceImpl` | 读 **CSV 文件** `<aweme_id>.csv`（`appProperties.getDatasDir()`） | 不走 stdout，CSV 格式是硬契约 |
| 3 | Java `DiscoveryService` | 解析 `search.py` 的 **stdout** 协议 `VIDEO\|aweme_id\|title\|author\|likes\|url` | stdout 协议是硬契约 |
| 4 | Java `AccountVerificationService.collectionReachable()` | `GET {app.collection-service-url}/health` | ⚠️ **语义错配，见下** |
| 5 | 前端 `sourceApi.list()` → `/v1/source/sources` | Java `SourceQueryService` 读 `ds_source.capability_json` | **不经过**采集服务；与本次改造解耦 |
| 6 | 前端任何 `.vue` | — | **零处**消费 `capability` 字段（grep `capability\|keywordSearch\|commentStatus` 在 `frontend/src/**/*.vue` 无命中） |
| 7 | `deploy/compose/docker-compose.yml` | flask 容器健康检查打的是 **ai-service** 的 `/health`（`cpi-flask:5000`） | collection-service 的 HTTP 服务**没有被任何容器运行** |

### 两个必须如实披露的既有缺陷

**缺陷 1：`AccountVerificationService` 探的不是采集服务。**
`app.collection-service-url` 在 `application.yml` 里**没有条目**，走的是内联默认值
`http://cpi-flask:5001`→ 实际是 `http://cpi-flask:5000`。而 `cpi-flask` 容器
（`deploy/docker/flask.Dockerfile`）跑的是 **`ai-service/app/server.py`**。
也就是说，账号验证里的"采集服务可达"探的其实是 **AI 服务**。
因为两边 `/health` 都返回 200，所以这个错误一直没暴露。

> **SOURCE-03 不修它**：改 URL 会让验证在采集服务未部署时恒定失败，
> 直接影响 `ds_account.auth_status` 的写回，属于状态机行为变更，超出本任务范围。
> 记入 SOURCE-04 的必办项。

**缺陷 2：collection-service 的 HTTP 服务当前是"离线"的。**
`collection-service/app/server.py` 没有任何容器在跑它（compose 里没有这个服务，
`flask` 容器跑的是 ai-service）。生产采集 100% 走**子进程**老链路。

> 这意味着 SOURCE-03 交付的 Registry 与新接口，在当前部署下**还没有被任何运行时调用方消费**。
> SOURCE-03 提供 `collection-service/tools/verify_capability.py` 与 `/v1/adapters` 等端点，
> 让能力视图**可观测、可验证**；真正的进程内接入留给 SOURCE-04。

---

## 03-01-C 既有 Adapter 实现盘点

| 文件 | 形态 | 能否 import | 能否作为 `adapterFunction` | 说明 |
|---|---|---|---|---|
| `app/adapters/base.py` | ABC + `Capability`/`RawContent`/`RawComment` | ✅ | ❌（抽象） | 旧契约，仅 `comments/content_detail/...` 布尔字段 |
| `app/adapters/stub.py` | `StubAdapter`，实现 `PlatformAdapter` | ✅ | ✅（但**假能力**） | 链路验证替身，能力全 false |
| `app/adapters/douyin/browser.py` | `build_options()` | ✅ | 工具函数 | Chrome 启动配置 |
| `app/adapters/douyin/comment.py` | **模块级脚本** | ❌ **import 即启动浏览器采集** | ❌ | 生产采集的实际载体 |
| `app/adapters/douyin/search.py` | 函数 + `main()` 读 `sys.argv` | ⚠️ 依赖 cwd | ⚠️ | stdout 协议是硬契约 |

**这是 03-02/03-03 的核心工作量**：`comment.py` 在模块顶层就读 `sys.argv` 并采集，
无法作为"可 import 的真实函数"被 Registry 绑定。必须把核心逻辑抽出来，
同时**保持 CLI 契约不变**（Java 的 `python comment.py <id> <max>` 调用方式、
CSV 列名与落盘路径、进度文件、`VIDEO|` stdout 协议全部不变）。

---

## 03-01-D 旧字段 → v1 Schema 映射表

### Python 侧（`Capability` 旧 dataclass → v1）

| 旧字段 | v1 对应 | 迁移处理 |
|---|---|---|
| `content_detail: bool` | `capabilities[] where code=CONTENT_DETAIL` | 拆成独立能力项 |
| `comments: bool` | `capabilities[] where code=COMMENT` | 同上 |
| `keyword_search: bool` | `capabilities[] where code=KEYWORD_SEARCH` | 同上 |
| `author_profile: bool` | `capabilities[] where code=AUTHOR_PROFILE` | 同上 |
| `pagination: bool` | `capabilities[].limits.supportsPagination` | 降级为能力级限额 |
| `status: Available/Limited/Manual/Disabled/Pending` | `capabilities[].implementationStatus` | **语义变更**：改为 `VERIFIED/IMPLEMENTED/DEGRADED/DISABLED/UNVERIFIED/UNSUPPORTED`，旧词表由映射函数保留给兼容端点 |
| `note: str` | `capabilities[].name` + `note` | 拆成"显示名"与"说明" |
| （无） | `accessModes` | 新增：`PUBLIC` / `AUTHENTICATED` |
| （无） | `adapterFunction` | 新增：**能力必须由它证明存在** |
| （无） | `contractVersion` | 新增 |
| （无） | `verifiedAt` | 新增：只由真实验证证据填写 |
| （无） | `limits` / `retryPolicy` | 新增 |

### Python 侧（`/v1/capabilities` 旧响应 → v1）

| 旧位置 | 旧字段 | v1 对应 | 迁移处理 |
|---|---|---|---|
| 顶层 | `items[]` | `connectors[]` | **旧字段保留**，新字段并列提供 |
| `items[]` | `sourceCode` | `connectors[].sourceCode` | 直接对应 |
| `items[]` | `comments: bool` | — | 布尔语义丢失 → 由能力项存在性表达；兼容端点保留布尔 |
| `items[]` | `keywordSearch: bool` | — | 同上 |
| `items[]` | `status` | `capabilities[].implementationStatus` | 映射见 §03-05 兼容口径 |
| `items[]` | `note` | `capabilities[].name` | 降级为说明 |
| 顶层 | — | `schemaVersion` | 新增 |
| 顶层 | — | `connectors[].connectorCode` / `connectorVersion` | 新增 |

### `/health` 旧响应

| 旧字段 | v1 对应 | 迁移处理 |
|---|---|---|
| `code` / `msg` | `code` / `msg` | 原样保留 |
| `adapters: ["stub"]` | `registry.adapters[]`（对象数组，带 `connectorCode`/`loadStatus`/…） | **结构变更**：字符串数组 → 对象数组。旧字段仍保留为字符串数组（元素改为 connectorCode） |
| — | `registry.loadedCount` / `failedCount` / `capabilityCount` / `degraded` | 新增 |
| — | `data` / `error` / `meta` | 新增（统一信封） |

### Java 侧（`ds_source.capability_json`）

| 旧字段 | v1 对应 | 迁移处理 |
|---|---|---|
| `contentDetail` | Desired 侧 `CONTENT_DETAIL` | **本次不动**（禁止改 Schema） |
| `comments` | Desired 侧 `COMMENT` | 同上 |
| `keywordSearch` | Desired 侧 `KEYWORD_SEARCH` | 同上 |
| `commentStatus: "AVAILABLE"` | Desired 侧 Effective 候选值 | ⚠️ `AVAILABLE` 在 SOURCE-02 的 `effectiveStatus` 枚举里，但 Java 这里是**逐能力**字段，语义需在 SOURCE-08 统一 |
| `keywordStatus: "PENDING"` | 同上 | ⚠️ **`PENDING` 不在 `effectiveStatus` 枚举内**（枚举是 `AVAILABLE/ADAPTER_MISSING/VERSION_MISMATCH/DEGRADED/ACCOUNT_REQUIRED/SOURCE_DISABLED/UNREGISTERED/UNKNOWN`）。SOURCE-03 不改这个枚举，只记录差异，留给 SOURCE-08 统一口径 |

---

## 03-01-E 兼容性结论

| 旧行为 | 风险 | 处置 |
|---|---|---|
| Java 调 collection-service 的 `/health`、`/v1/capabilities` | **无此调用方**（全仓 grep 确认） | 自由演进，仍保留旧字段 |
| Java 调 `comment.py` / `search.py` | **有，且是生产主链路** | **脚本与 CLI 契约一字不改**；核心逻辑抽出为可 import 模块，脚本变薄壳 |
| Java 读 `<aweme_id>.csv` | **有** | CSV 文件名、列名、BOM、增量落盘、去重规则全部保持 |
| Java 解析 `VIDEO\|...` stdout | **有** | 协议保持 |
| 前端读 `capability` | **无** | 无影响 |

---

## 03-01-F 需要 SOURCE-04 接手的事项（本任务不处理）

> **2026-11-16 更新**：第 2、3 项已在 SOURCE-03 补充审查中提前落地，
> 详见 `SOURCE-03-SUPPLEMENT-REVIEW.md`。当前真实状态：

| # | 事项 | 当前状态 |
|---|---|---|
| 1 | **collection-service 进程化**：独立容器 vs 并入 backend | ✅ 已定：**方案 A 独立容器**（`ADR-007`），`cpi-collection` 已部署并实测 |
| 2 | **`AccountVerificationService` 探活目标错配** | ✅ 已修：`ServiceHealthProbe` 显式绑定服务名；AI / 采集两服务独立判断；不可达不改 `auth_status` |
| 3 | **Java `ds_source.capability_json` 与 `effectiveStatus` 枚举口径统一**（`PENDING` 不在枚举内） | ⏳ **仍未做** → SOURCE-04-04 |
| 4 | **`/v1/jobs` 仍是 stub 实现** | ⏳ **仍未做** → SOURCE-06（本任务按"不擅自删除旧接口或旧 Adapter"保留该端点与 stub 行为不动） |
| 5 | **Java 消费 Python `/v1/capabilities`** | ⏳ **仍未做** → SOURCE-04-02（SOURCE-03 审计已确认：当时无任何调用方） |
| 6 | **Desired / Observed / Effective 核对** | ⏳ **仍未做** → SOURCE-04-03（架构决策与验收要求仍归 SOURCE-08 名下） |

---

## 03-01-G 验收矩阵 → 实现映射（供 03-06 对照）

| 场景 | Registry 预期 | Effective 预期 | SOURCE-03 落点 |
|---|---|---|---|
| Adapter 和函数均存在，验证通过 | `VERIFIED` | `AVAILABLE` | `verification_evidence.json` + `apply_evidence()` |
| Adapter 和函数存在，尚未验证 | `UNVERIFIED` | 不可标 AVAILABLE | 代码声明一律从 `UNVERIFIED` 起步 |
| Adapter 未加载 | `loadStatus=FAILED`（**不是** implementationStatus） | `UNAVAILABLE` | `AdapterRegistry.register_loader` 捕获异常并隔离 |
| 函数引用无法解析 | 该能力**不出现在 Observed 清单**，诊断 `INVALID_IMPLEMENTATION` | `UNAVAILABLE` | `CapabilityRegistry._evaluate()` |
| Java 未声明该能力 | Python 可观测 | 不允许执行 | Desired/Observed 分离（本任务只管 Observed） |
| Java 已停用 Source | Python 可能仍加载 | 不允许新执行 | Effective 由 Java 侧算，本任务不越界 |
| Registry 查询失败 | `ERROR` / `UNKNOWN` | 不得伪造 UNAVAILABLE | Java `CapabilityContractParser` 对未知主版本标记 `isUnknown()` |

> **枚举纪律**：表中 `NOT_LOADED` 与 `INVALID_IMPLEMENTATION` **不是**
> `implementationStatus` / `effectiveStatus` 的合法取值。
> 前者是**适配器加载状态**（`LOADED` / `FAILED`），后者是**诊断原因码**。
> 正式枚举一字未扩。
