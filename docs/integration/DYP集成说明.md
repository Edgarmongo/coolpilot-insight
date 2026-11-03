# DYP 集成说明（CoolPilot Insight ↔ DouYin Intelligence Platform）

**集成日期**：2026-11-04
**集成方式**：A+B（数据管道 + 统一入口），不做代码合并

> **2026-11-04 更新：已改为原生移植，导航外链已移除**
>
> 原先「深度分析」是打开 `localhost:8088` 的新标签页，体验上是「跳走」而不是「集成」。
> 现已把 DYP 的四项核心能力**用本工程的技术栈重新实现**，不再依赖跳转：
>
> | DYP 能力 | CoolPilot 落地方式 | 位置 |
> |---|---|---|
> | 评论树（`works/{id}/comments`） | `GET /api/comment/tree` + 评论页「回复树」弹窗 | Java + Vue |
> | 人工复核（`ai_review`） | `analysis_review` 表 + `/api/review/*` + 「复核」页 | Java + Vue |
> | PDF/Excel 报告（reportlab/openpyxl） | Flask `/render/report` + 报告页下载按钮 | Python + Vue |
> | 全局概览（overview/stats/trend） | `GET /api/overview` + 「运营概览」页 | Java + Vue |
>
> 移植时保留了 DYP 的关键语义：复核的 before/after 留痕、低置信优先队列、
> 结论可追溯到评论、报告不重复调用 AI。
>
> 本文余下内容仍然有效——数据管道（CSV 投递到 DYP）依然可用，
> 只是不再是产品内的默认入口。

---

## 一、为什么这样集成

两个系统技术栈完全不同，且各自有不可替代的能力：

| | CoolPilot Insight | DYP |
|---|---|---|
| 后端 | Java Spring Boot | Python FastAPI（5714 行） |
| 数据库 | MySQL（3 表） | PostgreSQL（17 表） |
| 强项 | **主动采集**（DrissionPage）、可视化大屏 | **数据治理、AI 评论级分析、报告导出** |
| 弱项 | 无报告导出、仅一级评论 | 无采集引擎 |

**把 5714 行 Python 重写成 Java 不现实**，因此选择「各司其职 + 管道打通」：

```
┌─────────────────┐   CSV 投递    ┌──────────────────┐
│  CoolPilot      │ ───────────► │  DYP             │
│  采集 + 大屏     │              │  AI 分析 + 报告   │
└─────────────────┘              └──────────────────┘
```

---

## 二、数据流

```
1. CoolPilot 采集抖音评论
   → Py_Data/Datas/<aweme_id>.csv

2. 投递到 DYP（二选一）
   a) 复制文件到 DYP 的 imports/inbox/（10 分钟内自动扫描）
   b) 调 API 立即导入

3. DYP 自动
   a) 从文件名提取 aweme_id，自动创建占位作品
   b) 按 (work_id, platform_comment_id) 幂等入库
   c) 可做：AI 评论级分析、需求洞察、PDF/Excel 报告
```

---

## 三、使用方式

### 方式 1：投递目录（推荐，零操作）

DYP 的 `imports/inbox/` 是 bind mount 的宿主目录：

```bash
cp D:/devworkspace/software/workspace/AIProjects/DataAnalysis/douyin_opinion_analysis/Py_Data/Datas/*.csv \
   D:/devworkspace/software/workspace/AIProjects/DataAnalysis/douyin-intelligence-platform/imports/inbox/
```

- 每 **10 分钟**自动扫描一次
- 按**文件内容 SHA-256** 去重（改名重投也会被判重）
- 处理完成后归档到 `imports/processed/`

立即触发扫描：

```bash
curl -X POST http://127.0.0.1:8088/api/imports/scan -H "Content-Type: application/json" -d '{}'
```

### 方式 2：API 直接上传

```bash
curl -X POST http://127.0.0.1:8088/api/imports \
  -F "file=@7703721523349239951.csv" \
  -F "entity=auto"
```

返回示例：

```json
{"job_id":16,"detected_format":"csv","total":2,
 "works_created":1,"comments_created":2,"skipped":0,"errors":[]}
```

---

## 四、字段映射（已适配）

CoolPilot 导出的 CSV 表头是中文，DYP 原先只认 `content`/`text`/`评论`/`评论内容`，现已扩展：

| CoolPilot CSV 列 | DYP 字段 | 说明 |
|---|---|---|
| `昵称` | `author_name` | |
| `抖音号` | 存入 `raw_json` | DYP 无独立列，保留在原始 JSON |
| `UID` | `author_uid` | 原代码已支持（`.lower()` 后 = `uid`） |
| `SEC_UID` | 存入 `raw_json` | |
| `地区` | **`ip_label`** | IP 归属地 |
| `日期` | `create_time` | 支持 `2026-11-03 09:24:13` 格式 |
| `内容` | `content` | **本次新增映射** |
| `点赞` | `digg_count` | |
| （文件名） | `work_aweme_id` | `<aweme_id>.csv` 自动提取 |

---

## 五、对 DYP 的改动（3 个文件）

> DYP 是独立仓库，以下改动需在 `douyin-intelligence-platform` 中单独提交。

### 1. `backend/app/services/importer.py`

| 改动 | 位置 | 说明 |
|---|---|---|
| CSV 中文表头映射 | `_normalize_csv` | 新增 `内容`/`昵称`/`地区`/`点赞`/`日期` 等 |
| 文件名提取作品 ID | `_normalize_csv` | `<aweme_id>.csv` → `work_aweme_id` |
| **自动建占位作品** | `_persist` | 评论带 aweme_id 但作品不存在时自动创建，避免整批 skipped |
| 同步评论计数 | `_upsert_comment` | 新建评论时 `work.comment_count += 1` |

### 2. `backend/app/api/catalog.py`

| 改动 | 说明 |
|---|---|
| 评论返回加 `ip_label` / `author_uid` | 原先数据在库里但 API 未输出 |

### 3. `deploy/docker-compose.yml`

**未改动**。`imports/` 本来就是 bind mount，直接可用。

---

## 六、统一入口

CoolPilot 顶部导航新增 **「深度分析」**，点击新标签打开 DYP（`http://localhost:8088`）。

可通过构建变量覆盖地址：

```bash
# 构建前设置
VITE_DYP_URL=http://your-host:8088
```

代码位置：`dy-frontend/src/components/AppLayout.vue`

---

## 七、验证记录

| 验证项 | 结果 |
|---|---|
| CoolPilot 格式 CSV 导入 | ✅ `total:4, works_created:1, comments_created:4, skipped:0` |
| 地区字段（辽宁/广东/黑龙江） | ✅ 数据库与 API 均正确 |
| 昵称 / 点赞 / 时间 | ✅ 全部正确解析 |
| 作品自动创建 | ✅ `<aweme_id>.csv` → 自动建作品并挂载评论 |
| 评论计数同步 | ✅ 新导入作品 `comment_count=2` |
| 前端入口 | ✅ 构建产物含「深度分析」与 `localhost:8088` |

---

## 八、注意事项

1. **两个系统仍是独立部署**：DYP 变更不影响 CoolPilot，反之亦然
2. **数据是两份**：CoolPilot 的 MySQL 与 DYP 的 PG 各自保存，不自动同步
3. **DYP 无认证**，仅绑定 `127.0.0.1:8088`，不要暴露到公网
4. **评论去重**：DYP 按 `(work_id, platform_comment_id)`；CoolPilot 的 CSV 无 cid，DYP 会按内容哈希生成稳定 ID
5. **删除测试数据**：本次验证导入了 2 个作品（aweme_id 末尾 951/952）与 6 条评论，可自行清理
