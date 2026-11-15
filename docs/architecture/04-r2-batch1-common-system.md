# R2 · 批次一：common + system

日期：2026-11-15　范围：`common`、`system`（并预置 `integration.config`）
原则：不兼容旧结构；移动文件的同时重构职责与依赖

## 1. 目标结构落地情况

```
com.coolskyai.coolpilot.insight
├── common/
│   ├── web/          Result · PageResult(新) · GlobalExceptionHandler · WebMvcConfig · OpenApiConfig · HealthController
│   ├── security/     JwtUtil · JwtInterceptor · RequestContext · RequireRole
│   └── util/         MemoryCache
├── system/
│   ├── controller/   UserController · AdminController
│   ├── application/  UserService · UserAdminService(新) · impl/UserServiceImpl
│   ├── domain/       User · AuditLog · SystemConfig
│   ├── dto/          LoginRequest · LoginResponse · RegisterRequest · UserView(新)
│   ├── config/       AdminBootstrap
│   └── infrastructure/persistence/  UserMapper · AuditLogMapper · SystemConfigMapper
└── integration/config/ AppProperties（外部服务配置，归 integration）
```

## 2. 修改文件清单（移动 + 改包）

| 旧位置 | 新位置 |
|---|---|
| `dto/Result` | `common/web/Result` |
| `config/GlobalExceptionHandler`、`WebMvcConfig`、`OpenApiConfig` | `common/web/` |
| `controller/HealthController` | `common/web/HealthController` |
| `utils/JwtUtil`、`interceptor/JwtInterceptor`、`interceptor/RequestContext`、`annotation/RequireRole` | `common/security/` |
| `utils/MemoryCache` | `common/util/MemoryCache` |
| `entity/User`、`AuditLog`、`SystemConfig` | `system/domain/` |
| `mapper/UserMapper`、`AuditLogMapper`、`SystemConfigMapper` | `system/infrastructure/persistence/` |
| `service/UserService`、`service/impl/UserServiceImpl` | `system/application/` |
| `controller/UserController`、`AdminController` | `system/controller/` |
| `config/AdminBootstrap` | `system/config/` |
| `dto/Login*`、`RegisterRequest` | `system/dto/` |
| `config/AppProperties` | `integration/config/` |

配套修改：
- `CoolPilotInsightApplication`：`@MapperScan` 增加 `system.infrastructure.persistence`
- `application.yml`：`type-aliases-package` 增加 `system.domain`
- `resources/mapper/{User,AuditLog,SystemConfig}Mapper.xml`：namespace 与 resultMap type 改为新 FQCN
- 全仓 100 个 Java 文件 + 3 个 XML 的 import/FQCN 重写

## 3. 新增文件

| 文件 | 职责 |
|---|---|
| `common/web/PageResult` | 统一分页响应，字段与前端既有约定一致 |
| `system/dto/UserView` | 用户管理视图，不含 passwordHash |
| `system/application/UserAdminService` | 从 Controller 下沉的分页、脱敏、删号并清理登录态 |
| `test/.../system/UserAdminServiceTest` | 4 条行为测试 |

## 4. 删除文件

无可删除类。原空的 `dto/`、`interceptor/`、`annotation/` 目录已随文件迁出而消失；
`utils/` 仅剩 `TokenStore`、`CollectUtils`（分别待迁 `common.security` 与 `collection`，不在本批次范围）。

## 5. 接口变化

| 接口 | 变化 |
|---|---|
| `GET /api/admin/users` | 返回类型 `Map<String,Object>` → `PageResult<UserView>`；**字段名不变**（records/total/current/size/pages）；`passwordHash` 字段从响应中**彻底移除**（原为 `null` 占位） |
| `DELETE /api/admin/user/{id}` | 用户不存在时返回「用户不存在」，原为「删除成功」（行为修正，不是回归） |

前端 `UserManageView` 只读取 `records` 与 `total`，**无需改动**。

## 6. 数据库变化

**本批次无 DDL 变更。** 表结构重建在 R3。

## 7. 测试报告（真实执行）

```
[INFO] Tests run: 3,  Failures: 0, Errors: 0  CollectionTaskSchedulingTest
[INFO] Tests run: 8,  Failures: 0, Errors: 0  MapperCrudSemanticsTest
[INFO] Tests run: 4,  Failures: 0, Errors: 0  system.UserAdminServiceTest   ← 新增
[INFO] Tests run: 6,  Failures: 0, Errors: 0  UserAuthFlowTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

运行方式：`cd backend && mvn -s ../deploy/docker/maven-settings.xml test`（Testcontainers 起真实 MySQL）

## 8. 运行时验证（容器内）

| 项 | 结果 |
|---|---|
| `docker compose build backend` | 成功 |
| 容器健康 | `healthy` |
| `/api/user/login` | 200 |
| `/api/admin/users?page=1&size=2` | `records/total/current/size/pages` 齐备；`phone=000****0001`；`passwordHash` 不存在 |

## 9. 本批次建立的两条硬约束

1. **Controller 不再接触 Mapper**：`AdminController` 的 2 处 Mapper 注入已消除（R0 审计中的违规点之一）。
   `AiController` 的 3 处仍在，随 analysis 批次处理。
2. **展示逻辑与领域对象分离**：不再「把实体字段置空后返回」，改用 `UserView`。

## 10. 下一批次

`source`（数据源与关键词）→ `content`（含 VideoGrant，已核实为内容读授权）。
`collection` 前需先定义 `integration` 的通信契约（Python 采集/AI 客户端），
以便 `collection.application` 只依赖接口而非 `ProcessBuilder`。
