# SOURCE-04 任务卡：Java 数据源管理与运行时能力接入

> 状态：**已冻结**，待执行。
>
> 定位调整（相对原 SOURCE-04）：本阶段**不是**"只做管理页面"。
> 页面做得再好，Java 仍然只能看到自己配置的 `capability_json`，
> 无法知道 Python 实际加载了什么——能力模型就落不了地。
> 因此本阶段的主线是**运行时能力接入**，管理页面是它的配套。
>
> 前置：SOURCE-03 补充审查已完成（`SOURCE-03-SUPPLEMENT-REVIEW.md`）。
> 本阶段的两张 P0 卡（04-01 探活目标、04-05 部署架构）已在审查中提前落地，
> 下面标注为「**已完成**」并附实测证据。

---

## 零、全局约束

| 约束 | 说明 |
|---|---|
| **枚举一字未扩** | `implementationStatus` / `effectiveStatus` / `capabilityCode` / `category` / `accessModes` 必须与 `SOURCE-02-capability-schema.json` 逐字一致。需要新状态时先改契约，再改代码。 |
| **Schema 只加字段** | 1.x 内新增字段必须向后兼容；主版本变更需升到 2.0 并双跑。 |
| **不得伪造状态** | Registry 查询失败 → `UNKNOWN`，**不得**伪造为 `UNREGISTERED` / `ADAPTER_MISSING`。 |
| **能力必须可追溯** | 每个 `AVAILABLE` 的 Effective 都要能追到：Observed 记录 + 验证证据 + 代码版本。 |
| **不越界** | 本阶段不动采集 Task/Run 状态机；`/v1/jobs` 仍为 stub（属 SOURCE-06）。 |
| **不引入写能力** | 契约只读；本阶段不得引入任何发布/互动类能力。 |

---

## 一、任务总表

| 任务 | 内容 | 优先级 | 状态 |
|---|---|---|---|
| SOURCE-04-01 | 修正 `AccountVerificationService` 探活目标 | P0 | ✅ **已完成**（补充审查中） |
| SOURCE-04-02 | Java 消费 Python Registry（**含快照一致性**） | P0 | ✅ **已实施，待评审**（`SOURCE-04-02-IMPLEMENTATION.md`） |
| SOURCE-04-03 | Desired / Observed / Effective 核对 | P0 | ✅ **已实施**（FIX-04-M1/L1 后待独立评审关闭；`SOURCE-04-03-*.md`） |
| SOURCE-04-04 | Desired v2 结构与状态语义定稿 | P0 | ✅ **设计已冻结 + 迁移已落地**（V31；`SOURCE-04-04-TASKCARD.md` + `ADR-008` + `SOURCE-04-04-EFFECTIVE-REASON.md`） |
| SOURCE-04-05 | Collection Service 部署架构决策 | P0 | ✅ **已完成**（ADR-007） |
| SOURCE-04-06 | Java 数据源管理 API | P1 | ✅ **已实施**（`SOURCE-04-06-IMPLEMENTATION.md`） |
| SOURCE-04-07 | 数据源管理前端 | P1 | ✅ **已实施**（`SOURCE-04-07-IMPLEMENTATION.md`；视觉验证缺口见其 §五） |
| SOURCE-04-08 | 集成测试与回归 | P0 | 🟡 **部分验证**：跨服务 HTTP / Java 全量 315 / 部署回归已做；Python 全量与生产链路未跑（见 `SOURCE-04-06-IMPLEMENTATION.md` §4） |

> **SOURCE-08 的追踪不合并掉**：Desired/Observed 状态核对的**架构决策与验收要求**
> 仍保留在 SOURCE-08 名下（`ADR-006` + 本卡 04-03 的推导规则表）。
> 04-03 是它的**实现载体**，不是它的全部。

> **04-03 的启动前置（2026-11-16 裁决）**：SOURCE-04-02 已验收通过，
> 但 N1（Error 路径 future 未完成）、N2（`platformScoped` 缺失兜底）两项
> 已在 `SOURCE-04-02-FIX-01` 中关闭。**当时要求：N2、N1 关闭前不进入 04-03 核心实现。**
> 当前状态见 `SOURCE-04-02-FIX01-REPORT.md`。

---

## 二、任务卡明细

### SOURCE-04-01：修正 `AccountVerificationService` 探活目标 ✅ 已完成

**问题**：验证逻辑把 ai-service 的 `/health` 当成 collection-service 的可达性。
`cpi-flask` 容器跑的是 ai-service，两者都返回 200，错误长期未暴露。

**已交付**：
- 新增 `backend/src/main/java/com/coolpilot/insight/integration/health/ServiceHealthProbe.java`
  —— 把"探测谁"变成显式参数（`serviceName` + `baseUrl`），不再靠 URL 恰好不同来区分；
- `AccountVerificationService` 拆成两个探针：
  `COLLECTION_SERVICE` 不可达 → `SERVICE_UNAVAILABLE` 且**不改 `auth_status`**；
  `AI_SERVICE` 不可达 → **不影响**账号判定，仅在 `serviceChecks` 如实报告；
- `VerifyResult` 增加 `Map<String, ServiceCheck> serviceChecks`；
- `application.yml` 拆成 `app.collection-service-url` 与 `app.ai-service-url` 两个键；
- 新增 `backend/src/test/java/com/coolpilot/insight/source/AccountVerificationProbeTest.java`
  （5 条，用两个本地 HTTP server 精确控制可达性组合）。

**验收证据**：Java 全量 **147/147** 通过（原 138 + 探活 5 + 契约 4）。
Python 侧 **56/56**（`test_registry.py` 23 + `test_server.py` 17 + `test_evidence.py` 16）。

**遗留**：`serviceChecks` 尚未在前端展示（随 04-07 推迟）。

---

### SOURCE-04-02：Java 消费 Python Registry（含快照一致性）（P0）

> **2026-11-16 已实施**：方案 A 落地，报告见 `SOURCE-04-02-IMPLEMENTATION.md`。
> 新增 `GET /v1/registry/snapshot`（内容哈希 `snapshotId`），Java 侧
> `RegistrySnapshot` / `RegistrySnapshotParser` / `CollectionServiceClient` /
> `SnapshotCache`。Python 73/73、Java 174/174、5 容器 healthy。
> **Effective 推导仍未实现**（属 04-03）。

**目标**：Java 侧能**实际**查询 Python 的 Registry，而不是只读自己的 `capability_json`。
并且必须保证「一次 Effective 计算用的是**同一份** Observed 快照」。

> **2026-11-16 修订（评审裁决）**：原卡只要求读 `/v1/capabilities`，这是不完整的。
> 必须同时消费 `diagnostics`，且两个端点的数据必须来自**同一份不可变快照**。
> 以下 §A / §B 是本次新增的硬约束。

#### A. 必须同时消费 capabilities 与 diagnostics

`SOURCE-04-04-TASKCARD.md` §3.2 的 #8（能力未注册）与 #9（函数解析不到）
在 `/v1/capabilities` 的 Observed 清单里**表现完全相同**——都只是"没有这条能力"。

要区分它们，必须读 `diagnostics`：

| 端点 | 提供 | 用途 |
|---|---|---|
| `GET /v1/capabilities` | Observed 能力清单（`implementationStatus`、`evidence`、`accessModes`…） | 判断是否注册、实现状态、证据有效性 |
| `GET /v1/adapters`（或 `/{connectorCode}`） | `loadStatus` + `diagnostics[]`（含 `reason=INVALID_IMPLEMENTATION`、`adapterFunction`） | 判断 Adapter 是否加载、函数是否解析失败 |

**只读 `/v1/capabilities` 会在 #8 与 #9 之间失明**，导致把"函数写错了"误报成"没注册"。

#### B. 快照一致性（硬约束，不可降级为后续优化）

**问题**：两个端点分别调用，Registry 可能在两次请求之间变化。

```
t1  Java → GET /v1/capabilities  → 能力 A 已注册
t2  Python 侧 Adapter 重载，能力 A 的函数失效
t3  Java → GET /v1/adapters      → 诊断显示 A 函数解析失败
t4  Java 合并 t1 与 t3 的结果 → 生成一个「从未同时存在过」的事实
```

**这类跨版本混合数据必须被禁止**，不能当作完整快照使用。

**方案对比（评审给定，本节确认）**

| 方案 | 设计 | 评价 | 裁决 |
|---|---|---|---|
| **A** | 新增统一快照接口，**一次返回** capabilities + diagnostics + 快照标识 | 原子性最好 | ✅ **首选** |
| B | 两个端点各自返回相同的 `registrySnapshotId`，Java 比对后接受或重试 | 可行，但需 Python 保证同一标识的语义 | ⚠️ 退路 |
| C | Java 连续读两个接口，不做任何版本校验 | 无法保证一致性 | ❌ **不接受** |

**裁决：优先方案 A**。若为保持 SOURCE-03 已冻结的接口契约而暂时不能新增端点，
则采用方案 B，但必须先补齐快照标识的定义与一致性规则。

**方案 A 的接口形状（建议，待实施时定稿）**

```
GET /v1/registry/snapshot
{
  "code": 0,
  "data": {
    "snapshotId": "sha256:3f9a...",      // 不可变快照标识
    "generatedAt": "2026-11-16T12:00:00Z",
    "schemaVersion": "1.1",
    "adapters": [ ... ],                  // 同 /v1/adapters 内容
    "connectors": [ ... ],                // 同 /v1/capabilities 的 connectors
    "diagnostics": [ ... ]
  }
}
```

**`snapshotId` 的语义要求**（关键，不能用时间戳替代）：

| 要求 | 说明 |
|---|---|
| 标识**内容**而非**时间** | 必须由 Registry 内容的哈希（或单调递增版本号）生成 |
| 同一份内容 → 同一 id | 内容不变时重复请求返回相同 id |
| 内容变化 → 新 id | 任一 Adapter 加载状态/能力/诊断变化都必须换 id |
| **不得**用 `generatedAt` 判断一致性 | 时间戳相同不代表内容相同；时间戳不同也不一定内容不同 |

**方案 B 的一致性规则**（若走退路）：

1. 两个端点响应都带 `registrySnapshotId`；
2. Java 读取两次后比对：相同 → 接受；不同 → **重试一次**，仍不同则判 `UNKNOWN` + `OBSERVED_STALE`；
3. 重试上限 1 次，避免在 Registry 高频变化时形成忙等；
4. **不得**在 id 不一致时"取交集"或"取并集"——那仍是混合数据。

**禁止的做法**

- ❌ 只依赖 `generatedAt` 时间戳比对；
- ❌ id 不一致时仍合并使用；
- ❌ 把两次读取的结果分别缓存后按需拼装。

#### C. 文件范围

- 新增 `backend/src/main/java/com/coolpilot/insight/integration/capability/CollectionServiceClient.java`
  —— HTTP 客户端（复用 `ServiceHealthProbe` 的超时策略；带 `X-Internal-Token`）。
- 新增 `backend/src/main/java/com/coolpilot/insight/integration/capability/RegistrySnapshot.java`
  —— 一次请求拿到的**不可变快照**（capabilities + diagnostics + snapshotId）。
- 新增 `backend/src/main/java/com/coolpilot/insight/integration/capability/ObservedCapabilityCache.java`
  —— 缓存**快照**（不是分别缓存两个端点）；TTL 可配（默认 60s）；
  查询失败时**保留上一次成功快照并标记 stale**，绝不返回空集合冒充"没有能力"。
- 扩展 `CapabilityContractParser.java` —— 解析 Schema 1.1 的 `semantics` 与 `evidence`。
- 扩展 `CapabilityItem.java` / `CapabilityDocument.java`：加 `semantics`、`evidence`（已完成）。

> **若走方案 A**，需在 collection-service 新增 `/v1/registry/snapshot`。
> 这属于**新增端点**，不删除也不修改既有端点，符合"不擅自删除旧接口"的约束。
> 但**它属于 Python 代码改动**，需在实施前单独确认范围。

#### D. 硬性要求

1. 解析失败 / Schema 主版本不支持 → `schemaSupported=false`，上层按 `UNKNOWN` 处理，
   **不得**当作"能力不存在"；
2. `evidence.accepted=false` 的能力，其 Observed `implementationStatus` **不得**是 `VERIFIED`
   （Python 侧已保证，Java 侧再断言一次，防上游回退）；
3. 超时与失败必须可观测（指标 / 日志），不能让查询失败静默降级；
4. **不得**在此任务里改动 `implementationStatus` / `effectiveStatus` 枚举；
5. 缓存单位是**快照**，不是"每个端点一份"——这是快照一致性的落地前提。

#### E. 测试命令

```bash
cd backend && mvn -s ../deploy/docker/maven-settings.xml -o test
```

#### F. 验收标准

- 新增 `CapabilityClientTest`：正常解析 / 超时 / 500 / Schema 2.0（未知主版本）
  / `semantics` 与 `evidence` 正确落库；
- 新增 `SnapshotConsistencyTest`（**本次新增的必测项**）：
  - 同一快照内 capabilities 与 diagnostics 版本一致；
  - 模拟两次读取 id 不同 → 触发重试 → 仍不同则 `UNKNOWN` + `OBSERVED_STALE`；
  - **断言绝不在 id 不一致时合并数据**；
- 至少一条**真实 HTTP 往返**测试（对 `cpi-collection` 容器），不得再用静态 JSON 样本；
- 断言"函数解析不到"能被识别为 `FUNCTION_UNRESOLVED` 而非 `CAPABILITY_NOT_REGISTERED`
  （需 diagnostics 参与）；
- Java 测试总数 ≥ 150。

---

### SOURCE-04-03：Desired / Observed / Effective 核对 （P0）

**目标**：实现 ADR-006 的三态模型，并按 SOURCE-02 的 `effectiveStatus` 枚举推导 Effective。

**文件范围**
- 新增 `backend/src/main/java/com/coolpilot/insight/source/application/CapabilityReconciler.java`
  —— 三态核对与 Effective 推导。
- 新增 `backend/src/main/java/com/coolpilot/insight/source/domain/EffectiveCapability.java`
  —— Effective 结果模型。
- 扩展 `SourceQueryService.java`：在 `DataSourceView` 上并列返回 Desired / Observed / Effective。
- **不改** `ds_source.capability_json` 的表结构（Desired 仍存在该列，见 04-04）。

**Effective 推导规则（必须与 Schema 枚举逐字对应）**

> **2026-11-16 修订**：本表已被 `SOURCE-04-04-TASKCARD.md` §3.2 取代。
> 原表第 9 条把 `UNVERIFIED` 折叠为 `UNREGISTERED` 是**事实错误**，
> 已按 `ADR-008` 拆为两层表达（`effectiveStatus` × `effectiveReason`）。
> 下面是修订后的权威表，**以它为准**。

| # | 条件（自上而下短路） | `effectiveStatus` | `effectiveReason` |
|---|---|---|---|
| 0 | Java 侧该 Source 已停用 | `SOURCE_DISABLED` | `SOURCE_DISABLED` |
| 1 | Registry 不可达 | `UNKNOWN` | `REGISTRY_UNREACHABLE` |
| 2 | Schema 主版本不支持 / 响应非法 | `UNKNOWN` | `SCHEMA_UNSUPPORTED` |
| 3 | 仅有过期缓存（stale 超 TTL） | `UNKNOWN` | `OBSERVED_STALE` |
| 4 | **Desired 配置不可解析**（非法 JSON / 未知版本 / 结构非法 / 重复能力） | `UNKNOWN` | `DESIRED_UNPARSEABLE` |
| 5 | Desired 未声明该能力 | `UNREGISTERED` | `NOT_DECLARED` |
| 6 | Adapter 未加载 | `ADAPTER_MISSING` | `ADAPTER_NOT_LOADED` |
| 7 | Adapter 版本与锁定要求不符 | `VERSION_MISMATCH` | `CONNECTOR_VERSION_MISMATCH` |
| 8 | Contract 主版本不兼容 | `VERSION_MISMATCH` | `CONTRACT_VERSION_MISMATCH` |
| 9 | Capability 未注册（且无相关诊断） | `UNREGISTERED` | `CAPABILITY_NOT_REGISTERED` |
| 10 | 函数不存在 / 不可调用（诊断 `INVALID_IMPLEMENTATION`） | `UNREGISTERED` | `FUNCTION_UNRESOLVED` / `FUNCTION_NOT_CALLABLE` |
| 11 | 能力被 Python 禁用 / 平台不支持 | `UNREGISTERED` | `CAPABILITY_DISABLED` / `PLATFORM_UNSUPPORTED` |
| 12 | 访问模式不覆盖 | `UNREGISTERED` | `ACCESS_MODE_UNSUPPORTED` |
| 13 | **已注册但未获真实平台验证**（`UNVERIFIED` / `IMPLEMENTED`） | `UNKNOWN` | `VERIFICATION_REQUIRED` |
| 14 | 证据过期 / 无效（**任意状态**，不受 `implementationStatus` 限制） | `UNKNOWN` | `EVIDENCE_EXPIRED` / `EVIDENCE_INVALID` |
| 15 | 需 AUTHENTICATED 且无可用账号 | `ACCOUNT_REQUIRED` | `ACCOUNT_REQUIRED` |
| 16 | 可用但受限（**且持有已接受证据**） | `DEGRADED` | `DEGRADED` |
| 17 | 正常可用（**`VERIFIED` 且证据已接受**，上述全通过） | `AVAILABLE` | `NONE` |

> **注意 1**：Schema 的 `effectiveStatus` 枚举里**没有** `UNAVAILABLE`。
> 用户示意表里的 `UNAVAILABLE` 对应到正式枚举是 `ADAPTER_MISSING` / `UNREGISTERED`，
> **不得**为了对齐示意表而扩充枚举。
>
> **注意 2**：`effectiveReason` 是 **Java 内部模型**，只在 Java 侧计算与展示，
> **不进入** Python 的 `/v1/capabilities` 输出，因此**不触碰** SOURCE-02 Schema。
> 详见 `ADR-008`。
>
> **注意 3**：`effectiveStatus` 相同不代表事实相同——
> `UNREGISTERED` 既可能是"没注册"也可能是"函数解析不到"；
> `UNKNOWN` 既可能是"服务不可达"也可能是"已注册未验证"。
> **必须同时看 `effectiveReason`**，只显示 `effectiveStatus` 会误导。
>
> **注意 4（2026-11-16，SOURCE-04-03-FIX-01）**：`implementationStatus` 是**六值**枚举，
> 准入推导必须对全部六值有结论。原表末行把 `IMPLEMENTED` 与 `VERIFIED` 并列判
> `AVAILABLE`，且证据校验只对 `UNVERIFIED`/`VERIFIED` 生效，导致
> **本地测试通过即被放行、证据被拒也被放行**（失败开放）。
> 现已按 `SOURCE-04-04-TASKCARD.md` §3.2.1 的「实现状态 × 证据矩阵」修订：
> 证据闸门对所有未被禁用/不支持拦下的状态一律生效，`IMPLEMENTED` 永不产出 `AVAILABLE`。
>
> **注意 5（2026-11-16，SOURCE-04-03-FIX-02）**：新增第 4 行 `DESIRED_UNPARSEABLE`。
> 此前"Desired 读不懂"被映射为 `SCHEMA_UNSUPPORTED`（Observed 快照的故障码），
> 会把排障方向指向采集服务；该判定也原本排在快照判定之前，导致"服务不可达"
> 被误报成"配置坏了"。现已下移并改用专属原因码。

**执行准入（与 Effective 并列，不是状态）**
- Java 未在 Desired 中声明的能力 → Python 可观测，但**不允许执行**；
- Java 已停用 Source → Python 可能仍加载，**不允许新执行**；
- **`UNKNOWN` 一律不允许执行**（评审强调：不得被下游理解为"允许"）；
- 准入判据用**白名单**，禁止写成 `!"UNREGISTERED".equals(status)` 这类语义反转形式
  （那会把 `UNKNOWN` 及未来新增状态默认放行）。

**计算输入必须来自同一份 Observed 快照（评审裁决，硬约束）**

| 要求 | 说明 |
|---|---|
| 单一快照 | 一次 Effective 计算只能使用 `RegistrySnapshot` 一个对象，不得分别取 capabilities 与 diagnostics |
| 版本校验 | 若走方案 B（双端点 + `registrySnapshotId`），id 不一致必须重试或判 `OBSERVED_STALE`，**禁止合并** |
| 不混用缓存 | 不得把"上一次的 diagnostics"与"这一次的 capabilities"拼装使用 |

**快照消费的四条硬约束（N7 冻结，2026-11-16）**

> 这四条来自 SOURCE-04-02 两轮评审的结论。它们不是建议，是**验收条件**——
> 违反其中任何一条，04-03 不予验收。

**N7-1　必须只遍历真实平台接入**

```java
// 正确：只看真实平台 Adapter
for (var adapter : snapshot.platformScopedAdapters()) { ... }

// 禁止：直接遍历全部 Adapter（STUB 会混入真实平台能力准入）
for (var adapter : snapshot.adapters()) { ... }
```

`platformScoped=false` 的 STUB（链路验证替身）**不得进入真实平台能力准入**。
不应靠 `sourceCode == "STUB"` 或 `capabilityCount == 0` 推断——那是巧合，不是契约。

**N7-2　`platformScoped=true` 不等于能力已验证**

它只表示"这是真实平台的接入实现"，与 `implementationStatus` 是两回事。
`VERIFIED` 与 `platformScoped` 正交：STUB 可能是 `VERIFIED`（如果给它写了证据），
真实接入也可能是 `UNVERIFIED`。

> **验收断言见「验收标准」的 N7-2 (a) / (b)**：未验证不得放行、已验证不得绕过。
> 本条**不允许**只作为背景说明存在——`platformScoped`、`implementationStatus`、
> `AVAILABLE` 是三个互不等价的维度，必须由断言锁定。

**N7-3　`VERIFIED` 不等于当前可执行**

`VERIFIED` 只说明"曾经用这一版实现真实跑通过"（见 `ADR-008` §3.3）。
当前能否执行还取决于：Source 状态、快照新鲜度、账号、访问模式、证据有效期。
**不得**把 `implementationStatus == VERIFIED` 直接当作可执行。

**N7-4　只有 `FRESH` 快照可以参与当前 Effective 准入**

```java
var read = snapshotCache.get();
if (read.state() != SnapshotCache.SnapshotCacheState.FRESH) {
    // STALE / UNAVAILABLE：一律不得进入 Effective 计算
    // 正确结果是 UNKNOWN + REGISTRY_UNREACHABLE 或 OBSERVED_STALE
    return unknown();
}
```

| 快照状态 | 参与 Effective 准入 | 对应 `effectiveStatus` |
|---|---|---|
| `FRESH` | ✅ 允许 | 按 §3.2 逐条推导 |
| `STALE` | ❌ **禁止** | `UNKNOWN` + `OBSERVED_STALE` |
| `UNAVAILABLE` | ❌ **禁止** | `UNKNOWN` + `REGISTRY_UNREACHABLE` |
| `UNKNOWN`（Effective 结果本身） | ❌ 不可执行 | — |

**禁止**用 `snapshot != null` 判断可用性——`STALE` 也携带快照。
唯一判据是 `state == FRESH` 或 `usableForExecution`。

**N7-5　准入必须是白名单，不能反向放行**

```java
// 正确：枚举允许的集合
boolean executable = effectiveStatus == AVAILABLE && effectiveReason == NONE
                  || effectiveStatus == DEGRADED;

// 禁止：排除法（会把 UNKNOWN 与未来新增状态默认放行）
boolean executable = effectiveStatus != UNREGISTERED;
```

**N7-6　`effectiveReason` 的测试覆盖（19 + 1）**

| 范围 | 要求 |
|---|---|
| 19 个**当前可达**的 reason | 逐个提供**正向推导测试**（构造输入使其真实产生该 reason） |
| `FUNCTION_NOT_CALLABLE` | 提供**不可达性测试**：确认当前诊断输出不会错误地产生该 reason |

**不得**为了凑齐 20 个正向用例而伪造一个当前无法产生的诊断输入
（Python 侧只有单一诊断码 `INVALID_IMPLEMENTATION`，见 `SOURCE-04-04-EFFECTIVE-REASON.md` Q9）。

**测试命令**
```bash
cd backend && mvn -s ../deploy/docker/maven-settings.xml -o test
```

**验收标准**
- **N7-6**：新增 `CapabilityReconcilerTest`——**19 个当前可达 `effectiveReason` 逐条有正向用例**，
  `FUNCTION_NOT_CALLABLE` 提供**不可达性测试**（确认当前诊断不会产生该 reason）；
  依据 `SOURCE-04-04-EFFECTIVE-REASON.md` §5；
- 判定顺序与 `SOURCE-04-04-EFFECTIVE-REASON.md` §4 逐行一致，**不得为实现方便调整**；
- 断言 `UNKNOWN` 一律不可执行；断言准入判据是白名单而非反转判断；
- 一条端到端测试：真实的 `ds_source` 行 + 真实 Observed 快照 → Effective；
- 明确断言「Java 未声明的能力不被执行」；
- 明确断言「计算只用单一快照，不混用两个端点的数据」；
- **N7-1**：断言核对只遍历 `platformScopedAdapters()`；用一条含 STUB 的真实快照验证
  STUB 不产生任何 Effective 结果；
- **N7-2**：两条**正交性**断言，缺一不可——
  - **(a) 未验证不得放行**：`platformScoped=true` + `implementationStatus=UNVERIFIED`
    ⇒ `effectiveStatus ≠ AVAILABLE`。
    证明"真实平台接入"不等于"已验证"，未验证能力不会被误放行。
  - **(b) 已验证不得绕过**：`platformScoped=true` + `implementationStatus=VERIFIED`
    + 快照 `FRESH`，**仍不足以**推出 `AVAILABLE`；
    必须同时满足 Desired 已声明、版本兼容、函数绑定、访问模式、账号等**全部**准入条件。
    证明"已验证"不是免检通道。
  - 两条合起来才说明：`platformScoped`、`implementationStatus`、`AVAILABLE`
    是三个互不等价的维度。
- **N7-3**：断言 `VERIFIED` 但快照 STALE 的能力**不可执行**；
- **N7-4**：断言 `STALE` / `UNAVAILABLE` 快照下所有能力为 `UNKNOWN`，不得出现 `AVAILABLE`；
- **N7-5**：断言准入判断里不存在 `!=` 形式的排除法。

---

### SOURCE-04-04：Desired v2 结构与状态语义定稿（P0）

> **2026-11-16 修订**：本卡的设计已冻结为 `SOURCE-04-04-TASKCARD.md`（v2 修订版），
> 状态语义部分另立 `ADR-008`，20 个 `effectiveReason` 的完整定义表与重复语义审查
> 见 `SOURCE-04-04-EFFECTIVE-REASON.md`。下面是**执行阶段**要点，设计细节以专项文档为准。
>
> **评审裁决（2026-11-16 二次）**：20 值暂不精简，但已完成定义表 / 触发条件 /
> 状态映射 / 重复审查 / 每值正向测试设计；`FUNCTION_NOT_CALLABLE` 因 Python
> 单一诊断码而当前不可达，已标注为预留（Q9）。

**问题**：库中现值

```json
{"comments": true, "commentStatus": "AVAILABLE", "contentDetail": true,
 "keywordSearch": false, "keywordStatus": "PENDING"}
```

四个问题：能力码不完整（缺 `COMMENT_REPLY` 等）、结构扁平无能力对象、
`PENDING` 不在 `effectiveStatus` 枚举内、无 `schemaVersion` 无法识别旧结构。

**已冻结的目标结构（v2）**

```json
{
  "schemaVersion": "2.0",
  "capabilities": [
    {
      "sourceCode": "DOUYIN",
      "connectorCode": "DOUYIN_PUBLIC",
      "code": "COMMENT",
      "category": "COMMENT",
      "accessModes": ["PUBLIC", "AUTHENTICATED"],
      "desiredStatus": "ENABLED"
    }
  ]
}
```

**执行阶段要做的事（本设计阶段不做）**

| # | 动作 | 说明 |
|---|---|---|
| E1 | 写 v1→v2 迁移 SQL | 可重入（`WHERE schemaVersion IS NULL`）；映射失败行保留 v1 并报诊断 |
| E2 | 升 `SourceQueryService.parseCapability` | 双读兼容（v1 映射 / v2 直解），返回视图同时含旧字段与新结构 |
| E3 | 旧字段改为派生输出 | `comments` 等由 v2 派生，只读不写 |
| E4 | 加 `EffectiveCapability` DTO | 含 `effectiveStatus` + `effectiveReason` |

**文件范围**：`V31__*.sql`、`SourceQueryService.java`、`DesiredCapability.java`（新）、
`EffectiveCapability.java`（新）、前端（随 04-07 推迟）。

**约束**
- **Flyway 是唯一结构入口**；不手改库；
- 迁移必须**可重入**：重复执行不报错；
- 迁移前后跑 `tools/check_schema_drift.py`；
- **不得**把 Observed 写回 `capability_json`；
- **不得**扩充 `effectiveStatus` / `implementationStatus` / `capabilityCode` 枚举。

**验收标准**
- `capability_json` 里不再出现 `PENDING` 等非契约状态词；
- 迁移可重入：连跑两次结果一致；
- 旧字段在兼容期仍可读，且与 v2 派生值一致；
- 迁移失败的能力有 `DESIRED_MIGRATION_FAILED` 诊断，**不被静默丢弃**。

---

### SOURCE-04-05：Collection Service 部署架构决策 ✅ 已完成

**决策**：方案 A —— 独立容器 `cpi-collection`。完整评审见
`docs/architecture/collection/ADR-007-collection-service-deployment.md`。

**已交付**
- `deploy/docker/collection.Dockerfile`（Python 3.12 + Flask + DrissionPage + Chrome）；
- compose 新增 `collection` 服务；
- CSV 目录与 backend 共享同一宿主目录 → **现有生产 CSV 链路未被容器化打断**；
- Chrome profile 共享 `chrome-data` 卷（约束：两容器不得并发启动 Chrome，已写入 ADR 风险项）；
- 查询端点在配置 `INTERNAL_TOKEN` 时要求 `X-Internal-Token`；`/health` 保持公开。

**验收证据**：`GET /health` / `GET /v1/capabilities` / `GET /v1/adapters` 实测 200。

实测响应（2026-11-16，`cpi-collection` 容器）：

```
schemaVersion: 1.1
legacy items: [{"sourceCode":"STUB","status":"PENDING",...},
               {"sourceCode":"DOUYIN","status":"AVAILABLE","comments":true,"keywordSearch":false,...}]
DOUYIN / DOUYIN_PUBLIC / 1.0.0
  - COMMENT         | VERIFIED   | semantics=READ | evidence.accepted=True
  - COMMENT_REPLY   | VERIFIED   | semantics=READ | evidence.accepted=True
  - CONTENT_DETAIL  | VERIFIED   | semantics=READ | evidence.accepted=True
  - KEYWORD_SEARCH  | UNVERIFIED | semantics=READ | evidence.accepted=null
```

**遗留**：采集执行链路切 HTTP 属 SOURCE-06。

---

### SOURCE-04-06：Java 数据源管理 API （P1）

**目标**：让管理侧能读、能配 Desired，并能看见 Observed / Effective。

**文件范围**
- 扩展 `backend/src/main/java/com/coolpilot/insight/source/controller/SourceController.java`
  —— 列表返回三态；新增 `GET /api/source/{code}/capabilities`（三态明细）；
- 新增 `PUT /api/source/{code}/capabilities`（写 Desired）；
- 扩展 `SourceQueryService` / `SourceAccountService`（按 04-04 的方案）。

**约束**
- 写 Desired **不得**触碰 `auth_status`（那是 `AccountVerificationService` 独占的）；
- 权限：复用现有 `@RequirePermission` 与按钮级 `usePermission()`。

**验收标准**：接口有鉴权测试；写操作有审计记录；返回结构与前端约定一致。

---

### SOURCE-04-07：数据源管理前端 ✅ 已实施

> 2026-11-17 更新：原定推迟，现随 04-06 一并落地，详见
> `SOURCE-04-07-IMPLEMENTATION.md`（含"视觉未验证"的明确缺口声明）。
> 下面保留推迟期的原始记录，便于回溯为什么当时不做。

推迟期间的前端现状（如实记录）：数据源管理页读的是 `capability` 字段，
即 `ds_source.capability_json` 的旧结构，**不反映** Python 的实际加载情况。
**这一点必须在前端页面上有明确标注**，避免运营把配置当成事实。

待 04-02 / 04-03 完成后一并做，届时前端需要展示三态并区分
「配置说支持」与「运行时确实可用」。

---

### SOURCE-04-08：集成测试与回归 （P0）

**测试范围**
1. **真实跨服务往返**：Java → `cpi-collection` → 能力解析 → Effective 推导；
2. **Java 全量回归**：`mvn -s ../deploy/docker/maven-settings.xml -o test`；
3. **Python 全量回归**：`tests/test_registry.py` + `tests/test_server.py` + `tests/test_evidence.py`；
4. **生产链路不回归**：`python comment.py <video> 20` 实跑，
   CSV 表头 / BOM / 列名 / 增量去重行为无变化；
5. **部署回归**：`docker compose up -d` 后五容器全部 healthy。

**验收标准**
- 至少一条测试**真的发出 HTTP 请求**（禁止再用静态 JSON 样本冒充跨服务契约测试）；
- 跨服务测试失败时，**不得**退化为"跳过"或"降低断言"；
- 全部通过后更新 `SOURCE-03-AUDIT.md` 的遗留清单。

**提交验收标准**
- 一张任务卡一次提交，提交信息写明「改了什么 / 为什么 / 验证方式 / 遗留」；
- 每张卡的测试命令与结果写进提交信息；
- 不夹带无关改动（格式化、批量重命名）。

---

## 三、依赖关系

```
04-01 ✅ ─┐
04-05 ✅ ─┼─→ 04-02（Java 消费 Observed + 快照一致性）
          │        ↓
          └─→ 04-04 🟡（Desired 结构定稿，评审通过）
                   ↓
                04-03（三态核对，必须用单一快照）
                   ↓
                04-06（管理 API）
                   ↓
                04-07（前端，推迟）
                   ↓
                04-08（集成测试与回归）
```

**建议执行顺序**：04-04 → 04-02 → 04-03 → 04-06 → 04-08。

| 顺序约束 | 理由 |
|---|---|
| 04-04 必须在 04-02 之前 | Desired 结构决定 04-02 / 04-03 的字段映射，先做 04-02 会返工 |
| 04-02 必须在 04-03 之前 | 04-03 依赖 `RegistrySnapshot`（含 diagnostics）；没有一致性快照，核对结果不可信 |
| 04-02 内部：快照一致性先于缓存 | 缓存单位必须是**快照**，不能先按端点缓存再补一致性 |

**进入代码实施前的门禁**（评审要求，不是授权）：

| 门禁 | 内容 |
|---|---|
| G1 | 04-04 设计已通过评审 ✅ |
| G2 | 04-02 的快照方案（A 或 B）**单独确认**，含是否允许修改 Python 代码 |
| G3 | 04-02 实施范围与验收标准**单独确认** |
| G4 | 04-06 的迁移 SQL 单独确认（不得与 04-02 混在一次提交里） |

---

## 四、与其它 SOURCE 的边界

| SOURCE | 归属 | 说明 |
|---|---|---|
| SOURCE-05 分层健康检查 | 独立 | 采集服务 / AI 服务 / MySQL 的分层健康，04-01 只做了账号验证场景下的两服务探活 |
| SOURCE-06 采集任务能力联动 | 独立 | 采集执行切 HTTP、`/v1/jobs` 由 Registry 派发、Chrome 并发约束 |
| SOURCE-08 Desired/Observed 状态核对 | **架构决策与验收要求仍保留在此名下** | 04-03 是实现载体；ADR-006 与本卡 04-03 的 9 条推导规则是它的验收依据 |
| SOURCE-09 CredentialProvider | 独立 | 凭证加密与密钥治理；含验证证据的数字签名 |
| SOURCE-07 最终集成验收 | 独立 | 全链路验收 |
