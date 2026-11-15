# R3-02 · 批次报告：source 模块

日期：2026-11-15
范围：数据源定义、能力状态、关键词管理（新增模块，未改动现有功能）

## 1. 迁移策略：增量扩展，不是切库

新表 `ds_source` / `ds_account` / `ds_keyword` 通过 **V9 过渡迁移**引入，业务库其余 26 张表一行未动。

原因：`V1__init.sql`（37 表）虽然已验证，但当前 22 个实体仍指向旧表。若此时切库，
`video`/`comment`/`collection_*` 全线崩，测试与前端同时失效。

因此采用 **expand-then-contract**：

| 阶段 | 动作 |
|---|---|
| 本批次（expand） | 新增 `ds_*` 三表 + 种子；source 模块使用新表 |
| R3-03/04/05 | 逐步迁移其余模块 |
| R3-05 末尾（contract） | 归档 V1~V9，启用 `V1__init.sql`，删旧表 |

V9 与 V1 的差异（过渡期特有，切库后由 V1 重建）：

- 外键指向 `user` 而非 `sys_user`（后者尚未建）
- 不建 `sys_role` / `sys_user_role`
- 不改动旧 `keyword` 表——旧关键词功能继续走旧表，直到 R7 前端切换

## 2. 新增文件

| 文件 | 职责 |
|---|---|
| `db/migration/V9__source_module.sql` | 建 `ds_*` 三表 + 预置 DOUYIN |
| `source/domain/{DataSource,SourceAccount,SourceKeyword}.java` | 领域模型 |
| `source/infrastructure/persistence/{DataSource,SourceAccount,SourceKeyword}Mapper.java` | 持久层（注解 SQL） |
| `source/application/SourceQueryService.java` | 数据源列表 + 能力 JSON 解析 + 状态切换 |
| `source/application/KeywordService.java` | 关键词 CRUD、去重、归属校验 |
| `source/dto/{DataSourceView,KeywordView}.java` | 视图 |
| `source/controller/SourceController.java` | `/api/v1/source/*` 共 6 个端点 |
| `test/.../source/SourceModuleTest.java` | 9 例 |

修改：`CoolPilotInsightApplication` 的 `@MapperScan` 增加 source 包。

## 3. 关键设计落在代码里

**能力状态存库，不写死**

`ds_source.capability_json` 里 DOUYIN 记录为：

```json
{"contentDetail":true,"comments":true,"keywordSearch":false,
 "commentStatus":"AVAILABLE","keywordStatus":"PENDING"}
```

`keywordSearch: false` 是刻意的——关键词搜索从未跑通（R0-05 已记录），
若标成 true，前端会开放一个必然失败的入口。

**关键词去重键**

`UK(source_id, owner_user_id, normalized)`，`normalized` 做 lowercase + 去空格。
实测"Java AI" 与 " java ai " 被判为同一条，返回 409/40901。

**归属校验**

普通用户只能看/改自己的关键词；管理员可见全部。删除他人关键词返回 403/40300（实测 9 例覆盖）。

## 4. 接口（新增，不影响现有前端）

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/v1/source/sources` | 登录 |
| PUT | `/api/v1/source/sources/{code}/status` | 管理员 |
| GET | `/api/v1/source/keywords` | 登录（按归属过滤） |
| POST | `/api/v1/source/keywords` | 登录 |
| POST | `/api/v1/source/keywords/{id}/disable` | 所有者 |
| DELETE | `/api/v1/source/keywords/{id}` | 所有者 |

旧的 `/api/keyword` 控制器**保持不动**，现网关键词页面继续可用（实测 HTTP 200）。
两套并存是过渡态，R7 前端切换后删除旧实现。

## 5. 数据库变化

| 项 | 内容 |
|---|---|
| 新增表 | `ds_source`、`ds_account`、`ds_keyword` |
| 新增迁移 | `V9__source_module.sql`（业务库已执行，`flyway_schema_history` 记录 version=9 success=1） |
| 种子 | DOUYIN 数据源 1 行 |
| 既有表 | **未改动** |

## 6. 测试报告（真实执行）

```
Tests run: 3   CollectionTaskSchedulingTest
Tests run: 8   ContentAccessServiceTest
Tests run: 8   MapperCrudSemanticsTest
Tests run: 9   source.SourceModuleTest          ← 新增
Tests run: 7   system.SystemConfigTest
Tests run: 4   system.UserAdminServiceTest
Tests run: 6   UserAuthFlowTest
Tests run: 45  Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

新增 9 例：能力解析（含 keywordSearch=false 的断言）、未知平台 404、
重复关键词 409、不同用户可同名、列表按归属过滤、删他人关键词 403、
空/超长关键词、禁用状态、未登录 401。

## 7. 运行时验证

| 项 | 结果 |
|---|---|
| `V9` 迁移 | `version=9, success=1` |
| `GET /api/v1/source/sources` | DOUYIN，能力 `{comments:true, keywordSearch:false, keywordStatus:PENDING}` |
| `POST /api/v1/source/keywords` | 200，返回 id=1 |
| 重复提交（大小写/空格不同） | **409 / 40901 关键词已存在** |
| `GET /api/v1/source/keywords` | `DOUYIN / Java AI / ENABLED` |
| 旧 `GET /api/keyword` | 200（现网未受影响） |

## 8. 踩到的坑：Boot 4 缺 ObjectMapper

`SourceQueryService` 起初构造注入 `ObjectMapper`，启动即失败：

```
No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper' available
```

Spring Boot 4 把 Jackson 自动配置拆到独立模块（与 Flyway 同一类问题）。
本处只需解析一小段配置 JSON，改为服务内 `private static final ObjectMapper`，
不引入全局 bean，避免与其他自动配置冲突。

## 9. 遗留

| 项 | 时机 |
|---|---|
| `ds_account` 尚无接口与页面 | R7（平台账号管理） |
| 旧 `keyword` 表与旧控制器 | R7 前端切换后删除 |
| `KeywordService.markDiscovered` | R3-03 由 collection 的发现任务调用 |
| `SourceQueryService.updateStatus` 无审计 | R4 统一补审计 |
