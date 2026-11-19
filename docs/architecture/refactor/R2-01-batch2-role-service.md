# R2-01 · Batch 2 第二步：角色服务

日期：2026-11-15　测试：**80/80**

## 1. 新增文件

| 文件 | 说明 |
|---|---|
| `system/domain/role/Role` | 角色实体 |
| `system/infrastructure/role/RoleMapper` | 查询/新增/删除/分配/解除（注解式 SQL） |
| `system/application/role/RoleService` | 角色 CRUD、分配/解除、按用户查角色 |
| `system/dto/role/RoleView` | 视图：带 `userCount` 与 `permissionIds` |
| `system/controller/role/RoleController` | 5 个端点 |
| `test/.../system/role/RoleServiceTest` | 6 例 |

## 2. 业务规则（都有测试）

1. **内置角色不可删除** —— 否则可能把 ADMIN 删掉，导致无人可管理系统
2. **仍有用户关联时不可删除** —— 否则这些用户会静默失去全部权限
3. 角色编码 `[A-Z][A-Z0-9_]{1,31}`，唯一
4. 分配/解除角色写审计

## 3. 接口

| 方法 | 路径 |
|---|---|
| GET | `/api/v1/system/roles` |
| POST | `/api/v1/system/roles` |
| DELETE | `/api/v1/system/roles/{id}` |
| POST | `/api/v1/system/roles/{id}/users/{userId}` |
| DELETE | `/api/v1/system/roles/{id}/users/{userId}` |

权限暂用 `@RequireRole(role=1)`，待 `@RequirePermission` 落地后切换为权限码。

## 4. 运行时验证

```
GET /api/v1/system/roles
  ADMIN    管理员   builtin=true users=2 perms=14
  USER     普通用户 builtin=true users=4 perms=3
  REVIEWER 审核员   builtin=true users=0 perms=4

DELETE /api/v1/system/roles/1
  → 403 {"code":40300,"message":"内置角色不可删除"}
```

## 5. 测试

```
Tests run: 80, Failures: 0, Errors: 0
```

新增 6 条：种子存在、内置不可删、编码重复、编码非法、有用户时不可删、分配与解除。

## 6. Batch 2 剩余

| 项 | 状态 |
|---|---|
| RBAC 表与种子 | ✅ |
| 角色服务 | ✅ |
| `PermissionService` 改 RBAC 查询 | ⬜ |
| `@RequirePermission` + 拦截器 | ⬜ |
| 前端角色管理页 | ⬜ |

**注意**：权限判定目前仍走 `user.role`，RBAC 表只写不读。
这是有意为之——切换判定需要单独一步并做双读比对，不能混在同一次提交里。
