# A-01 · V28 最终材料与迁移执行方案

状态：**SQL 已定稿，未移入 migration，未执行**

## 1. 核对中发现的一处不一致（需裁决）

| 项 | 我的设计文档写的 | 数据库实际 |
|---|---|---|
| `ds_account.status` | `ENABLED` / `DISABLED` | **NOT NULL DEFAULT `'ACTIVE'`**，VARCHAR(24) |
| `auth_type` | 未定义默认值 | NOT NULL DEFAULT `'COOKIE'` |

**影响**：若按 `ENABLED` 实现校验，现有默认值 `ACTIVE` 会被判为"未启用"，
新建账号立刻不可用——且不报错。

**两个选项**：

- **A（建议）**：接受 `ACTIVE` 为启用态，校验 `status = 'ACTIVE'`；`DISABLED` 为停用。不改数据。
- **B**：新增迁移把默认值改为 `ENABLED` 并回填。属额外变更，当前 0 行虽无风险，但没必要。

## 2. 最终 SQL

见 `sql/V28-FINAL-do-not-deploy.sql`：

- 6 列 `ADD COLUMN` + 1 个 `CHECK`（一条 ALTER，MySQL 原子）
- 约束名 `chk_ds_account_auth_status`（稳定、唯一、带表名前缀）
- CHECK 只限合法值集合，**不**表达"启用+有效+未过期+Source 归属"的组合规则
  （那是 AccountService 的业务校验）
- `PENDING_VERIFY` 纳入合法值，首期可不实现对应流程

## 3. 迁移执行方案（A-01.2 授权后才做）

| 步 | 动作 | 验证 |
|---|---|---|
| 1 | 记录当前 Flyway 版本（应为 27） | `SELECT MAX(version) FROM flyway_schema_history` |
| 2 | 记录 `ds_account` 结构 + 行数 | `SHOW CREATE TABLE` + `COUNT(*)`（当前 0） |
| 3 | 完整备份目标库 | `mysqldump --databases coolpilot_insight` |
| 4 | 校验备份 | 文件非空 + `grep "CREATE TABLE \`ds_account\`"` 命中 |
| 5 | 移入 `db/migration/V28__account_auth_status.sql` | 文件名与已有 V25/V26/V27 不冲突 |
| 6 | 重启后端触发 Flyway | 日志 `Migrating schema to version 28` |
| 7 | 迁移后核对 | 6 列存在、CHECK 存在、行数不变 |
| 8 | 约束行为测试 | 非法值被拒；5 个合法值均可写入（测试后清除） |

## 4. 回滚方案（含数据保全）

```sql
-- 若已有授权数据写入，先导出：
--   mysqldump ... --where="auth_status <> 'UNKNOWN'" ds_account
ALTER TABLE `ds_account`
  DROP CHECK `chk_ds_account_auth_status`,
  DROP COLUMN `remark`, DROP COLUMN `last_error_message`, DROP COLUMN `last_error_code`,
  DROP COLUMN `last_verified_at`, DROP COLUMN `authorized_at`, DROP COLUMN `auth_status`;
```

**注意**：`DROP CHECK` 必须写在 `DROP COLUMN` 之前——列删了约束会因引用不存在而报错。

回滚前必须导出 `auth_status <> 'UNKNOWN'` 的行，否则这些授权状态永久丢失。

## 5. 授权边界（按裁决）

| 阶段 | 内容 | 授权 |
|---|---|---|
| A-01 | 最终 SQL + 评审记录 + 执行方案 | ✅ 已完成（本次） |
| A-01.1 | 移入 `db/migration` | ❌ 待批 |
| A-01.2 | 备份并执行迁移 | ❌ 待批 |
| A-02 | AccountService + 6 接口 | ❌ 待批 |
| A-03 | 账号管理前端 | ❌ 待批 |

## 6. 待确认

1. §1 的 `status` 取值：接受 `ACTIVE`（方案 A）还是改为 `ENABLED`（方案 B）
2. 是否同意 `DROP CHECK` 先于 `DROP COLUMN` 的回滚顺序
3. A-01 完成后是否授权 A-01.1（移入目录但仍不重启）
