# R2-01 · Batch 2 第六步：菜单管理

日期：2026-11-15　测试：**101/101**

## 1. 交付

| 文件 | 说明 |
|---|---|
| `V15__system_menu.sql` | `sys_menu` 表 + 26 条种子（7 分组 + 15 页面 + 5 规划中） |
| `system/domain/menu/Menu` | 实体 |
| `system/dto/menu/MenuNode` | 树节点 |
| `system/infrastructure/menu/MenuMapper` | 持久层 |
| `system/application/menu/MenuService` | 树构建、按权限过滤、增删改状态 |
| `system/controller/menu/MenuController` | 4 个端点 |
| `test/.../system/menu/MenuServiceTest` | 6 例 |

## 2. 两个关键点

**菜单 ≠ 权限**

`/menus/current` 按 `permission_code` 过滤，只影响"看不看得见"。
真正的拦截仍在 `@RequirePermission`——把菜单藏起来但接口开着，等于没做权限。

**树不能有孤儿**

- 父级不存在时拒绝创建
- 有子菜单时拒绝删除

父级被权限过滤掉时，子节点降级为一级显示（`build()` 中处理），避免节点凭空消失。

## 3. 运行时验证

管理员 `/menus/current`：7 组，系统管理下 5 项。

普通用户（USER 角色，拥有 `system:user:list` + `system:config:list`）：

```
系统管理: 用户与权限, 系统配置        ← 有权限的两项可见
（角色管理 / 字典管理 / 操作审计被过滤）
```

与 V13 的权限种子完全一致，说明过滤逻辑正确。

## 4. 测试

```
Tests run: 101, Failures: 0, Errors: 0
```

新增 6 条：全量树结构、普通用户过滤、管理员全见、父级不存在拒绝、
有子级不可删、非法状态拒绝。

## 5. Batch 2 后端完成度

| 子域 | 状态 |
|---|---|
| 用户 User | ✅（此前批次） |
| 角色 Role | ✅ |
| 权限 Permission | ✅ |
| 菜单 Menu | ✅ 本次 |
| 配置 Config | ✅（此前批次） |
| 字典 Dict | ✅ |
| 审计 Audit | ✅（此前批次） |

**后端 System 七个管理子域全部就绪。**

## 6. 剩余

| 项 | 说明 |
|---|---|
| 前端 4 个页面 | 角色 / 字典 / 菜单 / 审计 |
| 前端菜单动态化 | `menu.js` 改为请求 `/menus/current` |
| `@RequireRole` 逐步被 `@RequirePermission` 取代 | 过渡期保留 |
| 移除 `user.role` 双读 | 观察日志无差异后 |
