# R3-03b · 批次报告：采集任务域迁入 collection 模块

日期：2026-11-15
范围：任务域（实体/持久层/服务/控制器）迁入 `collection`，**表名未改**

## 1. 本次只搬代码，不动表

`collection_task*` 四张表改名为 `col_task*` 的动作留到 R3-05 与其余模块一起切换。
理由：现在改表名要新增 V10 并迁移数据，而 R3-05 本来就要整库重建——
一次改名会让中间态多一套映射，纯属给自己找麻烦。

因此本批次达成的是**代码层面的模块边界**，物理表名仍是旧的。

## 2. 搬迁清单（11 个类 + 1 个测试）

| 原位置 | 新位置 |
|---|---|
| `entity/CollectionTask` | `collection/domain/CollectionTask` |
| `entity/CollectionTaskItem` | `collection/domain/CollectionTaskItem` |
| `entity/CollectionTaskLog` | `collection/domain/CollectionTaskLog` |
| `entity/CollectionCheckpoint` | `collection/domain/CollectionCheckpoint` |
| `mapper/CollectionTaskMapper` | `collection/infrastructure/persistence/` |
| `mapper/CollectionTaskItemMapper` | 同上 |
| `mapper/CollectionTaskLogMapper` | 同上 |
| `mapper/CollectionCheckpointMapper` | 同上 |
| `service/CollectService` | `collection/application/CollectService` |
| `service/impl/CollectServiceImpl` | `collection/application/impl/` |
| `controller/CollectController` | `collection/controller/` |
| `test/CollectionTaskSchedulingTest` | `test/.../collection/` |

配套：4 个 Mapper XML 的 `namespace` 与 `resultMap type` 改 FQCN；
`@MapperScan` 增加 `collection.infrastructure.persistence`。

## 3. 搬迁中修掉的隐式依赖

同包假设被打破后暴露了三处问题，都是真实隐患：

| 问题 | 原因 | 修复 |
|---|---|---|
| 4 个 Mapper 找不到 `BaseCrudMapper` | 原本同包，无需 import | 补显式 import |
| `CollectServiceImpl` 找不到 4 个实体 | 用 `import ...entity.*` 通配，实体已不在该包 | 补显式 import |
| `CollectServiceImpl` 找不到 4 个 Mapper | 用 `import ...mapper.*` 通配 | 补显式 import |

> 教训：通配 import 会让"移动文件"看起来无害，直到编译器报错。
> 后续迁移 content/analysis 时应先检查 `import ...*` 的使用。

## 4. 现在的 collection 模块

```
collection/
├── controller/CollectController.java      12 个端点（/api/collect/*）
├── application/
│   ├── CollectService.java
│   └── impl/CollectServiceImpl.java       606 行
├── domain/CollectionTask / Item / Log / Checkpoint
└── infrastructure/persistence/            4 个 Mapper（注解 + XML）
```

**仍未拆分**：`CollectServiceImpl` 依旧混着三件事——
任务状态机、调度轮询、CSV 落盘与清洗触发。子进程调用已在 R3-03a 抽走，
但类本身还是太大。进一步拆分需要先确定「任务域」与「执行域」的边界，
属于 R3-05 之后的重构，不硬拆。

## 5. 测试报告（真实执行）

```
Tests run: 3   collection.CollectionTaskSchedulingTest   ← 已随模块迁移
Tests run: 8   ContentAccessServiceTest
Tests run: 5   integration.ProcessRunnerTest
Tests run: 8   MapperCrudSemanticsTest
Tests run: 9   source.SourceModuleTest
Tests run: 7   system.SystemConfigTest
Tests run: 4   system.UserAdminServiceTest
Tests run: 6   UserAuthFlowTest
Tests run: 50  Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 6. 运行时验证

| 项 | 结果 |
|---|---|
| 容器重建 | 成功，healthy |
| `/api/health` | 200 |
| `/api/collect/tasks?page=1&size=2` | 200，返回真实任务（id=4，FAILED，1 项） |
| `/api/overview` | 200 |

任务数据仍能正常读出，说明实体/Mapper 迁移后映射关系正确。

## 7. 当前模块进度

| 模块 | 状态 |
|---|---|
| `common` | ✅ 完成 |
| `system` | ✅ 完成 |
| `source` | ✅ 完成（新表） |
| `collection` | ◐ 代码边界完成，表名待切 |
| `content` | ❌ 未开始（最大一块） |
| `analysis` | ❌ 未开始 |
| `lead` / `conversion` | ❌ 全新领域 |
| `analytics` | ❌ 未开始 |
| `integration` | ◐ 契约与子进程客户端已就位 |

## 8. 下一步

R3-04（采集服务化）：给 `collection-service` 加 HTTP 接口 + 用 stub 适配器做端到端验证，
补上 R3-03a 明确留下的"未验证真实链路"缺口。
之后才是 R3-05（content + 切库）。
