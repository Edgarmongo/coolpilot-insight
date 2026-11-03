# 09 · JAVA_BACKEND_AUDIT

> Java 后端专项审计（Spring Boot 2.7.18 / JDK 8）

---

## 1. 分层结构

```
Controller (6)  →  Service 接口 (5)  →  ServiceImpl (5)  →  Mapper (3)
      ↓                                      ↓
   DTO (5)                              Entity (3)
                                        Utils (5)
```

**分层规范度**：✅ 清晰，无 Controller 直连 Mapper 的情况（除 `AdminController` 直接用 `userMapper`，属可接受简化）。

### 1.1 类清单

| 包 | 类 | 职责 |
|----|-----|------|
| 根 | `DyCommentApplication` | 入口；本地开发时拉起 Flask |
| config | `AppProperties` | 路径与地址配置（环境变量） |
| config | `GlobalExceptionHandler` | 全局异常处理（400/500 分离） |
| config | `SchemaMigration` | 启动时自动补列（幂等） |
| config | `OpenApiConfig` | Swagger 文档配置 |
| config | `WebMvcConfig` | CORS + 拦截器注册 |
| annotation | `RequireRole` | 角色鉴权注解（int role，默认 0） |
| interceptor | `JwtInterceptor` | Token 校验 + 角色判定 |
| interceptor | `RequestContext` | ThreadLocal 用户上下文 |
| utils | `JwtUtil` | JWT 签发/校验 |
| utils | `TokenStore` | 内存 Token 存储 |
| utils | `MemoryCache` | 内存 TTL 缓存（30min） |
| utils | `CollectUtils` | 抖音链接/ID 解析 |
| dto | `CollectRequest` `LoginRequest` `LoginResponse` `RegisterRequest` `Result` | 请求响应模型 |
| entity | `Comment` `AnalysisResult` `User` | 数据库实体 |
| mapper | `CommentMapper` `AnalysisResultMapper` `UserMapper` | MyBatis-Plus BaseMapper |
| service | 5 个接口 + 5 个实现 | 业务逻辑 |
| controller | 6 个 | REST 端点（26 个） |

---

## 2. 分层专项检查（任务卡第六阶段 12 项）

### 2.1 Controller 层

| 检查项 | 结论 | 等级 |
|--------|------|------|
| API 规范 | ✅ RESTful，统一 `/api/{模块}` 前缀，统一 `Result<T>` 包装 | A |
| 参数校验 | ⚠️ **手工校验**（Service 内 `if` 判断），无 `@Valid`/JSR-303 | C |
| 异常处理 | ✅ `GlobalExceptionHandler` 统一捕获 | A |
| 权限控制 | ✅ `@RequireRole` 注解 + 拦截器 | A |
| 事务 | ⚠️ Service 中**无 `@Transactional`**（当前逻辑为单表操作，风险低） | C |
| 日志 | ⚠️ Controller 层无日志，仅 Service/采集层有 | C |

### 2.2 参数校验现状

```java
// UserServiceImpl.register()
if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) throw new RuntimeException("手机号格式不正确");
if (password == null || password.length() < 6 || password.length() > 20) throw new RuntimeException("密码长度为6-20位");
```

**问题**：用 `RuntimeException` 表达业务校验失败 → 被 `GlobalExceptionHandler` 归为 400，但：
- 无 `@Valid` 声明式校验
- 校验规则与业务逻辑耦合在 Service
- `collectMode` 值域**未校验**（传任意字符串都当作 online 处理）

### 2.3 Service 层

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 业务逻辑位置 | ✅ 基本在 Service | A |
| 事务注解 | ❌ 无 | D |
| 缓存 | ✅ `MemoryCache`（30min） | A |
| 空值保护 | ⚠️ 部分（`getSummary` 等有 null 检查，`getTopComments` 无） | C |
| 用户数据隔离 | ✅ `applyUserFilter()` 统一处理 | A |

### 2.4 Mapper 层

| 检查项 | 结论 | 等级 |
|--------|------|------|
| ORM | ✅ MyBatis-Plus 3.5.5，零 XML，纯注解 | A |
| SQL 注入 | ✅ **全部参数化**，无字符串拼接（唯一拼接在 `SchemaMigration`，表名/字段名为硬编码常量） | A |
| 复杂查询 | ✅ 用 `LambdaQueryWrapper` 链式构造 | A |
| N+1 查询 | ✅ 未发现循环内查询 | A |

### 2.5 Entity / DTO

| 检查项 | 结论 | 等级 |
|--------|------|------|
| Entity 与表映射 | ✅ `@TableName` + `@TableId`，驼峰自动映射 | A |
| DTO 校验注解 | ❌ 无 | D |
| 响应脱敏 | ✅ `AdminController` 对 phone 掩码、password 置 null | A |
| `Result<T>` 泛型 | ✅ 统一包装 | A |

---

## 3. 关键专项分析

### 3.1 采集任务是否在 HTTP 请求中执行

**否，已异步化** ✅

```java
// CollectServiceImpl:34
new Thread(() -> { ... }).start();
```

HTTP 请求立即返回"采集任务已启动"。**但存在两个问题**：
1. 裸线程无池化、无上限（见 `04_TASK_CENTER_AUDIT.md`）
2. 无同任务去重（实测 3 次提交 → 3 个任务）

### 3.2 是否存在长时间阻塞

| 位置 | 阻塞时长 | 是否阻塞 Tomcat 线程 | 等级 |
|------|---------|-------------------|------|
| `CollectServiceImpl:52-57` `readLine()` | 1~3 分钟（整个采集） | ❌ 不阻塞（独立线程） | C |
| `AnalysisServiceImpl` `callTopicAgent` | 最长 30 秒 | ✅ **阻塞 Tomcat 工作线程** | C |
| `AnalysisServiceImpl` 各统计接口 | 数据库查询耗时 | ✅ 阻塞（正常） | — |
| `CleanServiceImpl` `runClean` | 清洗全程（同步） | ✅ **阻塞 Tomcat 工作线程** | C |

**`CleanServiceImpl.runClean()` 是同步的** —— 清洗 10 万条评论可能阻塞 30 秒以上。
虽然当前数据量小（14 条），但这是**随数据量线性增长的阻塞点**。

### 3.3 是否存在重复提交问题

**存在**。`startCollect` 无状态检查，实测 3 次提交全部执行（2 个失败）。

### 3.4 事务范围问题

当前无 `@Transactional`，各 Service 方法为单表操作，**暂无事务范围过大的问题**。
但以下场景需要事务（当前未使用）：

| 场景 | 需要的原子性 |
|------|------------|
| 清洗入库 | 批量 INSERT 应在一个事务内（当前是分段 commit） |
| 多表写入 | 用户 + 统计等 |

`clean_main.py` 的批量 INSERT 用了分段 `conn.commit()`（每 500 条），中途失败会留下部分数据。
**在 Python 侧而非 Java 侧**，但同样存在。

### 3.5 SQL 性能问题

| 查询 | SQL | 索引 | 评估 |
|------|-----|------|------|
| 按视频查评论 | `WHERE video_id=?` | `idx_video_id` | ✅ |
| 地区统计 | `WHERE video_id=?` GROUP BY region | `idx_video_region` | ✅ |
| 高赞排序 | `WHERE video_id=? ORDER BY likes DESC` | `idx_video_likes` | ✅ |
| 用户过滤 | `WHERE video_id=? AND user_id=?` | ⚠️ `user_id` 无索引 | **P2** |
| 视频列表 | `SELECT * FROM comment`（全表） | — | ⚠️ **全表扫描**，数据量大时慢 |
| 词云 | `WHERE video_id=? [AND user_id=?]` | `idx_video_id` | ✅ |

**两个隐患**：
1. `getVideoList()` 查全表 comment 再在内存去重 → 数据量到百万级会 OOM
2. `comment.user_id` 无索引

### 3.6 接口与前端强耦合

| 检查项 | 结论 |
|--------|------|
| DTO 是否被前端直接消费 | ⚠️ `Comment` 实体直接作为响应返回（`Result<Page<Comment>>`），实体即 DTO |
| 字段变更影响 | ⚠️ 改实体即改前端契约，无隔离 |
| 错误码 | ⚠️ 统一用 `code` 字段（200/400/500），无细分错误码 |

---

## 4. 配置管理（`application.yml`）

```yaml
spring:
  datasource:  # 全部环境变量化，带默认值
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:dy_comment}?...
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:root}
  servlet.multipart:      # 上传限制
    max-file-size: 20MB
    max-request-size: 100MB
server.port: ${SERVER_PORT:8080}
jwt:
  secret: ${JWT_SECRET:<REDACTED-JWT-SECRET>}    # ⚠️ 默认值是公开弱密钥
  expire: ${JWT_EXPIRE:86400000}
app:                     # 路径与地址，全部环境变量化
  project-root / python-exe / python-data-dir / python-clean-dir
  / datas-dir / flask-base-url / flask-autostart
```

**评价**：
- ✅ 容器化友好，所有可变项均可注入
- ⚠️ `JWT_SECRET` 默认值是硬编码弱密钥，**生产必须覆盖**
- ⚠️ 无 profile 区分（dev/prod），所有环境共用一份

---

## 5. 异常处理与日志

### 5.1 GlobalExceptionHandler

```java
@ExceptionHandler(MissingServletRequestParameterException.class)  // 400
@ExceptionHandler(RuntimeException.class)                          // 400 + WARN 日志
@ExceptionHandler(Exception.class)                               // 500 + ERROR 完整堆栈
```

**评价**：✅ 分级合理，500 会打印完整堆栈（利于排查）。

**遗留问题**：用 `RuntimeException` 表达业务异常，缺少自定义异常类型，
无法按异常类型做差异化处理。

### 5.2 日志现状

| 位置 | 级别 | 内容 |
|------|------|------|
| 采集输出 | INFO | `log.info("[Collect {}] {}", videoId, line)` 逐行转写 Python stdout |
| 业务异常 | WARN | 异常消息 |
| 系统异常 | ERROR | 完整堆栈 |
| SQL | STDOUT | MyBatis `StdOutImpl`（**生产环境应关闭**） |
| 采集任务 | ❌ | **无任务级日志、无开始/结束时间、无耗时** |

---

## 6. 权限控制

```java
// WebMvcConfig
.addPathPatterns("/api/**")
.excludePathPatterns("/api/user/register", "/api/user/login", "/api/analysis/summary");
```

**注意**：`/api/analysis/summary` 被排除鉴权 → **任何人可查任意视频的统计概览**。
（其余 analysis 接口需鉴权，故实际信息泄露有限，但口径不一致。）

**角色机制**：
```java
if (requireRole.role() > role) → 403
```
`@RequireRole` 默认 role=0（登录即可），`@RequireRole(role=1)` 为管理员。

**数据隔离**：非管理员查询自动加 `user_id` 过滤（`applyUserFilter`），
管理员不加过滤可见全部。

---

## 7. 后端问题清单

| 编号 | 问题 | 严重度 | 等级 |
|------|------|--------|------|
| BE-01 | 采集任务无去重，并发提交互相踩踏 | **P0** | A |
| BE-02 | 裸线程无池化、无并发上限 | P1 | A |
| BE-03 | 清洗接口同步执行，阻塞 Tomcat 线程 | P1 | A |
| BE-04 | AI 调用同步阻塞 Tomcat 线程 30 秒 | P1 | C |
| BE-05 | 任务状态内存存储，重启即丢 | P1 | A |
| BE-06 | `getVideoList()` 全表扫描 + 内存去重 | P1 | A |
| BE-07 | `JWT_SECRET` 默认弱密钥 | P1 | A |
| BE-08 | `comment.user_id` 无索引 | P2 | A |
| BE-09 | 实体直接作为响应返回（无 DTO 隔离） | P2 | A |
| BE-10 | 无参数声明式校验（@Valid） | P2 | C |
| BE-11 | `collectMode` 值域未校验 | P2 | A |
| BE-12 | 无 `@Transactional` | P2 | A |
| BE-13 | 无自定义异常类型 | P3 | A |
| BE-14 | MyBatis StdOutImpl 在生产会刷屏 | P3 | A |
| BE-15 | `/api/analysis/summary` 免鉴权，口径不一致 | P3 | A |
| BE-16 | 无 profile 区分，dev/prod 共用配置 | P3 | A |
| BE-17 | 无任何单元测试 | P2 | A |
| BE-18 | 无 OpenAPI 注解，Swagger 文档不完整 | P3 | A |
