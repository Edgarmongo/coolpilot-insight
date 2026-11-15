# R3-03a · 批次报告：外部调用收敛到 integration

日期：2026-11-15
范围：R3-03 的前半部分——把散落的子进程调用收进 `integration`，**未搬动 collection 模块**

## 1. 为什么分两半

R3-03 的目标是「采集任务与子进程解耦」，包含两件事：

1. 子进程调用抽到 `integration`（本次完成）
2. 任务状态机、调度、断点迁入 `collection` 模块（下次）

先做第 1 件：它是第 2 件的前置，也是 R0 审计点名的违规（"Service 直接用 ProcessBuilder"），
且改动面可控、可独立验证。

## 2. 改造前后

| 位置 | 改造前 | 改造后 |
|---|---|---|
| `CollectServiceImpl.invokeCollector` | 自建 `ProcessBuilder`，读取流、`waitFor()`，**无超时** | `collectionClient.collectComments(...)` |
| `DiscoveryService.invokeSearch` | 同上，**无超时** | `collectionClient.searchByKeyword(...)` |
| `CleanServiceImpl.run` | 同上，**无超时** | `aiClient.cleanFiles(...)` |
| `CoolPilotInsightApplication` | 启动类里 `new ProcessBuilder(python, "server.py")` | `AiServiceLauncher.startIfConfigured(...)` |

三处实现各有细微差异（有的吞异常、有的没有超时、环境变量设置不一致），
现在统一由 `ProcessRunner` 承担。

## 3. 新增文件

| 文件 | 职责 |
|---|---|
| `integration/common/ProcessRunner.java` | 起进程、捕获输出、**超时强杀**、异常翻译 |
| `integration/common/CommandResult.java` | `exitCode / output / durationMs` record + `tail(n)` |
| `integration/collection/CollectionClient.java` | 采集客户端接口 |
| `integration/collection/ProcessCollectionClient.java` | 子进程实现（采集 600s、搜索 300s 超时） |
| `integration/ai/AiClient.java` | AI/清洗客户端接口 |
| `integration/ai/ProcessAiClient.java` | 子进程实现（清洗 300s 超时） |
| `integration/ai/AiServiceLauncher.java` | 本地开发时拉起 Flask（从主类搬出） |
| `common/exception/IntegrationException.java` | 外部调用失败 → 502 |
| `test/.../integration/ProcessRunnerTest.java` | 5 例 |

## 4. 解决的具体缺陷

**没有超时**

原三处都直接 `process.waitFor()`，无上限。采集脚本卡死时，请求线程会永久挂住，
任务一直是 RUNNING，前端只能看到转圈。现在超时即 `destroyForcibly()` 并抛 502。

**输出读取与 waitFor 的顺序**

如果先读流再 `waitFor(timeout)`，进程不输出时 `readLine` 会永久阻塞，超时形同虚设。
实现改为**独立守护线程读流**，主线程只做 `waitFor(timeout)`。

**异常语义不一致**

失败时有的返回 `false`、有的抛 `IllegalStateException`、有的吞掉只打日志。
现在统一：非零退出码 → 返回 `CommandResult`（由业务判断）；启动失败/超时 → `IntegrationException`。

## 5. 当前外部调用出口

```
grep -rn "new ProcessBuilder" backend/src/main/java
→ integration/common/ProcessRunner.java     (唯一实现)
→ integration/ai/AiServiceLauncher.java     (本地开发拉 Flask)
```

业务 Service 中已**零 ProcessBuilder**。

## 6. 测试报告（真实执行）

```
Tests run: 3   CollectionTaskSchedulingTest
Tests run: 8   ContentAccessServiceTest
Tests run: 5   integration.ProcessRunnerTest    ← 新增
Tests run: 8   MapperCrudSemanticsTest
Tests run: 9   source.SourceModuleTest
Tests run: 7   system.SystemConfigTest
Tests run: 4   system.UserAdminServiceTest
Tests run: 6   UserAuthFlowTest
Tests run: 50  Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

新增 5 例：成功捕获输出、非零退出码不抛异常、命令不存在抛 502、**超时被强杀且 10 秒内返回**、`tail()` 截断。

## 7. 运行时验证

| 项 | 结果 |
|---|---|
| 容器重建 | 成功 |
| 启动健康 | healthy |
| `/api/health` | 200 |
| `/api/overview` | 200（业务数据 3 视频/19 评论） |
| `/api/clean/files` | 200 |
| `/api/v1/source/sources` | 200 |
| 容器内 `/opt/venv/bin/python` | 可用 |

## 8. 未验证的部分（必须说明）

**没有执行真实的采集与清洗**，原因：

- `collection-service/datas/` 为空（没有待清洗的 CSV）
- 采集需要真实 Chrome + 抖音页面，属外部依赖，不适合在验证中触发

因此「Java → 子进程 → Python 脚本 → 产出」这条链路本次只验证到
「客户端能被注入、应用能启动、ProcessRunner 行为正确」，
**端到端跑通留到 R3-04 采集服务化时用一个可控的 stub 脚本验证**。

## 9. 下一步

R3-03b：把任务状态机、调度、断点迁入 `collection` 模块
（`collect_task` → `col_task` 需新建表，与 R3-01 的 V1 对齐）。
这是本次重构里风险最高的一处，`CollectServiceImpl` 仍有约 500 行混合逻辑。
