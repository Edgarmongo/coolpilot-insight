# 06 · 迁移建议

原则（来自项目建设总指令）：**保留现有业务数据与采集能力，渐进迁移，不推倒重写。**

## 1. 总体策略：加维度，不换核心

现有 25 张表能正常支撑「抖音视频 + 评论 + AI 分析 + 报告」这一条完整链路，
这条链路已被实测跑通（见 04 号报告）。**没有理由推翻它。**

推荐路径：

```
阶段 A：给现有表加 source 维度（不改语义）
   ↓
阶段 B：新建统一内容模型，双写并校验
   ↓
阶段 C：页面切换到新模型，旧表降级为归档
```

每一步都可独立回滚（A、B 阶段旧代码完全不受影响）。

## 2. 阶段 A：最小改动接入多来源（建议 P1 第一件事）

**只做三件事，不动旧逻辑：**

1. 新建 `data_source` 表，写入一行：`source_code=DOUYIN`（id=1）
2. 给 `video`、`comment` 加 `source_id BIGINT NOT NULL DEFAULT 1`
   - 默认值保证存量数据自动归位，**无需数据回填脚本**
   - 现有查询不加条件也不受影响
3. `collection_task` 加 `source_id`，采集入口带参传入

**收益**：立刻具备「按来源统计」的能力，为后续接入小红书/快手留出扩展位，
而现有代码**一行都不用改**。

**风险**：几乎为零。加列带默认值，MySQL 8 支持 instant add column。

## 3. 阶段 B：统一内容模型（新建，不替换）

新建三张表，与旧表**并存**：

| 新表 | 对应旧表 | 说明 |
|---|---|---|
| `content` | `video` | 统一 VIDEO / NOTE / ARTICLE / POST |
| `content_comment` | `comment` | 沿用双唯一键思路 |
| `content_author` | 无（现为 `comment.nickname` 冗余） | 作者公开资料独立建模 |

关键设计（吸取现有教训）：

- **去重不能只靠平台 ID**：`(source_id, external_content_id)` 唯一 +
  `content_hash` 指纹索引 + `source_url` 辅助，三层兜底
- **保留原始响应**：`raw_data_ref` 指向归档位置，便于上游变更后回溯
- **证据链独立**：新增 `content_evidence`，与 `analysis_evidence` 分开，
  避免线索级证据污染分析级证据

**双写策略**：Adapter 写完旧表后同步写新表，用对账脚本比对行数与字段一致性，
**连续 N 天零差异**后才允许切换页面读取。

## 4. 阶段 C：线索域（新增，与旧表无耦合）

5 张新表，全部外键指向新内容模型，不碰旧表：

```
lead → lead_evidence → demand_signal → analysis_result(新)
     → lead_score_history
     → lead_review → lead_followup → conversion_record
```

注意：设计文档中的 `analysis_result` 与旧代码里已废弃的同名概念**不是一回事**。
新建时建议改名为 `content_analysis`，避免与历史包袱混淆。

## 5. 明确禁止的动作

| 禁止 | 原因 |
|---|---|
| `DROP TABLE` 任何现有表 | 违反「保留历史数据」约束 |
| 修改 V1~V8 迁移文件 | `validate-on-migrate: true`，改动会导致启动失败 |
| 一次性重建数据库 | 数据虽小，但重建会丢失验证基线（45 条证据、实测报告） |
| 未经备份执行 DDL | MySQL DDL 隐式提交，无法回滚 |

## 6. 建议的迁移顺序（与任务卡对齐）

| 任务卡 | 迁移内容 | 风险 |
|---|---|---|
| CP-INSIGHT-01 | 建 `data_source`、加 `source_id` 三处 | 低 |
| CP-INSIGHT-02 | `collection_run`（执行记录独立成表，现有 `collection_task_log` 可升级） | 低 |
| CP-INSIGHT-03~06 | 各 Adapter 只写新模型，旧表由抖音 Adapter 双写 | 中 |
| CP-INSIGHT-07 | 补 `analysis_profile` 的 `prompt_version` / `model_config_id` | 中 |
| CP-INSIGHT-08 | 新建 5 张线索表 | 低（全新域） |
| CP-INSIGHT-09~10 | 只读聚合，不改结构 | 极低 |

## 7. 迁移前必须先做的事

1. **备份**：`mysqldump` 全库 + 数据卷快照（`dtk`/`cpi` 两套都在用命名卷）
2. **补漂移检查**：把 `tools/check_schema_drift.py` 接进构建流程，
   避免再出现 V8 那种「实体有字段、表里没有」的问题
3. **确认唯一开发副本**：两个路径的 compose 共用 `coolpilot-insight` 项目名与数据卷，
   迁移只能在一个副本上执行，另一个必须停用或改名

## 8. 一句话结论

**这套库不需要重建，需要的是「加维度 + 加新域」。**
现有 25 张表里，23 张可以直接复用；`file_asset` 是空壳，`analysis_result` 已废弃，
真正需要新建的是「数据源抽象」和「线索域」两处——它们都是纯增量，不动存量。
