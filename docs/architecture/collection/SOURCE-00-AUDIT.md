# SOURCE-00 · AiToEarn / DTK 源码审计报告

日期：2026-11-16　方式：WebFetch 读 GitHub tree + raw 文件，未 clone、未改任何文件
结论依据：每个结论都指向具体文件路径，非 README 推断

## 0. 先说一个网络问题

本地 `git clone` 两个仓库都失败（GitHub 502 + SSL 吊销检查失败），
改用 WebFetch 逐文件抓取完成审计。仓库均可达，是当前代理环境问题。

---

## 1. AiToEarn（yikart/AiToEarn，main）

### 技术栈
NestJS 11 单体 Monorepo（Nx + pnpm），MongoDB + Mongoose，Redis + BullMQ，
S3 兼容对象存储。两个后端：`aitoearn-server`(3002) + `aitoearn-ai`(3010)。
**与 Insight 的 Spring Boot + MySQL + 单体四服务完全不同，无直接可移植性。**

### 值得吸收的（可指向文件）

| 能力 | 证据 | 对 Insight 的价值 |
|---|---|---|
| 平台 Registry + forRoot 配置注入 | `platforms.registry.ts` / `platforms.module.ts` | 平台插件化加载模型 |
| 能力双层：静态 metadata + 运行时能力推导 | `platforms.interface.ts` / `registry.ts` 的 `createMetadataCache` | 正是我们缺的「能力由实现推导」 |
| OAuth 一次性 state + 登录主体校验 | `auth.service.ts` 的 `completeCallback`/`getAuthSessionResult` | 未来 OAuth 直接复用该模式 |
| Relay 一次性 ticket 读后即删 | `relay-callback.controller.ts` | 授权回调安全范式 |
| 账号与凭证分离存储 | `account.schema.ts` + `oauth2-credential.schema.ts` | 与我们 ds_account/credential_ref 同思路 |

### 不吸收的

- **Relay 本身**：Server Relay 是"官方服务器代 OAuth"，AI Relay 是"上游 AI 视频生成"，都不是通用采集能力。
- **Publish（发布）/ Create（内容生成）**：不在采集范围。
- **NestJS/MongoDB/BullMQ**：技术路线冲突。

### 一个澄清
"评论挖掘"真实形态是 `engagement.service.ts` 的 `listComments`（评论采集）+ AI 驱动的自动回复（`engagement-task.schema.ts`）。**没有找到独立的"意图识别/品牌监测"目录**——任务卡里"Engage 含品牌监测、购买意图发现"是 README 层面的说法，代码里未见对应实现。

---

## 2. DTK（Evil0ctal/Douyin_TikTok_Download_API，v5.1.11）

### 技术栈
Python 3.12+ / FastAPI / SQLAlchemy+asyncpg / Redis / Alembic。核心包 `src/dtk/`。

### 必须明确区分：一半是合规工程，一半是绕过访问控制

**⚠️ 不采用（绕过平台访问控制）**：

| 机制 | 证据 |
|---|---|
| 复刻反爬签名（a_bogus/X-Bogus/WBI/msToken） | `signing/native/abogus.py` 等 |
| 浏览器执行站点 JS 自动跟随签名 | `signing/rpc.py` |
| TLS/JA3 指纹伪装 | `transport/emulation.py` |
| 伪造 guest 设备指纹（身份铸造） | `identity/minting/` |
| 风控检测 + 身份冷却轮换 | `identity/pool.py` 的 `record_outcome` |

**结论**：DTK 的"采集"本质 = 签名复刻 + 指纹伪装 + 身份铸造，去访问抖音/TikTok 的**非公开 Web API**。这正是任务卡和合规要求里明确禁止的，**整块不采用**。

### ✅ 可借鉴的合规工程模式

| 模式 | 证据 | 价值 |
|---|---|---|
| Adapter 抽象 + 纯函数解析器 + 平台包零 IO | `platforms/base.py` 的 `PlatformAdapter` Protocol | 采集/解析/传输分层解耦 |
| 稳定错误码枚举 + HTTP 映射 + 可重试标记 | `core/errors.py` | 与我们的 failure_type 七类可对齐 |
| 响应四分类（OK/业务/风控/网络） | `transport/classify.py` | 区分"没需求"vs"没采到"的关键 |
| 令牌桶限流（身份×端点） | `scheduler/policies.py` | 限流思想 |
| 熔断器（closed/open/half-open） | `scheduler/circuit.py` | 端点熔断 |
| SSRF 防护（主机白名单/禁私网） | `api/routes/content.py` + `urls/` | Web 采集安全必备 |
| 统一信封 + 202 异步任务模型 | `api/app.py` | 与我们 Result 信封同思路 |

---

## 3. 对 SOURCE-02 契约冻结的三条直接结论

1. **能力必须"由实现推导"，不能靠 JSON 声明。**
   两个项目都验证了这一点：AiToEarn 的 `createMetadataCache` 从 provider 是否存在推导能力，
   DTK 的 Adapter Protocol 明确"能力绑定到函数"。**这正是我们当前最大的缺口**
   （`capability_json` 是手写的，与 `comment.py` 零联动）。

2. **错误分类必须落到枚举，不能是自由文本。**
   DTK 的 `errors.py` 有完整 `ErrorCode` 枚举 + `NON_RETRYABLE` 集合，
   对应我们 `col_execution.failure_type` 七类的落地依据。

3. **OAuth 安全范式可以提前锁定**（即使首期不做 OAuth）：
   state/ticket 一次性、校验登录主体、凭证与账号分离——三条规则直接写进 SOURCE-02 的契约，
   避免未来做 OAuth 时重蹈 AiToEarn 早期"回调信任 userId"的覆辙。

## 4. 许可证与依赖风险

| 项目 | 许可证 | 风险 |
|---|---|---|
| AiToEarn | 需单独确认（审计时未读 LICENSE 全文） | NestJS/Mongo 栈不可移植，仅借鉴设计 |
| DTK | 需单独确认 | 核心签名/指纹代码即使想抄也违反合规，且不在许可考量范围内 |

> 两个仓库的 LICENSE 具体条款未在本次审计中逐字核对，若后续真要复制任何代码片段，
> 必须先确认许可证并记录来源。

## 5. 结论

| 项 | 判定 |
|---|---|
| AiToEarn 平台注册/能力推导 | ✅ 借鉴设计 |
| AiToEarn OAuth 安全范式 | ✅ 锁定为契约规则 |
| AiToEarn 评论意图识别/品牌监测 | ⚠️ README 有、代码未见，不采纳为既成能力 |
| DTK 签名/指纹/身份铸造 | ❌ 明确不采用（合规红线） |
| DTK Adapter 分层/错误码/限流熔断/SSRF | ✅ 借鉴工程模式 |
| 是否引入独立运行环境 | ❌ 不引入任何一方的独立服务 |

**SOURCE-00 可解除阻塞。** 下一步可进入 SOURCE-02（契约冻结）。
