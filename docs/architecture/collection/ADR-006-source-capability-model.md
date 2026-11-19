# ADR-006 · 数据源能力模型：Desired / Observed / Effective 三态

日期：2026-11-16　状态：**已冻结（候选）**
关联：SOURCE-00 审计、SOURCE-01 基线、SOURCE-02 契约

## 背景

`ds_source.capability_json` 目前是手写的 JSON，与 Python 侧 `comment.py` 的实际能力**零联动**。
后果：Java 页面上"支持评论采集"，可能只是某次人工填写时的一句真话，之后无人再验证。

SOURCE-00 审计的两个外部项目给出了同一个答案：能力必须由实现推导。
AiToEarn 的 `createMetadataCache` 从 provider 是否存在推导；DTK 的 Adapter Protocol 把能力绑定到函数。

## 决策

### 1. 能力分三态，不互替

| 态 | 来源 | 含义 |
|---|---|---|
| Desired | Java `capability_json` | 业务上"声明/允许"提供 |
| Observed | Python Registry | 运行时"真实加载了" |
| Effective | 两侧核对计算 | 当前"真正允许执行" |

**保留 `capability_json`**（它是 Desired，有业务价值），
但**不允许 Python 运行态直接覆盖它**——否则"Java 已启用的能力"会被某次探测失败静默清空。

### 2. 能力必须由函数证明

`capabilities[].adapterFunction` 是必填字段，指向 Python 真实可调用的函数。
该函数无法 resolve 时，无论 `implementationStatus` 写什么，Effective 都不是 AVAILABLE。

### 3. Connector 与 Source 一对多

同一平台可有多种接入（公开网页 / 官方 API / 授权会话），各自独立的能力集合与健康状态。
**首期不建 `ds_connector` 表**，用 Python Registry + JSON 契约表达；出现"后台在线切换多接入"需求再持久化。

### 4. 错误分两层

技术错误码（细粒度定位）→ 业务 failure_type（复用现有七类）。
`retryable` 由 failure_type 决定，不由 Java 读错误文本猜。

### 5. 健康检查分层

L1 存活 / L2 Adapter 加载 / L3 能力函数注册 / L4 平台连通 / L5 授权真实采集。
L1~L3 自动执行，L4/L5 受控触发，**不为了健康检查伪造身份或高频调用平台**。

## 后果

| 正面 | 负面 |
|---|---|
| 页面能如实反映"能不能用"，而不是"曾经配过" | Java/Python 需要同时改，契约维护成本增加 |
| 单个能力失效不拖累整个 Source | 需要新增核对逻辑（SOURCE-08） |
| 新增平台时只需新增 Connector，不动业务模型 | 需要把现有 `capability_json` 迁移到 v1 Schema |

## 不做的事

- 不建 `ds_capability` / `ds_connector` / `ds_credential` / `ds_health_check` 表
- 不引入 OAuth、账号池、身份轮换、签名模拟
- 不让 Python 运行态覆盖 Java 业务配置
- 不把监测场景做成 Capability（它属于 analysis 层）
EOF
