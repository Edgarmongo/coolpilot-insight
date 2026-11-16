# SOURCE-04-06 实施报告 · Java 数据源管理 API

> 状态：**已实施**，待独立评审
> 提交：见本次提交（`feat(source): SOURCE-04-06 数据源能力读写接口 + Desired v2 迁移`）
> 关联：`SOURCE-04-TASKCARD.md` §SOURCE-04-06、`SOURCE-04-04-TASKCARD.md`（v2 结构与 §2.2/§2.6/§2.7）、
> `ADR-006`（三态）、`ADR-008`（status × reason）
>
> 本卡同时落地了 04-04 的 **E1（v1→v2 迁移）**、**E2 的写侧**、**E3（旧字段改派生）**。
> 04-04 剩余未做的只有前端（随 04-07 推迟）。

---

## 一、范围

| 交付 | 内容 |
|---|---|
| 读 | `GET /api/v1/source/sources/{code}/capabilities` —— Desired / Observed / Effective 三态明细 |
| 写 | `PUT /api/v1/source/sources/{code}/capabilities` —— 覆盖写 Desired（只接受 v2） |
| 迁移 | `V31__source_capability_v2.sql` —— `capability_json` v1 → v2，可重入、fail-closed |
| 权限 | `V32__source_capability_permissions.sql` —— `source:capability:view` / `source:capability:manage` |
| 兼容 | 列表接口的旧字段 `capability` 改为**由 Desired 派生**，不再原样回显 |

**不在本卡范围内**（明确不做）：前端页面（04-07）、`/v1/jobs`（SOURCE-06）、
账号验证与 `auth_status`（`AccountVerificationService` 独占）、任何写平台动作。

---

## 二、逐文件说明

### 2.1 新增

| 文件 | 作用 | 关键点 |
|---|---|---|
| `source/domain/DesiredSchema.java` | Desired v2 的**冻结枚举** | 四个枚举逐字抄自 `SOURCE-02-capability-schema.json`，附"一字不扩"说明 |
| `source/application/DesiredWriteValidator.java` | 写入闸门（唯一判定点） | 类型严格、未知字段拒收、落库 JSON 由**校验后的模型**重新序列化 |
| `source/application/SourceCapabilityService.java` | 读写用例 | 只写 `capability_json` 一列；失败请求不写库不写审计 |
| `source/dto/SourceCapabilityDetailView.java` | 三态明细 DTO | Desired / Observed / Effective 平行放置，不 JOIN 成一行 |

### 2.2 修改

| 文件 | 改动 |
|---|---|
| `source/controller/SourceController.java` | 新增两个端点；`@RequireRole` + `@RequirePermission` 双注解 |
| `source/application/SourceQueryService.java` | `capability` 旧字段改为 `legacyCapability(...)` 派生（E3） |
| `source/application/DesiredCapabilityParser.java` | `majorOf` 由 private 改为 public（供校验器复用同一套版本判定） |
| `source/infrastructure/persistence/DataSourceMapper.java` | 新增 `updateCapabilityJson`（只更新一列） |
| `test/.../SourceQueryServiceTest.java` | 匿名 Mapper 补实现新方法 |
| `test/.../SourceModuleTest.java` | 旧字段断言随之调整（见 §五·1） |

### 2.3 迁移

`V31` 是**单条 UPDATE**，可重入；`V32` 只加两个权限点并授予 ADMIN。

---

## 三、关键设计决定（附"为什么不能那样做"）

### 3.1 写接口不收 DTO，也不收 `JsonNode`

请求体在 controller 里以**原始字符串**接收，由 service 用**同一套 Jackson 2** 解析。

| 备选 | 为什么不用 |
|---|---|
| DTO record | Jackson 会做类型转换：`123` → `"123"`、`true` → `"true"`。那正是"猜测字段含义"，也正好是 SOURCE-04-03 FIX-01 修过的那类 fail-open |
| `com.fasterxml...JsonNode` | Spring Boot 4 的消息转换器用的是 Jackson **3**（`tools.jackson`）。参数写成 Jackson 2 的 `JsonNode` 会在**读请求体阶段**抛 `InvalidDefinitionException` → 500。这个错**与业务无关**，报错信息里只提到抽象类型无法实例化，排查成本极高（本次实测踩到，见 §六·1） |
| `tools.jackson...JsonNode` | 能把请求读进来，但校验器与解析器都在 Jackson 2 上；混用两代意味着同一个 `JsonNode` 语义在两处不同，后续维护必然出错 |

**结论**：接口层不做类型转换，只搬运字节；类型判定集中在 `DesiredWriteValidator`。

### 3.2 落库内容由校验后的模型序列化，不是请求体原文

`DesiredWriteValidator` 通过后重新构造 JSON（字段顺序固定、按 `connectorCode + code` 排序）。
两个直接收益：

- **未知字段不可能落库**：即使某天放宽了"未知字段拒收"，转存路径也不存在；
- **幂等**：编辑器重排一次数组不会产生一次"内容未变"的写审计。

### 3.3 `accessModes` 为空必须拒绝，不能填默认值

v1 迁移时 `accessModes` 取保守默认（SOURCE-04-04 §2.5），那是**迁移**场景——
旧数据没有这个概念，只能按契约保守推定。
**新写入**不同：业务显式声明"允许哪些访问模式"是这条能力的一部分，
系统替它填默认值，等于系统替业务做了授权决定。

### 3.4 写 Desired 只碰 `capability_json` 一列

Mapper 语句写死列名，不用整实体 update。原因不是风格：

- `status` 属于"是否允许新执行"，改动会让整平台能力一起变 `SOURCE_DISABLED`；
- `auth_status` 属于账号域，被 `AccountVerificationService` 独占。
  "改了能力配置导致账号掉线"这类现象一旦出现，几乎无法归因。

测试用 `FakeMapper.updateStatus` 抛异常来锁定这条边界——写路径一旦顺手改状态，测试必红。

### 3.5 审计只记成功写入，且记 before/after 全量

- 失败请求没有改变任何状态，写进审计只会稀释审计价值；
- 只记 after 无法回退，只记"谁在何时改了"无法定位改坏了什么；
- 能力声明是小 JSON（几条到几十条），全量留存的成本远低于事后推测。

实测一条审计 `detail_json` 为 1236 字节（3 条能力的 before + after）。

### 3.6 迁移整体迁移，不做部分迁移

`V31` 对每行只有两种结果：**整行迁成 v2**，或**整行保持 v1 不动**。

| 情况 | 处理 | 理由 |
|---|---|---|
| `code` 不在四个已登记平台内 | 不迁移 | `connectorCode` 只能查表，不能拼。拼错会让核对永远判 `ADAPTER_MISSING`，看起来像"Python 没加载" |
| 任一已出现的映射字段不是布尔 | 不迁移 | 不猜字段含义（`"yes"` 算不算 true？） |
| 一个可识别字段都没有 | 不迁移 | 否则会把"读不懂"写成"业务声明了空能力"——事实篡改 |
| 已有 `schemaVersion` | 跳过 | 可重入 |

保留 v1 的行**不会静默消失**：运行时 `DesiredCapabilityParser` 仍按 v1 双读，
读不懂就以 `desiredError` 暴露原因。

> 未引入 `DESIRED_MIGRATION_FAILED` 这个 `effectiveReason`：
> 它会**扩充**已冻结的 21 值枚举，而 04-03 的组合表（§3.2.2）是按 21 值穷举的。
> 迁移失败的事实由 `desiredError` + `DESIRED_UNPARSEABLE` 表达，语义已覆盖，
> 不需要再加一个状态词。这里明确记录该取舍，避免后续被当成遗漏。

### 3.7 旧字段 `capability` 改为派生

库中已是 v2，若把整份文档塞进 `capability` 字段，老前端读到的会是
`{schemaVersion, capabilities}`——**形状变了但没告诉调用方**，这不算兼容。

现在 v2 行由 Desired 派生三个布尔；`commentStatus` / `keywordStatus` **不再输出**。
信息没有丢：`keywordSearch=false` 就是 `KEYWORD_SEARCH` 的 `desiredStatus=DISABLED`，
"实现到什么程度"由 Observed 的 `implementationStatus` 表达（SOURCE-04-04 §2.4）。
v1 行仍原样返回——改写它等于伪造。

---

## 四、证据（按类别分开，不混称"测试通过"）

### 4.1 单元测试（无 Spring 容器，纯逻辑）

| 测试 | 数量 | 覆盖 |
|---|---|---|
| `DesiredWriteValidatorTest` | 25 | 合法通过、字段顺序固定、顺序无关幂等、v1 拒写、缺版本、3.0 拒写、非数组、顶层/能力对象未知字段、`sourceCode` 与路径不一致、能力码/category/`desiredStatus` 非法、`accessModes` 空/非法/重复、`connectorCode` 格式、`adapterVersion` 格式、类型错误不做字符串化、重复声明、多接入同码允许、空数组通过、失败时拒绝半成品 |
| `SourceCapabilityServiceTest` | 9 | 三态装配、诊断按 Connector 收窄、Registry 不可达不臆造 Observed、v1 行可读、404、写成功落库+审计、写路径不碰 status、校验失败零副作用、写不存在源 404 |

### 4.2 集成测试（Testcontainers MySQL + 真实 HTTP）

`SourceCapabilityApiTest` · 7 条：

1. Flyway 把种子 v1 行迁成 v2（3 条能力、映射与 §2.2 一致、旧状态词不再出现）；
2. 迁移可重入 + 非布尔字段行保持不动（另行插入两行验证，测后清理）；
3. 未登录 → GET/PUT 均 401；
4. 已登录但无权限码 → GET/PUT 均 403；
5. 非法写入 → 400 且库内容与 Source 状态都不变；
6. 合法写入 → 200、落库为规范化 v2、`audit_log` 有 before/after、`status` 未变；
7. 读明细 → 三态齐备，Observed 不含 `STUB_PUBLIC`。

### 4.3 真实容器验证（`cpi-backend` + `cpi-collection` + `cpi-mysql`）

脚本：`tools/verify_source_capability_api.sh`（在容器内执行，口令取自环境变量，不入日志）。

```
未登录 GET  → 401
未登录 PUT  → 401
已登录 GET  → 200
  desiredSchemaVersion = 2.0
  snapshotState        = FRESH          ← 真实 Registry 快照
  snapshotId           = sha256:f37f934b2fc44...
  observed connectors  = ['DOUYIN_PUBLIC']        ← STUB 被排除
  desired   = COMMENT/ENABLED, CONTENT_DETAIL/ENABLED, KEYWORD_SEARCH/DISABLED
  effective = COMMENT AVAILABLE/NONE/true,
              CONTENT_DETAIL AVAILABLE/NONE/true,
              KEYWORD_SEARCH UNREGISTERED/NOT_DECLARED/false,
              COMMENT_REPLY UNREGISTERED/NOT_DECLARED/false
写 v1        → 400「不接受 Desired v1 写入（收到 1.0）；管理接口只写 2.0.x」
写非法能力码 → 400「capabilities[0].code 非法取值: COMMENT_V2（允许 [...]）」
写合法 v2    → 200，语义与迁移结果一致
```

迁移在真实库的执行结果：

```
flyway_schema_history: 31 (success=1) / 32 (success=1)
ds_source.capability_json → schemaVersion 2.0，3 条能力，connectorCode=DOUYIN_PUBLIC
audit_log: action=SOURCE_CAPABILITY_UPDATE, resource_id=1, detail_json 1236 字节
```

> 登录用的临时账号（`19900000009`）验证后已删除，未留下角色授权残留。

### 4.4 全量回归

`mvn -o test` → **315 / 315 通过**（上一轮基线 274，本卡新增 41）。

Python 侧本卡**未改任何 Python 代码**，因此未重跑 pytest（当前环境无 pytest）。
上一轮基线为 76/76，本卡不引用为本次证据。

---

## 五、与验收标准逐条对照

| 任务卡验收项 | 结论 | 依据 |
|---|---|---|
| 接口有鉴权测试 | ✅ | 集成测试 3/4 条：匿名 401、无权限码 403，均为真实 HTTP |
| 写操作有审计记录 | ✅ | 集成测试 6 + 真实容器：`audit_log` 有 before/after |
| 返回结构与前端约定一致 | ⚠️ **部分** | 结构已定（`desired` / `desiredError` / `observed` / `effective` / `snapshotState`），但**前端本轮不做**（04-07 推迟），无法与页面联调验证 |
| 写 Desired 不得触碰 `auth_status` | ✅ | 语句级只更新 `capability_json`；测试断言 `updateStatus` 未被调用 |
| 权限复用 `@RequirePermission` | ✅ | `source:capability:view` / `source:capability:manage`（V32 授予 ADMIN） |

### 5.1 对既有测试的改动（唯一一处，需评审确认）

`SourceModuleTest.listSourcesWithCapability` 原先断言旧字段里存在
`commentStatus=AVAILABLE` / `keywordStatus=PENDING`。迁移后库中为 v2，
两个状态词按 SOURCE-04-04 §2.4 **不迁移**，断言随之改为"这两个键不得出现"。

这是**行为变更的必然结果**，不是为了让测试变绿而放宽断言：
断言的语义从"旧状态词存在"变为"旧状态词不再回到 Desired"，
并保留了 `comments=true` / `keywordSearch=false` 两条实质断言。

---

## 六、实施中实际踩到的问题

1. **Jackson 两代混用**：`@RequestBody com.fasterxml.jackson.databind.JsonNode`
   在 Spring Boot 4 下直接 500（消息转换器是 Jackson 3）。
   改为接收原始字符串后消除。这条已写入 controller 的注释，避免下次重犯。
2. **迁移 SQL 的自引用**：`UPDATE t ... JOIN (SELECT ... FROM t)` 在 **TEMPORARY** 表上
   报 `Can't reopen table`。验证脚本因此改用普通表副本；（真实 `ds_source` 是普通表，不受影响）。
3. **MySQL JSON 输出会重排键并加空格**，断言不能依赖文本顺序，只能逐键判断。

---

## 七、待裁决（不自行决定）

### 7.1 `COMMENT_REPLY` 是否应由 `comments: true` 派生

`SOURCE-04-04-TASKCARD.md` 内部存在**两处不一致**：

| 位置 | 内容 |
|---|---|
| §1.1「冻结示例（真实数据映射结果）」 | 含 `COMMENT_REPLY`（ENABLED），共 **4** 条 |
| §2.2「v1 → v2 映射规则」+「迁移后应得到」 | 只有 `COMMENT` / `CONTENT_DETAIL` / `KEYWORD_SEARCH`，共 **3** 条 |

v1 结构里**没有**任何字段能表示"楼中楼回复"。要点：

- 按 §2.2 迁移 → `COMMENT_REPLY` 判 `NOT_DECLARED`（当前实现，真实容器已验证）；
- 按 §1.1 迁移 → 需要从 `comments: true` **推定** `COMMENT_REPLY` 也启用。

推定属于"猜测字段含义"，与 §2.6 铁律冲突；但不推定意味着 Python 侧
已 `VERIFIED` 的 `COMMENT_REPLY` 永远进不了执行准入（Desired 未声明 → 不可执行）。

**当前实现选择 §2.2（不推定）**，并把矛盾如实暴露为 `NOT_DECLARED`。
需要裁决：是修 §1.1 的示例（去掉 `COMMENT_REPLY`），还是给 v1 增加一条
**显式**映射规则（例如新增 v1 字段 `commentReply`，而不是从 `comments` 推导）。

---

## 八、遗留

| 项 | 说明 |
|---|---|
| 04-07 前端 | 仍推迟。当前页面读的是 `capability` 派生字段，看不到三态；**运营可能把配置当事实**，这一点需要在前端落地时明确标注 |
| L1~L5 | SOURCE-04-03 的历史 LOW 仍未关闭（本卡未触碰其判定逻辑） |
| OBS3 | `04-04:619` 的"20 值"表述待文档清理时一并修正 |
| `ds_source.remark` | 迁移后 remark 仍是"关键词搜索未验证"，与 v2 结构不冲突但语义已过期，未改数据 |
