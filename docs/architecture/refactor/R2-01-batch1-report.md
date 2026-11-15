# R2-01 · Batch 1 执行报告

日期：2026-11-15　范围：Common 基础能力（不含 Batch 2 起的用户/角色/权限/菜单）

## 1. Trace ID 切片验收（六项）

| 检查项 | 实现 | 证据 |
|---|---|---|
| 长度校验 | 8~64 字符，越界丢弃并重新生成 | `TraceIdFilter.sanitize` |
| 字符合法性 | 仅允许 `[A-Za-z0-9_-]`，防日志注入 | 同上 |
| 非法处理 | **丢弃并生成新的**，不拒绝请求 | 测试 `rejectsIllegalChars`（换行/空格/中文/引号） |
| MDC 清理 | `finally` 中 `MDC.remove` | 测试 `clearsMdcAfterRequest`、`clearsMdcOnException` |
| 正常/异常响应一致性 | 均由 `Result.ok/fail` 从 MDC 取 | 测试 `shapeIsConsistent` |
| 线程复用污染 | 连续请求 traceId 不同；请求后 MDC 为空 | 测试 `noCrossRequestPollution` |

字段兼容性：`Result` 仅**新增** `traceId`，原有 `code/message/data` 不变；
前端只读这三个字段，已确认无影响。

## 2. Jakarta Validation

**依赖**（BOM 管理，未指定版本）：

```
spring-boot-starter-validation:jar:4.1.2:compile
└─ hibernate-validator:jar:9.1.5.Final
   └─ jakarta.validation-api:jar:3.1.1
```

**落地**：`RegisterRequest`（手机号正则、密码 8-64、确认密码非空）、
`LoginRequest`（手机号/密码非空）、`UserController` 加 `@Valid`、
`GlobalExceptionHandler` 新增 `MethodArgumentNotValidException` 与
`ConstraintViolationException` 处理 → 400 / 40001 + 字段中文提示。

**运行时验证**：

| 场景 | 结果 |
|---|---|
| 手机号 `123` | 400 · `手机号格式不正确` |
| 密码 `123` | 400 · `密码长度为 8-64 位` |
| 登录手机号为空 | 400 · `手机号不能为空` |
| 正常登录 | 200 |

**行为变化**：登录时手机号为空，原来返回 401「账号或密码错误」，现在返回
400「手机号不能为空」。属改善（区分参数错误与凭证错误），已在 §6 记录。

## 3. 之前已完成的 5 项（逐项证据）

| 项 | 文件 | 提交 | 测试 |
|---|---|---|---|
| 统一响应结构 | `common/web/Result.java`、`PageResult.java` | `1aff0af` / `6f42d92` | 本次 `TraceIdTest` |
| 统一异常处理（9 类） | `common/web/GlobalExceptionHandler.java` | `1aff0af` + 本次补校验异常 | — |
| 分页规范 | `common/web/PageResult.java` | `6f42d92` | `UserAdminServiceTest` 分页 3 例 |
| 用户上下文 | `common/security/RequestContext.java` | `ebd9375` | `ContentAccessServiceTest` 8 例 |
| 安全基础 | `JwtUtil`/`JwtInterceptor`/`RequireRole` | `ebd9375` + `1aff0af` 编码修复 | `UserAuthFlowTest` 6 例 |

## 4. 测试结果（真实执行）

| 类型 | 命令 | 总数 | 通过 | 失败 | 跳过 |
|---|---|---:|---:|---:|---:|
| Java 测试 | `mvn -s ../deploy/docker/maven-settings.xml test` | **74** | 74 | 0 | 0 |
| 前端构建 | `npm run build` | — | ✓ | — | — |

（本轮之前 62 → 本次 74，新增 TraceId 4 + TraceIdFilter 8）

## 5. 未完成项（Batch 1 内）

| 项 | 原因 | 影响 |
|---|---|---|
| `common/enums` 抽取 | 业务枚举分散在 8 个模块，应先随模块迁移归位，单独抽会二次改 | 不阻塞 |
| 全局 Jackson 序列化规范 | 改全局配置会影响所有接口返回格式，需单独一轮回归 | 不阻塞 |
| 分页请求对象（`PageQuery`） | 现有各接口用 `page/size` 裸参数，统一需改 12 个 Controller | 不阻塞 |

## 6. 风险与兼容性

| 项 | 说明 |
|---|---|
| 登录参数校验更严格 | 空手机号由 401 变 400，前端错误提示分支需确认（`request.js` 已按 `message` 展示，无影响） |
| Trace ID 不拒绝非法输入 | 有意设计：观测手段不应让业务失败 |
| `MDC` 与 `RequestContext` 需同时清理 | 两者都基于 ThreadLocal/ThreadLocal-MDC，线程池场景都要注意 |

## 7. 结论

**B：核心能力完成，存在非阻塞遗留项。**

Batch 1 的响应、异常、校验、分页、Trace ID、上下文、安全七项均可用且有测试；
枚举抽取与全局序列化留待后续（已说明原因与影响）。
