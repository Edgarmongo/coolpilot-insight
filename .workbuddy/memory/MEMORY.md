# 项目长期记忆 — CoolPilot Insight

## 定位
AI 内容洞察平台（原 DY-Comment）。Spring Boot 2.7(JDK8) + Vue3 + Python(Flask/jieba/DrissionPage) + MySQL8，
Docker Compose 四服务：`mysql` / `flask` / `backend`(内置 Python+Chrome) / `frontend`(Nginx)。
远程：`git@github.com:Edgarmongo/coolpilot-insight.git`（origin），旧仓库为 `upstream`。

## 架构基线（2026-11-04 落地）
- **Flyway 8.5.13 是唯一结构入口**（core + flyway-mysql 两个依赖缺一不可）。
  V1~V6 建 25 张表，39 外键 / 23 唯一键 / 6 CHECK。禁 `baselineOnMigrate`，禁 initdb 挂载。
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

## 待验证
`douyin_search.py` 真实关键词搜索（风控未知）；AI 全链路需配置 Key 实测；`file_asset` 未接入上传落库。
