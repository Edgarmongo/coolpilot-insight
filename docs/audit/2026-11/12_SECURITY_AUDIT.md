# 12 · SECURITY_AUDIT

> 系统安全专项审计
> 本轮为静态审计 + 只读验证，**未执行任何攻击性测试**

---

## 1. 认证机制

### 1.1 实现

| 组件 | 位置 | 说明 |
|------|------|------|
| Token 签发 | `JwtUtil.createToken()` | `auth0/java-jwt`，HMAC256，claims: userId/role/iat/exp |
| Token 校验 | `JwtInterceptor.preHandle()` | ① 校验 `TokenStore.exists(token)` ② `jwtUtil.getUserId(token)` 验签解 claims |
| Token 存储 | `TokenStore` | `ConcurrentHashMap` 内存存储，TTL 24h |
| 密码 | `BCryptPasswordEncoder` | 注册时 BCrypt；登录兼容历史 MD5 |
| 角色 | `user.role` | 0=普通用户，1=管理员 |

### 1.2 关键发现

#### ① JWT 签名有效但服务端状态才是最终依据

```java
// JwtInterceptor:44-50
if (!tokenStore.exists(token)) { → 401 "登录过期" }
Long userId = jwtUtil.getUserId(token);   // 此处才验签
```

**含义**：JWT 的签名不是唯一校验，**必须服务端 TokenStore 中存在**才有效。
这是**更安全的设计**（支持主动吊销），但带来副作用：

| 副作用 | 影响 |
|-------|------|
| TokenStore 是内存 | **容器重启 → 全部用户被强制登出** |
| 多实例部署 | 各实例 TokenStore 独立 → **登录在 A 实例，刷新到 B 实例即 401** |
| 无 Redis 共享 | 水平扩展需先解决 Token 共享 |

**等级**：C（当前单实例部署无影响，扩容前必须解决）

#### ② JWT_SECRET 默认值是公开弱密钥

```yaml
jwt:
  secret: ${JWT_SECRET:<REDACTED-JWT-SECRET>}
```

默认值写死在配置里，**任何拿到源码的人都知道**。
虽然 TokenStore 校验是主要防线，但若未来移除 TokenStore 或改为纯 JWT 校验，
该密钥将直接导致**任意伪造 Token**。

**建议**：生产环境必须设置 `JWT_SECRET` 环境变量为随机长字符串；或启动时检测到默认值则打印警告。

**等级**：P1

#### ③ 退出登录后 JWT 本身未失效

`logout()` 仅 `tokenStore.remove(token)`。JWT 在 24 小时内签名仍有效，
但因服务端已删除，无法通过 `exists()` 校验 → **实际不可用** ✅

**等级**：无（当前实现正确）

---

## 2. 权限控制

### 2.1 角色机制

```java
// JwtInterceptor:56-59
if (requireRole.role() > role) { → 403 "权限不足" }
```

| 注解 | 含义 |
|------|------|
| `@RequireRole` (默认 role=0) | 登录即可 |
| `@RequireRole(role=1)` | 仅管理员 |

### 2.2 数据隔离

```java
// AnalysisServiceImpl.applyUserFilter()
Long userId = RequestContext.getUserId();
Integer role = RequestContext.getRole();
if (userId != null && (role == null || role != 1)) {
    wrapper.eq(Comment::getUserId, userId);   // 非管理员仅见自己的
}
```

**评价**：✅ 隔离逻辑正确，管理员可见全部。

### 2.3 越权风险排查

| 检查点 | 结论 | 等级 |
|-------|------|------|
| 普通用户访问他人评论 | ✅ `applyUserFilter` 拦截 | A |
| 普通用户访问 admin 接口 | ✅ `@RequireRole(role=1)` | A |
| 普通用户访问他人采集任务状态 | ⚠️ `getStatus(videoId)` **无归属校验**——任何登录用户可查任意 videoId 的状态（仅状态字符串，无数据泄露） | C |
| 普通用户上传 CSV 后他人可见 | ⚠️ 上传文件落到**共享目录**，清洗后 `user_id` 由发起清洗的用户决定。若 A 上传、B 清洗，数据归属 B | C |
| 普通用户删除他人数据 | ❌ 无删除评论接口（仅管理员删用户） | A |
| **未登录访问 summary** | ⚠️ `/api/analysis/summary` 在 `excludePathPatterns` 中 → **任何人可查任意视频的统计概览** | **C** |

#### 关于 `/api/analysis/summary` 免鉴权

该接口返回 `totalComments/totalUsers/totalLikes/avgLikes`——
**不含评论原文、不含用户标识**。信息泄露程度低，但：
- 口径不一致：未登录看到全量，登录普通用户看到自己的
- 需登录才能查的 `/analysis/region` 反而泄露更多（地区分布）

**建议**：统一口径——要么 summary 也需鉴权，要么所有 analysis 接口都免鉴权（但那样用户隔离就失效了）。

**等级**：C

---

## 3. 敏感信息管理

### 3.1 检查清单

| 敏感项 | 位置 | 状态 | 等级 |
|-------|------|------|------|
| DeepSeek API Key | `docker-compose.yml:61` `${DEEPSEEK_API_KEY:-}` | ✅ 环境变量注入，不硬编码 | A |
| DeepSeek Key（本地） | `Py_Data/clean/.env` | ✅ `load_dotenv()` 读取 | A |
| DeepSeek Key（日志） | `wordcloud_service.py:34` | ✅ 仅打印"未设置"提示，不打印 Key | A |
| MySQL 密码 | `docker-compose.yml` `${DB_PASSWORD:-root}` | ⚠️ **默认 root** | C |
| JWT Secret | `application.yml:24` | ⚠️ **默认弱密钥** | P1 |
| 前端暴露密钥 | `dy-frontend/src/` | ✅ 无任何密钥 | A |
| `.env` 是否入库 | `.gitignore` | ✅ 已忽略 | A |
| Git 历史中的 `.env` | 提交 `777e6c3 Delete Py_Data/clean/.env` | ⚠️ 历史上曾存在过，已删除 | C |

### 3.2 前端敏感信息检查

```bash
grep -rn -i "password|secret|apikey|api_key" dy-frontend/src/
```

命中全部为**表单字段名**（`form.password`、`pwdForm.oldPassword` 等），**无任何硬编码凭据** ✅

### 3.3 响应体脱敏

| 接口 | 脱敏情况 | 等级 |
|------|---------|------|
| `/api/admin/users` | ✅ phone 掩码 `138****0000`、password 置 null | A |
| `/api/user/info` | ⚠️ 返回完整 `User` 实体，**含 password 字段**（BCrypt 哈希） | C |
| `/api/comment/list` | ✅ 无敏感字段 | A |

**`/api/user/info` 返回密码哈希**——BCrypt 哈希泄露不直接等于密码泄露，但不应返回。

**等级**：C

---

## 4. 注入类漏洞

### 4.1 SQL 注入

| 检查项 | 结论 | 等级 |
|-------|------|------|
| Java 侧字符串拼接 SQL | ✅ **无**。全部 MyBatis-Plus `LambdaQueryWrapper` 参数化 | A |
| Python 侧拼接 SQL | ✅ 全部 `%s` 占位符（`clean_main.py:90,99`） | A |
| `SchemaMigration` 的拼接 | ⚠️ `ALTER TABLE \`" + table + "\` ADD COLUMN " + columnDef` —— 但 `table`/`columnDef` **均为代码内硬编码常量**，非用户输入 | A（安全） |
| 动态排序字段 | ✅ 排序用 `orderByDesc(Comment::getLikes)` 等**方法引用**，非字符串拼接 | A |

**结论：SQL 注入风险极低** ✅

### 4.2 XSS

| 位置 | 渲染方式 | 风险 | 等级 |
|------|---------|------|------|
| 评论内容 | Vue `{{ }}` 插值 | ✅ 自动转义 | A |
| 高赞评论内容 | `{{ }}` | ✅ | A |
| 核心话题报告 | `<pre>{{ topicText }}</pre>` | ✅ 插值转义（非 `v-html`） | A |
| `region` / `nickname` | `{{ }}` / `prop` | ✅ | A |
| **`v-html` 使用** | ❌ **全项目无 `v-html`** | ✅ 无 XSS 面 | A |

**结论：XSS 风险低** ✅（无 `v-html`，全部走 Vue 插值转义）

### 4.3 路径遍历

| 接口 | 检查 | 等级 |
|------|------|------|
| `/api/clean/export` | 文件名来自请求体，但只做 `new File(DATAS_DIR, name)` 后 `exists()` 判断 + 读字节 → ⚠️ `new File(dir, "../x")` **可穿越目录**（读 `Datas` 外的文件） | **C** |
| `/api/collect/upload` | 上传文件名 `new File(dir, name)`，name 来自 `getOriginalFilename()` → ⚠️ **同样可穿越**（`../` 会被 new File 解析） | **C** |
| `/api/clean/run` | 文件名同样传给 Python 脚本 | ⚠️ 同源风险 |

**具体分析**：

```java
// CleanServiceImpl.exportFiles()
File f = new File(appProperties.getDatasDir(), fileNames.get(0));
if (!f.exists()) throw ...;
return Files.readAllBytes(f.toPath());
```

`new File("/app/Py_Data/Datas", "../../etc/passwd")` → 路径被规范化，
`exists()` 可能为 true → **可读取容器内任意文件**。

```java
// CollectController.upload()
File dest = new File(dir, name);      // name 来自前端
file.transferTo(dest);
```

若 `name = "../../etc/cron.d/x"` → **可写入容器内任意位置**（内容为用户上传的 CSV）。

**等级**：C → **建议升为 P1**（容器内虽影响面有限，但可读取 DB 密码配置、写入启动脚本）

**修复建议**：
```java
String base = new File(datasDir).getCanonicalPath();
File target = new File(base, name).getCanonicalPath();
if (!target.startsWith(base)) throw new RuntimeException("非法路径");
```

### 4.4 文件上传安全

| 检查项 | 结论 | 等级 |
|-------|------|------|
| 扩展名校验 | ❌ 后端不校验，仅前端 `accept=".csv"`（可绕过） | C |
| 内容校验 | ❌ 不解析 CSV 内容 | C |
| 大小限制 | ✅ `max-file-size: 20MB` / `max-request-size: 100MB` | A |
| Nginx 限制 | ✅ `client_max_body_size 50m` | A |
| 存储路径 | ⚠️ 落盘到共享 `Datas` 目录，**文件名可控** | C |
| 病毒扫描 | ❌ 无 | D |

**影响**：上传的 CSV 会被 `clean_main.py` 解析入库。若内容恶意构造（如超长字段、公式注入 `=cmd|...`），
可能影响导出环节（CSV 公式注入）。

---

## 5. 越权与数据访问

| 检查项 | 结论 | 等级 |
|-------|------|------|
| 用户数据隔离 | ✅ `applyUserFilter` | A |
| 管理员权限 | ✅ `@RequireRole(role=1)` | A |
| 管理员删除用户 | ✅ 但为**物理删除**，其名下 comment 变孤儿 | C |
| 管理员可删除自己 | ⚠️ 可，删后立即失去权限 | C |
| 评论数据删除接口 | ❌ 无（无法纠正误采数据） | D |
| 数据保留策略 | ❌ 无 | D |
| 数据导出权限 | ⚠️ `/clean/export` 可导出 `Datas` 全部文件（**含其他用户采集的数据**） | C |

**关于 `/clean/export`**：该接口列出 `Datas` 目录全部文件（`getPendingFiles` 不做用户过滤），
任何登录用户都能导出**其他用户**采集的原始 CSV（含他人昵称、抖音号、地区）。

**等级**：C → 建议 P1（**跨用户数据泄露**）

---

## 6. 第三方 API 调用安全

| 检查项 | 结论 | 等级 |
|-------|------|------|
| DeepSeek 通信 | ✅ HTTPS | A |
| 超时设置 | ✅ 30s | A |
| 异常处理 | ✅ 完整降级 | A |
| 重试 | ❌ 无 | D |
| 并发限制 | ❌ 无，N 个用户 = N 个并发 DeepSeek 调用 | C |
| 成本护栏 | ❌ 无 Token 统计、无预算上限 | C |
| 提示注入 | ⚠️ **评论原文若进入 Prompt，可包含指令注入**（当前 LLM 只看统计特征，**暂无此风险**；若未来改为传原文需处理） | B（未来风险） |
| 数据出境 | ⚠️ 评论统计特征会发送到 DeepSeek（第三方）。当前传的是聚合统计，**不传原文** | C |

---

## 7. 平台合规与个人信息边界

### 7.1 采集行为

| 检查项 | 结论 |
|-------|------|
| 是否绕过验证码 | ✅ **否**。未实现任何验证码破解 | A |
| 是否绕过访问控制 | ✅ 否 | A |
| 是否使用未授权接口 | ⚠️ 采集的是抖音公开前端接口，与浏览器正常访问行为一致，但**未获平台授权** | C |
| 是否使用代理池/IP 轮换 | ❌ 无（也**建议不引入**，规避风控不等于合规） | A |
| 采集频率 | ⚠️ 无速率限制，连续提交可触发大量请求（见 03 号文档实测） | C |
| 用户代理伪装 | ❌ 未伪装（使用默认 UA，**反而更合规**） | A |

**平台使用条款边界**：本项目为个人学习/内部分析用途。若用于商业化批量采集，
需评估抖音服务条款与《个人信息保护法》合规要求。

### 7.2 个人信息保护

| 检查项 | 结论 | 等级 |
|-------|------|------|
| 采集的个人信息 | 昵称、抖音号、UID、sec_uid、IP 归属地、内容 | — |
| 最小必要原则 | ⚠️ 采集了 5 类标识，其中 `sec_uid`（可定位到个人主页）**超出分析必需** | C |
| 用途限制 | ✅ 仅用于情感/话题分析，未外传原文 | A |
| 数据出境 | ⚠️ 聚合统计特征发往 DeepSeek（境外服务） | C |
| 保留期限 | ❌ 无策略 | D |
| 删除机制 | ⚠️ 仅管理员删用户（物理删），**评论数据无删除途径** | C |
| 脱敏展示 | ✅ 管理端 phone 掩码 | A |
| 前端展示个人信息 | ⚠️ 评论列表展示完整抖音号（用户可搜索到本人） | C |

**建议**：
1. `sec_uid` 改为可选采集（分析场景通常不需要定位到个人主页）
2. 增加评论数据的删除接口与保留策略
3. 评估评论统计特征出境的合规必要性

---

## 8. 采集频率控制

**当前无任何频率限制**：

| 层面 | 现状 | 等级 |
|------|------|------|
| 单用户提交频率 | ❌ 无限制，可连续提交 | D |
| 全局并发 | ❌ 无限制（裸线程） | D |
| 同视频去重 | ❌ 无 | D |
| 单 IP 请求速率 | ❌ 无 | D |

**后果（实测）**：3 次并发提交 → 3 个 Chrome 同时启动 → 2 个失败，1 个成功。
**风险**：既可能触发抖音风控，也可能对自身服务造成资源压力。

**建议**：任务表 + 每用户并发上限 1 + 全局并发上限 2 + 提交冷却时间。

---

## 9. 安全问题清单（按严重度）

| 编号 | 问题 | 严重度 | 等级 |
|------|------|--------|------|
| SEC-01 | 文件名路径穿越（上传与导出） | **P1** | C |
| SEC-02 | `/clean/export` 可导出其他用户的原始数据 | **P1** | C |
| SEC-03 | JWT_SECRET 硬编码默认值 | **P1** | A |
| SEC-04 | 无采集频率限制与并发上限 | **P1** | D |
| SEC-05 | MySQL 默认密码 root | P2 | A |
| SEC-06 | `/api/user/info` 返回密码哈希 | P2 | A |
| SEC-07 | 上传不校验扩展名与内容 | P2 | C |
| SEC-08 | TokenStore 内存 → 扩容即失效 | P2 | A |
| SEC-09 | 管理员可删除自己；用户物理删除留孤儿数据 | P2 | C |
| SEC-10 | 无评论删除接口、无数据保留策略 | P2 | D |
| SEC-11 | `/api/analysis/summary` 免鉴权，口径不一致 | P2 | A |
| SEC-12 | 采集状态查询无归属校验 | P3 | C |
| SEC-13 | CSV 公式注入（导出时） | P3 | D |
| SEC-14 | 无病毒扫描/内容校验 | P3 | D |
| SEC-15 | `sec_uid` 超出分析必需 | P3 | C |
| SEC-16 | 聚合统计特征出境未评估合规 | P3 | C |
| SEC-17 | Git 历史曾含 `.env` | P3 | C |
| SEC-18 | 无请求追踪 ID，跨服务日志难关联 | P3 | D |
