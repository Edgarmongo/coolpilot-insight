# R2-04 · 批次报告：统一业务异常与错误码

日期：2026-11-15
范围：新增 `ErrorCode` / `BizException`，替换 73 处异常抛出，统一 HTTP 状态

## 1. 解决的问题

改造前：

1. 业务错误用 `IllegalArgumentException` / `IllegalStateException` 表达，**无错误码**，前端只能靠文案判断；
2. 所有异常处理器返回 **HTTP 200**，仅响应体带 `code`——监控看不出错误率，网关无法按状态码告警；
3. `SecurityException` 一律映射为 403，导致"未登录"也返回 403，前端不会跳登录页。

## 2. 错误码设计（`common/exception/ErrorCode`）

| 码 | HTTP | 枚举 | 触发场景 |
|---|---|---|---|
| 40001 | 400 | `PARAM_INVALID` | 参数格式、长度、取值非法 |
| 40100 | 401 | `UNAUTHORIZED` | 未登录/无 Token |
| 40101 | 401 | `CREDENTIALS_INVALID` | 账号或密码错误（不区分，防枚举） |
| 40300 | 403 | `FORBIDDEN` | 已登录但无权 |
| 40400 | 404 | `NOT_FOUND` | 资源不存在 |
| 40900 | 409 | `STATE_CONFLICT` | 状态不允许该操作 |
| 40901 | 409 | `DUPLICATE` | 唯一约束冲突 |
| 42900 | 429 | `QUOTA_EXCEEDED` | Token 预算用尽 |
| 50001 | 500 | `CONFIG_MISSING` | 关键配置缺失 |
| 50200 | 502 | `UPSTREAM_ERROR` | AI/渲染服务失败 |
| 50000 | 500 | `INTERNAL` | 兜底 |

规则：前三位与 HTTP 类别对齐，后两位是场景序号。

## 3. 替换范围（73 处）

| 类型 | 数量 | 处理 |
|---|---:|---|
| `IllegalArgumentException` | 34 | → `BizException`（多为 40001） |
| `IllegalStateException` | 16 | → `BizException`（40900/40901/42900） |
| `SecurityException` | 14 | → `BizException`（40100/40300） |

涉及 13 个业务类。**逐条按文案语义映射**，不是机械替换。人工纠正了 7 处误分类：

| 原判 | 纠正为 | 原因 |
|---|---|---|
| 数据目录无法创建 / 保存文件失败 | `INTERNAL` | 服务端故障，不是客户端参数问题 |
| 搜索脚本执行失败 | `UPSTREAM_ERROR` | 依赖子进程失败 |
| 该视频还没有洞察结果 / 报告没有内容 | `STATE_CONFLICT` | 缺少前置数据，属状态问题 |
| 该视频还没有评论数据 | `STATE_CONFLICT` | 同上 |

## 4. HTTP 状态码生效

`GlobalExceptionHandler` 改为返回 `ResponseEntity`，状态码来自 `ErrorCode.httpStatus`。
`SecurityException` 兜底按文案区分 401/403（含"未登录"→401，其余→403）。

## 5. 前端同步调整（`frontend/src/api/request.js`）

原错误拦截器只认 401/403，其余一律显示"网络错误"。后端开始返回 400/404/409/429 后，
会把这些真实原因全部掩盖掉。改为：

- 优先展示 `error.response.data.message`
- 401 仍走登出跳转
- 5xx 提示"服务器开小差了"
- 无 message 时才回落"网络错误"

## 6. 文件清单

**新增**
- `common/exception/ErrorCode.java`
- `common/exception/BizException.java`

**修改（主代码 15 个）**
- `common/web/GlobalExceptionHandler.java`（重写：ResponseEntity + BizException）
- `controller/{Ai,Collect}Controller.java`
- `service/{AiAnalysis,Compare,Discovery,Report,Review}Service.java`
- `service/impl/{Analysis,Clean,Collect,Comment}ServiceImpl.java`
- `system/application/{Permission,ConfigAdmin}Service.java`
- `content/application/ContentAccessService.java`
- `service/impl/UserServiceImpl.java`（已迁移至 system）

**修改（测试 3 个）**
- `UserAuthFlowTest`：断言由异常类型改为 `BizException` + `ErrorCode`
- `SystemConfigTest`：同上
- `ContentAccessServiceTest`：`SecurityException` → `BizException(FORBIDDEN)`

**修改（前端 1 个）**
- `frontend/src/api/request.js`

## 7. 测试报告（真实执行）

```
Tests run: 3   CollectionTaskSchedulingTest
Tests run: 8   ContentAccessServiceTest
Tests run: 8   MapperCrudSemanticsTest
Tests run: 7   system.SystemConfigTest
Tests run: 4   system.UserAdminServiceTest
Tests run: 6   UserAuthFlowTest
Tests run: 36  Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

> 测试断言**从"异常类型"升级为"异常类型 + 错误码"**，是加严不是放宽。
> 期间发现一处断言映射错误（两次密码不一致被误判为 `CREDENTIALS_INVALID`），已改为 `PARAM_INVALID`。

## 8. 运行时验证（真实 HTTP 调用）

| 场景 | 结果 |
|---|---|
| 密码错误登录 | `HTTP 401` · `{"code":40101,"message":"账号或密码错误"}` |
| 手机号格式非法 | `HTTP 400` · `{"code":40001,"message":"手机号格式不正确"}` |
| 重复注册 | `HTTP 409` · `{"code":40901,"message":"该手机号已被注册"}` |
| 更新不存在的配置 | `HTTP 404` · `{"code":40400,"message":"配置项不存在: no.such.key"}` |
| 配置类型不匹配 | `HTTP 400` · `{"code":40001,"message":"配置值类型应为 INT，实际为: abc"}` |

正常流程回归：`/api/health`、`/api/overview`、`/api/admin/users`、
`/api/v1/system/configs`、`/api/v1/system/audit-logs` 全部 **200**；前端 200。

## 9. 对前端的兼容影响

| 影响 | 说明 |
|---|---|
| 错误响应状态码变化 | 原来错误也返回 200，现在返回真实 4xx/5xx。已同步修改 `request.js` |
| 响应体新增 `code` 语义 | 400 不再是"HTTP 失败"，前端按 `code` 判断更精确 |
| 401 行为变化 | 原来服务层"未登录"返回 403（前端显示"权限不足"），现在 401 会触发登出跳转——**这是修复** |
| 成功响应 | 未变（`code: 200`） |

## 10. R2 收尾状态

R2 四项全部完成：

| 任务 | 状态 | 回退点 |
|---|---|---|
| R2-01 根包迁移 | 完成 | `r2-01-root-package` |
| R2-02 governance 拆分 | 完成 | `r2-02-governance-split` |
| R2-03 配置与审计接口 | 完成 | `r2-03-system-config` |
| R2-04 统一异常与错误码 | 完成 | 本批次 |

测试基线：**36 例全绿**（R2 开始前 21 例）。

## 11. 遗留

- `traceId` 未落地（R1-06 要求贯穿 Java↔Python），随 R5 integration 一起做
- `sys_role` / `sys_user_role` 顺延 R3-01
- 少量 `IllegalArgumentException` 兜底处理器保留，供外部库调用路径使用
