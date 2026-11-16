# 2026-11-16 工作日志

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
