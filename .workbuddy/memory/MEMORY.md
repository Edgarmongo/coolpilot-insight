# 项目长期记忆 — CoolPilot Insight

## 定位
AI 内容洞察平台（原 DY-Comment）。Spring Boot 4.1.2(JDK21) + Vue3 + Python(Flask/jieba/DrissionPage) + MySQL8，
Docker Compose 四服务：`mysql` / `flask`(跑的是 **ai-service**) / `backend`(内置 Python+Chrome) / `frontend`(Nginx)。
远程：`git@github.com:Edgarmongo/coolpilot-insight.git`（origin），旧仓库为 `upstream`。

## 架构基线（2026-11-04 落地，2026-11-16 更新）
- **Flyway 是唯一结构入口**（core + flyway-mysql 两个依赖缺一不可）。
  V1~V30 建 25 张表，39 外键 / 23 唯一键 / 6 CHECK。禁 `baselineOnMigrate`，禁 initdb 挂载。
- `analysis_result` 已废弃；`analysis_result` 的统计字段历史上从未被读取。
- 关键表：`video`(内部id + aweme_id)、`comment`(cid + dedup_key 双唯一键)、
  `collection_task/_item/_log/_checkpoint`、`analysis_profile/_task/_batch/_item`、
  `comment_analysis`、`video_insight`、`analysis_evidence`、`report/_version`、`usage_record`。
- 前端页面：总览 / 评论 / 采集 / **任务** / 清洗 / **关键词** / **洞察** / **报告** / 用户 / 管理。

## 硬约束（踩过的坑，勿重犯）
1. **MyBatis-Plus 默认 NOT_NULL**：置 null 必须用 `LambdaUpdateWrapper.set(f, null)`；
   `entity.setX(null)+updateById` 不写库（曾导致 `active_flag` 永久占用）。
2. `Py_Data` 是 COPY 进镜像，仅 `Py_Data/Datas` 是 bind mount。
   改 Python 脚本必须重建 backend；临时脚本放 Datas 可直接跑。
3. 拦截器 `excludePathPatterns` 不能随意加：`/api/analysis/summary` 曾被排除，
   导致上下文为空 + 未登录可访问。
4. 调度线程无 HTTP 上下文，调用清洗前须显式注入 `RequestContext`（任务发起人身份）。
5. `app.admin-init-password` 需在 application.yml 显式映射 `ADMIN_INIT_PASSWORD`。
6. Maven 官方源不可达，本地/容器均用阿里云 https settings（`-s` 指定）。
7. 容器映射 MySQL→3307、前端→5173（本机 3306/3000 被占）。
8. Docker 挂载在 Windows Git Bash 下需 `MSYS_NO_PATHCONV=1`。

## 已验证事实
- 抖音评论接口**返回 cid**（19/19），楼中楼 `reply_comment` 有效。
- 匿名态采集上限约 3 页 15~20 条；二次采集靠双唯一键幂等，不产生重复。
- 无 `DEEPSEEK_API_KEY` 时 AI 优雅降级，不扣费。

## 授权模型（三层）
L1 视频基础信息公开；L2 评论需 管理员 / 任务发起人 / `video_grant`；L3 分析结果跟随 L2。
唯一判定入口 `GovernanceService`，Python 侧不再自行做 user_id 过滤。

## AI 全链路（2026-11-04 已实测通过）
DeepSeek Key 有效。评论级 19/19 成功；视频级洞察落 26 条证据；报告 PDF(3页)/XLSX(8 Sheet) 正常。
**登录接口字段是 `phone` 不是 `username`**，填错会报「账号或密码错误」。
`analysis_task` 的 token/成本必须按 usage_record 分项聚合，早期用 `tokens/2` 对半拆是错的。

## 迁移与表结构
Flyway 是唯一入口，当前 `V1~V30`（V9 source 模块 / V20 Run+Execution / V28 账号授权 / V30 账号权限码）。
V8 给 `analysis_evidence` 补 `source_level`（实体有、V4 建表漏），缺它会让评论级分析整批 FAILED。
改表前后跑 `tools/check_schema_drift.py`（实体字段转 snake_case vs information_schema）。

## 能力模型 SOURCE-00~03（2026-11-16）
- 三态模型：**Desired(Java `capability_json`) / Observed(Python Registry) / Effective(两侧核对)**。
- Python 侧 Registry 在 `collection-service/app/registry/`；契约文档
  `docs/architecture/collection/SOURCE-02-CONTRACT.md` + `SOURCE-02-capability-schema.json`。
- **能力 = 声明 + 函数绑定**：`adapterFunction` 解析不到真实可调用函数 ⇒ 该能力不进入 Observed 清单。
- **三层存在性要分清**：函数存在 / 函数可调用 / 函数执行成功。Registry 只证明前两层；
  第三层靠 `app/registry/verification_evidence.json`（由 `tools/verify_capability.py` 实跑产出）。
  代码里的声明一律从 `UNVERIFIED` 起步。
- **枚举纪律**：`implementationStatus` 六值（UNVERIFIED/IMPLEMENTED/VERIFIED/DEGRADED/DISABLED/UNSUPPORTED）、
  `effectiveStatus` 八值。`NOT_LOADED` 是适配器加载状态，`INVALID_IMPLEMENTATION` 是诊断原因码，
  **都不是状态枚举**——别为了凑场景说明表扩枚举。
- 已验证：DOUYIN_PUBLIC 的 COMMENT/COMMENT_REPLY/CONTENT_DETAIL = VERIFIED（有真实证据）；
  **KEYWORD_SEARCH 恒为 UNVERIFIED**（无验证器，风控未知）。
- 生产采集仍走**子进程**：`python comment.py <id> <max>` → CSV `<aweme_id>.csv`（Java 读文件，
  不读 stdout）；`search.py` 的 stdout 协议 `VIDEO|aweme_id|title|author|likes|url` 被 DiscoveryService 解析。
  这三条是硬契约，改脚本时不得破坏。
- **collection-service 的 HTTP 服务当前没有容器在跑**（compose 里 `flask` 容器跑的是 ai-service）。
  `AccountVerificationService` 的探活因此实际探的是 ai-service —— 已知缺陷，留给 SOURCE-04。

## 快速改后端（免重建镜像）
`mvn -s deploy/docker/maven-settings.xml -DskipTests compile` → zipfile 替换 `/app/app.jar` 里对应
`.class`（迁移文件放 `BOOT-INF/classes/db/migration/`）→ `docker restart cpi-backend`。
重建后用 `zipfile.testzip()` 校验，正常应有 65 个 `BOOT-INF/lib/` 条目。
改 `collection-service/` 的 Python 脚本必须 `docker compose build backend`（代码是 COPY 进镜像，
只有 `collection-service/datas` 是 bind mount）；临时验证可直接 `docker cp collection-service/. cpi-backend:/app/collection-service`。

## 待验证
`douyin_search.py` 真实关键词搜索（风控未知）；`file_asset` 未接入上传落库；
collection-service 进程化方案（SOURCE-04）。
