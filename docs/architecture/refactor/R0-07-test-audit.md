# R0-07 · 测试审计

执行时间：2026-11-15 18:00:12（GMT+8）
执行命令：`cd backend && mvn -s ../deploy/docker/maven-settings.xml test`

## 1. 安全性预检（先判定再执行）

| 检查项 | 结论 | 依据 |
|---|---|---|
| 是否会清空业务库 | **否** | 测试用 Testcontainers 启动**独立** MySQL 容器，随机端口，不连 3307 |
| 是否会重建业务库 | **否** | 同上；Flyway 只作用于测试容器 |
| 是否删除数据卷 | **否** | 测试容器停止后由 Ryuk 回收，不触碰 named volume |
| 是否调用真实外部 API | **否** | AI 相关用例只测落库与鉴权，不发 DeepSeek 请求 |
| 是否发送真实通知 | **否** | 无通知组件 |
| 是否修改外部平台数据 | **否** | 测试不含采集调用 |

结论：**安全，可执行**。

## 2. 测试清单与执行结果

| 测试类 | 用例数 | 通过 | 失败 | 跳过 | 耗时 | 覆盖 |
|---|---:|---:|---:|---:|---:|---|
| `CollectionTaskSchedulingTest` | 3 | 3 | 0 | 0 | 18.00 s | 原子领取、非 PENDING 拒绝、`clearActiveFlag` 写 NULL |
| `MapperCrudSemanticsTest` | 8 | 8 | 0 | 0 | 0.58 s | 自增 id 回填、`updateById` 跳过 null、双唯一键幂等、指纹去重、CRUD |
| `system.UserAdminServiceTest` | 4 | 4 | 0 | 0 | 0.21 s | 分页脱敏、分页边界归一、删除幂等、删不存在 |
| `UserAuthFlowTest` | 6 | 6 | 0 | 0 | 0.47 s | 注册登录、BCrypt 落库、重复手机号、密码错误、哨兵账号 |
| **合计** | **21** | **21** | **0** | **0** | — | — |

Maven 输出：`Tests run: 21, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`
报告位置：`backend/target/surefire-reports/`

## 3. 测试基础设施

| 项 | 说明 |
|---|---|
| 基类 | `backend/src/test/java/.../AbstractIntegrationTest.java` |
| 数据库 | Testcontainers `mysql:8.0`，JVM 级共享（手动 start，不随测试类停止） |
| 迁移 | 容器启动后由 Flyway 执行 V1~V8，**同时验证了空库可完整建表** |
| 为什么不用 H2 | 迁移脚本含 utf8mb4、CHECK 约束、`ON UPDATE CURRENT_TIMESTAMP`、JSON 列，H2 不兼容 |

## 4. 未覆盖的范围（缺口）

| 未覆盖 | 原因 | 风险 |
|---|---|---|
| AI 分析链路（Flask 6 个路由） | 需 mock 或起 AI 容器，且会消耗 LLM 额度 | 重构 integration 后可能悄悄断链 |
| 采集链路（子进程 → CSV → 入库） | 依赖真实 Chrome 与抖音页面 | 采集重构无自动化保护 |
| 报告渲染（PDF/XLSX） | 依赖 Flask `/render/report` | 导出格式可能回归 |
| 关键词发现链路 | 后端功能本身未跑通 | 无基线可测 |
| 全部 59 个 HTTP 端点 | 仅登录/用户管理被覆盖 | 接口层改动靠人工回归 |
| Python 侧（采集/AI） | **0 个测试** | 完全无保护 |
| 前端 | **0 个测试** | 完全无保护 |
| Docker 启动测试 | 无自动化；本次为人工验证 | — |

## 5. 测试有效性评估

**有效的部分**：21 个用例集中在「最容易静默出错」的地方——
`updateById` 的 null 语义、`active_flag` 释放、评论双唯一键、鉴权哨兵密码。
这些正是 ORM 迁移与模块重构中最容易改坏又不会报错的点。

**薄弱的部分**：接口层几乎无覆盖，Python 与前端完全空白。
因此 R2 后续批次的验收不能只看 `mvn test` 全绿——它只能证明「已覆盖的部分没坏」。

## 6. 结论

- 现有测试**真实执行、全部通过、无跳过、无删减断言**
- 可安全作为 R2 各批次的回归网
- 但覆盖率低（59 个端点中约 12 个被间接覆盖），且 Python/前端为零，需在 R5/R7 补
