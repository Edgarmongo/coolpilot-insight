# R2-03 · 批次报告：system 配置管理与审计查询

日期：2026-11-15
范围：新增配置管理、审计查询两个接口 + 配置缓存失效机制

## 1. 与路线图的偏差（必须说明）

`R1-09` 中 R2-03 原计划「启用 `sys_role` / `sys_user_role` 表」。
**本批次未做**，原因：这两张表属于 R3-01 的 V1 重建范畴（当前库仍是旧 26 表结构，
`role` 还是 `user` 上的一个字段）。现在建 `sys_role` 会在 R3 重建时被丢弃，属于无效工作。

因此本批次只做**与 schema 无关**的部分：配置管理、审计查询、缓存失效。
角色表的落地顺延到 R3-01，随 V1 一起建，R3-03 再接 `PermissionService` 查库。

## 2. 新增接口（2 个，均仅管理员）

| 方法 | 路径 | 功能 |
|---|---|---|
| GET | `/api/v1/system/configs` | 配置列表，密钥类脱敏 |
| PUT | `/api/v1/system/configs/{key}` | 更新配置值（校验类型 + 写审计） |
| GET | `/api/v1/system/audit-logs` | 审计分页（可按 action / userId 过滤） |

> 新接口直接用 `/api/v1` 前缀（R1-06 规范），存量接口仍在 R4 统一迁移。
> 这样新接口不必在 R4 再改一次。

## 3. 新增/修改文件

| 文件 | 说明 |
|---|---|
| `system/controller/ConfigController.java` | 新增 |
| `system/controller/AuditLogController.java` | 新增 |
| `system/application/ConfigAdminService.java` | 新增：列表、更新、类型校验、审计、缓存失效 |
| `system/application/AuditQueryService.java` | 新增：分页查询 |
| `system/dto/ConfigItemView.java` | 新增：**密钥项 value 恒为 null，只返回 configured** |
| `system/dto/AuditLogView.java` | 新增 |
| `system/application/ConfigService.java` | 修改：新增 `invalidate()` |
| `system/infrastructure/persistence/SystemConfigMapper.java` | 修改：`selectByKey`、`updateValue` |
| `system/infrastructure/persistence/AuditLogMapper.java` | 修改：`selectPage`、`countFiltered` |
| `test/.../system/SystemConfigTest.java` | 新增 7 例 |

无删除文件。

## 4. 三条设计约束的落地

### 4.1 密钥永不回显

`system_config.is_secret=1` 的项，`ConfigItemView.value` 恒为 `null`，只给 `configured` 布尔。
**不是"打码成 \*\*\*\*\*"，而是根本不返回** —— 打码仍可能被前端日志记录。

### 4.2 更新后缓存必须失效

`ConfigService` 有 60 秒进程内缓存。若只写库不失效，用户改了配置要等一分钟才生效，
表现为"改了没用"且不报错。已在 `ConfigAdminService.update()` 中显式调用 `invalidate()`，
并有专门用例覆盖（`updateInvalidatesCache`）。

### 4.3 值类型校验

按 `value_type` 校验：`INT` / `DECIMAL` / `BOOLEAN` / `STRING`。
非法值抛 `IllegalArgumentException`（`abc` 不能写进 INT 配置）。

## 5. 接口变化

| 变化 | 影响 |
|---|---|
| 新增 3 个 `/api/v1/system/*` 端点 | 前端暂无页面调用，属于为 R6/R7 预备 |
| 存量接口 | **未改动**（59 个端点路径与响应不变） |

## 6. 数据库变化

**无 DDL。** 复用了现有 `system_config`、`audit_log` 两张表。

## 7. 测试报告（真实执行）

```
Tests run: 3   CollectionTaskSchedulingTest
Tests run: 8   ContentAccessServiceTest
Tests run: 8   MapperCrudSemanticsTest
Tests run: 7   system.SystemConfigTest        ← 新增
Tests run: 4   system.UserAdminServiceTest
Tests run: 6   UserAuthFlowTest
Tests run: 36  Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

新增 7 例：

1. 密钥配置不回显明文
2. **更新后缓存立即失效**
3. 值类型不匹配被拒绝
4. 更新不存在的键被拒绝
5. 空值被拒绝
6. 审计分页 + 按 action 过滤
7. 审计分页边界归一（page=0,size=0）

> 首次运行编译失败：`SystemConfigMapper` 缺 `Param` import（该文件此前只有 `Select`）。
> 补 import 后通过，**未通过删减用例规避**。

## 8. 运行时验证（容器内真实调用）

| 项 | 结果 |
|---|---|
| `GET /api/v1/system/configs` | 9 项，字段 `key/value/valueType/secret/configured/description/updatedAt` |
| `PUT /api/v1/system/configs/collect.default_max_comments` | 200，`updatedAt` 刷新为 18:19:48 |
| `GET /api/v1/system/audit-logs?action=CONFIG_UPDATE` | `total=1`，记录到 `CONFIG_UPDATE / SYSTEM_CONFIG` |
| 普通用户访问两个新接口 | **均 403 + "权限不足"**（中文编码正常） |

## 9. 遗留

| 项 | 处理时机 |
|---|---|
| `sys_role` / `sys_user_role` 落地 | R3-01（随 V1 建表）+ R3-03（PermissionService 查库） |
| 配置变更后的多实例同步 | 单实例无问题；多实例需改用发布订阅或缩短 TTL |
| 前端配置/审计页面 | R7-01 |
