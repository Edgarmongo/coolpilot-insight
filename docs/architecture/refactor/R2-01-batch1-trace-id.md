# R2-01 · Batch 1 切片：统一响应的 Trace ID 贯穿

日期：2026-11-15

## 1. 交付内容

| 文件 | 类型 | 说明 |
|---|---|---|
| `common/web/TraceIdFilter.java` | 新增 | 请求级 traceId：写 MDC、回写响应头、finally 清理 |
| `common/web/Result.java` | 修改 | 增加 `traceId` 字段，`ok()/fail()` 自动从 MDC 取 |
| `test/.../common/TraceIdTest.java` | 新增 | 4 例 |

## 2. 设计要点

**调用方零改动**：`traceId` 由 `Result.ok()/fail()` 内部从 MDC 取，
不需要给 26 个 Controller 的每个返回值加参数。

**必须 finally 清理**：Tomcat 线程会复用，不清理会把上一个请求的 traceId 带进下一个
（与 `RequestContext` 同类问题，已在 R2-02 遇到过一次）。

**客户端可透传**：请求头带 `X-Trace-Id` 时沿用（限 64 字符），否则生成 16 位随机串。
网关或前端可以先埋点，后端日志与之对齐。

## 3. 验证

| 项 | 结果 |
|---|---|
| 响应头 | `X-Trace-Id: adff1993ecd7451a` |
| 响应体 | `{"code":200,...,"traceId":"354c2b495f404cf6"}` |
| 客户端透传 | 请求头带 `my-trace-001` → 响应体 traceId 为 `my-trace-001` |
| `mvn test` | **66/66**（新增 4，原 62） |

## 4. 与任务卡的关系

任务卡 Batch 1 共 9 项，本次完成第 6 项（Trace ID）。已完成/未完成对照：

| Batch 1 项 | 状态 |
|---|---|
| 1. 工程基础检查 | ✅ 已确认（Boot 4.1.2 / JDK 21 / MyBatis 原生 / Flyway） |
| 2. 统一响应 | ✅ 已有（`Result`/`PageResult`），本次补 traceId |
| 3. 异常处理 | ✅ 已有（9 类异常 + 404/401/403 区分） |
| 4. 参数校验 | ◐ 手写校验，未引入 Jakarta Validation |
| 5. 分页 | ✅ 已有（`PageResult` + 上限 100/200） |
| 6. Trace ID | ✅ **本次完成** |
| 7. 用户上下文 | ✅ 已有（`RequestContext`） |
| 8. 安全基础 | ✅ 已有（JWT + 拦截器 + `@RequireRole`） |
| 9. 单元测试 | ◐ 本次补 4 条；校验/分页边界仍缺 |

## 5. 未做及原因

| 项 | 原因 |
|---|---|
| 引入 Jakarta Validation（`@Valid`） | 需新增 `spring-boot-starter-validation` 依赖；任务卡禁止擅自改依赖，需你确认 |
| `common/enums` 抽业务枚举 | 涉及 8 个模块的枚举归位，应随各模块迁移一起做 |
| 序列化统一（时间格式/null 策略） | 改全局 Jackson 配置会影响所有接口返回，需单独验证一轮 |
| Batch 2–6（角色/权限/菜单/字典/前端） | 工作量约 40 个接口 + 5 张表 + 4 个页面，需按任务卡分多次会话执行 |
