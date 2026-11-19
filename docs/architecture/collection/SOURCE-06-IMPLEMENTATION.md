# SOURCE-06 实施报告（第一阶段：采集服务侧）

> 状态：**Python 侧已完成并实测**；Java 侧**未切换**（仍走子进程），等 §6 的三点裁决
> 关联：`SOURCE-06-TASKCARD.md`、`ADR-007`、`SOURCE-04-04-EFFECTIVE-REASON.md`
> 本阶段**不改变任何生产行为**：backend 依旧起子进程，新链路只在服务端就绪

---

## 一、交付

| 文件 | 作用 |
|---|---|
| `app/jobs/dispatch.py` | 按 Registry 解析"由谁执行 + 现在能不能执行"，7 类拒绝理由 |
| `app/jobs/queue.py` | 单 worker 队列（Chrome 单实例）、幂等提交、尽力取消、过程日志 |
| `app/jobs/executor.py` | 能力 → 调用的显式适配表 + 结果整理 + 0 条语义判定 |
| `app/server.py` | `/v1/jobs` 重写、新增 `POST /v1/jobs/{id}/cancel`、状态增 `queuePosition/pages/logTail` |
| `tests/test_jobs.py` | 34 条：派发拒绝、并发、幂等、取消、执行体语义 |

Python 全量 **110 / 110**（原 76 + 34）。

---

## 二、派发：谁被允许执行

`dispatch.resolve()` 逐条短路，**不返回 Optional**——"没解析到"必须走异常，
调用方无法用 `if dispatch:` 把失败悄悄当成跳过。

| 顺序 | 条件 | 错误码 | HTTP |
|---|---|---|---|
| 1 | 接入不在 Registry | `CONNECTOR_NOT_FOUND` | 404 |
| 2 | `loadStatus != LOADED` | `ADAPTER_NOT_LOADED` | 409 |
| 3 | 非真实接入（`platformScoped=false`）且未设 `COLLECTION_ALLOW_STUB=1` | `STUB_NOT_ALLOWED` | 403 |
| 4 | 该接入下无此能力 | `CAPABILITY_NOT_REGISTERED` | 409 |
| 5 | `adapterFunction` 解析不到 | `FUNCTION_UNRESOLVED` | 409 |
| 6 | `accessMode` 不在声明内 | `ACCESS_MODE_UNSUPPORTED` | 409 |
| 7 | `implementationStatus != VERIFIED` 或 `evidence.accepted != true` | `NOT_EXECUTABLE` | 409 |

几点说明：

- **顺序是刻意的**：先判"这个接入是谁"（1~3），再判"这个能力"（4~7）。
  先判能力会让"STUB 没声明能力"报成 `CAPABILITY_NOT_REGISTERED`，
  排查方向直接指向错误的地方；
- **`STUB` 不再有任何默认通道**：替身没有声明任何能力，要它跑只能同时满足
  "显式指定 `STUB_PUBLIC`" + "环境变量打开"。实测两种组合的返回不同
  （403 vs 409），见 §4；
- **`IMPLEMENTED` 不算可执行**：它表示"本地测试通过"，与 `VERIFIED`（真实平台跑通）
  不是同一件事，与 SOURCE-04-03 的准入结论保持一致。

---

## 三、队列：为什么必须排队

Chrome 只有一个，backend 与 collection 共享 `chrome-data` 卷（ADR-007）。
两个作业同时启动浏览器，第二个会撞 profile 锁 —— 表现为"采集随机失败"。

- **单 worker 线程**，任一时刻 `RUNNING ≤ 1`（有测试锁定）；
- 排队期间状态保持 `ACCEPTED`，**不新增 QUEUED**——作业状态词表已被 Java 侧引用；
- `queuePosition` 让调用方能解释"为什么还没开始"；
- 幂等：同一 `jobId` 重复提交返回既有作业且**不重复入队**；
- 排队上限 100，超出返回 429，不让内存无限增长。

### 取消：为什么只做"尽力"

| 状态 | 行为 |
|---|---|
| 排队中 | 立即 `CANCELLED`，worker 取到直接跳过（测试断言 runner 未被调用） |
| 执行中 | 只置 `cancelRequested`，结束时**丢弃结果**并置 `CANCELLED` |
| 已结束 | 原样返回，**不把"已完成"改写成"已取消"** |

不杀 Chrome 的原因：强杀会留下 profile 锁，下一次启动直接失败。
"晚几秒停"比"下个作业起不来"便宜。

---

## 四、实测（真实容器，非 mock）

### 4.1 拒绝路径（`tools/verify_jobs_dispatch.sh`）

```
✅ 缺 connectorCode   http=400 code=PARAM_INVALID
✅ 未知接入           http=404 code=CONNECTOR_NOT_FOUND
✅ STUB 默认拒绝      http=403 code=STUB_NOT_ALLOWED
✅ 访问模式不支持     http=409 code=ACCESS_MODE_UNSUPPORTED
✅ 能力未注册         http=409 code=CAPABILITY_NOT_REGISTERED
GET /v1/jobs/nope → 404；/result → 404；POST /cancel → 404
```

### 4.2 真实采集一次（`tools/verify_job_e2e.sh`）

对 `7703721523349239951`（库中已有 19 条评论）跑 `maxItems=5`：

```
status = SUCCEEDED | collected = 0 | pages = 3 | reason = NO_RESPONSE | err = None

logTail:
  ( ‵▽′)ψ 浏览器启动成功
  ( ‵▽′)ψ 抖音页面已打开，请等待评论加载...
  视频ID: 7703721523349239951
  检测到历史数据：19 个 cid / 19 条指纹，本次将自动跳过重复
  (●ˇ∀ˇ●) 已滚动到评论区
  (  •̀ ω •́ )y 正在采集第 1 页 … 第 3 页（本页新增 0 条）
  w(ﾟДﾟ)w 未捕获到评论数据包，结束采集
  ( °▽°)╯ 浏览器已关闭
```

**这说明整条链路真的通了**：派发 → 启动浏览器 → 打开真实页面 → 连收 3 页数据包 →
按 cid 全部判重 → 结束。`collected=0` 是**正确的**：这 19 条之前就采过了。

> 未覆盖的：`items > 0` 的完整路径。要证明它需要一条**从未采过**的视频，
> 而现有库里的视频都已有 CSV。条目映射由单元测试覆盖（含 cid/指纹两种去重标记）。

---

## 五、实施中发现并修掉的两个真实缺陷

这两个都是"跑起来才看得见"的，不跑这一次不会暴露：

### 5.1 采集器的过程输出被吞掉 → 0 条无从排查

第一版把 `printer` 换成了空函数（"作业模式没有控制台"），结果 0 条时
作业里什么线索都没有，只有一句 `NO_DATA`。

修：`Job.logs` 保存过程输出，`GET /v1/jobs/{id}` 返回 `logTail`（最后 20 行）。
**这一步是定位到"19 个 cid 全被判重"的唯一原因**——没有它就会误判成"采集坏了"。

### 5.2 过程输出里的控制字符让状态接口返回非法 JSON

采集器日志带前导换行与 `\r`（滚动刷新），直接塞进 `jsonify` 会生成
含裸控制字符的 JSON，调用方 `json.load` 直接抛 `Invalid control character`。
实测踩到：状态接口 200 但内容无法解析。

修：`Job.log()` 统一净化（回车/换行折叠为空格、丢弃其余控制字符），
并加测试断言 `json.dumps(to_status())` 必定成功。

### 5.3 0 条的语义（顺带修正）

最初 0 条一律判 `PARTIAL / NO_DATA`，但实测场景是"收到 3 页数据、全部重复"——
那是**完成**，不是不完整。

修：判据用"是否真的收到过数据"（`outcome.pages > 0`），而不是猜文案：

| 情况 | 结论 |
|---|---|
| 收到过数据包，但全部判重 | `SUCCEEDED`，`collected=0` |
| 一个数据包都没收到 | `PARTIAL` + `NO_DATA`（可能被风控拦了） |
| 预检失败 / 客户端异常 | `FAILED` + 对应错误码 |

---

## 六、Java 侧为什么还没切（需要裁决）

切 Java 会**改变生产行为**，而下面三点会直接影响实现形状：

| # | 问题 | 我的建议 | 不裁决的后果 |
|---|---|---|---|
| D1 | 服务端是否重复判 `VERIFIED + evidence.accepted`（Java 已判过） | **要判**：Java 判的是"业务允许 + 快照新鲜"，服务端判的是"我这份 Registry 现在真能跑"，少一侧就会漏掉 Registry 漂移 | 已在 Python 侧按"要判"实现；若要推翻，删一个 `if` 即可，但准入会退化成单侧判定 |
| D2 | 取消只做"尽力"（不杀 Chrome） | **接受** | 已按此实现 |
| D3 | `ProcessCollectionClient` 保留多久 | 保留到验收通过后的下一个版本，默认 `http`，`process` 仅作回滚 | 决定我是否现在写 `HttpCollectionClient` 并把开关接上 |

D1、D2 已按建议实现（可一句话推翻）；**D3 决定 Java 改动范围**，未裁决前不动 Java。

---

## 七、遗留

| 项 | 说明 |
|---|---|
| Java `HttpCollectionClient` | 未实现（等 D3） |
| Java 提交前 Effective 准入 | 未实现（等 D3） |
| `items > 0` 的端到端 | 需要一条全新视频，见 §4.2 注 |
| `CONTENT_DETAIL` / `KEYWORD_SEARCH` 作业派发 | 已注册但未接适配，返回 `CAPABILITY_NOT_DISPATCHABLE`（明确拒绝，不是静默跳过） |
| 作业持久化 | 仍是进程内存，重启即丢；权威状态在 Java 侧 `col_task_item` |
| 多实例 | 单实例假设；多副本时队列会各跑一份，需要外部队列或选主 |

---

## 八、Java 侧切换（第二阶段，2026-11-17 追加）

按 D1/D2/D3 的建议实施，**默认已切到 http**。

| 文件 | 内容 |
|---|---|
| `integration/collection/HttpCollectionClient.java` | 提交作业 → 轮询 → 收敛；五类失败分别可辨 |
| `integration/collection/CollectionClientConfig.java` | `app.collection.executor=http|process` 选择实现，默认 `http` |
| `integration/collection/ProcessCollectionClient.java` | 去掉 `@Component`、标记 `@Deprecated`，仅作回滚通道 |
| `collection/application/CollectionAdmissionGuard.java` | 采集前的准入校验，拒绝时给出 `status/reason/detail` |
| `collection/application/impl/CollectServiceImpl.java` | `submit()` **第一条**语句做准入；建任务之前就拒绝 |
| `application.yml` | 新增 `app.collection.executor` / `app.collection.default-connector` |

### 8.1 为什么准入要放在 `submit()` 的第一条

不可执行时应该"什么都不发生"：不建任务、不插 item、不发 HTTP。
放在逐条判断里，会先建出一堆必然失败的任务再逐个报错——用户看到的是"提交成功"，
半小时后才发现全红。测试用"其余依赖全为 null"来锁定这个顺序：
守卫若不是第一条，测试会以 NPE 而不是业务异常失败。

### 8.2 关键词搜索为什么不一起切

`KEYWORD_SEARCH` 至今没有真实平台验证证据（`SOURCE-01` 记录"风控行为未知"），
接进作业派发只会被闸门判 `NOT_EXECUTABLE`。硬切等于把一个**未验证能力**
包装成"可用功能"。因此 `searchByKeyword` 显式委派给旧实现，
并在启动日志里写明这是已知不一致——等该能力拿到证据后并入。
注意 `run_search` 的 `printer` 接受 `**kwargs`（内部传 `file=sys.stderr`），
接入时不能直接传 `job.log`。

### 8.3 验证（真实容器）

```
启动日志： [collection] 采集执行走 HTTP 作业派发（executor=http, connector=DOUYIN_PUBLIC）
提交接口： POST /api/collect/start → taskId=6
跨服务：   172.21.0.2 - - "POST /v1/jobs HTTP/1.1" 202      ← backend 容器 IP
子进程：   后端日志中 ProcessRunner / python / comment.py 命中数 = 0
结果：     collection_task_item.status=SUCCESS, collected_count=20；该视频评论 19 → 20
```

Java 全量 **328 / 328**（新增 13：`HttpCollectionClientTest` 7 + `CollectionAdmissionTest` 6）。

### 8.4 一个尚未对上的现象（如实记录）

作业的内存状态显示 `collected=0 / pages=3`（本次无新增），但同一 `jobId` 对应
的子任务最终 `SUCCESS` 且评论数 +1。两者至少有一处解释不完整：

- 可能是本次运行确实采到 1 条新评论，而 `collected` 统计口径与服务端 `items` 不一致；
- 也可能是 `SUCCEEDED 但无 CSV → Java 判失败` 这条边界没有被这次运行区分开
  （Java 侧 `runItem` 有"采集未产出数据文件"分支）。

**未定性**。要定位需要把 `com.coolpilot.insight` 的日志级别开到 debug 再跑一次
（当前 `application.yml` 只给旧包名 `com.dy.comment` 开了 debug，新代码的
debug 日志根本没输出——这本身也是个待修项）。

### 8.5 Java 侧遗留

| 项 | 说明 |
|---|---|
| 上述计数口径 | 见 §8.4，需要一次带 debug 日志的复跑 |
| 日志级别配置过期 | `logging.level.com.dy.comment: debug` 指向旧包名，新代码 debug 日志不输出 |
| 平台码硬编码 | `CollectServiceImpl` 用常量 `DOUYIN`：当前链路只认抖音链接，推不出别的来源；多平台时必须把 source 带进请求 |
| `PARTIAL` 判定 | 客户端把 `PARTIAL` 一律视为失败。若将来服务端把"部分成功"用于别的语义，需要重新约定 |
