# Spring Boot 4 + JDK 21 + 原生 MyBatis 迁移记录

日期：2026-11-15　分支：`feat/boot4-jdk21-mybatis`　commit：`22c665f`
基线：`main` @ `415027f`（Spring Boot 2.7.18 / JDK 8 / MyBatis-Plus 3.5.5）

## 1. 为什么要一次做完两件事

MyBatis-Plus 3.5.x 通过 `mybatis-spring 2.x` 依赖 **javax** 命名空间，而 Spring Boot 4 是
Jakarta EE 11 基线（Servlet 6.1）。Maven 上 MyBatis-Plus 最高只发布到
`mybatis-plus-spring-boot3-starter 3.5.18.1`，**没有 Boot 4 版本**。

因此「先升框架、再换 ORM」不可行：升到 Boot 4 的那一刻 MP 就会启动失败。两者必须同时替换。

## 2. 变更清单

| 项 | 变更前 | 变更后 | 说明 |
|---|---|---|---|
| Spring Boot | 2.7.18 | **4.1.2** | Spring Framework 7 |
| JDK | 1.8 | **21** | Boot 4 基线 17，推荐 21 |
| ORM | mybatis-plus-boot-starter 3.5.5 | **mybatis-spring-boot-starter 4.1.0** | 原生 MyBatis |
| 接口文档 | springdoc-openapi-ui 1.7.0 | **springdoc-openapi-starter-webmvc-ui 3.2.0** | Boot 3+ 的 groupId 变更 |
| JDBC 驱动 | mysql:mysql-connector-java 8.0.33 | **com.mysql:mysql-connector-j** | 版本由 Boot 管理 |
| 连接池 | Druid 1.2.20 | **HikariCP**（Boot 默认） | 见 §5 风险 |
| Servlet API | `javax.servlet` | `jakarta.servlet` | 4 处 import |
| 分页 | MP `Page` / `selectPage` | 手写 `LIMIT/OFFSET` | 返回结构保持兼容 |

## 3. ORM 迁移方式

现有调用量：26 个 Mapper、**135 处** BaseMapper 调用、**58 处** `LambdaQueryWrapper`、25 个实体。

### 3.1 保留方法名，缩小改动面

新增 `mapper/BaseCrudMapper<T>`，声明与原 MP 同名的方法：

```java
T selectById(Serializable id);
List<T> selectBatchIds(Collection<? extends Serializable> ids);
int insert(T entity);
int updateById(T entity);
int deleteById(Serializable id);
```

各 Mapper 由 `extends BaseMapper<T>` 改为 `extends BaseCrudMapper<T>`，
于是 **90 处既有调用（selectById/insert/updateById/deleteById/selectBatchIds）无需改动**。

### 3.2 CRUD 由生成器产出，不手抄

`tools/codegen/generate_mybatis_crud.py` 解析实体的 `@TableName` 与字段，
生成 25 个 `resources/mapper/*.xml`。实体上的 `@TableName` / `@TableId` 注解随之移除，
映射改由 XML `resultMap` 承担。

**语义严格对齐原 MP 行为**：

- `insert` / `updateById` 使用 `<if test="x != null">` **跳过 null 字段**（MP 默认 NOT_NULL 策略）
- `insert` 使用 `useGeneratedKeys="true" keyProperty="id"` 回填自增主键

### 3.3 条件查询改为具名方法

58 处 `LambdaQueryWrapper` 全部改写为 Mapper 接口上的具名方法，简单条件用 `@Select`，
带可选条件的用 `<script>` 动态 SQL。例如：

```java
@Select("<script>SELECT * FROM keyword"
        + "<if test='ownerUserId != null'> WHERE owner_user_id = #{ownerUserId}</if>"
        + " ORDER BY id DESC</script>")
List<Keyword> selectOrdered(@Param("ownerUserId") Long ownerUserId);
```

## 4. 必须留意的三处语义陷阱

**1. `updateById` 会跳过 null —— 这是隐性契约**

原代码里 `clearActiveFlag` 注释已写明：`setActiveFlag(null) + updateById` 不会写库，
必须用 `LambdaUpdateWrapper.set(...)`。改到 MyBatis 后同样不能靠 updateById，
已单列为显式 SQL：

```java
@Update("UPDATE collection_task_item SET active_flag = NULL WHERE id = #{id}")
int clearActiveFlag(@Param("id") Long id);
```

**2. 原子领取依赖条件更新**

`CollectServiceImpl.dispatch()` 用「`UPDATE ... WHERE status='PENDING'` 返回影响行数=1」
实现互斥领取。改写为 `claimPending(id, startedAt)`，条件写进 WHERE，语义不变。

**3. 分页返回结构不能变**

`AdminController` 原返回 MP 的 `Page<User>`，前端读 `data.records` / `data.total`。
改为手写分页后仍构造同名 Map 字段（`records/total/size/current/pages`），**前端零改动**。

## 5. Druid 为何被移除

`druid-spring-boot-3-starter 1.2.28`（Maven 上最高版本）在 Boot 4 启动即崩：

```
Caused by: java.lang.ClassNotFoundException:
  org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
```

Boot 4 把自动配置拆成模块，`DataSourceProperties` 已迁至独立 artifact，Druid 的 starter 引不到。
改用 Boot 原生 HikariCP。

**代价**：失去 Druid 的监控面板、SQL 防火墙与慢 SQL 统计。

### 5.1 已做的补偿

| 原 Druid 能力 | 替代方案 | 状态 |
|---|---|---|
| 连接池监控面板 | `spring-boot-starter-actuator` + Micrometer，`/actuator/metrics/hikaricp.connections.*` | 已接入 |
| 慢 SQL 查看 | p6spy（`P6SPY_ENABLED=true` 开启，默认关闭） | 已接入 |
| SQL 防火墙（WallFilter） | 未替代 —— 原项目从未启用，且会误杀动态 SQL | 不需要 |

**安全提醒**：`/actuator/health` 当前 `show-details: always`，会暴露数据库类型与磁盘信息。
若将来把服务暴露到公网，务必改为 `never`，或用 `management.server.port` + 回环绑定隔离。

## 6. 验证记录

| 项 | 结果 |
|---|---|
| `mvn compile` / `package` | 通过，产物 `coolpilot-insight-server-1.0.0.jar` 40 MB |
| 残留 `baomidou` / `javax` 引用 | 0 / 0 |
| 应用启动 | 成功（Boot 4.1.2 + JDK 21 + MySQL 8），无 Mapper 绑定异常 |
| 读接口回归 | 登录、总览、评论树、地区统计、AI 任务、报告、复核统计、用户分页 —— 均 200，数据与升级前一致（3 视频 / 19 评论 / 14 用户） |
| 写路径 | 新建报告返回自增 id（`useGeneratedKeys` 生效），删除成功 |
| 可观测性 | `/actuator/health` 返回 db UP；`/actuator/metrics/hikaricp.connections.active` 正常；p6spy 开启后输出带耗时 SQL |

**未覆盖**：定时采集调度、AI 分析调用（Flask 链路）、报告 PDF/XLSX 渲染、关键词发现、前端联调。
项目仍**无单元测试**，本次为人工接口回归。

### 6.1 容器部署验证

`docker/backend.Dockerfile` 基镜像 JDK 8 → 21 后，实际构建并运行整套容器：

| 项 | 结果 |
|---|---|
| `docker compose build backend` | 成功，耗时约 5 分钟，镜像 1.92 GB |
| 容器内 JDK | `openjdk 21.0.13 LTS (Temurin)` |
| 容器内 Chrome | `157.0.8103.78`（DrissionPage 依赖） |
| 容器内 Python | `3.10.12` |
| 四个容器状态 | mysql / flask / backend 均 healthy，frontend Up |
| `/api/health` | `{"status":"UP","db":"UP"}` |
| `/actuator/health` | db UP，磁盘信息正常 |
| 业务回归 | 登录 200；`/api/overview` 返回 3 视频 / 19 评论 / 14 用户，与升级前一致 |
| 前端 | 5173 HTTP 200 |

> 注意：容器内 Python 为 **3.10.12**（Ubuntu 22.04 自带），而非 README 早期写的 3.12。
> 采集脚本目前在 3.10 下运行正常，README 已同步更正。

## 7. 回滚方式

分支未合入 `main`，直接切回即可：

```bash
git checkout main
```

若已部署，需同时回退镜像到旧构建（JDK 8 版）。数据库结构未变更，**无需回滚数据**。

## 8. 遗留待办

- [ ] 补冒烟测试：评论入库幂等、AI 结果落库、报告导出（把本次人工回归固化）
- [x] 验证容器镜像构建与运行 —— 已实际 build 并启动整套容器（见 §6.1）
- [ ] 仓库缺 `.env`，`docker compose` 因 `JWT_SECRET` 必填而无法执行
- [x] 评估是否用 p6spy 补回 Druid 的慢 SQL 能力 —— 已接入 actuator + p6spy（见 §5.1）
