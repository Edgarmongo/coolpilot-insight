# C-05 · 平台账号设计定稿（ds_account）

状态：设计，未实施　基线：`0d1a9bb`

## 1. 现状（实测 12 列）

`ds_account`：`id, source_id, account_name, external_account_id, auth_type, credential_ref, status, expires_at, last_used_at, created_at, updated_at, tenant_id`，**0 行数据、0 接口、0 页面**。

## 2. 字段落差（需 Migration 评审）

| 提案字段 | 现状 | 处置 |
|---|---|---|
| source_id / account_name / credential_ref / status / expires_at | ✅ 有 | 复用 |
| external_account_id | ✅ 有 | 复用（平台侧唯一标识） |
| auth_type | ✅ 有 | 改为 `account_type`（PUBLIC/AUTHENTICATED 是访问模式，不是账号类型，见 §3） |
| **auth_status** | ❌ 无 | **新增**（与 status 分离，见 §4） |
| **authorized_at** | ❌ 无 | 新增 |
| **last_verified_at** | ❌ 无 | 新增（替代不建的 ds_health_check） |
| **last_error_code / last_error_message** | ❌ 无 | 新增 |
| **remark** | ❌ 无 | 新增 |

**结论：需 V28 新增 6 列 + 1 处列语义调整**。按禁令，实施前单独提 Migration 评审，本文件不执行。

> 注意：`auth_type` 若改名为 `account_type` 属破坏性变更，但表为空、无引用，风险为零。
> 若保守起见可保留 `auth_type` 不改，仅新增 6 列——**建议后者**，减少无谓变更。

## 3. PUBLIC 不是账号类型

裁决 DECISION-02 §7：PUBLIC 不创建虚拟账号。
所以 `PUBLIC/AUTHENTICATED` 是**采集任务的访问模式**（`col_execution.auth_mode` 已有），
不是 `ds_account` 的属性。账号表只存真实账号。

推论：`ds_account` 不需要 `account_type` 列。§2 建议保留 `auth_type`（表示凭证类型：COOKIE/TOKEN），语义更准确。

## 4. 双状态模型（核心）

| 字段 | 含义 | 取值 |
|---|---|---|
| `status` | 管理员是否启用 | ENABLED / DISABLED |
| `auth_status` | 授权是否有效 | VALID / EXPIRED / INVALID / REVOKED / UNKNOWN |

两个维度独立：

```
status=ENABLED  + auth_status=EXPIRED   → 账号启用但授权过期，AUTHENTICATED 任务不可用
status=DISABLED + auth_status=VALID     → 授权有效但被管理员停用，任何任务不可用
```

**任务创建校验顺序**：`status=ENABLED` → `auth_status=VALID` → `expires_at > now()` → 账号属于任务 source。

## 5. 状态机

**status**：`ENABLED ⇄ DISABLED`（管理员操作，无自动流转）

**auth_status**：

| 从 | 事件 | 到 |
|---|---|---|
| UNKNOWN | 首次验证成功 | VALID |
| UNKNOWN | 首次验证失败 | INVALID |
| VALID | 到期时间过去 | EXPIRED（定时任务扫描） |
| VALID | 验证失败（401/403） | INVALID |
| 任意 | 管理员撤销 | REVOKED |
| 任意 | 重新授权成功 | VALID |

**规则**：`REVOKED` 不可自动恢复，必须重新授权；`EXPIRED` 可由续期回到 VALID。

## 6. 凭证边界

`credential_ref` 只存引用（如 `env:DOUYIN_COOKIE_A` 或密管路径）。硬约束：

1. 任何 API 响应不含凭证，只返回 `credentialConfigured: true/false`
2. 不进入 `col_run.config_snapshot`
3. 不写日志（含异常堆栈）
4. Python 通过受控接口取值，Java 不转发明文

## 7. API（`/api/v1/source/accounts`）

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/accounts` | `source:account:list` |
| POST | `/accounts` | `source:account:manage` |
| PUT | `/accounts/{id}` | 同上 |
| PATCH | `/accounts/{id}/status` | 同上 |
| POST | `/accounts/{id}/verify` | 同上（触发验证，写 last_verified_at） |
| GET | `/accounts/{id}/usage` | `source:account:list` |

**不提供**凭证上传接口（凭证走外部密管）。

## 8. 方案 A / B 选择

**采用 A（账号管理）**，不实现账号池调度。
预留空间：`last_used_at` 已在表里（记录使用），未来加 `quota`/`cooldown` 列即可扩展为 B。
**不预留**：不加 `pool_group`/`weight` 这类只有池调度才用的字段。

## 9. 任务卡（CP-INSIGHT-ACCOUNT-01）

| 批次 | 内容 | 验收 |
|---|---|---|
| A-01 | V28 新增 6 列（评审通过后执行） | 迁移 success，表结构符合 |
| A-02 | AccountService + 6 接口 | 校验四步顺序；凭证不回显 |
| A-03 | 前端账号列表 + 新建/编辑/验证 | 双状态分列展示 |
| A-04 | 采集任务接入账号校验 | AUTHENTICATED 无有效账号时拒绝创建 |
| A-05 | 测试 | 双状态组合、过期拦截、凭证不泄漏 |

## 10. 待确认

1. 是否批准 V28（6 列新增）；`auth_type` 改名是否接受（建议不改）
2. 到期扫描（VALID→EXPIRED）用 `@Scheduled` 还是验证时惰性判断（建议后者+每日扫描兜底）
3. 凭证引用格式约定：环境变量 `env:NAME` vs 密管路径 `vault:path`
