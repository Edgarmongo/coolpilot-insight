# R3-06 · 内容查询 API（读新表）

日期：2026-11-15　提交：见 git log

## 1. 做了什么

`ContentQueryService` 与一致性测试此前已完成，但**没有任何 HTTP 出口**——
能力存在却不可用。本批次补上控制器：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/content/contents` | 登录 | 内容分页（含总数） |
| GET | `/api/v1/content/contents/{id}` | 可见性校验 | 内容详情 |
| GET | `/api/v1/content/contents/{id}/comments` | 可见性校验 | 评论分页 |

详情与评论接口都先调用 `ContentAccessService.requireRead(id)`——
新接口不能成为绕过可见性判定的后门。

## 2. 与旧接口的关系

| 接口 | 读哪张表 | 状态 |
|---|---|---|
| `GET /api/comment/list` | 旧 `comment` | 在用（前端） |
| `GET /api/v1/content/contents/{id}/comments` | 新 `ct_comment` | **新增，已验证一致** |

两套并存。字段名刻意沿用前端既有约定（`cid` / `content` / `videoId` / `publishTime`），
前端切换时不需要改名。

## 3. 运行时验证（真实数据）

```
GET /api/v1/content/contents?page=1&size=5
→ total=3，返回 3 条（id 1/2/3，status=NORMALIZED）

GET /api/v1/content/contents/1/comments?page=1&size=2
→ total=19，返回 2 条
  cid=7703943264597279522  videoId=1  他们嘴硬就@来
  cid=7703943040482591525  videoId=1  哎 这种视频看不见 根本看不见
```

与旧接口 `GET /api/comment/list?videoId=1` 的 `total=19` 一致。

## 4. 一个数据质量问题（不是本次引入）

内容标题全部为空。核对后确认旧表 `video.title` 同样为 NULL：

```
旧表: 1[NULL] 2[NULL] 3[NULL]
新表: 1[NULL] 2[NULL] 3[NULL]
```

这是采集时未抓到标题的历史问题，迁移如实复制，未做美化。
**记录在案**：后续要么补抓标题，要么前端列表改用评论内容做摘要。

## 5. 测试

```
Tests run: 62, Failures: 0, Errors: 0
BUILD SUCCESS
```

本批次未新增 Java 测试——控制器为纯映射层，其依赖的查询与可见性逻辑
分别由 `ContentReadParityTest`（5 例）与 `ContentAccessServiceTest`（8 例）覆盖。
接口层用真实 HTTP 调用验证（见 §3）。

## 6. 下一步

前端切到 `/api/v1/content/*` 后，可删除旧 `CommentController` 的 list 实现。
真正的大块仍是**读写路径整体切换**（`VideoMapper` / `CommentMapper` 改指向新表），
涉及 `video` 表独有的计数列（`digg_count` 等，新模型存在 `metrics_json` 里），
需要一并改造读取这些计数的统计服务。
