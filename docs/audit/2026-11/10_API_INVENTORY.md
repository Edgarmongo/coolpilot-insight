# 10 · API_INVENTORY

> 完整 API 清单（26 个端点）
> 取证方式：Controller 注解扫描 + 运行时接口验证

---

## 1. 总览

| 模块 | 端点数 | 鉴权 |
|------|-------|------|
| `/api/user` | 5 | 2 免鉴权 + 3 需登录 |
| `/api/collect` | 3 | 全部需登录 |
| `/api/clean` | 3 | 全部需登录 |
| `/api/comment` | 2 | 全部需登录 |
| `/api/analysis` | 11 | 10 需登录 + 1 免鉴权 |
| `/api/admin` | 2 | 需管理员 |
| **合计** | **26** | |

**统一响应格式**：
```json
{ "code": 200, "message": "success", "data": {...} }
```
`data` 在失败时为 `null`。

**鉴权方式**：`Authorization` 请求头携带 JWT token（**无 `Bearer` 前缀**，直接放裸 token）。

---

## 2. 用户模块 `/api/user`

| API | 方法 | 功能 | 参数 | 返回值 | 鉴权 | 实现状态 |
|-----|------|------|------|--------|------|---------|
| `/api/user/register` | POST | 注册 | body: `{phone, password, confirmPassword}` | `Result<String>` | ❌ 免 | ✅ 完整 · 手机号正则校验+查重+BCrypt |
| `/api/user/login` | POST | 登录 | body: `{phone, password}` | `Result<LoginResponse>`（token/userId/maskPhone/role） | ❌ 免 | ✅ 完整 · 兼容 MD5/BCrypt |
| `/api/user/info` | GET | 当前用户信息 | — | `Result<User>` | ✅ | ✅ 完整 |
| `/api/user/password` | PUT | 修改密码 | body: `{oldPassword, newPassword}` | `Result<String>` | ✅ | ✅ 完整 |
| `/api/user/logout` | POST | 登出 | — | `Result<String>` | ✅ | ⚠️ 仅移除内存 Token |

**已知问题**：
- `logout` 只删服务端 TokenStore，**JWT 本身未失效**（无黑名单），24 小时内该 token 仍可通过签名校验；但因 TokenStore 是服务端唯一校验源，实际已不可用 ✅
- 注册无频率限制、无验证码 → **可被批量注册**
- 密码强度仅长度 6~20，无复杂度要求

---

## 3. 采集模块 `/api/collect`

| API | 方法 | 功能 | 参数 | 返回值 | 鉴权 | 实现状态 |
|-----|------|------|------|--------|------|---------|
| `/api/collect/start` | POST | 启动评论采集 | body: `{videoId, maxComments, collectMode}` | `Result<String>` | ✅ | ⚠️ **部分** · 支持单视频；`videoId` 必须是 String（传数组 400）；无任务去重 |
| `/api/collect/status` | GET | 查询采集状态 | query: `videoId` | `Result<Map>`（videoId/status） | ✅ | ⚠️ **部分** · 内存态，重启即丢；无进度 |
| `/api/collect/upload` | POST | 上传 CSV（本机导入） | multipart: `files[]`, query: `videoId` | `Result<List<Map>>` | ✅ | ✅ 完整 · 落盘 Datas 目录 |

**`collectMode` 值域**（**未做服务端校验**，非法值静默按 online 处理）：

| 值 | 含义 | 实现 |
|----|------|------|
| `online` | 在线采集（匿名态，headless） | ✅ |
| `online-login` | 在线采集（复用 chrome-data 登录态） | ✅（但容器内无法手动登录，见 03 号文档） |
| `upload` | 本机导入 | ⚠️ **此模式下 `/start` 未特殊处理**，实际应走 `/upload` |

---

## 4. 清洗模块 `/api/clean`

| API | 方法 | 功能 | 参数 | 返回值 | 鉴权 | 实现状态 |
|-----|------|------|------|--------|------|---------|
| `/api/clean/files` | GET | 待清洗文件列表 | — | `Result<List<Map>>`（fileName/size） | ✅ | ✅ 完整 |
| `/api/clean/run` | POST | 执行清洗入库 | body: `["file1.csv", ...]` | `Result<Map>`（success/output） | ✅ | ⚠️ 完整但**同步阻塞** |
| `/api/clean/export` | POST | 导出原始数据 | body: 文件名数组 | 二进制流（CSV/ZIP） | ✅ | ✅ 完整 |

**已知问题**：
- `/clean/run` **同步执行**，清洗耗时随数据量线性增长，会占用 Tomcat 线程
- 无进度反馈、无结果持久化（只返回当次输出文本）
- `export` 单文件直接返回 CSV，多文件打包 ZIP ✅

---

## 5. 评论模块 `/api/comment`

| API | 方法 | 功能 | 参数 | 返回值 | 鉴权 | 实现状态 |
|-----|------|------|------|--------|------|---------|
| `/api/comment/list` | GET | 评论分页列表 | query: `videoId, page, size, region, sentiment, sort` | `Result<Page<Comment>>` | ✅ | ✅ 完整 · 支持地区/情感筛选、4 种排序 |
| `/api/comment/search` | GET | 评论内容搜索 | query: `keyword, videoId, page, size` | `Result<Page<Comment>>` | ✅ | ✅ 完整 · LIKE 模糊查询 |

**注意**：这是**已入库评论**的内容检索，与"关键词搜索视频"无关。

**sort 取值**：`time_desc`（默认）/ `time_asc` / `likes_desc` / `likes_asc`

---

## 6. 分析模块 `/api/analysis`

| API | 方法 | 功能 | 参数 | 返回值 | 鉴权 | 实现状态 |
|-----|------|------|------|--------|------|---------|
| `/api/analysis/summary` | GET | 数据概览 | query: `videoId` | `Result<Map>`（totalComments/totalUsers/totalLikes/avgLikes） | ❌ 免 | ✅ 完整 |
| `/api/analysis/region` | GET | 地区分布 | query: `videoId` | `Result<List<Map>>`（region/count/percentage） | ✅ | ✅ 完整 |
| `/api/analysis/sentiment` | GET | 情感分布 | query: `videoId` | `Result<List<Map>>`（sentiment/count/percentage） | ✅ | ✅ 完整 |
| `/api/analysis/trend` | GET | 时间趋势 | query: `videoId` | `Result<List<Map>>`（date/count） | ✅ | ✅ 完整 |
| `/api/analysis/top-comments` | GET | 高赞评论 Top20 | query: `videoId` | `Result<List<Map>>`（douyinId/nickname/content/likes/region/publishTime） | ✅ | ✅ 完整 |
| `/api/analysis/videos` | GET | 视频列表 | — | `Result<List<Map>>`（videoId/totalComments/analyzedAt） | ✅ | ⚠️ 全表扫描 |
| `/api/analysis/wordcloud` | GET | 词云数据 | query: `videoId` | `Result<List<Map>>`（name/value） | ✅ | ✅ 完整 · 经 Flask jieba |
| `/api/analysis/sankey` | GET | 桑基图数据 | query: `videoId` | `Result<Map>`（nodes/links） | ✅ | ✅ 完整 |
| `/api/analysis/topic` | GET | AI 核心话题报告 | query: `videoId` | `Result<String>`（Markdown） | ✅ | ⚠️ 完整但**同步阻塞 30s**，仅内存缓存 |
| `/api/analysis/refresh` | POST | 刷新分析缓存 | query: `videoId` | `Result<AnalysisResult>` | ✅ | ✅ 完整 · 写 analysis_result 表 |

**统一约定**：全部按 `videoId` 单视频查询，**无多视频联合分析接口**。

**缓存**：除 `videos`/`refresh` 外，其余均走 `MemoryCache`（30 分钟 TTL）。

---

## 7. 管理模块 `/api/admin`

| API | 方法 | 功能 | 参数 | 返回值 | 鉴权 | 实现状态 |
|-----|------|------|------|--------|------|---------|
| `/api/admin/users` | GET | 用户列表 | query: `page, size` | `Result<Page<User>>` | ✅ 管理员 | ✅ 完整 · phone 掩码、password 置 null |
| `/api/admin/delete/user/{id}` | DELETE | 删除用户 | path: `id` | `Result<String>` | ✅ 管理员 | ⚠️ **物理删除**，无软删除 |

> 注意实际路径为 `/api/admin/user/{id}`（`@DeleteMapping("/user/{id}")` + 类级 `/api/admin`）

**风险**：
- 管理员可删除**自己**，删后立即失去管理员权限
- 物理删除用户，其名下 comment 记录（`user_id`）成为孤儿数据

---

## 8. 缺失的 API（对照任务卡目标）

| 目标能力 | 对应 API | 状态 |
|---------|----------|------|
| 关键词搜索视频 | — | ❌ D |
| 视频发现列表 | — | ❌ D |
| 批量采集任务 | — | ❌ D |
| 任务列表/详情 | — | ❌ D |
| 任务暂停/取消/重试 | — | ❌ D |
| 任务日志查询 | — | ❌ D |
| 视频管理 CRUD | — | ❌ D |
| 关键词管理 | — | ❌ D |
| 评论单条情感重判 | — | ❌ D |
| 多视频对比分析 | — | ❌ D |
| AI 报告列表/导出 | — | ❌ D |
| 数据统计（全局） | — | ❌ D |
| 文件导入任务 | `/api/collect/upload` | ✅ 部分（仅落盘，不入库） |

---

## 9. API 规范性问题

| 问题 | 说明 | 等级 |
|------|------|------|
| 鉴权头无 `Bearer` 前缀 | `Authorization: <token>`，与常规不符 | C |
| 错误码仅 200/400/500 | 无细分业务错误码 | C |
| 无请求追踪 ID | Java→Flask→DeepSeek 三跳无统一 ID | C |
| 无 API 版本号 | 路径无 `/v1`，未来升级需破坏性变更 | C |
| 无 OpenAPI 注解 | swagger 只列出路径，无字段说明 | C |
| 分页参数不一致 | 评论用 `page/size`，分析用 `videoId` 单值 | 低 |
| 实体直接外泄 | `Comment` 含全部字段（未来加内部字段会泄露） | C |
