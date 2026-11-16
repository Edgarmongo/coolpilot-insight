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
| SOURCE-04-02 | Java 消费 Python `/v1/capabilities` | P0 | 待执行 |
| SOURCE-04-03 | Desired / Observed / Effective 核对 | P0 | 待执行 |
| SOURCE-04-04 | 修正 `PENDING` 与 `effectiveStatus` 枚举不一致 | P0 | 🟡 **设计已冻结，待评审**（`SOURCE-04-04-TASKCARD.md`） |
| SOURCE-04-05 | Collection Service 部署架构决策 | P0 | ✅ **已完成**（ADR-007） |
| SOURCE-04-06 | Java 数据源管理 API | P1 | 待执行 |
| SOURCE-04-07 | 数据源管理前端 | P1 | **推迟**（用户明确要求本轮不做前端） |
| SOURCE-04-08 | 集成测试与回归 | P0 | 待执行 |

> **SOURCE-08 的追踪不合并掉**：Desired/Observed 状态核对的**架构决策与验收要求**
> 仍保留在 SOURCE-08 名下（`ADR-006` + 本卡 04-03 的推导规则表）。
> 04-03 是它的**实现载体**，不是它的全部。

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

### SOURCE-04-02：Java 消费 Python `/v1/capabilities` （P0）

**目标**：Java 侧能**实际**查询 Python 的 Registry，而不是只读自己的 `capability_json`。

**文件范围**
- 新增 `backend/src/main/java/com/coolpilot/insight/integration/capability/CollectionServiceClient.java`
  —— HTTP 客户端（复用 `ServiceHealthProbe` 的超时策略；带 `X-Internal-Token`）。
- 新增 `backend/src/main/java/com/coolpilot/insight/integration/capability/ObservedCapabilityCache.java`
  —— 缓存 Observed 快照（TTL 可配，默认可设 60s；查询失败时**保留上一次成功结果并标记 stale**，
  绝不返回空集合冒充"没有能力"）。
- 扩展 `backend/src/main/java/com/coolpilot/insight/integration/capability/CapabilityContractParser.java`
  —— 解析 Schema 1.1 新增的 `semantics` 与 `evidence` 字段。
- 扩展 `CapabilityItem.java` / `CapabilityDocument.java`：加 `semantics`、`evidence`。

**硬性要求**
1. 解析失败 / Schema 主版本不支持 → `ObservedCapabilities.schemaSupported=false`，
   上层按 `UNKNOWN` 处理，**不得**当作"能力不存在"；
2. `evidence.accepted=false` 的能力，其 Observed `implementationStatus` **不得**是 `VERIFIED`
   （Python 侧已保证，Java 侧要再断言一次，防止上游回退）；
3. 超时与失败必须可观测（指标 / 日志），不能让查询失败静默降级；
4. **不得**在此任务里改动 Python 的响应结构。

**测试命令**
```bash
cd backend && mvn -s ../deploy/docker/maven-settings.xml -o test
```

**验收标准**
- 新增 `CapabilityClientTest`：正常解析 / 超时 / 500 / Schema 2.0（未知主版本）
  / `semantics` 与 `evidence` 正确落库；
- 至少一条**真实 HTTP 往返**测试（对 `cpi-collection` 容器），不得再用静态 JSON 样本；
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

| # | 条件（自上而下短路） | `effectiveStatus` |
|---|---|---|
| 1 | Java 侧该 Source 已停用 | `SOURCE_DISABLED` |
| 2 | Registry 查询失败 / Schema 主版本不支持 | `UNKNOWN` |
| 3 | Observed 里找不到该 Connector | `ADAPTER_MISSING` |
| 4 | `connectorVersion` 与 Desired 声明不一致 | `VERSION_MISMATCH` |
| 5 | 该 Connector 下没有这条能力 | `UNREGISTERED` |
| 6 | Observed 的 `implementationStatus = DEGRADED` | `DEGRADED` |
| 7 | 能力只支持 `AUTHENTICATED` 且无可用账号 | `ACCOUNT_REQUIRED` |
| 8 | Observed 的 `implementationStatus ∈ {VERIFIED, IMPLEMENTED}` 且账号/平台条件满足 | `AVAILABLE` |
| 9 | 其余（`UNVERIFIED` / `DISABLED` / `UNSUPPORTED`） | `UNREGISTERED` |

> **注意**：Schema 的 `effectiveStatus` 枚举里**没有** `UNAVAILABLE`。
> 用户示意表里的 `UNAVAILABLE` 对应到正式枚举是 `ADAPTER_MISSING` / `UNREGISTERED`，
> **不得**为了对齐示意表而扩充枚举。

**执行准入（与 Effective 并列，不是状态）**
- Java 未在 Desired 中声明的能力 → Python 可观测，但**不允许执行**；
- Java 已停用 Source → Python 可能仍加载，**不允许新执行**；
- Effective ≠ `AVAILABLE` → 一律不允许执行。

**测试命令**
```bash
cd backend && mvn -s ../deploy/docker/maven-settings.xml -o test
```

**验收标准**
- 新增 `CapabilityReconcilerTest`：上表 9 条规则**逐条**有断言（含边界：Source 停用 +
  能力可用、Registry 失败 + 能力可用）；
- 一条端到端测试：真实的 `ds_source` 行 + 真实 Observed → Effective；
- 明确断言「Java 未声明的能力不被执行」。

---

### SOURCE-04-04：修正 `PENDING` 与 `effectiveStatus` 枚举不一致 （P0）

> **2026-11-16 更新**：本卡的设计部分已单独冻结为 `SOURCE-04-04-TASKCARD.md`，
> 含六份交付物（真实样本、字段映射、三态转换表、PENDING 裁决、版本规则、八场景测试设计）。
> 下面是它的**执行阶段**要点，设计细节以专项文档为准。

**问题**：库中现值

```json
{"comments": true, "commentStatus": "AVAILABLE", "contentDetail": true,
 "keywordSearch": false, "keywordStatus": "PENDING"}
```

`PENDING` **不在** `effectiveStatus` 枚举
（`AVAILABLE / ADAPTER_MISSING / VERSION_MISMATCH / DEGRADED / ACCOUNT_REQUIRED /
SOURCE_DISABLED / UNREGISTERED / UNKNOWN`）里。这是 Java 自己定义的历史词表，
与契约各说各话。

**处置方案（须在实施前确认走哪条）**
- **方案 A（推荐）**：`ds_source.capability_json` 升级为**契约形状**
  （`{capabilities: [{code, desired, ...}]}`），旧字段在兼容期保留并由服务端派生。
  优点：Desired 与 Observed 用同一套结构，04-03 的核对不需要做字段映射。
  代价：需要一次数据迁移 + 兼容期双写。
- **方案 B**：保留旧结构，只把状态词换成契约枚举（`PENDING` → `UNREGISTERED`）。
  优点：改动小。代价：Desired 与 Observed 仍是两套结构，04-03 要写映射。

**文件范围**：`V31__*.sql`（如走方案 A）、`SourceQueryService.java`、
`CapabilityReconciler.java`、前端（随 04-07 推迟）。

**约束**
- **Flyway 是唯一结构入口**；不手改库；
- 迁移必须**可重入**：重复执行不报错；
- 迁移前后跑 `tools/check_schema_drift.py`。

**验收标准**
- `capability_json` 里出现的每个状态词都属于 `effectiveStatus` 枚举；
- 一条测试断言：库里所有 Source 的 capability_json 状态词全部合法；
- 旧字段（`comments` / `keywordSearch` / `commentStatus` / `keywordStatus`）在兼容期仍可用。

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

### SOURCE-04-07：数据源管理前端 ⏸ 推迟

用户明确要求本轮不做前端页面。

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
04-05 ✅ ─┼─→ 04-02（Java 消费 Observed）
          │        ↓
          └─→ 04-04（Desired 结构定稿）
                   ↓
                04-03（三态核对）
                   ↓
                04-06（管理 API）
                   ↓
                04-07（前端，推迟）
                   ↓
                04-08（集成测试与回归）
```

**建议执行顺序**：04-04 → 04-02 → 04-03 → 04-06 → 04-08。
04-04 先做，是因为 Desired 的结构决定了 04-02 / 04-03 的字段映射，
先做 04-02 会返工。

---

## 四、与其它 SOURCE 的边界

| SOURCE | 归属 | 说明 |
|---|---|---|
| SOURCE-05 分层健康检查 | 独立 | 采集服务 / AI 服务 / MySQL 的分层健康，04-01 只做了账号验证场景下的两服务探活 |
| SOURCE-06 采集任务能力联动 | 独立 | 采集执行切 HTTP、`/v1/jobs` 由 Registry 派发、Chrome 并发约束 |
| SOURCE-08 Desired/Observed 状态核对 | **架构决策与验收要求仍保留在此名下** | 04-03 是实现载体；ADR-006 与本卡 04-03 的 9 条推导规则是它的验收依据 |
| SOURCE-09 CredentialProvider | 独立 | 凭证加密与密钥治理；含验证证据的数字签名 |
| SOURCE-07 最终集成验收 | 独立 | 全链路验收 |
