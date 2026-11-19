# SOURCE-03 补充审查报告

> 状态：**已执行，5 项全部有结论**。3 项判定为"存在真实缺陷"并已修复，2 项判定为"表述/部署缺口"并已补齐。
>
> 触发原因：SOURCE-03 阶段验收时提出的五个追问。本报告逐项给出**代码级证据**，
> 不接受"代码已经存在"作为"运行时可用"的证据。
>
> 范围：仅 SOURCE-03 交付物（Python Registry、Java 契约测试）与部署编排。
> 不涉及 MySQL Schema、Flyway、采集 Task/Run 状态机。

---

## 零、审查前置：三件事必须分清

审查开始前先把三个层次钉死，后续所有结论都建立在这个区分上：

| 层次 | 含义 | SOURCE-03 时的状态 |
|---|---|---|
| **模块存在** | 代码写完了、单元测试通过 | ✅ 成立 |
| **运行时可用** | 进程真的在跑、HTTP 真的能响应 | ❌ **不成立**（无容器） |
| **跨服务接通** | Java 真的通过 HTTP 消费 Python 的能力 | ❌ **不成立**（无调用方） |

> **138/138 的 Java 测试验证的是「响应结构兼容性」，不是「跨服务运行链路」。**
> 这个区别在审查中被反复确认：Java 侧 `CapabilityContractTest` 的样本是从
> Python 容器里**手工导出**的静态 JSON（`backend/src/test/resources/capabilities-sample.json`），
> 测试过程中没有任何一次真实 HTTP 往返。

审查后这三层的状态：

| 层次 | 审查后状态 |
|---|---|
| 模块存在 | ✅ 成立（Python 56/56，Java 147/147） |
| 运行时可用 | ✅ **成立**（`cpi-collection` 容器独立运行，`/health` 与 `/v1/capabilities` 实测 200） |
| 跨服务接通 | ❌ **仍不成立** —— Java 侧消费留待 SOURCE-04-02 |

---

## 一、审查项 1：`verification_evidence.json` 是否真的可信？

### 1.1 结论：**不合格。原先的"手工编辑等同伪造"是一句没有机制支撑的约定。**

SOURCE-03 的原文写的是：

> 手工编辑本文件等同于伪造验证证据——禁止。

这句话的问题不在于态度，而在于**它没有对应的机制**。原始证据文件长这样：

```json
{
  "records": {
    "DOUYIN_PUBLIC": {
      "COMMENT": {
        "implementationStatus": "VERIFIED",
        "verifiedAt": "2026-11-16T08:12:22Z",
        "method": "REAL_PLATFORM_RUN",
        "observed": { "collected": 19, "cidCoverage": "19/19" },
        "message": "评论采集成功且全部带 cid"
      }
    }
  }
}
```

它**没有任何东西**能阻止这些行为：

| 行为 | 原实现能否发现 |
|---|---|
| 把 `collected: 19` 改成 `1900` | ❌ 完全发现不了 |
| 把 `verifiedAt` 改成未来时间 | ❌ 完全发现不了 |
| 手工插入一条从未跑过的 `CONTENT_DETAIL` 记录 | ❌ 完全发现不了 |
| 改了 `collector.py` 的实现后，旧证据仍然标 VERIFIED | ❌ 完全发现不了 |
| 换了平台（DOUYIN → XIAOHONGSHU）仍沿用旧证据 | ❌ 完全发现不了 |

换句话说：**原实现里，"这份证据对应哪一版实现"这个信息根本不存在。**
`apply_evidence()` 只做了两件事——看 `implementationStatus` 是不是 `VERIFIED`，
然后把 `verifiedAt` 抄到输出里。中间没有任何校验。

### 1.2 已实施修复

新增 `collection-service/app/registry/evidence.py`，证据升级为 schema **2.0**，每条记录携带两类绑定：

**（1）记录本体完整性 —— `contentHash`**

```json
"contentHash": "4360a431b2be7768ab160bc5c90017a06398fcae01e21c05b8c93484b8b8e3c8"
```

对记录的规范化 JSON（`sort_keys` + 紧凑分隔符 + UTF-8）求 SHA-256。
加载时重算并比对，**任何**对 `verifiedAt` / `observed` / `message` / `scope` 的改动
都会立刻被发现并拒绝。

实测（容器内）：

```
篡改 observed.collected 19 → 999
  → COMMENT 回落 UNVERIFIED，诊断 EVIDENCE_TAMPERED
  → COMMENT_REPLY / CONTENT_DETAIL 不受影响（各自独立记录）
```

**（2）适用范围与实现指纹 —— `scope`**

```json
"scope": {
  "sourceCode": "DOUYIN",
  "connectorCode": "DOUYIN_PUBLIC",
  "connectorVersion": "1.0.0",
  "capabilityCode": "COMMENT",
  "accessModes": ["PUBLIC", "AUTHENTICATED"],
  "contractVersion": "1.0",
  "adapterFunction": "app.adapters.douyin.collector.collect_comments",
  "implementationHash": "173a2122d97d74ef252309b51b7c167ef975678b8a596e77420171d6616fff4b"
}
```

`implementationHash` = SHA-256(能力声明的规范化 JSON + 目标函数的源码指纹)。
**改了声明或改了函数实现，哈希就变，旧证据自动失效。**

### 1.3 诚实声明：这不是防伪造，是防误改

必须说清楚，否则会形成新的安全幻觉：

- `contentHash` 的算法是**公开**的。任何能写入该文件的人都能重算出一个合法哈希。
  所以它**不是**数字签名，**不能**证明"这条记录一定来自真实运行"。
- 真正的防伪依据是**流程**：证据只能由 `tools/verify_capability.py` 产生，
  且 `observed` 里的统计必须与真实运行一致（`collected=19`、`cidCoverage=19/19`、
  `pages=3` 这类数字无法凭空编造出可信的组合）。
- 用户提出的"首期不一定需要数字签名"这个判断是对的，本轮按此执行。
  真正的签名（HMAC / Ed25519，密钥不入库）属于 SOURCE-09 凭证与密钥治理的范围。

**当前机制的准确描述**：验证证据的**生成规则明确**（只有工具能写）、
**完整性可校验**（contentHash + implementationHash）、
**版本绑定明确**（scope 六项）。这三条已落地；"防恶意伪造"未落地，也未假装落地。

---

## 二、审查项 2：`COMMENT_REPLY` 的能力命名与业务语义

### 2.1 结论：**歧义真实存在，但根因不是名字，是契约缺一个维度。**

用户的质疑：

> `COMMENT_REPLY` 很容易被理解为「回复评论」或「发布评论回复」。
> 采集评论回复和自动回复评论是完全不同的能力。

核对结果：

| 检查项 | 实际情况 |
|---|---|
| SOURCE-02 Schema 的中文说明 | 「评论回复（楼中楼）」—— 未说明读取方向 |
| `CapabilityCode` 注释 | 无 —— 只有裸常量 |
| Adapter 里的中文名 | 「楼中楼回复采集」—— 未说明读取方向 |
| 实际实现 | `collector.collect_comments()` —— 从评论接口的 `reply_comment` 字段读出嵌套回复，**不单独翻页** |
| 契约层有没有写操作的位置 | **没有**。SOURCE-02 明确写「Capability 只表达能从平台取到什么」 |

所以：`COMMENT_REPLY` 这个名字**本身**没有歧义——它和 `COMMENT`、`AUTHOR_POSTS` 一样，
都是"读"的宾语结构。真正的风险在于：**「只读」这个约束只存在于文档里，不存在于数据结构里。**
一个只读文档的实现者完全可能在 v2 里加一个 `PUBLISH_REPLY`，而 Schema 层面没有任何东西拦住。

### 2.2 已实施修复：把「只读」从文档搬进 Schema

**决定：不改 `COMMENT_REPLY` 这个 code，改契约的维度。**

理由：
- 改 code 会破坏 Schema 1.x 的向后兼容承诺，且 Java 侧契约测试已锚定该 code；
- 改名只解决 `COMMENT_REPLY` 一个能力，而 `MEDIA_DOWNLOAD`（下载媒体文件）
  同样容易被误读成"有副作用"——**逐个改名治标**；
- 缺的是维度，不是名字。

新增 `capabilities[].semantics`，**唯一合法值 `READ`**：

```json
"semantics": {
  "type": "string",
  "enum": ["READ"],
  "description": "能力的读写语义。本契约只包含只读采集能力，唯一合法值是 READ。发布评论、点赞、关注等写操作不在本契约的能力模型内，没有表达位置。"
}
```

Schema 升到 **1.1**（只加字段，主版本不变 → Java 侧 `majorOf("1.1") == 1` 仍兼容）。

配套改动：
- `CapabilitySpec.semantics` 字段，默认 `READ`，`__post_init__` 强制校验；
- 所有能力的 `to_v1()` 都输出 `semantics`；
- `COMMENT_REPLY` 的中文名改为「**楼中楼回复读取**」；
- `COMMENT_REPLY` 的 note 补上「**本能力不含任何写操作**」；
- SOURCE-02 契约文档新增「能力语义范围」章节。

**效果**：写能力在本 Schema 里**没有表达位置**。
`COMMENT_REPLY` 具备 = 能**读**楼中楼回复；它永远不会、也不可能被解读成"能发布回复"。

---

## 三、审查项 3：`VERIFIED` 的适用范围与有效期

### 3.1 结论：**适用范围缺失，有效期缺失。**

用户提出的绑定要求：

```
sourceCode / connectorCode / capabilityCode / accessMode
adapterVersion / contractVersion / verifiedAt
```

逐项核对 SOURCE-03 交付物：

| 要求 | 原实现 | 审查后 |
|---|---|---|
| `sourceCode` | ❌ 缺 | ✅ `scope.sourceCode` |
| `connectorCode` | ⚠️ 只有外层 JSON 键，没有记录内绑定 | ✅ `scope.connectorCode` |
| `capabilityCode` | ⚠️ 同上 | ✅ `scope.capabilityCode` |
| `accessMode` | ❌ 缺 | ✅ `scope.accessModes` |
| `adapterVersion` | ❌ 缺 | ✅ `scope.connectorVersion` |
| `contractVersion` | ❌ 缺 | ✅ `scope.contractVersion` |
| `verifiedAt` | ✅ 有 | ✅ 有 |
| 实现版本绑定 | ❌ 缺 | ✅ `scope.implementationHash` |
| **有效期** | ❌ **完全没有** | ✅ 默认 30 天，`CAPABILITY_EVIDENCE_TTL_DAYS` 可调 |

### 3.2 已实施修复

`EvidenceVerifier.verify()` 逐条检查，**任何一条不过，证据整体不生效**：

| 检查 | 原因码 | 触发条件 |
|---|---|---|
| 记录本体完整性 | `EVIDENCE_MALFORMED` | 缺 `contentHash` |
| 记录本体未被改动 | `EVIDENCE_TAMPERED` | `contentHash` 重算不符 |
| 平台一致 | `EVIDENCE_SCOPE_MISMATCH` | `scope.sourceCode` 与当前接入不符 |
| 接入版本一致 | `EVIDENCE_VERSION_MISMATCH` | `connectorVersion` 变了 |
| 契约版本一致 | `EVIDENCE_VERSION_MISMATCH` | `contractVersion` 变了 |
| 实现指纹一致 | `EVIDENCE_STALE` | 声明或函数代码变了 |
| 访问模式覆盖 | `EVIDENCE_SCOPE_MISMATCH` | 证据未覆盖当前声明的访问模式 |
| 未超期 | `EVIDENCE_EXPIRED` | `verifiedAt + TTL < now` |

被拒绝的证据**不是静默降级**：

- 能力回落为声明时的状态（`UNVERIFIED`）；
- 诊断区出现对应原因码；
- v1 文档里该能力的 `evidence.accepted = false`，并带 `rejectionReason` / `rejectionDetail`。

### 3.3 「VERIFIED ≠ 实时健康状态」

这一点必须写进契约，否则下游一定会误用：

> `VERIFIED` 的含义是「**曾经**在某台机器、某个时间点、用这一版实现真实跑通过」。
> 它**不是**实时健康状态，**不是**当前可用的保证，**不覆盖**其它账号 / 其它接入 / 其它模式。

当前证据的实际覆盖范围（必须如实理解）：

| 维度 | 覆盖情况 |
|---|---|
| 平台 | 仅 `DOUYIN` |
| 接入 | 仅 `DOUYIN_PUBLIC` |
| 能力 | `COMMENT` / `COMMENT_REPLY` / `CONTENT_DETAIL`（`KEYWORD_SEARCH` **无验证器 → 机制上无法提升**） |
| 访问模式 | 匿名态（`PUBLIC`）实跑通过；`AUTHENTICATED` 未经真实验证 |
| 目标 | 单个视频 `7703721523349239951` |
| 时间 | 2026-11-16T08:44Z（UTC） |
| 实现版本 | `implementationHash` 绑定的那一版 |

**特别是"所有抖音账号都能用"这个推论是错的**——
本轮证据只覆盖匿名态公开页面读取，不覆盖登录态、不覆盖风控差异、不覆盖其它账号。

---

## 四、审查项 4：`/health` 与 `/v1/capabilities` 的部署问题

### 4.1 结论：**代码存在，运行时服务不存在。已按方案 A 部署。**

用户提出的分层核对表，审查时的真实状态：

| 能力 | 审查前 | 审查后 |
|---|---|---|
| Registry Python 模块 | ✅ 已实现 | ✅ |
| Registry 单元测试 | ✅ 已通过 | ✅ 56/56 |
| HTTP 接口代码 | ✅ 已实现 | ✅ |
| **HTTP 服务部署** | ❌ **未完成** | ✅ `cpi-collection` 容器 |
| **Java HTTP 消费** | ❌ 未完成 | ❌ **仍未完成**（SOURCE-04-02） |
| **Java/Python 运行时状态核对** | ❌ 未完成 | ❌ **仍未完成**（SOURCE-04-03） |

审查前实测证据：四个容器里**没有任何一个**跑 collection-service。
`docker compose ps` 只有 `cpi-mysql` / `cpi-flask` / `cpi-backend` / `cpi-frontend`。
`cpi-flask` 跑的是 **ai-service**（`ai-service/app/server.py`），与采集服务无关。

### 4.2 已实施修复：方案 A 独立容器

用户选定方案 A 并要求「必须先做架构评审，不能直接决定增加容器」。评审结论记录在
`ADR-007-collection-service-deployment.md`，要点：

- 新增 `deploy/docker/collection.Dockerfile`（Python 3.12 + Flask + DrissionPage + Chrome）；
- compose 新增 `collection` 服务（`cpi-collection`），与 `flask`（ai-service）**是两个独立服务**；
- **CSV 数据目录共享**：collection 容器与 backend 容器挂同一宿主目录
  `collection-service/datas` → 因此「采集服务写 CSV → backend 读 CSV」的现有生产链路
  **没有被容器化打断**，这是 SOURCE-03 冻结的硬契约之一；
- **Chrome profile 共享** `chrome-data` 卷：保留登录态不分裂；
  代价是**两个容器不得并发启动 Chrome**（同一 profile 不能被两个 Chrome 同时占用），
  已写入 compose 注释与 ADR 风险项；
- 服务间认证：查询类端点在配置 `INTERNAL_TOKEN` 时要求 `X-Internal-Token`；
  `/health` 保持公开（容器健康检查需要，且响应不含凭证信息）。

### 4.3 审查时的实测

容器内启动后逐个打真实 HTTP（`cpi-collection` 独立容器，宿主端口 5001）：

```
GET /health          → 200，registry.loadedCount=2，capabilityCount=4，degraded=false
                        registry.evidence: recordCount=3, acceptedCount=3, rejectedCount=0, ttlDays=30
GET /v1/capabilities → 200，schemaVersion=1.1
                        COMMENT         | VERIFIED   | semantics=READ | evidence.accepted=True
                        COMMENT_REPLY   | VERIFIED   | semantics=READ | evidence.accepted=True
                        CONTENT_DETAIL  | VERIFIED   | semantics=READ | evidence.accepted=True
                        KEYWORD_SEARCH  | UNVERIFIED | semantics=READ | evidence.accepted=null
                        旧 items 保留：DOUYIN → status=AVAILABLE, comments=true, keywordSearch=false
GET /v1/adapters     → 200，DOUYIN_PUBLIC.loadStatus=LOADED
```

容器间互通：`cpi-backend` 内 `curl http://collection:5001/health` → 200。

**结论**：HTTP 运行时可用已成立；**跨服务接通仍未成立**——Java 侧还没有任何代码调用这些端点。

---

## 五、审查项 5：`AccountVerificationService` 探活目标错误

### 5.1 结论：**缺陷确认存在，且此前一直未暴露。**

原代码：

```java
@Value("${app.collection-service-url:http://cpi-flask:5000}") String collectionBaseUrl
...
HttpRequest.newBuilder(URI.create(collectionBaseUrl + "/health"))
```

`cpi-flask` 容器里跑的是 **ai-service**（`flask.Dockerfile` → `COPY ai-service/app/` → `CMD python app/server.py`）。
两个服务的 `/health` 都返回 200，所以：

```
ai-service 正常 + collection-service 根本不存在
  → 验证逻辑判定「采集服务可达」
  → 账号被判为 VALID
  → 平台级有效性从未被验证过，结论却是"有效"
```

这正是用户描述的污染路径。**缺陷的真实范围**：不只是"URL 写错"，
而是**验证逻辑把一个与账号授权无关的服务，当成了账号授权的前置条件**，
并且在它不存在时给出肯定结论。

### 5.2 已实施修复

**（1）把"探测谁"变成显式参数** —— 新增 `integration/health/ServiceHealthProbe`：

```java
public ServiceHealthProbe(String serviceName, String baseUrl)
```

两个不同的实例、两个不同的名字、两个不同的 URL。**不再靠"URL 恰好不同"来区分服务。**

**（2）两个服务各自独立判断**：

| 服务 | 标识 | 不可达时 |
|---|---|---|
| 采集服务 | `COLLECTION_SERVICE` | 返回 `SERVICE_UNAVAILABLE`，**不改 `auth_status`**，账号保持原状态 |
| AI（分词/分析）服务 | `AI_SERVICE` | **不影响**账号状态判定；仅在 `serviceChecks` 中如实报告 |

**（3）验证结果显式带上两个服务的结论**：

```java
public record VerifyResult(Long accountId, String authStatus, boolean success,
                           String errorCode, String message, String scope,
                           Map<String, ServiceCheck> serviceChecks) { }
```

**（4）配置拆成两个键**（`application.yml`）：

```yaml
collection-service-url: ${COLLECTION_SERVICE_URL:http://127.0.0.1:5001}
ai-service-url:         ${AI_SERVICE_URL:http://127.0.0.1:5000}
```

**（5）新增测试**（`AccountVerificationProbeTest`）用两个本地 HTTP server 精确控制可达性组合，
覆盖：采集服务挂掉不改账号状态、AI 服务挂掉账号仍 VALID、两者结果分别呈现、探活不串台。

**（6）运行时实测**（`cpi-backend` + `cpi-collection` + `cpi-flask` 三容器）：

```
① 两服务都可达
   success=true  authStatus=VALID  errorCode=null
   COLLECTION_SERVICE: url=http://collection:5001  reachable=true  (HTTP 200)
   AI_SERVICE:         url=http://flask:5000       reachable=true  (HTTP 200)
   message: 凭证已配置且未过期（平台级有效性需真实采集验证）

② 停掉 cpi-collection 后
   success=false authStatus=VALID  errorCode=SERVICE_UNAVAILABLE
   COLLECTION_SERVICE: reachable=false (HttpConnectTimeoutException)
   AI_SERVICE:         reachable=true  (HTTP 200)      ← 不受影响
   message: 采集服务不可达，未改变账号状态
   库中 ds_account: auth_status=VALID, last_error_code=NULL   ← 未被污染
```

> ② 是这次修复最关键的一条：**环境问题不写进账号状态**。
> 修复前由于探活打的是 ai-service，采集服务即使根本不存在也会被判成"可达"，
> 从而给出"凭证有效"的肯定结论。现在两个服务各判各的，采集服务挂掉时
> 账号状态原封不动。

---

## 六、修复清单

| # | 审查项 | 处置 | 主要文件 |
|---|---|---|---|
| 1 | 证据不可信 | 证据升级为 schema 2.0：`contentHash` + `scope` 六项绑定 + `implementationHash` | `collection-service/app/registry/evidence.py`（新） |
| 1 | 证据生成 | 工具写出自带完整性信息的记录 | `collection-service/tools/verify_capability.py` |
| 2 | 语义歧义 | Schema 加 `semantics`（枚举仅 `READ`），升 1.1 | `SOURCE-02-capability-schema.json` |
| 2 | 语义歧义 | 中文名与 note 明确"读取"、"不含任何写操作" | `douyin_adapter.py` |
| 3 | 适用范围 | `EvidenceVerifier` 八项校验 + 拒绝原因可见 | `evidence.py` / `bootstrap.py` |
| 3 | 有效期 | 默认 30 天，环境变量可调 | `evidence.py` |
| 4 | 未部署 | 方案 A 独立容器 + 共享 CSV 目录 + 服务间鉴权 | `deploy/docker/collection.Dockerfile`（新）/ `docker-compose.yml` |
| 5 | 探活错配 | `ServiceHealthProbe` + 两服务独立判断 + 显式 `serviceChecks` | `ServiceHealthProbe.java`（新）/ `AccountVerificationService.java` |
| — | 契约文档 | 补「能力语义范围」章节 | `SOURCE-02-CONTRACT.md` |

---

## 七、审查后仍然遗留的事项

**本报告不宣称这些已完成：**

| 事项 | 归属 | 说明 |
|---|---|---|
| Java 消费 Python `/v1/capabilities` | SOURCE-04-02 | Java 侧**仍然只读自己的 `capability_json`**，看不到 Python 实际加载了什么 |
| Desired / Observed / Effective 三态核对 | SOURCE-04-03 | Effective 目前只存在于 `SOURCE-02-capability-schema.json` 的枚举定义里，**没有任何代码在算** |
| `ds_source.capability_json` 的 `PENDING` 不在 `effectiveStatus` 枚举内 | SOURCE-04-04 | 库中现值：`{"comments":true,"commentStatus":"AVAILABLE","contentDetail":true,"keywordSearch":false,"keywordStatus":"PENDING"}` |
| Java `capability_json` 与 Python Observed 的口径统一 | SOURCE-04-04 | 当前是两套独立来源 |
| `/v1/jobs` 仍是 stub（内部用 `StubAdapter` 造数据） | SOURCE-06 | 采集执行链路切 HTTP 属 SOURCE-06 |
| 验证证据的数字签名 | SOURCE-09 | 当前只有完整性校验，无防恶意伪造 |
| Chrome profile 并发约束 | SOURCE-06 | 两个容器不得并发启动 Chrome |

---

## 八、验收结果

| 项目 | 审查前 | 审查后 |
|---|---|---|
| Python 测试 | 40/40 | **56/56**（新增 `test_evidence.py` 16 条） |
| Java 测试 | 138/138 | **147/147**（新增 `AccountVerificationProbeTest` 5 条 + 契约测试 4 条） |
| HTTP 运行时 | ✗ 无服务 | ✓ `cpi-collection` 独立容器，`/health` + `/v1/capabilities` 实测 200 |
| 证据完整性 | ✗ 无机制 | ✓ 篡改 / 改实现 / 改版本 / 过期 四类实测均被拒绝 |
| 服务探活 | ✗ 串台 | ✓ 两服务独立判断，运行时实测覆盖「都可达」与「采集服务挂掉」两种组合 |
| 容器拓扑 | 4 个 | 5 个（新增 `cpi-collection`），全部 healthy |
| Java 消费 Python | ✗ 无 | ✗ **仍无**（SOURCE-04-02） |
