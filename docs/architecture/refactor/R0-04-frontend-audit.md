# R0-04 · 前端审计

技术栈：Vue 3.3 + Vite 5 + Element Plus 2.4 + ECharts 5.4 + Pinia + vue-router 4
代码位置：`frontend/src/`（23 个 .vue，其中 13 个页面）

## 1. 当前架构

```
frontend/src/
├── main.js              注册 Element Plus / Pinia / Router / 全局图标
├── App.vue
├── api/{request.js, index.js}   Axios 封装 + 58 个接口方法
├── config/menu.js       侧边栏信息架构（7 组、34 项、12 可用 / 22 规划中）
├── components/          AppLayout（侧边栏+顶栏）及各图表组件
├── router/index.js      15 条路由 + 登录守卫 + 角色守卫
├── stores/user.js       Pinia：token、userInfo、currentVideo
├── utils/useECharts.js
└── views/               13 个页面
```

- API Base：`/api`，Vite dev 代理到 `http://localhost:8080`；生产由 Nginx 反代
- Token：存 Pinia + localStorage，`request.js` 注入 `Authorization` 头（**无 `Bearer` 前缀**）
- 权限：`router.beforeEach` 校验 token；`meta.role` 控制管理员页面

## 2. 页面清单与后端映射

| 页面 | 路由 | 主要功能 | 调用 API | 对应模块（目标） | 状态 | 处理建议 |
|---|---|---|---|---|---|---|
| `DashboardView` | `/dashboard` | 总览大屏 | `analysisApi.{region,sentiment,sankey,trend,wordcloud,summary,refresh}` | analytics | 已用 | 复用 |
| `OverviewView` | `/overview` | 视频维度统计 | `overviewApi.get`、`analysisApi.videoList` | analytics | 已用 | 复用 |
| `CommentListView` | `/comments` | 评论检索/楼中楼 | `commentApi.{list,tree,search}` | content | 已用 | 复用 |
| `CollectView` | `/collect` | 提交链接采集 | `collectApi.{start,upload}` | collection | 已用 | 复用 |
| `TaskCenterView` | `/tasks` | 任务状态/日志/重试 | `collectApi.{taskDetail,taskLogs,cancel,retry}` | collection | 已用 | 复用 |
| `CleanView` | `/clean` | 清洗执行/导出 | `cleanApi.{getFiles,run,exportFiles}` | content | 已用 | 复用 |
| `KeywordView` | `/keywords` | 关键词与发现 | `keywordApi.{create,disable,discover}`、`collectApi.start` | source | 已用（**后端链路无数据**） | 保留，待验证 |
| `InsightView` | `/insight` | 评论级/视频级/对比 | `aiApi.{analyzeComments,commentSummary,insights,task}`、`reportApi.create` | analysis | 已用 | 复用，支持 `?tab=` |
| `ReviewView` | `/review` | 人工复核 | `reviewApi.{queue,stats,submit}` | analysis | 已实现未使用 | 复用 |
| `ReportView` | `/reports` | 报告与导出 | `reportApi.{list,detail,exportMd,file}` | analytics | 已用 | 复用 |
| `ProfileView` | `/profile` | 个人信息/改密 | `userApi.{info,updatePassword}` | system | 已用 | 复用 |
| `UserManageView` | `/admin/users` | 用户管理 | `adminApi.{listUsers,deleteUser}` | system | 已用 | 复用（响应结构已兼容） |
| `LoginView` / `RegisterView` | `/login` `/register` | 登录/注册 | `userApi.{login,register}` | system | 已用 | 复用 |

## 3. 菜单树（`config/menu.js`，7 组 34 项）

| 组 | 可用项 | 规划中项 |
|---|---|---|
| 工作台 | 获客驾驶舱、待处理事项 | 今日需求 |
| 数据采集 | 关键词管理、采集任务、采集记录、数据清洗 | 数据源管理、平台账号 |
| 内容洞察 | 内容库、评论库 | 用户公开资料、需求信号、热点趋势 |
| AI 分析 | 分析任务、需求分类 | 意向分析、线索评分、产品匹配、分析规则 |
| 智能获客 | — | 线索池、线索审核、跟进记录、转化记录 |
| 数据分析 | 分析报告 | 渠道分析、需求趋势、产品机会、获客效果 |
| 系统管理 | 用户与权限 | 模型配置、任务配置、数据保留策略、操作审计 |

统计：**12 项可用 / 22 项规划中**（规划项菜单禁用并标「规划中」）。

## 4. 前后端不一致与问题

| 问题 | 说明 | 影响 |
|---|---|---|
| 无 API 版本前缀 | 后端 `/api/...`，目标 `/api/v1/...` | R6 需全量改 `api/index.js` |
| `review` 页面无数据 | 后端 `analysis_review` 0 行、AI 复核未实际使用 | 页面可用但无内容可审 |
| `keyword` 页面链路未跑通 | `keyword`/`discovery_task` 0 行，关键词搜索能力未验证 | 用户使用会得到空结果 |
| 规划中菜单占 65% | 22/34 项不可点 | 演示时需说明；R7 逐步启用 |
| 分页字段依赖 | 前端读 `res.data.records` / `res.data.total` | 后端已保持该结构（`PageResult`），**无需改动** |
| 无前端测试 | 0 个单元/E2E 测试 | 重构后需人工回归 |

## 5. 需要补充的页面（对应目标模块）

| 模块 | 缺失页面 |
|---|---|
| source | 数据源管理、平台账号 |
| lead | 线索池、线索审核（真正意义上的线索，非分析复核）、跟进记录 |
| conversion | 产品匹配、转化记录 |
| analytics | 渠道分析、需求趋势、产品机会、获客效果 |
| system | 模型配置、任务配置、数据保留策略、操作审计 |
| 工作台 | 今日需求（需求信号聚合视图） |

## 6. 结论

前端**结构与新架构方向一致**（7 组菜单已按业务中心组织），主要工作是：
1. API 路径适配 `/api/v1/{module}/...`
2. 补齐 lead / conversion / source 三个模块的页面
3. 收敛 `api/index.js`（58 个方法按模块分组，删除废弃项）

无需推倒重写，组件与图表能力可全部复用。
