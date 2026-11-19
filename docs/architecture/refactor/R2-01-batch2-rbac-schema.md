# R2-01 · Batch 2 第一步：RBAC 表结构

日期：2026-11-15　迁移：`V13__system_rbac.sql`（flyway version=13 success=1）

## 1. 新增表（4 张）

| 表 | 说明 |
|---|---|
| `sys_role` | 角色，`code` 唯一，`builtin` 标记内置角色不可删 |
| `sys_user_role` | 用户-角色，`UK(user_id, role_id)` |
| `sys_permission` | 权限点，`code` 唯一（如 `system:user:list`），按 module 分组 |
| `sys_role_permission` | 角色-权限，`UK(role_id, permission_id)` |

## 2. 种子数据（实测）

| 项 | 数量 |
|---|---|
| 角色 | 3（ADMIN / USER / REVIEWER，均 `builtin=1`） |
| 权限点 | 14（覆盖 6 个 system 子域的入口） |
| 角色-权限 | 21（ADMIN 14 / REVIEWER 4 / USER 3） |
| 用户-角色 | 6（现有 6 个用户全部映射） |

## 3. 关键决策：`user.role` 保留

存量用户的 `role`（0/1）已映射到新角色（1→ADMIN，0→USER），但**该列不删除**。

原因：权限判定一旦从 `user.role` 切到 RBAC，判错的后果是**越权**或**全员失去权限**，
且不会报错。切换期间保持双读比对，确认一致后再移除。

## 4. 验证

| 项 | 结果 |
|---|---|
| flyway | version=13, success=1 |
| 表与种子 | 4 表 + 3/14/21/6 行 |
| 用户映射覆盖率 | 6/6（100%） |
| `mvn test` | 74/74 |
| `/api/health` | 200 |

## 5. 下一步（Batch 2 未完）

1. `RoleService` / `RoleController`：角色 CRUD、分配角色
2. `PermissionService` 改造：由读 `user.role` 改为查 RBAC，带 60s 缓存
3. `@RequirePermission` + 拦截器（与现有 `@RequireRole` 并存过渡）
4. 角色权限分配的接口与测试

**本批次只完成了表结构，业务逻辑尚未实现**，不能算 Batch 2 完成。
