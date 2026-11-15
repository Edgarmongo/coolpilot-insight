# R3-01 · 新库基线脚本与验证

日期：2026-11-15
产出：`backend/src/main/resources/db/migration-next/V1__init.sql`（37 表 + 种子）
状态：**脚本已写并验证，尚未在业务库执行**

## 1. 为什么没有直接删库

任务虽然授权删除测试数据，但当前代码仍有 **22 个实体、25 个 Mapper** 指向旧 26 表结构。
先删库建新表、再慢慢改代码，中间这段时间系统是**跑不起来**的，而且 36 个测试会全红。

因此本批次采取：

1. 新基线写在 `db/migration-next/`（不在 Flyway 的 `classpath:db/migration` 扫描范围内，不生效）
2. 在**独立临时库** `coolpilot_v1_check` 上验证脚本
3. 业务库 `coolpilot_insight` **一行未动**
4. 真正切换放到 R3-05（代码迁移完成后），届时一并归档 V1~V8

## 2. 备份

```
deploy/backup/coolpilot_insight_20261115_182706.sql   102 KB / 27 条 CREATE TABLE
```

即使数据允许删除，切换前仍保留一份，回滚成本最低。

## 3. 脚本内容

| 模块 | 表数 | 表 |
|---|---:|---|
| system | 6 | `sys_user` `sys_role` `sys_user_role` `sys_config` `sys_audit_log` `sys_file` |
| source | 3 | `ds_source` `ds_account` `ds_keyword` |
| collection | 4 | `col_task` `col_task_item` `col_task_log` `col_checkpoint` |
| content | 5 | `ct_raw_archive` `ct_author` `ct_content` `ct_comment` `ct_grant` |
| analysis | 8 | `an_profile` `an_task` `an_task_item` `an_result` `an_insight` `an_evidence` `an_review` `an_usage` |
| lead | 6 | `ld_signal` `ld_lead` `ld_evidence` `ld_score_history` `ld_review` `ld_followup` |
| conversion | 3 | `cv_product` `cv_match` `cv_conversion` |
| analytics | 2 | `ag_report` `ag_report_version` |
| **合计** | **37** | — |

种子数据：2 角色、1 管理员（哨兵密码）、1 用户角色关联、9 条配置、1 个 DOUYIN 数据源、
1 个 `java-ai` v1 分析档案。

## 4. 关键设计在脚本中的落地

| 设计要求 | 实现 |
|---|---|
| 原始与标准化分离 | `ct_raw_archive` 存平台原始 payload（哈希幂等），`ct_content` 只存标准化字段 |
| Profile 版本化 | `UK(profile_code, profile_version)`；`an_task.profile_id` 锁定具体版本 |
| 分析结果可追溯 | `an_result` 冗余 `model_name` / `prompt_version` 快照 |
| 评论双唯一键 | `UK(source_id, external_comment_id)` + `UK(dedup_key)`；cid 允许 NULL |
| 采集不依赖内容 | `col_task_item` 只存 `external_content_id`，**不 FK 到 `ct_content`** |
| 活动标记释放 | `UK(source_id, external_content_id, active_flag)`，终态显式置 NULL |
| 证据必须有归属 | `an_evidence` 的 `CHECK (result_id IS NOT NULL OR insight_id IS NOT NULL)` |
| CoolSkyAI 边界 | `cv_product` 无价格字段；`cv_conversion` 只存订单号与金额快照 |
| 租户预留 | 全部业务表 `tenant_id DEFAULT 0` |

## 5. 验证结果（真实执行）

在 `coolpilot_v1_check` 临时库执行脚本：

| 项 | 结果 |
|---|---|
| 执行退出码 | 0（无错误） |
| 表数量 | **37** |
| 外键数量 | **49** |
| 种子：角色 | 2 |
| 种子：用户 / 用户角色 | 1 / 1 |
| 种子：配置 | 9 |
| 种子：数据源 / 分析档案 | 1 / 1 |

约束行为实测：

| 验证 | 结果 |
|---|---|
| 重复手机号 | `Duplicate entry '00000000001' for key 'sys_user.uk_user_phone'` ✓ |
| 无效外键 | `foreign key constraint fails` ✓ |
| 证据无归属 | `ck_evi_owner` CHECK 拦截 ✓ |
| `active_flag=1` 重复插入 | `Duplicate entry '1-V1-1' for key 'uk_item_active'` ✓ |
| 置 NULL 后可再插入 | 成功，行数 1 → 2 ✓ |

最后一项是本次重构最容易丢的机制：**唯一索引对 NULL 不生效**，靠它实现"同一内容同时只能有一个活动采集"。

## 6. 与旧库的映射

| 旧表 | 新表 |
|---|---|
| `user` / `audit_log` / `system_config` | `sys_user` / `sys_audit_log` / `sys_config`（+ `sys_role`、`sys_user_role` 拆分） |
| `keyword` / `keyword_video` | `ds_keyword`（`keyword_video` 并入内容关联） |
| `video` / `comment` | `ct_content` + `ct_raw_archive` / `ct_comment` / `ct_author` |
| `video_grant` | `ct_grant` |
| `collection_*`（4 表） | `col_*`（4 表，去掉对内容表的外键） |
| `discovery_task(_item)` | `col_task(task_type=DISCOVERY)` / `col_task_item` |
| `analysis_profile` | `an_profile`（+ 版本、taxonomy、prompt、model） |
| `analysis_task*` | `an_task*` |
| `comment_analysis` / `video_insight` / `analysis_evidence` / `analysis_review` / `usage_record` | `an_result` / `an_insight` / `an_evidence` / `an_review` / `an_usage` |
| `report` / `report_version` | `ag_report` / `ag_report_version` |
| `file_asset`（空壳） | `sys_file`（重新设计） |
| — | `ds_source` `ds_account` `ld_*` `cv_*`（全新） |

## 7. 切换步骤（R3-05 执行，此处记录）

```bash
# 1. 归档旧迁移（不删除，保留追溯）
git mv backend/src/main/resources/db/migration \
       docs/architecture/refactor/legacy-migrations
# 2. 启用新基线
git mv backend/src/main/resources/db/migration-next \
       backend/src/main/resources/db/migration
# 3. 重建库（确认容器 cpi-mysql、库名 coolpilot_insight）
docker exec cpi-mysql mysql -uroot -proot -e \
  "DROP DATABASE coolpilot_insight; CREATE DATABASE coolpilot_insight DEFAULT CHARACTER SET utf8mb4;"
# 4. 重启后由 Flyway 自动执行 V1
docker compose up -d backend
```

回滚：`deploy/backup/*.sql` 导入即可恢复旧结构。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 切换后代码未改完导致全站 500 | 切换与 R3-02/03/05 合并在同一批次末尾执行，不单独切换 |
| 备份文件丢失 | 文件在 `deploy/backup/`，已 `.gitignore` 排除（含数据，不入库）；建议另存一份 |
| 临时库残留 | 验证后已 `DROP DATABASE coolpilot_v1_check`，当前只剩 `coolpilot_insight` |

## 9. 下一步

R3-02（source 模块）→ R3-03（collection）→ R3-04（采集服务化）→ R3-05（content + 切库）。

**切换前 36 个测试必须仍然全绿**，否则不能动业务库。
