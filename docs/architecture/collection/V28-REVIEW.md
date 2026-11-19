# V28 迁移评审材料

状态：**仅评审，未执行、未放入 migration 目录**　草案：`sql/V28-DRAFT-do-not-deploy.sql`

## 1. 字段与类型

| 列 | 类型 | NULL | 默认 | 理由 |
|---|---|---|---|---|
| `auth_status` | VARCHAR(32) | NOT NULL | `UNKNOWN` | 五态枚举；NOT NULL 避免"空=未知"二义 |
| `authorized_at` | DATETIME | NULL | — | 从未授权过即为空，空有语义（不等于 1970） |
| `last_verified_at` | DATETIME | NULL | — | 从未验证过即为空；用于判断"多久没验" |
| `last_error_code` | VARCHAR(64) | NULL | — | 无错误时为空 |
| `last_error_message` | VARCHAR(500) | NULL | — | 截断存储，完整信息在日志 |
| `remark` | VARCHAR(500) | NULL | — | 运营备注 |

**不使用 TINYINT 存状态**：枚举值会扩展（如未来加 `PENDING_VERIFY`），VARCHAR 加值不需迁移。

## 2. NULL 语义（逐个确认）

| 列 | NULL 的含义 | 是否会被误判 |
|---|---|---|
| `authorized_at` | 从未授权 | 校验时不参与判断 ✅ |
| `last_verified_at` | 从未验证 → 视为"未验证"，不阻断任务但监控告警 | 需在代码中显式处理 ✅ |
| `expires_at`（已有） | **无已知过期时间，不算过期**，但仍须 `auth_status=VALID` | 已按裁决写入设计 ✅ |
| `last_error_*` | 无错误 | 展示层显示 `—` ✅ |

## 3. 历史数据回填

现表 **0 行**（实测），无回填。

**若将来有存量数据**，回填规则必须固定：

```sql
-- 仅当有可信证据时才设为 VALID；否则保持 UNKNOWN
UPDATE ds_account SET auth_status = 'UNKNOWN' WHERE auth_status IS NULL;  -- 无需，因 NOT NULL DEFAULT
```

**禁止**：把 `UNKNOWN` 批量改 `VALID`——那等于凭空断言"授权有效"。

## 4. 索引必要性

| 候选索引 | 结论 |
|---|---|
| `(source_id, status)` | **暂不加**。账号量级为个位数，全表扫可接受；等出现按平台筛账号的分页需求再加 |
| `(auth_status)` | 不加。每日扫描一次，全表扫成本可忽略 |

**结论：本次不新增索引。** 加索引属额外变更，收益无法证明。

## 5. 约束（建议加入，需评审）

```sql
ALTER TABLE ds_account ADD CONSTRAINT ck_account_auth_status
  CHECK (auth_status IN ('VALID','EXPIRED','INVALID','REVOKED','UNKNOWN'));
```

与 `col_execution.ck_exec_auth_account` 同样思路：防拼写错误导致的静默失效。
**此项可选**——若要绝对保守，仅靠应用层枚举校验。

## 6. 幂等性

`ALTER TABLE ... ADD COLUMN` **不幂等**：重复执行报 1060。
Flyway 按版本号只执行一次，正常不会重复；但迁移中断后 repair 重跑会失败。

**处置**：保持单条 ALTER 语句（6 列一次加），MySQL 单 ALTER 原子，不会出现"加了 3 列"的中间态。

## 7. 回滚

```sql
ALTER TABLE ds_account
  DROP COLUMN remark, DROP COLUMN last_error_message, DROP COLUMN last_error_code,
  DROP COLUMN last_verified_at, DROP COLUMN authorized_at, DROP COLUMN auth_status;
```

前提：**无业务依赖**。若有账号数据写入过 `auth_status`，回滚会丢失授权状态 →
回滚前必须备份 `ds_account`。

## 8. API 权限分层（裁决第 5 条）

| 操作 | 接口 | 谁能调 | 能否写 auth_status |
|---|---|---|---|
| 增/改账号资料 | `POST/PUT /accounts` | 管理员（`source:account:manage`） | **否** |
| 启用/停用 | `PATCH /accounts/{id}/status` | 管理员 | **否**（只写 status） |
| 触发验证 | `POST /accounts/{id}/verify` | 管理员 | 间接（由验证结果决定） |
| 验证结果回写 | **内部服务调用，不暴露 HTTP** | `AccountVerificationService` | 是 |
| 凭证更新 | **不提供 HTTP 接口** | — | — |

**硬约束**：`auth_status=VALID` 只能由 `AccountVerificationService` 在收到可信验证结果后写入。
普通更新接口收到 `authStatus` 字段一律忽略（而非报错，避免前端误传导致 500）。

## 9. 凭证引用（裁决第 3 条）

格式：`local-secret:account/<opaque-id>`，未来可平滑改为 `vault:...`。

接口：

```java
public interface CredentialProvider {
    boolean isConfigured(String ref);
    /** 仅 Execution 执行期调用，需权限校验 + 审计 */
    char[] resolve(String ref);
}
```

首期 `LocalEncryptedCredentialProvider`：密文存本地文件，密钥从环境变量读取（**密钥与密文分离**）。

**诚实声明**：本阶段只落"引用 + Provider 接口"，**加密存储本身尚未实现**。
在实现之前，不能对外宣称"已具备密管能力"——这正是裁决里强调的那一点。

## 10. 评审结论请求

| # | 项 | 建议 |
|---|---|---|
| 1 | 6 列类型与默认值 | 如 §1 |
| 2 | 是否加 `auth_status` CHECK 约束 | 建议加 |
| 3 | 是否加索引 | 建议**不加** |
| 4 | 回滚前置备份 `ds_account` | 必须 |
