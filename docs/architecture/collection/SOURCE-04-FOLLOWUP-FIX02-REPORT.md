# SOURCE-04-FOLLOWUP-FIX-02 实施报告 · P1 准入覆盖 + P2 错误信息脱敏

> 范围：任务 1（仅此一项）。任务 2（U1 A-API 设计）与任务 3（DOC-FIX）**未开始**。
> 基线：`3e9024f`
> 关联：`SOURCE-04-FOLLOWUP-REVIEW-02.md` §2.2（B1-1）、§2.4（B1-3）

---

## 一、P1 · 能力准入覆盖所有执行入口

### 1.1 根因

准入只做在 `POST /v1/jobs` 上，而 backend 内部还有一条直连采集器的路径：

```
过去（错）：                        现在（对）：
调用方 A → 准入 → HTTP 作业          调用方 A ┐
调用方 B → ─────────────────→ 子进程           ├→ 闸门 → 具体实现（HTTP / 子进程）
                                               ┘
```

调用方 B 就是 `HttpCollectionClient.searchByKeyword` → `ProcessCollectionClient` 子进程。
它完全不看 Effective：`KEYWORD_SEARCH` 明明是 `UNVERIFIED`、没有任何验证证据，
搜索仍然照跑。**同一个能力，两条路径，两套判定**——这是安全约束上的实质漏洞，不是日志问题。

### 1.2 方案：把闸门放在接口装饰器上

新增 `collection/application/AdmissionGatedCollectionClient`，实现 `CollectionClient`，
在**每个方法进入委托之前**调用同一个 `CollectionAdmissionGuard`：

| 方法 | 要求的能力 |
|---|---|
| `collectComments` | `COMMENT` |
| `searchByKeyword` | `KEYWORD_SEARCH` |

为什么选这个位置：

- `CollectionClient` 是**所有执行路径的必经接口**（HTTP 作业与子进程都实现了它）；
- 接口方法数是有限的，**新增方法必须在这里加一行准入**，漏不掉；
- 新增调用方自动被覆盖，不需要每个调用方记得自己判一次。

装配方式（`CollectionClientGateConfig`）：

```java
@Bean("rawCollectionClient")  CollectionClient   // integration 层：HTTP 或子进程
@Bean @Primary                CollectionClient   // 带闸门的装饰器
```

业务侧按类型注入拿到的是**带闸门**的实现；`integration` 层不反向依赖 `application` 层。

### 1.3 拒绝时的行为

`guard.requireExecutable` 抛 `BizException(STATE_CONFLICT)`，
**委托对象一次都不会被调用**——不起子进程、不发 HTTP、不产生任何平台请求。
测试用"记录调用次数的假实现"直接断言这一点（`delegate.searchCalls == 0`）。

### 1.4 对 KEYWORD_SEARCH 的影响（这是裁决要求的后果，不是副作用）

关键词搜索此前能跑，是因为它绕过了准入。闸门装上之后：

- `KEYWORD_SEARCH` 的 `implementationStatus = UNVERIFIED`、`evidence = None`（【运】实测）；
- 因此搜索请求会以 `NOT_EXECUTABLE` 被拒绝，**关键词发现功能暂时不可用**；
- **没有**为了让功能继续可用去伪造验证证据——按裁决保留 `UNVERIFIED` 状态。

前端/调用方看到的错误形如：
`当前不允许执行 KEYWORD_SEARCH：UNVERIFIED/VERIFICATION_REQUIRED —— 没有已接受的验证证据`。

`HttpCollectionClient.searchByKeyword` 里的子进程委派**保留但当前不可达**：
等该能力拿到证据后，把它换成真正的 `POST /v1/jobs(capabilityCode=KEYWORD_SEARCH)` 即可，
闸门逻辑不用改。类注释已同步更新，避免下次误以为它还在生效。

### 1.5 验证

| 类型 | 证据 |
|---|---|
| 【测】 | `AdmissionGatedCollectionClientTest` 4 条：搜索被拒→零调用；采集被拒→零调用；放行→各调用一次；拒绝按能力隔离 |
| 【测】 | 原有 `CollectionAdmissionTest` 6 条仍通过（submit 前置判定未被破坏） |
| 【运】 | 启动日志：`[collection] 采集执行已统一经过能力准入：实现=HttpCollectionClient` |
| 【运】 | `mvn test 343/343` |

> **未做的运行时验证**：端到端触发一次关键词发现、看它被拒绝。
> 原因：触发器是"运行关键词任务"，会写入任务/日志行。本轮禁止 DML，故只做代码级断言 + 装配日志。
> 这项建议在允许触发一次任务时补做。

---

## 二、P2 · 清洗失败时的异常原文泄露

### 2.1 传播链（复核确认）

```
clean.py  print(f"... {e}")            ← pymysql 消息含 cid / 字段值
  → stdout
  → ProcessRunner 捕获 → CommandResult.output()
  → CollectServiceImpl: error = "清洗入库失败：" + output
  → markItem → collection_task_item.error_message（TEXT，截 2000）
             → writeLog → collection_task_log.message（varchar 512，截 500）
  → 任务详情页 / 接口响应
```

### 2.2 三层修复

| 层 | 改动 | 说明 |
|---|---|---|
| Python 输出 | 新增 `ai-service/app/redact.py` 的 `error_summary()`；`clean.py` 三处异常打印改用它 | 只输出 `类名(错误码)`，**不含 message、不含 args 文本** |
| Java 摘要 | `CollectServiceImpl`：`error = "清洗入库失败（详见服务端清洗诊断日志）"` | 不再拼接第三方原文；错误码仍为 `COLLECT_FAILED`，**状态与重试语义不变** |
| 搜索错误 | `DiscoveryService.invokeSearch`：不再把脚本非结果行拼进 `BizException` | 改为 `"搜索脚本执行失败（exitCode=n，详情见服务端诊断日志）"`，行数/字符数只进 WARN 日志 |

`redact.py` 的规则很窄，是有意的：

```python
error_summary(FakeDbError(1062, "Duplicate entry '7703…' for key …"))  →  "FakeDbError(1062)"
error_summary(RuntimeError("绝望的大鼻嘎 … cid=7703…"))                →  "RuntimeError"
```

只放行两个结构化信息：异常类名、**整数型**错误码。其余一律丢弃。

### 2.3 关于"原始堆栈进受控诊断日志"

裁决允许原始堆栈进入受控诊断日志，但要求不含正文/CID/凭据。
**当前实现选择"不输出原始堆栈"**：没有受控诊断通道的情况下，
把它打到容器日志与打到 stdout 是同一种泄露。等有独立诊断通道（单独开关 + 单独保留期 + 脱敏）
再考虑，避免现在给自己开一个后门。

### 2.4 验证

| 类型 | 证据 |
|---|---|
| 【测】 | `ai-service/tests/test_redact.py` 4 条：含模拟 CID（`7703837650353177401`）、昵称（`绝望的大鼻嘎`）、正文片段的异常，输出三者皆不含；无错误码时只留类名；`None` 与空异常兜底 |
| 【测】 | Java 343/343（含闸门 4 条） |
| 【运】 | 回归脚本新增 `pytest (ai-service)` 项：4 passed |

> **未做的运行时验证**：造一次"清洗失败"看 `error_message` 实际落库内容。
> 需要构造不合法的 CSV 并触发清洗（写库 + 可能写评论），本轮禁止，故以单测 + 代码路径审查为准。

---

## 三、变更范围审计

| 文件 | 授权依据 |
|---|---|
| `collection/application/AdmissionGatedCollectionClient.java`（新增） | P1 |
| `collection/application/CollectionClientGateConfig.java`（新增） | P1 |
| `integration/collection/CollectionClientConfig.java`（bean 更名 + 日志措辞） | P1 |
| `integration/collection/HttpCollectionClient.java`（注释：委派当前不可达） | P1 |
| `service/DiscoveryService.java`（错误信息脱敏） | P2 |
| `collection/application/impl/CollectServiceImpl.java`（清洗失败文案） | P2 |
| `ai-service/app/redact.py`（新增）、`ai-service/app/clean.py`（三处打印） | P2 |
| `ai-service/tests/test_redact.py`（新增）、`collection/AdmissionGatedCollectionClientTest.java`（新增） | 测试 |
| `tools/regression_04_08.sh`（增加 ai-service 测试项） | 工具 |

**未触碰**：SOURCE-02 契约与枚举、`EffectiveCapability` 推导、Task/Run 状态机、Flyway/DB Schema、
`collector.py`、U1 相关（未回填、未加 MySQL 凭据）、U2、U4、SOURCE-05。
未删除任何 CSV / 评论 / 审计数据。

---

## 四、测试结果

| 项 | 结果 |
|---|---|
| Java 全量 | **343 / 343**（+4 闸门） |
| Python（collection） | 110 / 110 |
| Python（ai-service，新增） | 4 / 4 |
| 回归脚本 | **12 / 12**（新增 ai-service 一项） |
| 部署 | 后端已重建重启，启动日志确认闸门装配；未触发任何真实采集 |

---

## 五、遗留

| 项 | 说明 |
|---|---|
| 端到端拒绝验证 | 需触发一次关键词任务（写行），本轮禁止 DML，未做 |
| 原始堆栈诊断通道 | 未实现；当前选择"不输出堆栈" |
| `DiscoveryService` 其它 `e.getMessage()` | 任务失败日志仍有 `e.getMessage()`，来源是上游异常（含本次已脱敏的 BizException），未逐一审查 |
| U1 A-API 设计 | 任务 2，未开始 |
| DOC-FIX（A-1 metrics 表述 / A-2 混合格式约定 / C-4 文档一致性） | 任务 3，未开始 |
