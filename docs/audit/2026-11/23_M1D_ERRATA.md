# 23 · M1-D 元审计勘误（对 #22 的自查）

**任务**：M1-D 的补充复核
**执行模式**：READ ONLY
**背景**：M1-D 已于 2026-11-03 22:23 完成，交付 `22_DATABASE_FREEZE_REVIEW.md`（876 行）。本轮对该报告做**元审计**（对报告自身的复核），非重复评审。
**结论**：#22 存在 **3 项缺陷**，其中 **1 项推翻其既有结论**
**授权状态**：**未授权任何实施**

> 证据等级：**A** 直接验证｜**B** 结构推导｜**C** 设计建议

---

## 零、为什么要做元审计

#22 通过了自检（25 表、39 外键、14 问题、ER 25 实体），但自检只验证了**数量与结构**，未验证**约束之间的语义相容性**。本轮补上这一层，方法：

| 检查维度 | 方法 |
|---|---|
| 编号连续性 | 提取 FK 清单序号，检查 1..39 无重复无跳号 |
| 规则分布统计 | 重新统计 `RESTRICT/CASCADE/SET NULL` 实际数量，与报告声称值比对 |
| **约束相容性** | 逐项检查 `ON DELETE` 动作与列可空性、`CHECK` 约束、级联链是否兼容 |
| 外部依据 | 关键约束行为查 MySQL 官方文档验证 |

**结果**：编号 ✅ 连续；规则分布 ❌ 错误；约束相容性 ❌ 2 处冲突。

---

## 一、S1：`analysis_evidence` 的 `SET NULL` 会违反自身 `CHECK`

### 1.1 冲突

#22 第十三节：

```sql
-- FK #32
analysis_evidence.comment_analysis_id → comment_analysis.id  ON DELETE SET NULL
-- 约束（#21 4.10）
CONSTRAINT ck_evidence_target CHECK (insight_id IS NOT NULL OR comment_analysis_id IS NOT NULL)
```

### 1.2 失败路径

设某条证据仅挂评论级分析（`insight_id = NULL`，`comment_analysis_id = 10`）。删除 `comment_analysis` 10 时：

```
1. SET NULL 触发 → comment_analysis_id 变为 NULL
2. 此刻该行：insight_id = NULL 且 comment_analysis_id = NULL
3. CHECK 求值为 FALSE
4. MySQL 拒绝该删除操作  →  ERROR 3819 (HY000): Check constraint 'ck_evidence_target' is violated
```

**结果**：`comment_analysis` 的删除被意外阻止，且错误信息指向 CHECK 而非外键，排查困难。这是**半年前不会暴露、上线后必然踩到**的隐性缺陷。

### 1.3 修复裁定

| 方案 | 说明 | 判断 |
|---|---|---|
| A. `comment_analysis_id` 改 **CASCADE** | 分析结果删除时，其证据一并删除 | ✅ **采用** |
| B. 放宽 CHECK 允许双 NULL | 会产生「无任何指向」的僵尸证据行 | ❌ |
| C. `comment_analysis` 删除改 RESTRICT | 阻断删除，但分析结果本就需要可清理 | ❌ |

**理由**：证据的存在意义就是支撑某个分析结论。结论被删，证据无独立价值。CASCADE 语义正确。

**关联影响**：`insight_id` 已是 CASCADE，两者行为统一——**证据总是跟随其所依附的分析结果消亡**。

---

## 二、S2：复合外键 `ON DELETE SET NULL` 违反 MySQL 强制要求（推翻 N06 结论）

### 2.1 #22 原方案

```sql
-- FK #23（复合）
FOREIGN KEY (batch_id, task_id) REFERENCES analysis_task_batch(id, task_id)
    ON DELETE SET NULL
```

其中 `analysis_task_item.task_id` 为 `BIGINT UNSIGNED NOT NULL`。

### 2.2 违反的官方规定（A 级，已查证）

MySQL 8.0 Reference Manual, 15.1.20.5 FOREIGN KEY Constraints → Referential Actions：

> `SET NULL`: Delete or update the row from the parent table and set the foreign key column or columns in the child table to `NULL`.
> **"If you specify a `SET NULL` action, make sure that you have not declared the columns in the child table as `NOT NULL`."**

复合外键的 `SET NULL` 会把**所有子列**置 NULL。`task_id` 是 `NOT NULL` → 该 FK **无法创建**（`ALTER TABLE` 报 1215/3780）。

### 2.3 为什么不能简单改成 CASCADE

若改 `ON DELETE CASCADE`：删除某个 batch 会**连带删除该批次所有 `analysis_task_item` 行**。但任务是分析对象的权威记录，删批次不应丢任务项（且 `analysis_task_item` 还要被 `comment_analysis` 以 SET NULL 引用）。

### 2.4 根本矛盾

```
需求1：batch_id 可空（未分批的 item）      → 需要 SET NULL
需求2：task_id 非空（item 必属某任务）      → 不能 SET NULL
需求3：复合 FK 保证 batch.task_id = item.task_id
冲突：复合 FK 只能有一个 ON DELETE 动作，无法同时满足需求1与需求2
```

**MySQL 的复合外键不支持「部分列 SET NULL、部分列保持」**，故复合方案在本场景**不可行**。

### 2.5 推翻 #22 的 N06 结论

| #22 原结论 | 本次修订 |
|---|---|
| 「采用方案 A（复合外键）……MySQL 对含 NULL 的复合外键不校验，恰好满足未分批场景」 | ❌ **错误**。前半句（NULL 不校验）本身成立，但忽略了 `SET NULL` + `NOT NULL` 的硬冲突。**改为方案 B（应用层）** |

### 2.6 修订后设计

```sql
-- 拆为两个单列外键
CONSTRAINT fk_ati_task   FOREIGN KEY (task_id)  REFERENCES analysis_task(id)        ON DELETE CASCADE,
CONSTRAINT fk_ati_batch  FOREIGN KEY (batch_id) REFERENCES analysis_task_batch(id)  ON DELETE SET NULL
```

| 项 | 结果 |
|---|---|
| `task_id NOT NULL` + CASCADE | ✅ 合法（CASCADE 不要求可空） |
| `batch_id` 可空 + SET NULL | ✅ 合法 |
| 级联链 | ✅ task 删除 → batch CASCADE + item CASCADE，顺序无关 |
| **同任务一致性** | ⚠️ **降级为应用层 + 巡检**（见 2.7） |

**连带**：`analysis_task_batch` 的 `uk_id_task (id, task_id)` **不再需要**，从 #22 第十四节唯一键清单删除（它本身是为主键 id 加的冗余唯一索引，仅服务于复合 FK）。

### 2.7 应用层补偿

```sql
-- 巡检：找出 batch 与 task 不匹配的 item
SELECT ati.id, ati.task_id, b.task_id AS batch_task_id
FROM analysis_task_item ati
JOIN analysis_task_batch b ON b.id = ati.batch_id
WHERE ati.task_id <> b.task_id;
```

写入时校验（伪代码）：
```
if (item.batchId != null) {
    batch = batchMapper.selectById(item.batchId);
    if (!batch.getTaskId().equals(item.getTaskId())) throw new BizException("批次与任务不匹配");
}
```

**诚实说明**：这是数据库强制力的一次**降级**。原因是 MySQL 复合外键的 `ON DELETE` 动作不可分列配置，不是设计懒惰。#22 声称「数据库强制」是过度乐观。

---

## 三、S3：ON DELETE 规则统计数字错误

### 3.1 实测统计（A 级）

对 #22 第十三节 39 行逐行提取 `ON DELETE` 值：

| 规则 | #22 声称 | **实际** | 差异 |
|---|---|---|---|
| RESTRICT | 21 | **19** | -2 |
| CASCADE | 8 | **10** | +2 |
| SET NULL | 10 | **10** | 0 |
| 合计 | 39 | 39 | — |

总和正确掩盖了分布错误。**这是自检只查总数的盲区。**

### 3.2 计入 S1/S2 修订后的最终分布

| 规则 | 数量 | 变动说明 |
|---|---|---|
| RESTRICT | 19 | 不变 |
| CASCADE | **11** | +1（`comment_analysis_id` 由 SET NULL 改 CASCADE，S1） |
| SET NULL | **9** | -1（同上） |
| **合计** | **39** | 总数不变（S2 只是把复合 FK 拆为两个单列，数量不变） |

### 3.3 修订后的完整外键清单（以本表为准）

| # | 子表.字段 | 父表.字段 | ON DELETE |
|---|---|---|---|
| 1 | `video_grant.grantee_user_id` | `user.id` | RESTRICT |
| 2 | `video_grant.video_id` | `video.id` | CASCADE |
| 3 | `video_grant.granted_by` | `user.id` | RESTRICT |
| 4 | `keyword.owner_user_id` | `user.id` | RESTRICT |
| 5 | `keyword_video.keyword_id` | `keyword.id` | CASCADE |
| 6 | `keyword_video.video_id` | `video.id` | RESTRICT |
| 7 | `discovery_task.keyword_id` | `keyword.id` | RESTRICT |
| 8 | `discovery_task.owner_user_id` | `user.id` | RESTRICT |
| 9 | `discovery_task_item.task_id` | `discovery_task.id` | CASCADE |
| 10 | `discovery_task_item.video_id` | `video.id` | RESTRICT |
| 11 | `comment.video_id` | `video.id` | RESTRICT |
| 12 | `comment.source_item_id` | `collection_task_item.id` | SET NULL |
| 13 | `collection_task.owner_user_id` | `user.id` | RESTRICT |
| 14 | `collection_task_item.task_id` | `collection_task.id` | CASCADE |
| 15 | `collection_task_item.video_id` | `video.id` | RESTRICT |
| 16 | `collection_checkpoint.task_item_id` | `collection_task_item.id` | CASCADE |
| 17 | `collection_task_log.task_id` | `collection_task.id` | CASCADE |
| 18 | `collection_task_log.task_item_id` | `collection_task_item.id` | SET NULL |
| 19 | `analysis_task.owner_user_id` | `user.id` | RESTRICT |
| 20 | `analysis_task.profile_id` | `analysis_profile.id` | RESTRICT |
| 21 | `analysis_task_batch.task_id` | `analysis_task.id` | CASCADE |
| 22 | `analysis_task_item.task_id` | `analysis_task.id` | CASCADE |
| **23** | **`analysis_task_item.batch_id`** | **`analysis_task_batch.id`** | **SET NULL**（单列，S2 修订） |
| 24 | `comment_analysis.comment_id` | `comment.id` | RESTRICT |
| 25 | `comment_analysis.profile_id` | `analysis_profile.id` | RESTRICT |
| 26 | `comment_analysis.analysis_task_id` | `analysis_task.id` | SET NULL |
| 27 | `comment_analysis.analysis_task_item_id` | `analysis_task_item.id` | SET NULL |
| 28 | `video_insight.video_id` | `video.id` | RESTRICT |
| 29 | `video_insight.analysis_task_id` | `analysis_task.id` | SET NULL |
| 30 | `video_insight.profile_id` | `analysis_profile.id` | RESTRICT |
| 31 | `analysis_evidence.insight_id` | `video_insight.id` | CASCADE |
| **32** | **`analysis_evidence.comment_analysis_id`** | **`comment_analysis.id`** | **CASCADE**（S1 修订） |
| 33 | `analysis_evidence.comment_id` | `comment.id` | RESTRICT |
| 34 | `file_asset.owner_user_id` | `user.id` | RESTRICT |
| 35 | `report.owner_user_id` | `user.id` | RESTRICT |
| 36 | `report_version.report_id` | `report.id` | CASCADE |
| 37 | `report_version.analysis_task_id` | `analysis_task.id` | SET NULL |
| 38 | `usage_record.owner_user_id` | `user.id` | SET NULL |
| 39 | `system_config.updated_by` | `user.id` | SET NULL |

**统计**：RESTRICT 19 / CASCADE 11 / SET NULL 9 = **39** ✅

---

## 四、连锁修订：唯一键清单

| 表 | #22 原清单 | 本次修订 |
|---|---|---|
| `analysis_task_batch` | 含 `uk_id_task (id, task_id)` | **删除**（复合 FK 已撤销，该索引无意义） |
| `analysis_task_item` | `uk_task_target (task_id, target_type, target_id)` | 保留不变 |
| `analysis_evidence` | 无唯一键 | 保留（`CHECK` 仍有效，因两条 FK 均 CASCADE） |

---

## 五、对 #22 冻结结论的影响

### 5.1 阻塞项变化

| 来源 | 编号 | 内容 | 状态 |
|---|---|---|---|
| #22 | N01 | `video_insight.profile_id` 无字段定义 | 待修（#22 第四节已给定义） |
| #22 | N02 | `analysis_profile.params_hash` 无定义 | 待修（#22 第五节已给定义） |
| #22 | N03 | `video_grant` 删除策略矛盾 | 待修（#22 第六节已裁定） |
| **本报告** | **S1** | `analysis_evidence` SET NULL 违反 CHECK | **新阻塞**（本文 1.3 已裁定） |
| **本报告** | **S2** | 复合 FK `SET NULL` + `NOT NULL` 非法 | **新阻塞**（本文 2.6 已裁定） |
| **本报告** | **S3** | ON DELETE 统计错误 | **非阻塞**（本文 3.3 已修正） |

**阻塞项：3 → 5**（S1、S2 为新增）。

### 5.2 冻结判定

| 判定项 | #22 结论 | 本次修正 |
|---|---|---|
| 字段定义完整 | ❌ | ❌（不变，N01/N02） |
| 关联完整性策略明确 | ✅ | **⚠️ 降级**：`analysis_task_item.batch_id` 的同任务一致性由「数据库强制」降为「应用层 + 巡检」（S2） |
| 外键/索引/ER 一致 | ❌ | ❌（S1/S2 又添两处） |
| 授权规则无矛盾 | ❌ | ❌（N03 未修） |
| **能否冻结** | ❌ 否 | ❌ **否** |

**结论不变：不能冻结。** 且阻塞从 3 增至 5。

### 5.3 修复清单（合并 #22 + 本文）

| # | 修复动作 | 出处 | 工作量 |
|---|---|---|---|
| 1 | `video_insight` 补 `profile_id`，删 4 个版本列 | #22 第四节 | 文档补 1 节 |
| 2 | `analysis_profile` 补 `params_hash CHAR(16)` + 规范化算法 | #22 第五节 | 文档补 2 节 |
| 3 | `video_grant.video_id` 改 CASCADE，修 7.3 表述 | #22 第六节 | 改 1 句 |
| 4 | `analysis_evidence.comment_analysis_id` 改 **CASCADE** | 本文 1.3 | 改 1 格 |
| 5 | `analysis_task_item.batch_id` 拆为**单列** FK（SET NULL） | 本文 2.6 | 改 2 行 |
| 6 | 删除 `analysis_task_batch.uk_id_task` | 本文第四节 | 删 1 行 |
| 7 | ON DELETE 统计改为 19/11/9 | 本文 3.3 | 改 1 表 |

全部为**文档修订**，均不改变表结构（表数量仍 25，外键仍 39）。

---

## 六、对 #22 未验证事项的补充

| # | 事项 | 级别 | 说明 |
|---|---|---|---|
| 11 | **MySQL 复合外键 `ON DELETE CASCADE` 在双表互引场景的行为** | 未验证 | 本文已避开复合 FK，此风险消除 |
| 12 | **`CHECK` 约束与外键 CASCADE 执行顺序** | 未验证 | S1 修复后两条 FK 均 CASCADE，理论上不触发 CHECK 违例，但**需在 M2-B 实测**：删除一条 `comment_analysis` 后，其证据行应被级联删除而非报错 |
| 13 | `params_hash` 在 JSON 含浮点时的规范化稳定性 | 未验证 | 建议固定 6 位小数并在测试用例覆盖 |

---

## 七、本轮操作记录（READ ONLY）

| 项 | 状态 |
|---|---|
| 修改 Java / Python / Vue | **未执行** |
| 修改 SQL / Flyway / 配置 / 依赖 | **未执行** |
| DDL / DML / 清库 / 迁移 | **未执行** |
| Docker / Volume 变更 | **未执行** |
| Git 操作 | **未执行** |
| 覆盖现有文件 | **未执行**（#22 保留原文，仅追加勘误指针；本文为新文件） |
| 只读操作 | `sed` / `grep` / `wc` 读 #22；WebFetch 查 MySQL 8.0 官方文档（FOREIGN KEY Constraints） |
| 新增文件 | 本文 `docs/audit/2026-11/23_M1D_ERRATA.md` |

**本任务完成不代表获得任何实施授权。**
