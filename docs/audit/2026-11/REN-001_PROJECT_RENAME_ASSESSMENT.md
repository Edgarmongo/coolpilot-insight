# REN-001 · 工程更名影响评估（CoolPilot Insight）

**任务编号**：REN-001
**执行模式**：READ ONLY（仅盘点与方案设计）
**盘点日期**：2026-11-03
**目标**：评估将 `douyin_opinion_analysis` / `DY-Comment` 更名至 **CoolPilot Insight** 的完整影响
**结论**：**方案可行，但有 2 个前置阻塞**（许可证缺失、来源权属未确认）
**授权状态**：**未授权任何修改**

> 证据等级：**A** 文件/命令直接确认｜**B** 由代码结构推断｜**C** 尚未验证

---

## 零、两个必须先解决的前置问题

在讨论命名之前，有两个事实会影响「能否更名」而不只是「如何更名」。

### 0.1 阻塞一：项目**没有 LICENSE 文件**（A 级）

```
$ ls LICENSE* COPYING* NOTICE*
→ 无匹配
$ grep -ri "license|copyright|MIT|Apache|GPL" --include="*.md" .
→ 仅误匹配 README 中的 "LIMIT 20"
$ grep -ri "copyright|@author|@license" --include="*.java" --include="*.py" .
→ 无结果
```

**事实**：全项目**无任何开源许可证声明、无版权头、无 NOTICE 文件**。

**影响**：
- 任务卡要求「不删除原开源项目的 LICENSE」——但**它不存在**，不存在「保留」的对象
- 无许可证声明的作品，按著作权法默认 **保留所有权利（All Rights Reserved）**，而非自动进入公有领域或默认可商用
- 更名并将代码作为自有产品发布，属于**需要权利人授权**的行为

**这不是技术问题，是需要你确认权属后 decides 的法律问题。** 本报告不对合法性下判断，仅如实列出事实。

**建议动作**（需你决策）：
1. 若你与原始作者为同一人 → 应补一份 LICENSE（如 Apache-2.0 或 MIT）明确授权范围
2. 若为他人代码 → 需取得书面授权后，方可作为 CoolPilot 产品发布
3. 无论哪种，**建议保留 `NOTICE` 说明代码来源**

### 0.2 阻塞二：最早提交作者与仓库所有者不一致（A 级 / C 级推断）

```
$ git log --reverse --format="%h %ad %an %s" --date=short | head -5
b14e8c1 2026-08-21 guo'jun'ru  feat:抖音舆情分析系统完整代码
777e6c3 2026-08-21 suhuideyu  Delete Py_Data/clean/.env
cb367e9 2026-08-21 suhuideyu  Delete dy-comment笔记.txt
607f8a2 2026-08-21 suhuideyu  Delete q.md
c59fba9 2026-08-22 suhuideyu  Update README.md

$ git remote -v
origin  git@github.com:suhuideyu/douyin_opinion_analysis.git
```

| 观察 | 事实等级 | 说明 |
|---|---|---|
| 首发提交作者为 `guo'jun'ru`，内容是「完整代码」 | **A** | 明确 |
| 后续提交作者为 `suhuideyu`，内容为删文件/改 README | **A** | 明确 |
| 当前 remote 属于 `suhuideyu` | **A** | 明确 |
| 仓库总提交仅 **5 条**，且集中在 2 天内 | **A** | 说明是重新初始化后的仓库，非完整上游历史 |
| `guo'jun'ru` 与 `suhuideyu` 是否同一人 | **C** | **未验证**，可能是同一人的两个 Git 身份 |

**推断（B）**：这更像「A 提供代码 → B 接手维护」的模式，而非普通 fork（普通 fork 会保留上游全部提交历史）。

**建议动作**：确认 `guo'jun'ru` 与你的关系。这将直接决定 0.1 的处理方式。

---

## 一、原工程名称及来源

| 项 | 当前值 | 证据 |
|---|---|---|
| 本地目录 | `douyin_opinion_analysis` | A |
| Git remote | `git@github.com:suhuideyu/douyin_opinion_analysis.git` | A |
| 当前分支 | `main` | A |
| HEAD | `c59fba9 Update README.md` | A |
| 提交总数 | 5 | A |
| 产品显示名 | **DY-Comment** | A（README 标题） |
| 产品副标题 | 抖音多视频评论分析与可视化平台 | A |
| 在制品规模 | 已修改 26 文件 / 未跟踪 42 项 | A |

---

## 二、新旧名称映射表

| # | 位置 | 当前值 | 目标值 | 风险 | 证据 |
|---|---|---|---|---|---|
| 1 | README 标题 | `DY-Comment — 抖音多视频评论分析与可视化平台` | `CoolPilot Insight — 智能内容洞察平台` | 🟢 | A |
| 2 | `index.html` `<title>` | 抖音多视频评论分析与可视化平台 | CoolPilot Insight | 🟢 | A |
| 3 | `LoginView.vue` 副标题 | 抖音多视频评论分析可视化平台，一站式洞察评论数据 | AI-Powered Content Intelligence Platform | 🟢 | A |
| 4 | `RegisterView.vue` 文案 | 注册账号，开始分析你的抖音评论数据 | 通用化改写 | 🟢 | A |
| 5 | `package.json` name | `dy-frontend` | `coolpilot-insight-web` | 🟢 | A |
| 6 | Maven `name` | `DY-Comment` | `coolpilot-insight-server` | 🟢 | A |
| 7 | Maven `artifactId` | `dy-comment` | `coolpilot-insight-server` | 🟡 影响 JAR 名 | A |
| 8 | Maven `groupId` | `com.dy` | `com.coolpilot` | 🔴 106 处 | A |
| 9 | Java package | `com.dy.comment.*` | `com.coolpilot.insight.*` | 🔴 41 文件 | A |
| 10 | 启动类 | `DyCommentApplication` | `CoolPilotInsightApplication` | 🟡 | A |
| 11 | Docker project | `name: dy-comment` | `coolpilot-insight` | 🔴 **卷名随之变** | A |
| 12 | 容器名 | `dy-mysql`/`dy-flask`/`dy-backend`/`dy-frontend` | `cpi-*` | 🟡 | A |
| 13 | 网络名 | `dy-net`（实际 `dy-comment_dy-net`） | `cpi-net` | 🟡 | A |
| 14 | 卷名 | `dy-comment_mysql-data` / `dy-comment_chrome-data` | 建议**保持原名** | 🔴 **数据** | A |
| 15 | 数据库名 | `dy_comment` | `coolpilot_insight`（建议延后） | 🔴 | A |
| 16 | JWT secret 默认值 | `<REDACTED-JWT-SECRET>` | `coolpilot_insight_...` | 🟡 会使存量 token 失效 | A |
| 17 | Dockerfile JAR 引用 | `target/dy-comment-1.0.0.jar` | `target/coolpilot-insight-server-1.0.0.jar` | 🟡 必须同步 | A |
| 18 | 镜像名 | `dy-comment-backend` 等 | `coolpilot-insight-backend` 等 | 🟢 | A |
| 19 | Python 项目名 | **无**（仅 requirements.txt，无 pyproject） | 可选新增 | 🟢 | A |
| 20 | Flask app 名 | `Flask(__name__)`（无显式名） | 可选 `Flask("coolpilot-insight-ai")` | 🟢 | A |

---

## 三、Java 更名影响

### 3.1 现状（A 级）

```xml
<groupId>com.dy</groupId>
<artifactId>dy-comment</artifactId>
<version>1.0.0</version>
<name>DY-Comment</name>
<description>抖音多视频评论分析与可视化平台 - 后端</description>
```

```
package com.dy.comment.{annotation,config,controller,dto,entity,...}
启动类: com.dy.comment.DyCommentApplication
```

### 3.2 影响量化

| 项 | 数量 | 说明 |
|---|---|---|
| `com.dy` 出现 | **106 处** | 含 package 声明、import、XML namespace |
| 涉及文件 | **41 个** (.java + .xml) | |
| 启动类改名 | 1 个文件 | 文件名 + 类名 |
| Mapper XML namespace | 需同步 | 若有 |

### 3.3 分层评估

| 层级 | 内容 | 建议 | 理由 |
|---|---|---|---|
| **L1 安全** | Maven `name`、`description` | ✅ 第一批改 | 纯元数据，零影响 |
| **L2 低危** | `artifactId` | ⚠️ 第二批改 | 需同步 Dockerfile 的 JAR 路径 |
| **L3 中危** | `groupId` + package + 启动类 | ❌ **第四批，单独审批** | 106 处 + 41 文件；改错会导致编译全红 |

**`com.dy` 改名的具体风险**：
1. IDE 需整体重构（rename package），Git diff 会显示 41 个文件全改，**review 困难**
2. MyBatis XML 的 `namespace` 必须同步，漏一处则启动报 `Invalid bound statement`
3. 若 `@MapperScan("com.dy.comment.mapper")` 未同步 → Mapper 注入失败
4. `application.yml` 中若有 `type-aliases-package` 需同步
5. **建议在前端/文档更名完成、业务稳定后再做，且单独一次提交**

---

## 四、Python 更名影响

### 4.1 现状（A 级）

```
Py_Data/
├── Datas/                  # CSV 产出（bind mount）
├── clean/                  # 清洗 + Flask 服务
├── config_browser.py
├── douyin_comment.py       # 采集脚本
└── verify_region_map.py
```

- **无** `pyproject.toml` / `setup.py` / `__init__.py` 包结构
- `requirements.txt` 无项目元数据
- Flask：`app = Flask(__name__)`，无显式服务名
- 启动：`CMD ["python", "clean/wordcloud_service.py"]`

### 4.2 影响评估

| 项 | 影响 | 建议 |
|---|---|---|
| 项目名 | **无**（不存在该概念） | 可选新增 `pyproject.toml` |
| 包导入路径 | **无**（脚本直接执行，无包导入） | 不涉及 |
| 文件名 `douyin_comment.py` | 若改名需同步 Java 侧 `CollectServiceImpl` 的调用 | ⚠️ 观察 |
| Flask 服务名 | 无显式名 | 可选加 `Flask("coolpilot-insight-ai")` |

**`douyin_comment.py` 的调用点（A 级）**：
```java
// CollectServiceImpl 中以字符串形式引用
pb = new ProcessBuilder(pythonExe, "douyin_comment.py", videoId, maxComments);
```
改文件名必须同步改 Java 字符串常量，否则采集直接失败。

> **结论**：Python 侧更名成本极低（几乎无项目名概念），但**不建议改 `douyin_comment.py` 文件名**——它是「抖音采集器」的准确描述，与产品品牌无关。若未来支持多平台，可改为 `collector_douyin.py`，属功能重构而非更名。

---

## 五、Vue 更名影响

### 5.1 现状（A 级）

| 位置 | 当前值 |
|---|---|
| `package.json` name | `dy-frontend` |
| `index.html` `<title>` | 抖音多视频评论分析与可视化平台 |
| `LoginView.vue` 副标题 | 抖音多视频评论分析可视化平台，一站式洞察评论数据 |
| `RegisterView.vue` | 注册账号，开始分析你的抖音评论数据 |
| favicon | **无**（public 目录无 favicon/logo 文件） |
| 侧边栏/导航 | **无独立 Layout 组件**（`src/` 下无 layouts 目录） |

### 5.2 影响评估

| 项 | 数量 | 风险 | 建议 |
|---|---|---|---|
| `package.json` name | 1 + package-lock 2 处 | 🟢 | 第一批 |
| 页面标题 | 1 处（index.html） | 🟢 | 第一批 |
| 登录/注册文案 | **2 个文件** | 🟢 | 第一批 |
| favicon | 无文件，需**新建** | 🟢 | 可延后（需设计资源） |
| `抖音` 字样（产品文案） | **10 处** | 🟡 | 需逐条判断是否保留 |

**关于「抖音」字样的处理原则**：
- **产品定位文案**（如「抖音评论分析平台」）→ 应改为「内容洞察平台」
- **功能描述**（如「选择抖音视频链接」）→ **保留**，因为当前确实只支持抖音
- 区分标准：描述「产品是什么」→ 改；描述「用户操作什么」→ 留

**无 Layout 组件意味着**：目前品牌名只出现在登录页和标题栏，**改名覆盖面很小**，这是有利条件。

---

## 六、Docker 更名影响

### 6.1 现状（A 级）

```yaml
# docker-compose.yml:13
name: dy-comment                    # ← Compose project name

services:
  mysql:    container_name: dy-mysql
  flask:    container_name: dy-flask
  backend:  container_name: dy-backend
  frontend: container_name: dy-frontend

volumes:
  mysql-data:
  chrome-data:

networks:
  dy-net:
```

**实际运行状态**：

| 类型 | 实际名称 |
|---|---|
| 容器 | `dy-mysql` / `dy-flask` / `dy-backend` / `dy-frontend` |
| 镜像 | `dy-comment-backend` / `dy-comment-flask` / `dy-comment-frontend` |
| 网络 | `dy-comment_dy-net` |
| 卷 | `dy-comment_mysql-data` / `dy-comment_chrome-data` |

### 6.2 🔴 最高风险点：改 `name:` 会导致卷名变化 → 数据「消失」

**机制**（A 级）：
```
Volume 全名 = <Compose project name>_<volume 逻辑名>
当前: dy-comment  +  mysql-data  → dy-comment_mysql-data
改名后: coolpilot-insight + mysql-data  → coolpilot-insight_mysql-data
```

**后果链**：
```
1. 新卷被创建（空）
2. MySQL 在新空卷上初始化 → 建库建表 → 但**没有历史数据**
3. 旧卷 dy-comment_mysql-data 仍存在，但**不再被挂载**
4. 用户看到的是「数据全部丢失」
5. Chrome 登录态卷同理 → 需重新登录
```

**这不是数据被删，而是「挂载错了卷」。** 但业务视角等同于丢失。

### 6.3 必须的保护方案

**方案 A（推荐）：给卷显式固定名称**

```yaml
volumes:
  mysql-data:
    name: dy-comment_mysql-data      # 锁定，不随 project name 变
  chrome-data:
    name: dy-comment_chrome-data     # 锁定
```

这样改 `name: coolpilot-insight` 后，卷名**保持不变**，数据无缝衔接。

**方案 B：迁移卷**（复杂，不推荐）
```
docker run --rm -v dy-comment_mysql-data:/from -v coolpilot-insight_mysql-data:/to alpine cp -av /from/. /to/
```

**方案 C：保持 `name: dy-comment` 不变**
只改显示名，Docker 标识全不动。风险最低，但品牌不彻底。

> **建议**：采用**方案 A**。改名前先给两个卷加 `name:` 锁定，验证 `docker compose up` 后仍挂旧卷，再改 project name。

### 6.4 其他 Docker 风险

| 项 | 风险 | 说明 |
|---|---|---|
| `container_name` 硬编码 | 🟡 | 改名后旧容器需 `down` 再 `up`；任何脚本/文档引用 `dy-backend` 会失效 |
| 网络名变化 | 🟡 | 容器间通过 service 名通信（`mysql`/`flask`），**不受 project name 影响**；但 `dy-net` 显式改名需三处同步 |
| 镜像名变化 | 🟢 | 重建后旧镜像成悬空镜像，`docker image prune` 可清 |
| **服务名不改** | ✅ | `mysql`/`flask`/`backend`/`frontend` 是 service 名，容器内部 DNS 依赖它，**绝对不能改** |

---

## 七、数据库更名影响

### 7.1 现状（A 级）

| 项 | 值 | 位置 |
|---|---|---|
| 数据库名 | `dy_comment` | `application.yml:5` 默认值；compose `MYSQL_DATABASE` |
| JWT secret 默认值 | `<REDACTED-JWT-SECRET>` | `application.yml:24` |
| 表数量 | 3（user/comment/analysis_result） | A |
| 数据量 | comment 14 行、user 3 行 | A |

### 7.2 改库名的代价

| 步骤 | 说明 |
|---|---|
| 1 | MySQL **不支持** `RENAME DATABASE`（5.1.23 后移除） |
| 2 | 只能 `CREATE DATABASE coolpilot_insight` + `mysqldump` + `import` |
| 3 | 改 `application.yml` 的 `DB_NAME` 默认值 + compose 的 `MYSQL_DATABASE` |
| 4 | Flask 侧 `DB_CONFIG` 也要同步（`wordcloud_service.py`） |
| 5 | 重启全部服务 |

**风险**：🟡 中。有完整备份可回滚，但涉及停服窗口。

### 7.3 建议：延后到第四批，或干脆不改

**理由**：
1. 数据库名是**纯内部标识**，用户永远看不到
2. 改名唯一收益是「技术上彻底统一」，无业务价值
3. 与 `com.dy` 改名叠加会放大停服风险
4. 若未来做多租户/多环境，届时统一规划更合理

**若确需改**，建议与 `com.dy` 同批做（反正都要停服重建）。

---

## 八、Git 仓库更名影响

| 项 | 现状 | 建议 |
|---|---|---|
| 本地目录 | `douyin_opinion_analysis` | 可改为 `coolpilot-insight`（`git mv` 不适用，需操作系统重命名） |
| remote URL | `suhuideyu/douyin_opinion_analysis` | 先在 GitHub 改名仓库，再 `git remote set-url` |
| 提交历史 | 5 条 | **不修改**（保护规则） |
| 未提交修改 | 26 改 + 42 未跟踪 | ⚠️ **改名/移动目录前必须先提交或 stash** |

**关键提醒**：
- GitHub 仓库改名后，旧 URL 会**自动重定向**（保留 1 年），但 `git remote` 不会自动更新
- 若你只是本地目录改名，`git` 无感知，无需操作
- **不要在 26 个未提交修改 + 42 个未跟踪文件的状态下移动目录**，容易丢失工作区

---

## 九、原始开源许可证与版权保留情况

### 9.1 结论：**无任何需要保留的许可证文件，因为不存在**（A 级）

| 检查项 | 结果 |
|---|---|
| `LICENSE` / `LICENSE.md` | ❌ 不存在 |
| `COPYING` | ❌ 不存在 |
| `NOTICE` | ❌ 不存在 |
| README 中的协议声明 | ❌ 无 |
| 源码文件头 `@author` / `@license` | ❌ 无 |
| 第三方依赖许可 | ✅ 由各包自带（Maven/npm/pip），不受影响 |

### 9.2 这意味着什么

| 情形 | 后果 |
|---|---|
| 代码为你本人原创（`guo'jun'ru` 是你的另一身份） | 你拥有全部权利，可自由更名。**建议补一份 LICENSE** 以便未来开源或商用 |
| 代码来自他人 | 无协议 = 保留所有权利，**更名并作为自有产品发布需获授权** |

### 9.3 建议补充的文件（需你决策后添加）

**`NOTICE`（无论哪种情形都建议有）**
```
CoolPilot Insight（智能内容洞察平台）
本项目初始代码来自 douyin_opinion_analysis（作者：guo'jun'ru，2026-08）。
后续由 <你的名称/组织> 维护与扩展。
原始代码未附带明确开源许可证声明。
```

**`LICENSE`**：若你有权处置，建议 Apache-2.0（含专利授权，适合商业产品）；若希望宽松，MIT。

> ⚠️ 这两份文件属于**新增法律文件**，需你明确授权后由我生成。

---

## 十、潜在运行风险清单

| # | 风险 | 级别 | 触发条件 | 后果 | 缓解 |
|---|---|---|---|---|---|
| R1 | **改 Compose project name 导致挂载空卷** | 🔴 高 | 未锁定 volume name 就改 `name:` | 数据「全部丢失」（实为挂错卷）+ 登录态丢失 | 先加 `volumes.*.name` 锁定 |
| R2 | `com.dy` 改名遗漏 MyBatis `namespace` | 🔴 高 | 批量替换未覆盖 XML | 启动报 `Invalid bound statement` | 改后全量编译 + 冒烟 |
| R3 | `@MapperScan` / `type-aliases-package` 未同步 | 🟡 中 | 同上 | Mapper 注入失败 | 全文搜索确认 |
| R4 | JAR 名变化但 Dockerfile 未同步 | 🟡 中 | 改 `artifactId` 未改 `COPY` | 镜像构建失败 | 同批修改 |
| R5 | JWT secret 默认值变更 | 🟡 中 | 改 `<REDACTED-JWT-SECRET>` | **所有存量 token 失效**，用户被登出 | 仅在无会话时改，或提前公告 |
| R6 | 数据库改名中途失败 | 🟡 中 | dump/restore 出错 | 停服时间延长 | 先备份 + 演练 |
| R7 | `container_name` 改名后旧容器残留 | 🟢 低 | 未 `down` 直接 `up` | 名称冲突报错 | 先 `docker compose down` |
| R8 | 前端 `抖音` 文案误改导致功能描述失真 | 🟢 低 | 全局替换 | 用户困惑（「是不是不支持抖音了」） | 逐条人工判断 |
| R9 | 未提交修改在改名中丢失 | 🟡 中 | 移动目录/切分支 | 工作成果丢失 | 先提交或 stash |
| R10 | `douyin_comment.py` 改名未同步 Java 字符串 | 🟡 中 | 改文件名 | 采集直接失败 | 不建议改此文件名 |

---

## 十一、建议修改文件清单（按风险分级）

### 11.1 第一批：产品展示名称（🟢 低风险，可立即做）

| 文件 | 修改内容 |
|---|---|
| `README.md` | 标题、简介、品牌名 |
| `dy-frontend/index.html` | `<title>` |
| `dy-frontend/src/views/LoginView.vue` | 副标题文案 |
| `dy-frontend/src/views/RegisterView.vue` | 注册文案 |
| `dy-frontend/public/favicon.*` | **新建**（需设计资源，可延后） |

**共 4 个文件**（favicon 待资源）。

### 11.2 第二批：工程名称（🟢 低风险）

| 文件 | 修改内容 | 注意 |
|---|---|---|
| `dy-backend/pom.xml` | `<name>`、`<description>` | 安全 |
| `dy-backend/pom.xml` | `<artifactId>` → `coolpilot-insight-server` | **必须同步 Dockerfile** |
| `docker/backend.Dockerfile:91` | `COPY ... dy-comment-1.0.0.jar` → 新名 | 与上一条同批 |
| `dy-frontend/package.json` | `"name"` → `coolpilot-insight-web` | 需删 `package-lock.json` 重新 install，或手工同步 |
| `docker-compose.yml` | 镜像名（自动随 service 名） | 无需显式改 |

**共 4 个文件**。

### 11.3 第三批：部署标识（🟡 中风险）

| 文件 | 修改内容 | 前置条件 |
|---|---|---|
| `docker-compose.yml:13` | `name: dy-comment` → `coolpilot-insight` | **必须先锁定 volume name** |
| `docker-compose.yml` volumes | 加 `name: dy-comment_mysql-data` / `..._chrome-data` | **先做这一步** |
| `docker-compose.yml` | `container_name: dy-*` → `cpi-*` | 需 `down` 后 `up` |
| `docker-compose.yml` | `dy-net` → `cpi-net`（3 处） | 同上 |
| `docker/` 下 Dockerfile | 若含项目名注释 | 可选 |

**共 2 个文件 + 运行态重启**。

### 11.4 第四批：内部技术标识（🔴 高风险，需单独审批）

| 文件 | 修改内容 | 数量 |
|---|---|---|
| `dy-backend/pom.xml` | `groupId` → `com.coolpilot` | 1 |
| 全部 `.java` | package + import + `@MapperScan` | **41 文件 / 106 处** |
| 全部 Mapper XML | `namespace` | 需确认数量 |
| `DyCommentApplication.java` | 类名 + 文件名 | 1 |
| `application.yml` | `type-aliases-package`（若有）、DB 名、JWT 默认值 | 1 |
| `dy_comment.sql` | 库名（若改） | 1 |
| **数据库** | 改名 + 迁移 | 运行态 |

**共 45+ 文件 + 数据库变更**。建议**独立一次提交**，单独 review。

---

## 十二、建议暂时保留的原有标识

| 项 | 建议 | 理由 |
|---|---|---|
| **Docker service 名**（`mysql`/`flask`/`backend`/`frontend`） | ❌ **永不改** | 容器内 DNS 依赖，改了通信全断 |
| **Docker 卷名** | ⚠️ **保持 `dy-comment_*`** | 数据安全优先；品牌价值为零 |
| **数据库名 `dy_comment`** | ⚠️ 建议延后或不改 | 用户不可见，改造成本高收益低 |
| **`douyin_comment.py` 文件名** | ✅ 建议不改 | 「抖音采集器」的准确描述；改名属功能重构 |
| **功能文案中的「抖音」** | ✅ 保留（10 处中的大部分） | 当前确实只支持抖音，保留是诚实表述 |
| **`com.dy`** | ⚠️ 可改但建议最后做 | 106 处，风险与收益不成比例 |
| **Git 提交历史** | ❌ **绝不修改** | 保护规则 + 追溯来源需要 |

---

## 十三、分阶段实施顺序

```
阶段 0：前置确认（无代码改动）
  ├─ 确认 guo'jun'ru 与你的关系
  ├─ 决定 LICENSE 方案（补 Apache-2.0 / MIT / 不补）
  ├─ 提交或 stash 当前 26 改 + 42 未跟踪
  └─ 打 tag 或创建分支作为回滚锚点
        ↓
阶段 1：产品展示名（4 文件，30 分钟）
  └─ 验证：前端刷新，标题与登录页显示新名
        ↓
阶段 2：工程名（4 文件，1 小时）
  ├─ pom name/artifactId + Dockerfile JAR + package.json name
  └─ 验证：mvn package 成功；docker build 成功
        ↓
阶段 3：部署标识（2 文件 + 重启，1~2 小时）
  ├─ 3a 先加 volume name 锁定 → up → 验证数据仍在
  ├─ 3b 改 project name / container_name / network
  └─ 验证：docker compose ps 全 healthy；数据完整；登录态可用
        ↓
阶段 4：内部标识（45+ 文件 + DB，1~2 天）
  ├─ 4a groupId + package（IDE 重构）
  ├─ 4b Mapper XML namespace
  ├─ 4c DB 改名（可选）
  └─ 验证：编译 + 26 API 冒烟 + 采集全链路
```

**关键顺序约束**：
- 阶段 3 的 3a **必须**在 3b 之前（否则挂载空卷）
- 阶段 4 必须独立提交，且在前三阶段稳定后进行

---

## 十四、备份与回滚方案

### 14.1 改动前必做

| # | 动作 | 命令 |
|---|---|---|
| 1 | 提交工作区 | `git add -A && git commit -m "chore: 更名前快照"` 或 `git stash` |
| 2 | 打 tag | `git tag pre-rename-$(date +%F)` |
| 3 | 数据库全量备份 | `mysqldump --single-transaction --routines --triggers dy_comment > backup_pre_rename_$(date +%F_%H%M).sql` |
| 4 | 验证备份 | `grep -c "CREATE TABLE" backup.sql` 应为 3 |
| 5 | 记录卷状态 | `docker volume ls | grep dy-comment` 存档 |
| 6 | 记录容器配置 | `docker compose config > compose_before.yml` 存档 |
| 7 | **卷数据备份**（额外保险） | `docker run --rm -v dy-comment_mysql-data:/d -v $(pwd):/b alpine tar czf /b/mysql-volume.tar.gz -C /d .` |

### 14.2 回滚路径

| 阶段 | 回滚方式 |
|---|---|
| 阶段 1/2 | `git checkout -- <file>` 恢复单文件 |
| 阶段 3 | `git checkout docker-compose.yml` → `docker compose down` → `up`（卷因已锁定，数据不受影响） |
| 阶段 4 | `git reset --hard pre-rename-tag`（**需你明确授权**，属破坏性操作）→ 重新构建镜像 |
| 数据库 | `mysql -uroot -p dy_comment < backup.sql` |

### 14.3 停服窗口估算

| 阶段 | 停机 |
|---|---|
| 1/2 | 无需停服（仅前端静态资源 + 构建产物） |
| 3 | 需 `down` + `up`，约 **2~3 分钟** |
| 4 | 需重建后端镜像 + 数据库迁移，约 **20~30 分钟** |

---

## 十五、本轮盘点结论

| 验收标准 | 结果 |
|---|---|
| 已完整识别工程中的旧名称 | ✅ 6 类标识，20 处映射 |
| 已形成新旧名称映射表 | ✅ 20 行 |
| 已识别跨模块引用关系 | ✅ Dockerfile↔JAR、Java↔Python 脚本名、compose↔卷名 |
| 已明确哪些名称可安全调整 | ✅ 阶段 1/2 |
| 已明确哪些需额外审批 | ✅ 阶段 4（`com.dy`、DB 名） |
| 已保留原开源项目来源和许可证 | ⚠️ **无许可证可保留**（见 0.1） |
| 已形成可执行的分阶段方案 | ✅ 4 阶段 |
| 无任何代码/配置/DB/Docker/Git 变更 | ✅ 仅只读命令 |

### 需你决策的事项

| # | 事项 | 优先级 |
|---|---|---|
| **1** | `guo'jun'ru` 与你的关系？代码权属如何？ | 🔴 **最高（阻塞）** |
| **2** | 是否补 LICENSE？选哪个协议？ | 🔴 高 |
| **3** | Docker 卷是「锁定旧名」还是「迁移」？ | 🔴 高（决定阶段 3 方案） |
| **4** | 数据库名是否改？何时改？ | 🟡 中 |
| **5** | `com.dy` 是否改？何时改？ | 🟡 中 |
| **6** | 「抖音」文案哪些保留、哪些改？ | 🟢 低 |
| **7** | 是否需要 favicon/Logo 设计资源？ | 🟢 低 |

---

## 十六、READ ONLY 操作记录

| 项 | 状态 |
|---|---|
| 修改任何代码/配置/SQL | **未执行** |
| 修改数据库/结构/数据 | **未执行** |
| 修改 Flyway 迁移 | **未执行** |
| Docker Compose 重建/容器重启/卷删除 | **未执行** |
| Git add/commit/checkout/reset/rebase/merge | **未执行** |
| 修改 Java package / Python import | **未执行** |
| 修改 Maven 依赖 | **未执行** |
| 删除/新建 LICENSE | **未执行**（仅建议） |
| 只读操作 | `git remote/branch/log/status`、`grep`、`ls`、`docker compose ps`、`docker volume ls`、`docker network ls`、`SHOW DATABASES` |
| 新增文件 | 仅本文 `docs/audit/2026-11/REN-001_PROJECT_RENAME_ASSESSMENT.md` |

**本任务仅完成只读盘点和方案设计，不代表授权实际修改工程。**
