# SOURCE-04-08 · 集成测试与回归

> 状态：**已执行**（一项明确未执行，见 §5）
> 复现：`bash tools/regression_04_08.sh`（一条命令，11 项检查）
> 关联：`SOURCE-04-TASKCARD.md` §SOURCE-04-08、`SOURCE-04-06-IMPLEMENTATION.md`、`SOURCE-04-07-IMPLEMENTATION.md`

---

## 一、结论

| 任务卡要求 | 结论 |
|---|---|
| 真实跨服务往返（Java → `cpi-collection` → 能力解析 → Effective） | ✅ 链路通，且快照 ID 与接口返回一致 |
| Java 全量回归 | ✅ 315 / 315 |
| Python 全量回归 | ✅ 76 / 76 |
| 生产链路不回归 | 🟡 **部分**：可导入 + 产出格式（BOM/13 列）实证；**真实采集未跑**（§5） |
| 部署回归（容器全部 healthy） | 🟡 四个带 healthcheck 的容器 healthy；frontend 无 healthcheck，只验"在跑" + 深链接 200 |
| 至少一条测试真的发出 HTTP | ✅ 两处：`SourceCapabilityApiTest`（→ 应用）、`CollectionSnapshotClientTest`（→ collection 容器） |
| 跨服务测试失败不得退化为跳过 | ✅ 容器不可达时测试**失败**而非 skip |
| 更新 `SOURCE-03-AUDIT.md` 遗留清单 | ✅ 03-01-F 表已按实际状态改写 |

---

## 二、一条命令复现

```bash
bash tools/regression_04_08.sh
# SKIP_JAVA=1 bash tools/regression_04_08.sh   # 只跑其余 9 项
```

实测输出（2026-11-17 00:52）：

```
四容器 healthy                          ok  total=4 unhealthy=0
前端容器运行中                          ok  cpi-frontend Up（无 healthcheck，不宣称 healthy）
Flyway 版本                             ok  V33
ds_source 已是 Desired v2               ok  schemaVersion=2.0
mvn test                                ok  Tests run: 315, Failures: 0, Errors: 0, Skipped: 0
pytest                                  ok  76 passed in 0.85s
backend → collection 快照               ok  http=200 snapshotId=sha256:f37f934b2fc44
能力接口未登录必须 401                  ok  http=401
前端深链接 /sources                     ok  http=200（Nginx fallback 生效）
采集链路可导入                          ok  SAVE_DIR=/app/collection-service/datas
CSV 产出格式（BOM + 13 列）             ok  BOM=yes COLS=13
通过 11 / 失败 0
```

脚本存在的理由：这些检查此前散落在手工命令里，换个人就复现不出来，
而"上次跑过、结果是好的"不是证据。脚本化后结论可重跑、可比对；
任一项失败会如实列出并返回非 0（`SKIP_JAVA=1` 会被记为**失败项**，
因为跳过的结果不能当回归证据）。

---

## 三、逐项证据

### 3.1 部署回归

- `cpi-mysql` / `cpi-flask` / `cpi-collection` / `cpi-backend` 均 `healthy`；
- `cpi-frontend` 无 healthcheck（Nginx 静态站），脚本只断言"容器在跑"，
  **不把它写成 healthy**——那是把"没做检查"说成"检查通过"；
- Flyway `MAX(version)=33` 且 `success=1`；
- `ds_source.capability_json` 的 `schemaVersion = 2.0`。

### 3.2 Java 全量

`mvn -s ../deploy/docker/maven-settings.xml -o test` → **315 / 315**。

其中与跨服务直接相关的两组：

| 测试 | 性质 |
|---|---|
| `CollectionSnapshotClientTest`（10） | 含一条**直连 `cpi-collection` 容器**的往返；不可达则失败，不 assume 跳过 |
| `SourceCapabilityApiTest`（7） | 真实 HTTP 打到应用：匿名 401、无权限 403、写 v1 → 400、写 v2 → 200、三态读取 |

### 3.3 Python 全量

`python -m pytest tests -q` → **76 / 76**（`test_registry` 23 + `test_server` 17 + `test_evidence` 16 + `test_snapshot` 20）。

> 运行前提：本机需装 `pytest`、`Flask`、`DrissionPage`。
> **缺 `DrissionPage` 会假性出现 12 条失败**（douyin adapter 导入失败 → 只注册了 STUB 的能力），
> 这不是测试坏了，是环境不全。本轮已补齐后复跑通过。

### 3.4 跨服务链路

```
cpi-backend → http://collection:5001/v1/registry/snapshot
  http=200  snapshotId=sha256:f37f934b2fc44...
```

同一个 `snapshotId` 也出现在能力接口返回里（`SOURCE-04-06-IMPLEMENTATION.md` §4.3），
说明 Effective 用的确实是**这一份**快照，而不是两次读取拼起来的。

### 3.5 生产链路

两条实证，替代"跑一次真实采集"：

1. **可导入**：容器内 `import comment, collector` 成功，`SAVE_DIR=/app/collection-service/datas`
   （共享挂载目录）。导入即会暴露语法/依赖断裂；
2. **产出格式**：用 `ensure_header()` 真写一次文件（写 `/tmp`，不碰生产目录），
   校验首字节是 BOM（`EF BB BF`）、表头 13 列。
   这是生产 CSV 的对外契约（`CSV_FIELDS`：昵称/抖音号/UID/SEC_UID/地区/日期/内容/点赞/CID/PARENT_CID/ROOT_CID/回复数/是否回复），
   变了就等于打穿了所有下游读 CSV 的脚本。

---

## 四、本轮发现的问题

### 4.1 `tools/check_schema_drift.py` 早已失效（假通过风险）

任务卡把"迁移前后跑 `check_schema_drift.py`"列为约束。实际执行发现：

- 脚本扫描 `com/coolskyai/coolpilot/insight/entity` —— **该包路径已不存在**
  （包名改为 `com.coolpilot.insight`，实体散在 `source/domain`、`system/domain`、`content/domain`）；
- 全仓库 **0 处** `@TableName`——持久层是注解 SQL + 普通 POJO，识别依据本身不成立。

即：即使把路径改对，它也会输出"实体数 0"然后打印 ✅ 一致——**这是假通过**。

处置：改为 `os.walk` 全量扫描 + 跳过 `static/final` 常量，并在"找不到实体"时
**显式失败（退出码 2）**，附说明"请勿把这条输出当作表结构一致的证据"。

> 本轮 V31~V33 是纯数据迁移，无 DDL，本就不存在结构漂移；
> 但把这个陷阱留在仓库里，下一个人一定会踩。
> 真正的漂移检查要按 mapper 的 SQL 反推列名，属于独立工具，未在本轮实现（已记入遗留）。

### 4.2 本机 Python 环境缺依赖会伪装成测试失败

见 §3.3 备注。已在报告里写清，避免下次把"环境不全"误判为"代码坏了"。

---

## 五、明确未执行的一项：真实采集演练

任务卡原文要求 `python comment.py <video> 20` 实跑，核对 CSV 表头 / BOM / 列名 / 增量去重。

**未执行**，理由如下（不是"来不及"）：

1. 会启动 Chrome，而 `ADR-007` 明确 backend 与 collection **共享 chrome-data 卷**、
   不得并发启动 Chrome。当前 `cpi-collection` 在线，跑演练必须先把采集服务停掉，
   这属于改变运行态的操作，不适合在自动化回归里静默进行；
2. 会向生产 CSV 目录写入真实数据（`SAVE_DIR=/app/collection-service/datas`），
   且结果受平台风控影响——可能返回 0 条，"0 条"无法区分"代码坏了"和"被风控拦了"，
   拿它当回归证据反而误导；
3. 本轮 `collection-service/` 与 `ai-service/` 的 diff 为**空**
   （`git diff --stat 15e46dc~1..HEAD -- collection-service/ ai-service/` 无输出），
   采集链路的代码路径没有任何变化。

> 但"没改"不等于"没坏"：镜像重建、依赖升级都可能影响它。
> 因此保留 §3.5 的两条实证（可导入 + 落盘格式），并把"真实采集演练"
> 列为**需人工在停采集服务的时间窗执行**的遗留项。

---

## 六、遗留

| 项 | 说明 |
|---|---|
| 真实采集演练 | 见 §5；建议在停 `cpi-collection` 的时间窗执行，并记录 cid 是否仍返回 |
| 结构漂移检查工具 | `check_schema_drift.py` 现只做到"不再假通过"，尚未实现按 mapper SQL 反推 |
| 前端视觉验证 | 04-07 遗留，`agent-browser` 在本机不可用 |
| `COMMENT_REPLY` 派生规则 | 04-06 §7.1 待裁决 |
| L1~L5 / OBS3 | SOURCE-04-03 历史发现，未关闭 |
