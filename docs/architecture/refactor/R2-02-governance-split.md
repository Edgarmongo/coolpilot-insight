# R2-02 · 批次报告：拆分 GovernanceService

日期：2026-11-15　commit：见 `git log --oneline -1`
目标：切除 `content ↔ system` 的潜在循环依赖（R1-03 §6 标注的唯一必然跨模块点）

## 1. 拆分结果

原 `service/GovernanceService`（约 190 行）同时承担 4 类职责，现拆为 4 个服务：

| 新类 | 模块 | 职责 | 说明 |
|---|---|---|---|
| `system.application.PermissionService` | system | `isAdmin` / `currentUserId` / `requireLogin` / `requireAdmin` | 只回答"我是谁" |
| `system.application.AuditService` | system | `audit(...)` + 客户端 IP 解析 | 旁路，失败不影响主流程 |
| `system.application.ConfigService` | system | `get` / `getInt` / `getDecimal`（60s 缓存） | 从原类摘出配置缓存 |
| `content.application.ContentAccessService` | content | `canRead` / `requireRead` / `readableContentIds` | 内容可见性规则 |

**依赖方向**：`content → system`（单向），符合 R1-03 矩阵。原类被删除。

## 2. 方法映射

| 原调用 | 新调用 |
|---|---|
| `governance.isAdmin()` / `currentUserId()` | `permission.*` |
| `governance.canReadComments(id)` | `contentAccess.canRead(id)` |
| `governance.requireReadComments(id)` | `contentAccess.requireRead(id)` |
| `governance.readableVideoIds()` | `contentAccess.readableContentIds()` |
| `governance.audit(...)` | `auditService.audit(...)` |
| `governance.get/getInt/getDecimal(...)` | `configService.*` |

## 3. 修改文件清单（16 个）

| 文件 | 变更 |
|---|---|
| `service/GovernanceService.java` | **删除** |
| `system/application/PermissionService.java` | 新增 |
| `system/application/AuditService.java` | 新增 |
| `system/application/ConfigService.java` | 新增 |
| `content/application/ContentAccessService.java` | 新增 |
| `controller/AiController.java` | 依赖改为 auditService + configService |
| `controller/CollectController.java` | auditService |
| `service/AiAnalysisService.java` | configService + contentAccess + permission |
| `service/CompareService.java` | contentAccess |
| `service/DiscoveryService.java` | auditService + permission |
| `service/OverviewService.java` | contentAccess |
| `service/ReportService.java` | auditService + contentAccess + permission |
| `service/ReviewService.java` | auditService + contentAccess + permission |
| `service/VideoService.java` | contentAccess |
| `service/impl/AnalysisServiceImpl.java` | contentAccess |
| `service/impl/CleanServiceImpl.java` | contentAccess + permission |
| `service/impl/CollectServiceImpl.java` | auditService + permission |
| `service/impl/CommentServiceImpl.java` | contentAccess + permission |
| `system/application/impl/UserServiceImpl.java` | auditService |
| `common/security/JwtInterceptor.java` | **缺陷修复**（见 §6） |
| `test/.../content/ContentAccessServiceTest.java` | 新增（8 例） |

## 4. 删除文件

- `backend/src/main/java/com/coolpilot/insight/service/GovernanceService.java`

## 5. 接口变化

**无。** 全部为后端内部重构，59 个 HTTP 端点的路径、参数、响应结构均未变。
唯一可见差异是 §6 修复的 401/403 响应体编码。

## 6. 顺带修复的缺陷：401/403 中文变 `????`

`JwtInterceptor` 原先四处直接 `response.getWriter().write("{...中文...}")`，
`getWriter()` 默认按 ISO-8859-1 编码，实测返回：

```
HTTP 403 {"code":403,"message":"????"}
```

修复方式：抽出 `writeJson(response, status, message)`，显式设置
`setCharacterEncoding("UTF-8")` + `Content-Type: application/json;charset=UTF-8`。

修复后实测：

```
HTTP 403 {"code":403,"message":"权限不足"}
HTTP 401 {"code":401,"message":"未登录"}
```

> 这个缺陷在本次拆分前就存在（原 `GovernanceService` 抛出的 `SecurityException` 走的是
> Spring 转换器，编码正常；只有拦截器分支受影响）。因为 R2-02 首次让"普通用户访问管理接口"
> 成为被验证的路径，才暴露出来。

## 7. 数据库变化

**无 DDL。** 新增测试数据仅存在于 Testcontainers 临时库。

## 8. 测试报告（真实执行）

```
Tests run: 3,  Failures: 0, Errors: 0   CollectionTaskSchedulingTest
Tests run: 8,  Failures: 0, Errors: 0   ContentAccessServiceTest   ← 新增
Tests run: 8,  Failures: 0, Errors: 0   MapperCrudSemanticsTest
Tests run: 4,  Failures: 0, Errors: 0   UserAdminServiceTest
Tests run: 6,  Failures: 0, Errors: 0   UserAuthFlowTest
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

新增用例覆盖（8 条）：

1. 管理员可读任意内容，且集合不受限（返回 null）
2. 本人采集过的内容可读
3. 被显式授权者可读
4. 无关用户不可读 + `requireRead` 抛 `SecurityException`
5. 未登录一律不可读
6. **授权过期后失效**
7. 有权限时 `requireRead` 不抛
8. 内容 id 为 null 的边界

> 首次运行有 4 条失败：测试用硬编码 userId 违反 `collection_task.owner_user_id` 外键。
> 已改为运行时注册真实用户，**未通过放宽断言规避**。

## 9. 运行时验证

| 项 | 结果 |
|---|---|
| 容器重建 + 启动 | healthy |
| 管理员登录 | 200，`audit_log` 62 → 63（审计仍生效） |
| 管理员访问 `/api/admin/users` | 200 |
| 普通用户访问同一接口 | **403 + 正确中文** |
| 未登录访问 | **401 + 正确中文** |

## 10. 已知接缝（记录，不在本批次处理）

`ContentAccessService` 仍直接使用 collection 侧的 `CollectionTaskMapper` / `CollectionTaskItemMapper`
判断"是否本人采集"。当前这些 Mapper 尚未归属模块，不构成模块级越界；
**R3 引入 collection 模块后**应改为调用 `collection.CollectionQueryService`。

## 11. 下一步

- R2-03：system 补角色表、配置管理接口、审计查询接口
- R2-04：统一 `BizException` 与错误码（本次修复的响应编码应纳入其覆盖范围）
