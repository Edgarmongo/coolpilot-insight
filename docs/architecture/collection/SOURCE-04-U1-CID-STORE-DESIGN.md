# U1 方案设计 · 跨次 CID 去重记忆

> 状态：**方案设计，未实施**（本轮只读审计 + 设计，未回填、未写新 Store、未改 clean.py）
> 关联：`SOURCE-04-FOLLOWUP-REVIEW.md` §5、`SOURCE-04-FOLLOWUP-AUDIT-AND-DIAGNOSIS.md` §5
> 前置结论：`datas` 目录现存唯一 CSV 为 108 字节表头（`45752568906.csv`），**历史已不可用于恢复**

---

## 一、只读审计结果

### 1.1 `comment` 表的 CID 结构

```
cid              varchar(64)  DEFAULT NULL          -- 平台评论 ID，可空
dedup_key        varchar(64)  NOT NULL              -- CID 时等于 cid；否则为 sha256 指纹前 40 位
dedup_strategy   varchar(16)  NOT NULL DEFAULT 'CID'-- CID / FINGERPRINT
platform                      (未展示，唯一键组成部分)
video_id                      FK → video.id
UNIQUE KEY uk_platform_cid   (platform, cid)        -- cid 唯一（NULL 不参与比较）
UNIQUE KEY uk_platform_dedup (platform, dedup_key)
KEY idx_video_time (video_id, publish_time)         -- video_id 前缀可支撑按视频检索
```

实测数据（当前库）：

| 指标 | 值 |
|---|---|
| 总行数 | 20 |
| `cid IS NULL` | 0 |
| `COUNT(DISTINCT cid)` | 20 |
| `cid` 长度 | 全部 19 |

**结论**：`info_schema` 与实测一致——CID 非空、唯一、定长；`video_id` 上有可用索引前缀。
这意味着"按视频取历史 CID"是一条可用的廉价查询，**不需要新建表**。

### 1.2 Python 侧去重实现

`load_existing_cids(csv_path)` 返回两个集合：

```python
cids: set[str]          # 来自 CSV 的 CID 列
fingerprints: set[str]  # 来自 CSV 的 (昵称|日期|内容[:120]) 的 sha256[:40]
```

它在 `collect_comments()` 里以 `csv_path` 为唯一输入；**CSV 不在 → 两个集合都为空 → 全部视为新**。
这与 `dedup_key_for()` 的两种策略（CID / FINGERPRINT）一一对应。

### 1.3 CSV 的生成与删除路径

| 阶段 | 位置 | 行为 |
|---|---|---|
| 建表头 | `collector.ensure_header` | 文件不存在时写 `utf-8-sig` 表头（13 列，约 108 字节） |
| 追加 | `collector.append_rows` | 每页追加，普通 `open(...,'a')`，无临时文件、无 fsync |
| 清洗 | `clean.py:process_file` → `insert_to_db` | 入库后 `os.remove(filepath)`（第 252 行） |

### 1.4 并发与目录共享

| 项 | 事实 |
|---|---|
| 作业并发 | collection 内 `JobQueue` 单 worker 线程 → 同一进程内串行 |
| 同视频互斥 | backend `itemMapper.countActiveByVideo` + 表约束 `uk_task_video` |
| 数据目录 | `/app/collection-service/datas` 由 backend 与 collection **共享同一宿主目录** |
| 部署副本 | 单副本（`ADR-007` §4.2.1 已列为硬约束） |
| 多 Job 同视频风险 | **当前不会并发**；但**串行重复**是常态（同一视频反复采集） |

### 1.5 变化点小结

> CSV 同时承担两个职责：**本次交付产物**（会被消费、会被删）与**跨次去重状态**（必须长期存在）。
> 前者生命周期以分钟计，后者以月计，却被绑在同一个文件上——这就是根因。

---

## 二、Store 选型

| 方案 | 一致性 | 并发 | 额外状态 | 迁移成本 | 风险 |
|---|---|---|---|---|---|
| **A. 直接查 `comment` 表** | 强（DB 即权威） | 只读，天然安全 | **无**（不新增存储） | 极低：一条 SELECT | 采集容器需要 DB 只读账号 |
| B. 新建 `cid_store` 表 | 强 | 需事务/唯一键 | 新表（DDL） | 中：需回填 + 双写 | 两套真相会漂移 |
| C. SQLite 文件（datas 卷） | 本地事务 | 单文件锁，多进程差 | 新文件（可被误删） | 中：需回填 | 与 CSV 同类风险（仍在同一卷） |
| D. JSONL 每视频一份 | 无事务 | 追加需锁 | 新文件（可被误删） | 中 | 同上，且解析成本高 |

**推荐 A**，理由：

1. **去重记忆本来就已经存在**——`comment.cid` 就是历史。CSV 只是它的一份易失缓存，
   而缓存被删导致"记忆丢失"，本质是**用缓存当权威**；
2. 不引入第三份状态：B/C/D 都要回答"回填期间新旧不一致怎么办"，A 不需要；
3. 并发天然安全：只读查询，多副本也不冲突（与 C-4 的未来扩容方向相容）；
4. 回滚成本最低：不通过就退回 CSV 路径。

**代价与缓解**：collection 容器需要访问 MySQL。

- 使用**只读专用账号**（`GRANT SELECT ON comment`），不复用 backend 的 root；
- 失败必须 **fail-closed**（见 §5），不允许"读不到历史就盲采"。

---

## 三、历史回填

### 3.1 方案 A 下：**无需回填**

`comment` 表已经是权威历史，采集前直接查即可。CSV 丢失不影响去重依据——这正是选 A 的核心收益。

### 3.2 若最终选择 B/C/D：回填 SQL（供评估，本轮不执行）

```sql
-- 按视频回填；dedup_key 已包含两种策略的结果，直接搬运即可
INSERT INTO cid_store (platform, video_id, cid, fingerprint, updated_at)
SELECT c.platform, c.video_id, c.cid,
       CASE WHEN c.dedup_strategy = 'FINGERPRINT' THEN c.dedup_key END,
       NOW()
FROM comment c
WHERE c.video_id = ?
  AND (c.cid IS NOT NULL AND c.cid <> '' OR c.dedup_strategy = 'FINGERPRINT');
```

回填前必须核对的四点（否则会写出脏数据）：

| 检查 | 现状 / 处理 |
|---|---|
| `cid` 是否可空 | 可空（`DEFAULT NULL`）。实测 0 条为空，但**不能假设永远如此** → 必须 `IS NOT NULL` 过滤 |
| `cid` 是否唯一 | `uk_platform_cid(platform,cid)` 保证平台内唯一；回填需按 `(platform, cid)` 去重 |
| 是否有 NULL 参与唯一键 | MySQL 唯一索引允许多个 NULL；**不能**用 `cid` 单独做幂等键 |
| 指纹行如何搬 | `dedup_strategy='FINGERPRINT'` 时 `cid` 为空、`dedup_key` 为哈希 → 两列都要搬 |

### 3.3 重复 CID 的处理

`INSERT ... ON DUPLICATE KEY UPDATE updated_at=NOW()`，或 `INSERT IGNORE`。
前者保留最新时间戳，后者保留首见时间——按"是否需要知道最后确认时间"决定，建议前者。

---

## 四、增量写入与并发控制

### 4.1 采集前（读）

```sql
-- 一次查询同时得到两个集合，与 load_existing_cids 的返回结构对齐
SELECT cid, dedup_key, dedup_strategy
FROM comment
WHERE video_id = ?
```

映射：

```
seen_cids = { cid | dedup_strategy='CID' and cid not null }
seen_fps  = { dedup_key | dedup_strategy='FINGERPRINT' }
```

索引：`idx_video_time(video_id, publish_time)` 的 `video_id` 前缀可用；
单视频评论量级（数十~数千）下无需新增索引。**若将来单视频过万，再加 `(video_id, cid)`。**

### 4.2 采集后（写）

**采集侧不写**。去重记忆的唯一写入者是 `clean.py` 的 `insert_to_db`（它本来就在写 `comment`）。
这样只有一条写路径，不存在"两处写、谁覆盖谁"的问题。

> 这是方案 A 与 B/C/D 的关键差别：B/C/D 都需要"clean 入库成功后同步写 Store"，
> 那是一次**跨存储双写**，必然要处理部分成功。

### 4.3 并发

| 场景 | 结论 |
|---|---|
| 同一视频两个 Job | 当前被 `countActiveByVideo` + `uk_task_video` 拦住，不会并发 |
| 不同视频两个 Job | collection 单 worker 串行；即使并行，查询互不干扰（只读） |
| 采集进行中 clean 写入 | 查询发生在采集**开始前**一次；本次新增的评论在下次采集时自然可见 |
| 采集容器直接查 DB 的额外连接压力 | 每 Job 1 次查询；相对入库写操作可忽略 |

---

## 五、失败恢复与幂等

### 5.1 分级处理（关键设计）

去重记忆是**性能与风控**手段，不是**数据正确性**前提——数据正确性由 `uk_platform_cid` 兜底。
因此失败时可以选择"拒绝执行"，但不能选择"静默盲采"。

| 失败点 | 处理 | 理由 |
|---|---|---|
| DB 不可达 / 查询超时 | **Job 失败**，`errorCode=DEDUP_SOURCE_UNAVAILABLE` | 盲采会重复请求平台，风控风险不可控；且结果本来也写不进 DB |
| 查询返回空（该视频首次采集） | 正常执行 | 空 ≠ 失败 |
| 返回行数异常大（> 阈值） | 只取最近 N 条 + 告警 | 单视频十万条时内存与耗时不可控 |
| clean 入库失败 | 已有行为：CSV 保留（`insert_to_db` 抛错则不会执行 remove） | 产物不丢，可重试 |
| 采集成功但进程崩溃 | `progress.json` 已有断点；CSV 已落盘 | 现状可恢复 |

### 5.2 幂等

- 读：幂等（只读）；
- 写：仍由 `ON DUPLICATE KEY UPDATE` 保证；
- 重复采集：数据不重复（DB 唯一键），但会重复请求平台 —— 这正是 U1 要消除的，不靠幂等兜。

### 5.3 与现有 `progress.json` 的关系

`progress.json` 是**单次运行**的断点（页号、lastCid），不是跨次记忆，两者不冲突。
跨次记忆只在采集开始时读一次，`progress.json` 仍按现状写。

---

## 六、数据一致性验证 SQL

### 6.1 采集前：历史基线

```sql
SELECT COUNT(*) AS before_total, COUNT(DISTINCT cid) AS before_cid
FROM comment WHERE platform='douyin' AND video_id = ?;
```

### 6.2 采集后：增量与去重核对

```sql
-- 1) 本次新增条数（与 job.collected 对比：应满足 inserted ≤ collected）
SELECT COUNT(*) AS after_total FROM comment WHERE video_id = ?;

-- 2) CSV 中的 cid 是否全部已在库（清洗后应为 100%）
--    CSV 已被删除时跳过此步，改用 §6.3 的"第二次运行 collected=0"验证
SELECT COUNT(*) FROM comment WHERE video_id = ? AND cid IN (<csv 中的 cid 列表>);

-- 3) 唯一性未被破坏（应恒为 0）
SELECT COUNT(*) FROM (
  SELECT cid FROM comment WHERE platform='douyin' AND cid IS NOT NULL
  GROUP BY cid HAVING COUNT(*) > 1
) t;
```

### 6.3 端到端验收（推荐，不依赖 CSV）

同一视频**连续采集两次**：

| 指标 | 期望（修复后） | 现状（修复前） |
|---|---|---|
| 第一次 `job.collected` | 页内条数 | 页内条数 |
| 第二次 `job.collected` | **0**（全部命中历史） | 仍等于页内条数 |
| 第二次 `finishedReason` | `NO_MORE_DATA` 或 `MAX_LIMIT` 但新增 0 | 同左 |
| 第二次 DB 增量 | 0 | 0 |

**"第二次 collected=0" 是 U1 是否修好的唯一硬判据**，比任何静态检查都可靠。

---

## 七、回滚方案

| 层 | 回滚动作 | 影响 |
|---|---|---|
| 采集侧读取 | 配置开关 `COLLECT_DEDUP_SOURCE=db|csv`，默认 `db`，改回 `csv` 即恢复旧行为 | 旧行为下 CSV 被删仍是"无记忆"，但不会更糟 |
| 不新增 Store（方案 A） | 无需回滚数据 | 无新状态可清理 |
| 若误选 B/C/D | 停双写 + 保留表 | 需单独清理脚本（本轮不涉及） |

开关默认值建议：**先灰度**——一次采集用 `csv`（基线），下一次用 `db` 对比 `collected`。
两者在"历史存在"时应给出相同结果；不一致说明映射错了。

---

## 八、历史 CSV 已丢失的影响

| 影响 | 结论 |
|---|---|
| 能否恢复历史去重集合 | **能**——`comment` 表就是历史（20 条 cid 齐全、无 NULL） |
| 能否恢复"被跳过而从未入库"的评论 | **不能**。被 CSV 去重跳过的评论只存在于当时那次运行的上下文，从未入库，也无留存 |
| 对 U3（19→20）的影响 | 无。该结论已终止，不再依赖 CSV |
| 对现有数据完整性 | 无。入库路径由唯一键保证，未发现重复 |

---

## 九、实施前置条件（本轮不满足，故不实施）

1. collection 容器获得 MySQL **只读**账号与网络可达性（compose 变更 + 密钥管理）；
2. `collector.py` 增加一次查询 + 失败分类（`DEDUP_SOURCE_UNAVAILABLE`）；
3. `COLLECT_DEDUP_SOURCE` 开关与灰度验证；
4. Python 测试：历史命中 / 空历史 / DB 不可达 / 超大结果截断；
5. 端到端验收按 §6.3 的"第二次 collected=0"。

**明确不做**：本轮不执行回填、不写新 Store、不修改 `clean.py`、不新增表、不改 Flyway。

---

## 十、遗留风险（设计本身不解决的）

| 风险 | 说明 |
|---|---|
| 采集容器持有 DB 凭据 | 即使只读，也是新增的凭据面。可考虑由 backend 提供受限查询接口，代价是多一跳 |
| 平台侧"删除的评论" | 去重记忆只增不减；若平台删评，本地历史仍认为"见过"，这是可接受的偏差 |
| 首次采集仍会重复 | 该视频第一次用新逻辑时没有本地历史，会全量抓一遍（一次性） |
| 指纹策略的跨视频串扰 | 指纹含 `aweme_id`，理论上不会跨视频；但指纹行无法用 cid 定位，排查更难 |
