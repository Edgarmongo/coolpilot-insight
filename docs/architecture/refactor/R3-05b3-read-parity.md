# R3-05b-3 · 内容读查询与一致性验证

日期：2026-11-15
范围：新增 `ContentQueryService`（读新表）+ 一致性测试，**未切换现有读路径**

## 1. 为什么先建读查询而不直接改 12 个 Service

切读路径的前提是"新表能查出与旧表**完全一致**的结果"。
如果先改调用方再验证，一旦字段映射有误，表现为页面少数据/串数据——
这种问题点几下界面看不出来，但用户会立刻发现。

所以顺序是：**先让新查询独立可用 → 证明结果一致 → 再替换调用方**。

## 2. 新增内容

| 文件 | 说明 |
|---|---|
| `content/application/ContentQueryService` | 列表、分页、按 id/外部 id 查找、评论分页与计数 |
| `content/infrastructure/persistence/*Mapper` | 补 `selectPage`、`countAll`、`selectPageByContent` |
| `test/.../content/ContentReadParityTest` | 5 条一致性测试 |

## 3. 一致性测试怎么做的

测试跑在 Testcontainers 独立库（空库），所以**在测试里造旧表数据**，
再执行与 V12 相同的迁移语句，最后逐字段比对新旧两侧。

覆盖：

1. 迁移后 id 与旧表一一对应
2. 每个内容的评论数与旧表一致
3. 评论正文/点赞数逐条一致（用 `(cid, 文本, 点赞)` 三元组比对，避免排序差异误判）
4. 内容标题一致
5. 分页参数边界归一

## 4. 真实业务库核对（与测试库分开验证）

测试证明的是逻辑，真实数据的迁移结果必须单独核：

| 检查项 | 结果 |
|---|---|
| `video` 行数 | 3 |
| `ct_content` 行数 | 3 |
| 评论数不一致的内容 | **0** |
| 评论字段不一致条数 | **0** |

## 5. 测试报告

```
Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

新增 5 条，累计 62 条。

> 过程中测试失败过 3 次，都是测试自身的问题，未放宽任何断言：
> 1. `DELETE FROM video` 触发 FK（`collection_task_item` 引用）→ 改为按 id 清理
> 2. 迁移语句未限定范围，把其他用例的视频也插进 `ct_content` → 加 `WHERE id IN`
> 3. 一致性遍历了全部 `ct_content`，包含其他用例造的数据 → 限定到本用例 id

## 6. 当前读路径状态

| 读什么 | 走哪张表 | 服务 |
|---|---|---|
| 内容列表/统计 | 旧 `video` | `VideoService` |
| 评论列表/楼中楼 | 旧 `comment` | `CommentServiceImpl` |
| — | 新 `ct_*` | **`ContentQueryService`（已可用、已测）** |

两套并存，互不干扰。下一步是把上表前两行改为走 `ContentQueryService`，
即真正的读路径切换。

## 7. 切换时的注意点

1. `ContentQueryService.listComments` 的 `contentId` 就是旧 `videoId`（V12 已对齐）
2. 楼中楼查询依赖 `parent_external_id`，旧代码用 `parent_cid`，注意空值语义
3. 旧 `comment` 表有 `root_cid`（顶层评论 id），新模型没有——若楼中楼按 root 聚合，
   需要确认用 `parent_external_id` 能否表达，否则要在 `ct_comment` 补列
4. 切换后先跑 `mvn test`，再验证 `/api/comment/list?videoId=1` 返回 19 条

> 第 3 点已核实（见 §8）：`root_cid` 只被查询带出、业务逻辑从未读取，不构成阻塞。

## 8. 已核实：`root_cid` 不影响切换

上一节把「旧表有 `root_cid`、新表没有」列为潜在阻塞点。核实结果：

| 检查 | 结果 |
|---|---|
| `CommentMapper.xml` 中的 `root_cid` | 仅出现在 `resultMap`、`Base_Column_List`、insert/update 的 if 分支，**无任何 where/group/order 使用** |
| `CommentMapper.java:93` | 自定义 SQL 里 `c.root_cid AS rootCid` 只是带出字段 |
| `CommentServiceImpl` 楼中楼组装（110–135 行） | 只用 `cid` 建索引、用 `parentCid` 挂父子；`rootCid` **零引用** |

结论：`rootCid` 是历史遗留的冗余字段，切换时可以：

- 查询里把 `root_cid AS rootCid` 删除，或固定返回 `null`
- 前端如果读了该字段（需单独确认），保持返回 `null` 即可

**不需要给 `ct_comment` 补列。** 这消除了切换路径上的一个不确定项。
