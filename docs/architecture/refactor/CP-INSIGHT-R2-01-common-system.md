# CP-INSIGHT-R2-01 · Common + System 基础模块开发任务卡

版本：v1（2026-11-15）
状态：**待确认**（§6 有 5 个需要拍板的决策）
前置：R0/R1 已完成（19 份文档）；R2-02/03/04 已部分落地

---

## 0. 先对齐基线（避免重复开发）

你提出的 R2-01（公共基础）与 R2-02（System 核心）**有相当一部分已经完成**，直接照单全做会重复劳动。

### 已完成（有提交与测试为证）

| 你列的项 | 现状 | 证据 |
|---|---|---|
| 统一响应结构 | ✅ `common/web/Result` + `PageResult` | R2-04 `1aff0af` |
| 统一异常处理 | ✅ `BizException` + `ErrorCode` + 全局处理器（含 404/401/403 区分） | 同上 |
| 参数校验 | ◐ 手写校验（手机号/密码/关键词长度） | 未引入 `@Valid` |
| 分页规范 | ✅ `PageResult.of(records,total,current,size)` | R2-01 |
| 用户上下文 | ✅ `common/security/RequestContext`（ThreadLocal） | R2-02 |
| 安全认证基础 | ✅ JWT 签发/校验 + 拦截器 + `@RequireRole` | 既有 + R2-04 修复编码 |
| 序列化规范 | ◐ Jackson 默认；未统一 null/时间格式 | — |
| 通用枚举/常量 | ❌ 业务枚举散落在代码里 | — |
| User 用户管理 | ✅ 注册/登录/改密/分页/脱敏/删号 | `6f42d92`、`ebd9375` |
| Config 系统配置 | ✅ 列表/更新/类型校验/密钥脱敏/缓存失效 | `6e4bed3` |
| Audit 审计管理 | ✅ 写入（登录/删号/配置变更）+ 分页查询 | 同上 |
| Role 角色管理 | ❌ 当前是 `user.role` 一个 int | — |
| Permission / Menu | ❌ 完全没有 | — |
| Dict 字典 | ❌ 没有 | — |
| File / Log / Notification / Job / Organization / Tenant | ❌ 没有 | — |

**结论**：本任务卡只覆盖**缺口部分**，已完成的不重做。

---

## 1. 本批次范围（只做这些）

| # | 子领域 | 交付 |
|---|---|---|
| 1 | `common` 收口 | 引入 `@Valid` 参数校验、统一时间/null 序列化、抽业务枚举到 `common/enums` |
| 2 | `system/role` | `sys_role` + `sys_user_role`，用户-角色多对多 |
| 3 | `system/permission` | 基于「角色→权限码」的判定，接口级校验（`@RequirePermission`） |
| 4 | `system/menu` | 菜单树 + 角色-菜单授权，供前端动态菜单 |
| 5 | `system/dict` | 字典类型 + 字典项（业务状态/分类的单一来源） |
| 6 | `system/auth` | 认证策略收敛（Token 失效、刷新、退出） |

**明确不做**（理由见 §7）：Organization、Tenant、Notification、Job、Log（运行日志）。

---

## 2. 数据库（过渡迁移 V13）

现有 `user` / `system_config` / `audit_log` 三表不动，新增 5 张（与 `db/migration-next/V1__init.sql` 对齐，过渡期外键指向 `user`）：

### `sys_role`

| 列 | 类型 | 约束 |
|---|---|---|
| id | BIGINT UNSIGNED | PK |
| code | VARCHAR(32) | **UK**（ADMIN / USER / REVIEWER） |
| name | VARCHAR(64) | NOT NULL |
| builtin | TINYINT | 默认 0，内置角色不可删 |
| status | VARCHAR(16) | ENABLED/DISABLED |
| created_at / updated_at | DATETIME | 自动 |

### `sys_user_role`

| 列 | 约束 |
|---|---|
| id | PK |
| user_id | FK → user |
| role_id | FK → sys_role |
| | **UK(user_id, role_id)** |

### `sys_permission`（权限点，不是数据权限）

| 列 | 说明 |
|---|---|
| id | PK |
| code | VARCHAR(64) **UK**，如 `system:user:list`、`collection:task:create` |
| name / module | 名称、所属模块 |
| type | API / MENU / BUTTON |

### `sys_role_permission`

| 列 | 约束 |
|---|---|
| role_id / permission_id | **UK(role_id, permission_id)** |

### `sys_menu`

| 列 | 说明 |
|---|---|
| id / parent_id | 树结构 |
| name / path / icon / sort | 渲染用 |
| permission_code | 可空；有值则需该权限才显示 |
| visible / status | 是否展示、是否启用 |

### `sys_dict_type` + `sys_dict_item`

`type_code` UK；item 有 `item_value` / `item_label` / `sort` / `status`，UK(type_id, item_value)。

### 迁移与种子

- `V13__system_rbac.sql`：建表 + 种子（ADMIN/USER/REVIEWER 三角色、约 20 个权限点、
  与现有 7 组菜单对应的菜单树）
- `user.role` 现有值迁移：`role=1` → ADMIN，`role=0` → USER，**迁移后该列保留但不再作为判定依据**
  （避免一次性删除引发兼容问题，R3-05b 切库时再移除）

---

## 3. Java 结构与类清单

```
common/
├── validation/  @Valid 配置、自定义校验器（手机号、密码强度）
├── enums/       UserStatus、TaskStatus、ReviewStatus、DemandCategory…
├── serialization/ Jackson 全局配置（时间格式、null 策略）
└── annotation/  @RequirePermission

system/
├── controller/{role,permission,menu,dict}/     新增 4 个子包
├── application/{role,permission,menu,dict}/    4 个应用服务
├── domain/{role,permission,menu,dict}/         实体
└── infrastructure/{role,permission,menu,dict}/ Mapper
```

| 类 | 职责 |
|---|---|
| `RoleService` / `RoleController` | 角色 CRUD、给用户分配角色 |
| `PermissionService`（改造） | 由「读 user.role」改为「查 user_role → role_permission」，带 60s 缓存 |
| `PermissionInterceptor` / `@RequirePermission` | 接口级校验；与现有 `@RequireRole` 并存过渡 |
| `MenuService` / `MenuController` | 菜单树、按角色过滤、授权 |
| `DictService` / `DictController` | 字典类型与项 |
| `AuthService`（抽自 `UserServiceImpl`） | 登录、退出、Token 失效、刷新 |

> **不建空包**：`organization`、`tenant`、`notification`、`job`、`log` 首期不创建。

---

## 4. API（`/api/v1/system/*`）

| 方法 | 路径 | 权限 | 优先级 |
|---|---|---|---|
| GET/POST/PUT/DELETE | `/roles`、`/roles/{id}` | `system:role:*` | P0 |
| POST/DELETE | `/users/{id}/roles` | `system:user:assign-role` | P0 |
| GET | `/permissions` | `system:permission:list` | P0 |
| GET | `/menus/tree` | 登录 | P0 |
| GET/POST/PUT | `/roles/{id}/menus` | `system:menu:assign` | P1 |
| GET/POST/PUT/DELETE | `/dicts`、`/dicts/{type}/items` | `system:dict:*` | P1 |
| POST | `/auth/logout`、`/auth/refresh` | 公开/登录 | P0 |

现有 `/api/v1/system/configs`、`/audit-logs` 保持不变。

---

## 5. 前端页面

| 页面 | 路由 | 依赖 |
|---|---|---|
| 角色管理 | `/system/roles` | 角色 CRUD + 菜单授权树 |
| 权限查看 | `/system/permissions` | 只读列表（按模块分组） |
| 菜单管理 | `/system/menus` | 树形编辑（P1） |
| 字典管理 | `/system/dicts` | 类型 + 项两级 |
| 用户管理（改造） | `/admin/users` | 增加「分配角色」 |

`config/menu.js` 改为从 `/api/v1/system/menus/tree` 拉取，不再硬编码 34 项。

---

## 6. 需要你拍板的 5 个决策

| # | 问题 | 我的建议 | 理由 |
|---|---|---|---|
| 1 | 用户模型：单管理员 vs 多用户 | **多用户 + 三角色**（ADMIN/USER/REVIEWER） | 现有代码已有注册与 role 字段，改造成本低；审核角色是 lead 模块的刚需 |
| 2 | 权限粒度 | **接口级 + 菜单级分离**，不做数据级 | 数据级（行权限）已有 `ContentAccessService` 承担，混在一起会乱 |
| 3 | 菜单存库 vs 前端硬编码 | **存库** | 你要"前端动态菜单"；且现在 34 项硬编码在 `menu.js`，加模块就要改代码 |
| 4 | Token 策略 | **单 Token 24h + 服务端黑名单**，不做 Refresh Token | 现有 `TokenStore` 是进程内 Map，多实例会失效——首期单实例可接受，但要写进已知限制 |
| 5 | 组织/租户 | **首期不做**（仅保留 `tenant_id` 列） | 现在就 2 个用户，组织树是纯负担 |

---

## 7. 明确不做及理由

| 项 | 理由 |
|---|---|
| Organization 组织管理 | 首期用户数 2，无组织结构需求；建了就是空表 |
| Tenant 租户管理 | 仅保留 `tenant_id` 列占位，不做隔离逻辑 |
| Notification 通知 | 无站内信/邮件需求；任务完成通知用前端轮询足够 |
| Job 定时任务管理 | 现有 `@Scheduled` 单实例够用；做管理界面等于重造 Quartz |
| Log 运行日志 | 容器 stdout + `docker logs` 已可查；做入库会把日志变成业务负担 |
| 数据权限（行级） | 已由 `ContentAccessService` 承担，不重复建设 |

---

## 8. 测试与验收

| 层 | 用例 | 数量 |
|---|---|---|
| 角色 | 建角色、分配角色、删除内置角色被拒 | 3 |
| 权限 | 无权限访问返回 403/40300；有权限放行；`user.role` 不再影响判定 | 3 |
| 菜单 | 按角色过滤树、无权限菜单不返回 | 2 |
| 字典 | 类型+项 CRUD、重复 item 被拒 | 2 |
| 认证 | 退出后 Token 失效、刷新 | 2 |
| 回归 | 现有 62 条必须全绿 | 62 |

**验收标准**

1. `mvn test` ≥ 74 条全绿（62 现有 + 12 新增）
2. `/api/v1/system/roles` 可增删改查，内置 ADMIN 不可删
3. 普通用户访问 `/api/v1/system/roles` 返回 403 + `40300`
4. 登录后 `/api/v1/system/menus/tree` 返回该角色可见菜单
5. 前端"角色管理"页面可用；菜单来自后端而非硬编码
6. 容器重建后全链路可用，`/api/health` 正常

---

## 9. 风险

| 风险 | 影响 | 控制 |
|---|---|---|
| 权限判定从 `user.role` 切到 RBAC | **判错会越权或全员看不到数据** | 保留 `user.role` 列做双读比对一周，再移除 |
| `TokenStore` 是进程内 Map | 重启后所有人被登出 | 已知限制，写入文档；不做 Refresh Token |
| 菜单存库后前后端菜单不一致 | 页面能进但菜单不显示 | 种子数据必须覆盖现有 34 项 |
| V13 与 V1 外键目标不同（`user` vs `sys_user`） | 切库时需重建 | 与 V9/V10 一致，已在切库清单中 |

---

## 10. 执行顺序（建议拆 3 个提交）

1. **R2-01a**：`V13` 建表 + 种子 + Role/Permission 后端（含 5 条测试）
2. **R2-01b**：PermissionService 改 RBAC + `@RequirePermission` + 拦截器（含 3 条测试）
3. **R2-01c**：Menu/Dict 后端 + 前端 4 个页面 + 菜单动态化

每步独立提交、独立打 tag，测试不全绿不进入下一步。
