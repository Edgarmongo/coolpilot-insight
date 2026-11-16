# SOURCE-00 · 审计范围如实标注

日期：2026-11-16　配合 `SOURCE-00-AUDIT.md` 阅读

> 你要求明确审计范围，避免"局部源码审计被描述成完整仓库审计"。以下如实记录。

## 1. 审计方式与覆盖范围

| 项 | 实际情况 |
|---|---|
| 方式 | WebFetch 抓取 GitHub tree 页 + raw 文件（**非本地完整 clone**） |
| AiToEarn | 读 main 分支。本地 clone 失败（GitHub 502 + SSL 吊销检查失败） |
| DTK | 读 main 分支（v5.1.11）。本地 clone 同样失败 |

**结论：这是"关键路径源码审计"，不是"完整仓库审计"。**

## 2. 实际读取的文件

### AiToEarn（main）

| 文件 | 用途 |
|---|---|
| `project/aitoearn-backend/package.json` | 技术栈 |
| `docker-compose.yml` | 服务拓扑 |
| `libs/channel-db/src/schemas/account.schema.ts` | 账号模型 |
| `libs/channel-db/src/schemas/oauth2-credential.schema.ts` | 凭证模型 |
| `apps/aitoearn-server/src/core/channels/platforms/platforms.registry.ts` | 注册机制 |
| `apps/aitoearn-server/src/core/channels/platforms/platforms.interface.ts` | 能力契约 |
| `apps/aitoearn-server/src/core/channels/platforms/platforms.module.ts` | 模块装配 |
| `apps/aitoearn-server/src/core/channels/auth/auth.service.ts` | OAuth 流程 |
| `apps/aitoearn-server/src/core/channels/relay/relay-callback.controller.ts` | Relay 回调 |
| `apps/aitoearn-server/src/core/channels/engagement/engagement.service.ts` | 评论采集 |
| `libs/channel-db/src/schemas/engagement-task.schema.ts` | 互动任务 |

### DTK（main / v5.1.11）

| 文件 | 用途 |
|---|---|
| `pyproject.toml` | 技术栈 |
| `src/dtk/platforms/base.py` | Adapter 协议 |
| `src/dtk/platforms/registry.py` | 注册机制 |
| `src/dtk/signing/base.py`、`native/*`、`rpc.py` | 签名实现 |
| `src/dtk/transport/emulation.py` | TLS 指纹 |
| `src/dtk/identity/pool.py`、`minting/*` | 身份池 |
| `src/dtk/core/errors.py` | 错误码枚举 |
| `src/dtk/scheduler/policies.py`、`circuit.py` | 限流熔断 |
| `src/dtk/transport/classify.py` | 响应分类 |
| `src/dtk/api/app.py`、`routes/content.py` | 对外接口 |

## 3. 未覆盖范围（如实声明）

| 未做 | 影响 |
|---|---|
| **未逐个平台目录深入** | AiToEarn 14 个平台子目录只抽查了 douyin/rednote 的装配方式，未逐一看实现细节 |
| **未核对调用链完整性** | 主要读的是接口定义与关键函数，未完整跟到最底层平台 HTTP 调用 |
| **未运行任何代码/测试** | 所有结论均为**静态代码推断**，未经运行时验证 |
| **未逐字读 LICENSE** | 两个项目的许可证条款未逐字核对，若后续要复制代码片段必须补做 |
| **未确认审计 HEAD 与"某次修复提交"的包含关系** | AiToEarn 的 OAuth 回调安全修复（你提到的 `c7887e0`）未核对是否在当前 HEAD 内 |

## 4. 结论置信度分级

| 结论 | 置信度 | 依据 |
|---|---|---|
| 技术栈、目录结构、注册机制 | **高** | 直接读 tree 与配置文件 |
| 能力由实现推导这一模式 | **高** | 两项目均有明确代码实现 |
| OAuth 一次性 state/ticket 与主体校验 | **高** | 读到具体校验代码行 |
| "AiToEarn 评论意图识别不存在" | **中** | 是"未找到"，不是"证明不存在"——可能在我未覆盖的目录 |
| DTK 签名/指纹属绕过访问控制 | **高** | 代码实现明确，无歧义 |
| 许可证合规性 | **未评估** | 未逐字读 LICENSE |

## 5. 对 SOURCE-02 的影响

SOURCE-02 契约的**核心结论**（三态模型、能力由函数证明、错误两层、健康分层）
不依赖"完整仓库审计"——它们同时被两个独立项目的实现共同佐证，且与我们自身缺口吻合。

但**"AiToEarn 没有意图识别"这一条，在 SOURCE-02 里我已降级为"代码未见对应实现"，
不写成"该能力不存在"。**

## 6. 若要提升为完整审计

需要：本地完整 clone（当前网络环境阻塞）→ 逐平台目录过一遍 → 跑通各自测试套件。
这超出当前只读审计卡的范围，建议等真正要吸收某段具体代码时再做针对性深读。
