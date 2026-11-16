# U1 · A-API 实施报告（含一个阻断性发现）

> 状态：**已按 A 路线完成**——真实验证重签证据 → 接线 → 端到端验证（见 §八）
> 基线：`00cda61`
> 关联：`SOURCE-04-FOLLOWUP-REVIEW-02.md` §5、`SOURCE-04-U1-CID-STORE-DESIGN.md`

---

## 一、交付内容

| 文件 | 作用 |
|---|---|
| `backend/.../collection/application/DedupQueryService.java` | 按 `externalContentId` 分页产出 `cids` / `fingerprints`，带 `total` / `nextCursor` / `truncated` |
| `backend/.../collection/controller/InternalCollectionController.java` | `GET /internal/collection/dedup`，`X-Internal-Token` 鉴权（定长比较） |
| `backend/.../mapper/CommentMapper.java`（+2 方法） | `selectDedupPage` / `countDedupRecords`，按 `id` 排序（`publish_time` 可空，不能做游标） |
| `collection-service/app/jobs/dedup.py` | 客户端：分页拉全、令牌透传、失败分类（`DedupUnavailable` / `DedupTooLarge`） |
| `DedupQueryServiceTest`（6）、`InternalCollectionControllerTest`（4）、`tests/test_dedup.py`（9） | 见 §三 |

### 与裁决要求的对照

| 要求 | 落实 |
|---|---|
| 批量查询 / 分页 / 禁止全量 | ✅ 默认 1000、上限 5000（服务端夹取）；`nextCursor` 驱动 |
| truncated | ✅ `total > MAX_SCAN(20000)` 即置位，且超过上限后不再继续分页 |
| 认证 | ✅ 复用 `X-Internal-Token`；**未配置即 401**（不"缺配置就放行"） |
| 认证失败处理 | ✅ 客户端把 401 归为 `DedupUnavailable`，不降级 |
| 并发 | ✅ 只读查询，无锁；同视频串行由 `countActiveByVideo` + `uk_task_video` 保证 |
| 幂等 | ✅ 读无副作用；重复调用返回同一集合 |
| 失败恢复 | ✅ fail-closed：不可达 / 401 / 非法 JSON / truncated 全部抛错 |
| 回滚 | ✅ 客户端保留 `COLLECT_DEDUP_SOURCE=csv` 开关（回到 CSV 记忆） |
| 截断策略 | ✅ 阈值 `MAX_SCAN`、单页 1000/上限 5000、排序按 `id` |
| video_id ↔ CID | ✅ 通过 `videoService.resolveInternalId` 解析；视频未入库返回空集合（不是错误） |
| 指纹去重 | ✅ 两类策略分别映射（`CID`→cids，`FINGERPRINT`→fingerprints），不是只回填 cid |
| TOCTOU | ⚠️ 读发生在采集前；**前提是"单 worker + 同视频串行"**，已在设计文档写明 |

---

## 二、运行时验证（真实容器）

```
带令牌：GET /internal/collection/dedup?externalContentId=7703721523349239951&limit=2
        → 200 {"cids":["7703789895005143818","7703772882871517986"],
                "fingerprints":[],"nextCursor":2,"total":20,"truncated":false}
不带令牌：→ 401

恢复默认配置（COLLECTION_INTERNAL_TOKEN 为空）：→ 401（fail-closed 生效）
```

令牌校验的一次补充说明：第一版实现是"未配置令牌就放行 + WARN"，实测发现
**8080 映射到宿主/内网，等于对任何能访问该端口的人开放**，已改为**未配置即 401**，
并用 `InternalCollectionControllerTest` 三条断言锁死（对/错/未配置）。

---

## 三、测试

| 套件 | 结果 |
|---|---|
| Java 全量 | **349 / 349**（+6） |
| `InternalCollectionControllerTest` | 4 / 4 |
| Python collection-service | **119 passed**（110 + 9） |
| Python ai-service | 4 passed |

`tests/test_dedup.py` 覆盖：单页 / 多页游标 / 令牌透传 / 401 / truncated / 连不上 / 非法 JSON / 空 id 不请求 / 开关默认值。

---

## 四、**阻断性发现：接线会让 COMMENT 证据失效，采集全面停摆**

### 4.1 我做了什么、发生了什么

把 `seen_cids` / `seen_fps` 作为参数加进 `collector.collect_comments`，
并让 `executor` 在采集前调用 `fetch_seen`。改完跑测试：

```
12 failed, 98 passed
FAILED tests/test_snapshot.py::test_capabilities_and_diagnostics_are_disjoint
       AssertionError: 同一能力不能同时出现在能力清单与诊断里:
                       {('DOUYIN_PUBLIC','COMMENT_REPLY'), ('DOUYIN_PUBLIC','COMMENT')}
FAILED tests/test_registry.py::test_verified_capability_promoted_by_evidence
FAILED tests/test_server.py::test_job_lifecycle   （作业被准入拒绝，未完成）
```

回退后：**110 passed**。同一个提交、同一套环境，差别只有那一处函数改动。

### 4.2 根因

```
evidence.implementation_hash(spec, fn)
  = sha256({ ..., "functionFingerprint": function_fingerprint(fn) })
function_fingerprint(fn) = sha256(inspect.getsource(fn))     ← 函数源码
```

`verification_evidence.json` 里 COMMENT 记录的 `implementationHash=173a2122…` 绑定的是
**改动前**的 `collect_comments` 源码。改了函数 → 哈希变 → 证据判 `EVIDENCE_INVALID`
→ `evidence.accepted=false` → 我们的准入闸门判 `NOT_EXECUTABLE` → **COMMENT / COMMENT_REPLY 全被拒**。

这不是 bug，是 SOURCE-04 自己的设计：**改了实现，旧证据就必须失效**。
它顺带证明了一件事——准入闸门确实在起作用（否则这种降级不会表现得这么明显）。

### 4.3 为什么不能用"不改函数"的方式绕过去

- 记录集合只能由 `collect_comments` 内部读取（它读 CSV 拿 `load_existing_cids`）；
- 不动这个函数，就无法让它使用接口给的历史；
- 想"先写一个种子 CSV 再指给它"能在技术上绕过，但那等于**为了让证据保持有效而扭曲实现**，
  并且会在采集与清洗之间插入一个临时目录 + 搬迁步骤，新增一类失败模式。

**结论：这个改动绕不过去，必须正面决策。**

---

## 五、需要裁决的三条路

| 选项 | 内容 | 代价 |
|---|---|---|
| **A. 重新验证 COMMENT/COMMENT_REPLY** | 按现有流程跑一次真实平台验证、重签 `verification_evidence.json`，然后接线 | 需要一次**真实采集**授权；这是唯一能拿到 U1 全部收益的路 |
| **B. 放弃"少抓一次"的目标** | 保留 A-API 作为**其它用途**（如运营侧查重、报表），采集仍按 CSV 记忆 | 零风险，但 U1 要解决的"重复抓取/风控暴露"没解决 |
| **C. 接线但不改函数** | 不可行（见 §4.3） | — |

我的建议是 **A**，但要先把前提说清楚：
- 验证必须真实跑一次（不能补录、不能复用旧 `contentHash`），否则就是伪造证据；
- 跑完之后 `implementationHash` 会变，`verifiedAt` 也要更新，这是**预期行为**，不是污染；
- 如果暂时不允许真实采集，就选 **B**，把 A-API 明确标记为"已就绪、未接线"，不要半挂在那里。

---

## 六、当前代码状态（重要）

- **保留**：Java 接口 + 客户端 + 测试（价值独立存在，且不会影响任何现有行为）；
- **回退**：`collector.py`、`executor.py`、`queue.py` 的全部改动（已 `git checkout` 回到已验证实现）；
- 因此：**采集链路与线上行为没有任何变化**，`dedup.py` 目前是"已就绪、未被调用"。

---

## 七、变更范围审计

未触碰：SOURCE-02 契约与枚举、`EffectiveCapability` 推导、Task/Run 状态机、Flyway/DB Schema、
`collector.py`（已回退）、U2、U4、SOURCE-05。未删除任何数据、未执行真实采集。

部署说明：后端已重建并重启（含新接口）；`COLLECTION_INTERNAL_TOKEN` 保持为空 → 接口默认 401。


---

## 八、A 路线执行结果（2026-11-17 03:2x）

### 8.1 做了什么

1. **真实重验**：容器内执行 `python tools/verify_capability.py --video 7703721523349239951 --max 30 --out /tmp/evidence.json`
   - COMMENT：通过，collected=19，cidCoverage **19/19**，pages=3
   - COMMENT_REPLY：通过，读取 6 条楼中楼
   - CONTENT_DETAIL：通过（未改其实现，指纹未变，作为对照）
2. **重签证据**：用工具产出的记录覆盖 `verification_evidence.json`（不手改任何字段）
3. **接线**：`collect_comments` 增加 `seen_cids/seen_fps`；executor 采集前调 `fetch_seen`
4. **端到端**：真实跑两次任务

### 8.2 证据变更（hash 变化本身就是"换了实现"的证明）

| 能力 | implementationHash | verifiedAt |
|---|---|---|
| COMMENT | `173a2122…` → **`cffb5fb0…`** | 2026-11-16T19:29:54Z |
| COMMENT_REPLY | `b0083529…` → **`0fd5f9da…`** | 2026-11-16T19:30:28Z |
| CONTENT_DETAIL | `31b7bb1f…`（**未变**） | 2026-11-16T19:30:34Z |

CONTENT_DETAIL 未变是重要对照：证明指纹是**按函数**变化的，不是整文件哈希。

重签后注册表：三者 `VERIFIED` 且 `evidence.accepted=true`；准入恢复放行。

### 8.3 端到端结果

```
job=dedup-e2e-1  status=SUCCEEDED collected=1 pages=3
dedupSource=api  dedupSeen=20  finishedReason=NO_RESPONSE
log: 去重记忆（接口）：total=20 pages=1 cid=20 指纹=0
     （￣︶￣）↗ 本页新增 0 条，本次累计 1 条
```

- 采集前从 backend 拿到 **20 个 cid**（DB 里该视频的现有评论）
- 19 条被正确跳过，只剩 1 条判为"新增"
- 第二次任务：`collected=1`、`dedupSeen=20`、DB 仍为 **20 条**

### 8.4 未能达成"第二次 collected=0"——原因已定位

那 1 条不是漏网的去重失败，而是**一条内容为空的楼中楼回复**：

| 证据 | 内容 |
|---|---|
| CSV 行 | `内容` 为空、`是否回复=1`、`回复数=1`、cid=`7703945686237299491` |
| DB | 该 cid **不存在**（该视频 20 条全部 `dedup_strategy=CID`，无 FINGERPRINT 行） |
| clean.py:66 | `filtered = [r for r in rows if 昵称 and 内容]` → **内容为空的行被丢弃** |
| 结果 | 永不入库 → 下次采集仍是"新" → 循环 |

也就是说：**去重（19/20）是对的，失的是"采到但入不了库"的那一条**。
它属于 U2/U4 的问题域（空内容语义、采集-清洗口径不一致），本轮不动。

**因此"第二次 collected=0"这个验收判据在当前数据上不成立**，
需要先决定"空内容回复"该怎么处理（丢弃？保留？），再谈判据。这一点不能含糊过去。
