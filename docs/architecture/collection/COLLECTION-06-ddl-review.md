# COLLECTION-06 · DDL 评审材料

版本：v1　依据：DECISION-02 裁决 + 真实库核对（2026-11-16 02:05）
**状态：待评审。未执行任何 DDL。**

---

## 0. 先纠正上一版文档的两处错误

| # | 上一版（COLLECTION-02）写的 | 真实情况（information_schema 实测） |
|---|---|---|
| 1 | `UK(task_id, video_id, active_flag)` | 实际是**两个**唯一键：`uk_task_video(task_id, video_id)` + `uk_video_active(video_id, active_flag)` |
| 2 | "历史 4 个任务的 PENDING 归入新建 Run" | **历史任务没有 PENDING**：2 个 FAILED、2 个 SUCCESS |

**影响 1**：`uk_video_active(video_id, active_flag)` 是**跨任务**唯一的——同一视频在全库同时只能有一个活动采集。这比裁决描述的"同一 Task 内唯一"更严格，且更符合"防重复采集"的本意，**建议保留不动**。

**影响 2**：回填规则改为按终态映射（见 §5），不涉及 PENDING。

---

## 1. 目标 ER

```
ds_source (1) ──< ds_account
    │
    ├──< collection_task            [加 source_id, execution_mode, schedule_expression, next_run_at]
    │        │
    │        └──< col_run           [新建]  run_no, trigger_type, status(PENDING..), config_snapshot
    │                 │
    │                 └──< col_execution  [新建] account_id?, auth_mode, policy_id, capability,
    │                          │                 status, failure_type, idem_key(UK)
    │                          │
    │                          └──< collection_task_item   [加 run_id, execution_id]
    │                                   │  UK(task_id,video_id) + UK(video_id,active_flag)
    │                                   ├──1:1 collection_checkpoint  (uk_item, 已有 last_cid/page_no)
    │                                   └──<  collection_task_log     [加 run_id, execution_id]
    │
    ├──< ds_keyword_group  [新建] ──< ds_policy [新建]  UK(group_id, version)
    │        └──< ds_keyword [加 group_id]
    │
    └── capability_json（不建 ds_capability）
```

关系要点：

- Task → Run 是 1:N；Run → Execution 1:N；Execution → Item 1:N。
- **Item 仍是内容粒度**（一个视频一条），唯一键不变。
- `col_execution.account_id` 在 `auth_mode='PUBLIC'` 时**必须为 NULL**。
- `col_execution.policy_id` 指向**具体发布版本**，不可指向分组。

---

## 2. 字段映射（旧 → 目标）

| 旧字段 | 目标 | 处理 |
|---|---|---|
| `collection_task.status` | `collection_task.status` | 语义收窄为聚合态（IDLE/RUNNING/PAUSED/DISABLED）；历史值映射见 §5 |
| `collection_task.params_json` | `collection_task.params_json` + `col_run.config_snapshot` | 保留；Run 创建时复制一份快照 |
| `collection_task.total_items/success_items/failed_items` | 保留为聚合统计 | 由 Run 汇总回写 |
| `collection_task.started_at/finished_at` | 保留 | 改为"最近一次 run"的时间（语义变化需在 API 文档标注） |
| （无） | `collection_task.source_id` | 新增，可空；回填见 §5 |
| （无） | `col_run.*` | 新建，为每个历史任务回填 1 条 |
| `collection_task_item.status` | 不变 | 内容粒度状态 |
| `collection_task_item.retry_count/max_retry` | 不变 | 裁决 §4：不建 col_retry，靠这两个字段 + 日志 |
| （无） | `collection_task_item.run_id` | 新增，可空 |
| （无） | `collection_task_item.execution_id` | 新增，可空 |
| `collection_checkpoint.last_cid` | **不变，仅修复 Java 未写入** | 见 §6 |
| `collection_task_log.event_type/event_level/message/details_json` | 不变 | 加 `run_id`/`execution_id` 便于检索 |
| `ds_keyword.group_id` | （无） | 新增，可空 |

**重试可追溯性**（裁决 §4 要求确认）：现有 `retry_count` + `max_retry` + `collection_task_log.details_json` 已能记录"第几次、什么时候、什么错误"。
`details_json` 现状未被写入具体内容——**建议在 retry 路径补一条 log（event_type=RETRY，details_json 存 error_code）**，这是代码改动，不在本阶段。

---

## 3. DDL 草案

### V19 · 关键词分组与策略

```sql
CREATE TABLE `ds_keyword_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `source_id`   BIGINT UNSIGNED NOT NULL,
  `group_code`  VARCHAR(64)  NOT NULL,
  `group_name`  VARCHAR(128) NOT NULL,
  `description` VARCHAR(255) NULL,
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_code` (`source_id`, `group_code`),
  CONSTRAINT `fk_group_source` FOREIGN KEY (`source_id`) REFERENCES `ds_source` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ds_policy` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `group_id`      BIGINT UNSIGNED NOT NULL,
  `version`       INT             NOT NULL,
  `policy_json`   JSON            NOT NULL,
  `status`        VARCHAR(16)     NOT NULL DEFAULT 'DRAFT',
  `change_note`   VARCHAR(255)    NULL,
  `published_at`  DATETIME        NULL,
  `published_by`  BIGINT UNSIGNED NULL,
  `created_by`    BIGINT UNSIGNED NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_version` (`group_id`, `version`),
  KEY `idx_policy_status` (`group_id`, `status`),
  CONSTRAINT `fk_policy_group` FOREIGN KEY (`group_id`) REFERENCES `ds_keyword_group` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `ds_keyword` ADD COLUMN `group_id` BIGINT UNSIGNED NULL AFTER `source_id`,
  ADD KEY `idx_kw_group` (`group_id`);
```

> 未加外键：`ds_keyword.group_id` 允许为 NULL（历史 0 行数据，但保持宽松），加外键后删除分组需先解绑。**建议加**，若要严格性；评审时确认。

### V20 · Run 与 Execution

```sql
CREATE TABLE `col_run` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id`               BIGINT UNSIGNED NOT NULL,
  `run_no`                INT             NOT NULL,
  `trigger_type`          VARCHAR(16)     NOT NULL DEFAULT 'MANUAL',
  `status`                VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
  `config_snapshot`       JSON            NULL,
  `total_execution_count` INT             NOT NULL DEFAULT 0,
  `success_count`         INT             NOT NULL DEFAULT 0,
  `failed_count`          INT             NOT NULL DEFAULT 0,
  `started_at`            DATETIME        NULL,
  `finished_at`           DATETIME        NULL,
  `error_summary`         VARCHAR(512)    NULL,
  `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_no` (`task_id`, `run_no`),
  KEY `idx_run_status` (`status`, `id`),
  CONSTRAINT `fk_run_task` FOREIGN KEY (`task_id`) REFERENCES `collection_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `col_execution` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `run_id`         BIGINT UNSIGNED NOT NULL,
  `source_id`      BIGINT UNSIGNED NOT NULL,
  `account_id`     BIGINT UNSIGNED NULL,
  `auth_mode`      VARCHAR(16)     NOT NULL DEFAULT 'PUBLIC',
  `policy_id`      BIGINT UNSIGNED NULL,
  `capability`     VARCHAR(48)     NOT NULL,
  `status`         VARCHAR(24)     NOT NULL DEFAULT 'PENDING',
  `attempt_no`     INT             NOT NULL DEFAULT 0,
  `request_count`  INT             NOT NULL DEFAULT 0,
  `fetched_count`  INT             NOT NULL DEFAULT 0,
  `inserted_count` INT             NOT NULL DEFAULT 0,
  `duplicate_count` INT            NOT NULL DEFAULT 0,
  `failed_count`   INT             NOT NULL DEFAULT 0,
  `failure_type`   VARCHAR(24)     NULL,
  `error_code`     VARCHAR(64)     NULL,
  `error_message`  VARCHAR(1000)   NULL,
  `adapter_version` VARCHAR(32)    NULL,
  `idem_key`       VARCHAR(96)     NOT NULL,
  `started_at`     DATETIME        NULL,
  `finished_at`    DATETIME        NULL,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_exec_idem` (`idem_key`),
  KEY `idx_exec_run` (`run_id`, `id`),
  KEY `idx_exec_status` (`status`),
  CONSTRAINT `fk_exec_run` FOREIGN KEY (`run_id`) REFERENCES `col_run` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_exec_source` FOREIGN KEY (`source_id`) REFERENCES `ds_source` (`id`),
  CONSTRAINT `fk_exec_account` FOREIGN KEY (`account_id`) REFERENCES `ds_account` (`id`),
  CONSTRAINT `fk_exec_policy` FOREIGN KEY (`policy_id`) REFERENCES `ds_policy` (`id`),
  CONSTRAINT `ck_exec_public_no_account` CHECK (
    (`auth_mode` = 'PUBLIC' AND `account_id` IS NULL)
    OR (`auth_mode` = 'AUTHENTICATED' AND `account_id` IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

> `CHECK` 是本设计里唯一一处用数据库约束表达业务规则的地方——因为它能防止"AUTHENTICATED 但没绑账号"这种静默错误。MySQL 8.0.16+ 支持（本库 8.0.46 已实测支持）。

### V21 · 现有表加列

```sql
ALTER TABLE `collection_task`
  ADD COLUMN `source_id`          BIGINT UNSIGNED NULL AFTER `task_type`,
  ADD COLUMN `execution_mode`     VARCHAR(16) NOT NULL DEFAULT 'MANUAL' AFTER `status`,
  ADD COLUMN `schedule_expression` VARCHAR(64) NULL AFTER `execution_mode`,
  ADD COLUMN `next_run_at`        DATETIME NULL AFTER `schedule_expression`,
  ADD KEY `idx_task_source` (`source_id`);

ALTER TABLE `collection_task_item`
  ADD COLUMN `run_id`       BIGINT UNSIGNED NULL AFTER `task_id`,
  ADD COLUMN `execution_id` BIGINT UNSIGNED NULL AFTER `run_id`,
  ADD KEY `idx_item_run` (`run_id`);

ALTER TABLE `collection_task_log`
  ADD COLUMN `run_id`       BIGINT UNSIGNED NULL AFTER `task_id`,
  ADD COLUMN `execution_id` BIGINT UNSIGNED NULL AFTER `task_id`,
  ADD KEY `idx_log_run` (`run_id`);

ALTER TABLE `ds_source`
  ADD COLUMN `last_checked_at`     DATETIME NULL,
  ADD COLUMN `last_check_result`   VARCHAR(32) NULL;
```

**不加外键**（`run_id`/`execution_id`）：历史行全为 NULL，且这两列是"追溯用"而非强从属；加外键会让回填顺序变刚性。DB 层用索引保障查询。

---

## 4. 索引与约束变化汇总

| 表 | 新增 | 保留 |
|---|---|---|
| `ds_keyword_group` | `UK(source_id, group_code)` | — |
| `ds_policy` | `UK(group_id, version)`、`idx(group_id,status)` | — |
| `col_run` | `UK(task_id, run_no)`、`idx(status,id)` | — |
| `col_execution` | `UK(idem_key)`、`idx(run_id,id)`、`idx(status)`、`CHECK auth_mode/account_id` | — |
| `collection_task` | `idx_task_source` | `UK(task_no)`、`idx(status,created_at)`、`idx(owner_user_id,created_at)` |
| `collection_task_item` | `idx_item_run` | **`UK(task_id,video_id)`、`UK(video_id,active_flag)` 均不动**（裁决 §1） |
| `collection_task_log` | `idx_log_run` | `idx(task_id,created_at)`、`idx(task_item_id)` |
| `ds_keyword` | `idx_kw_group` | `UK(source_id,owner_user_id,normalized)` 不动 |

---

## 5. 历史数据回填规则（按实测数据）

实际数据：4 条任务，**FAILED 2 / SUCCESS 2**，item 共 5 条，全部有终态。

```sql
-- 1) 为每个历史任务建 1 条 run（run_no=1），状态按终态映射
INSERT INTO col_run (task_id, run_no, trigger_type, status, config_snapshot,
                     total_execution_count, success_count, failed_count,
                     started_at, finished_at, created_at)
SELECT t.id, 1, 'MANUAL',
       CASE t.status
         WHEN 'SUCCESS' THEN 'COMPLETED'
         WHEN 'FAILED'  THEN 'FAILED'
         ELSE 'FAILED'            -- 兜底：无法判定的历史状态一律记 FAILED，不假装成功
       END,
       t.params_json, 1, t.success_items, t.failed_items,
       t.started_at, t.finished_at, t.created_at
FROM collection_task t
WHERE NOT EXISTS (SELECT 1 FROM col_run r WHERE r.task_id = t.id AND r.run_no = 1);

-- 2) 任务状态收敛为聚合态
UPDATE collection_task SET status = 'IDLE'
WHERE status IN ('SUCCESS','FAILED','PARTIAL_SUCCESS','CANCELLED');

-- 3) item 关联到回填的 run
UPDATE collection_task_item i
JOIN col_run r ON r.task_id = i.task_id AND r.run_no = 1
SET i.run_id = r.id
WHERE i.run_id IS NULL;

-- 4) source_id 回填：现有任务无源信息，按 adapter 推断为 DOUYIN
UPDATE collection_task t
SET t.source_id = (SELECT id FROM ds_source WHERE code = 'DOUYIN')
WHERE t.source_id IS NULL;
```

**第 2 步的语义变化必须记录**：`collection_task.status` 从"结果态"变为"聚合态"，
历史 SUCCESS/FAILED 收敛为 IDLE（结果保存在 Run 里）。这是**不可逆的语义变更**，回滚需同时恢复 status。

---

## 6. checkpoint 修复（裁决 §4）

不改表，只改代码（**本阶段不执行**）：

```java
// CollectServiceImpl.saveCheckpoint 现状只写 persistedCount
cp.setPersistedCount(persisted);
// 应补：
cp.setLastCid(lastCidFromCollector);   // Python save_progress 已产出
cp.setPageNo(pageFromCollector);
```

字段映射对齐需确认：Python `save_progress` 写的是 `{"page","count","lastCid"}`，
Java 对应 `page_no` / `persisted_count` / `last_cid` —— **三者一一对应，无歧义**。

---

## 7. 回滚方案

| 阶段 | 回滚 |
|---|---|
| V19~V21 执行后未回填 | `DROP TABLE col_execution, col_run, ds_policy, ds_keyword_group;` + `ALTER TABLE ... DROP COLUMN` 逐列删除 |
| 已执行回填（§5） | **不可简单回滚**：`collection_task.status` 已被改写。必须先备份 `collection_task` 全表快照，回滚时按快照恢复 status |
| 迁移失败中途 | Flyway 对 DDL 不支持事务回滚（MySQL DDL 隐式提交）→ **必须在临时库先完整演练** |

**前置备份**（执行前必做）：

```bash
docker exec cpi-mysql mysqldump -uroot -proot --databases coolpilot_insight \
  --single-transaction > deploy/backup/pre-collection-ddl-$(date +%Y%m%d_%H%M).sql
```

**教训沿用**：不要在目标库"试跑后回滚"——自增序列不回滚，V11 已因此导致 id 跳号。

---

## 8. 迁移后验证 SQL

```sql
-- 结构
SELECT COUNT(*) FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='coolpilot_insight'
   AND TABLE_NAME IN ('ds_keyword_group','ds_policy','col_run','col_execution');  -- 期望 4

-- 唯一键存在性
SELECT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA='coolpilot_insight'
   AND INDEX_NAME IN ('uk_group_code','uk_policy_version','uk_run_no','uk_exec_idem');  -- 期望 4 行

-- 回填完整性
SELECT (SELECT COUNT(*) FROM collection_task) tasks,
       (SELECT COUNT(*) FROM col_run) runs,                       -- 应 >= tasks
       (SELECT COUNT(*) FROM collection_task_item WHERE run_id IS NULL) orphan_items,  -- 期望 0
       (SELECT COUNT(*) FROM collection_task WHERE status NOT IN ('IDLE','RUNNING','PAUSED','DISABLED')) bad_status; -- 期望 0

-- CHECK 约束生效（应报错）
INSERT INTO col_execution (run_id,source_id,auth_mode,capability,idem_key)
VALUES (1,1,'AUTHENTICATED','FETCH_COMMENTS','ck-test');   -- 期望 CHECK 失败

-- 原有能力未被破坏
-- 在应用层跑：MapperCrudSemanticsTest（含 active_flag 置 NULL 语义）
```

---

## 9. 评审清单（请逐项确认）

| # | 项 | 我的建议 |
|---|---|---|
| 1 | `task_item` 双唯一键保持不变 | ✅ 按裁决，且实测比描述更严格（跨任务唯一） |
| 2 | `ds_keyword.group_id` 是否加外键 | 建议**加**，避免分组删除后留下悬空引用 |
| 3 | `run_id`/`execution_id` 是否加外键 | 建议**不加**（历史 NULL，加外键使回填变刚性） |
| 4 | `collection_task.status` 语义变更（结果态→聚合态） | 必须确认可接受，这是不可逆变更 |
| 5 | 回填时 `source_id` 一律推断为 DOUYIN | 现有 4 任务确实都来自抖音，但属推断，需确认 |
| 6 | `col_execution` 的 CHECK 约束 | 建议保留，能防"AUTHENTICATED 无账号" |
| 7 | 是否补 `idx_log_run` | 建议加（Run 详情页要按 run 查日志） |

---

## 10. 结论

**B：设计完成，7 项评审待确认后方可执行 DDL。**

同时修正了上一版两处事实错误（唯一键结构、历史无 PENDING），并在 §5 用真实数据给出回填规则。
