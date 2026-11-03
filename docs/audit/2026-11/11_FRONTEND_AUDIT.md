# 11 · FRONTEND_AUDIT

> Vue 3 前端专项审计

---

## 1. 技术栈与结构

| 项 | 版本/实现 |
|----|----------|
| 框架 | Vue 3.3（`<script setup>` 组合式 API） |
| 构建 | Vite 5 |
| UI | Element Plus 2.4 + @element-plus/icons-vue |
| 图表 | ECharts 5.4 + echarts-wordcloud |
| 状态 | Pinia 2.1 |
| 路由 | vue-router 4.2（history 模式） |
| HTTP | axios（request.js 封装拦截器） |
| 语言 | JS（非 TS），无 `<script lang="ts">` |

```
src/
├── main.js              入口：Pinia/Router/ElementPlus/中文语言包
├── App.vue
├── api/
│   ├── index.js         26 个端点的接口定义（5 个模块）
│   └── request.js       axios 实例 + 请求/响应拦截器
├── router/index.js      6 路由 + 1 管理页 + 路由守卫
├── stores/user.js       Pinia: useUserStore(登录态) + useAppStore(当前视频)
├── utils/useECharts.js  ECharts 组合式封装
├── components/          7 个图表组件 + AppLayout
└── views/               7 个页面 + admin/UserManageView
```

---

## 2. 页面清单（实际存在的 8 个页面）

| 路由 | 视图文件 | 功能 | 对应 API |
|------|---------|------|---------|
| `/login` | `LoginView.vue` | 登录 | `POST /user/login` |
| `/register` | `RegisterView.vue` | 注册 | `POST /user/register` |
| `/dashboard` | `DashboardView.vue` | **总览仪表盘** | 9 个 analysis 接口 |
| `/collect` | `CollectView.vue` | **数据采集** | `collect/start` `collect/status` `collect/upload` |
| `/clean` | `CleanView.vue` | **数据清洗** | `clean/files` `clean/run` `clean/export` |
| `/comments` | `CommentListView.vue` | **评论管理** | `comment/list` `comment/search` `analysis/videos` |
| `/profile` | `ProfileView.vue` | 个人中心 | `user/info` `user/password` |
| `/admin/users` | `admin/UserManageView.vue` | **用户管理** | `admin/users` `admin/user/{id}` |

**任务卡列出的页面核对**：

| 任务卡要求 | 实际 | 结论 |
|-----------|------|------|
| 总览仪表盘 | ✅ DashboardView | 有 |
| 评论管理 | ✅ CommentListView | 有 |
| 视频采集 | ✅ CollectView | 有 |
| 关键词搜索 | ❌ | **无** |
| 用户管理 | ✅ admin/UserManageView | 有 |
| 数据统计 | ⚠️ 部分 | 仅单视频维度，无全局统计 |
| 系统管理 | ⚠️ 部分 | 仅有用户管理，无系统配置/日志 |

---

## 3. 逐页审计

### 3.1 DashboardView（总览仪表盘）

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 页面↔API 对应 | ✅ 9 个 analysis 接口 | A |
| 数据刷新机制 | ⚠️ 仅 `onMounted` 一次 + 手动「刷新分析」按钮，**无轮询** | C |
| 视频切换 | ✅ `onVideoChange` 触发 `loadAll()` 重新拉全部 8 个接口 | A |
| 空数据状态 | ✅ 无视频时显示指引卡片 | A |
| 加载状态 | ❌ **无 loading 态**，图表在数据到达前显示空图 | C |
| 请求异常处理 | ✅ 每个接口独立 try/catch，失败降级为空数组 | A |
| 自动选中视频 | ✅ 加载列表后自动选中第一个 | A |
| 大数据量 | ⚠️ `loadAll` 串行 await 8 个接口，**总耗时 = 各接口之和** | C |

**组件构成**：`StatsCards` / `RegionMap` / `SentimentPie` / `TopComments` / `WordCloud` / `SankeyChart` / `TrendLine`

**RegionMap 特殊说明**：采用**双独立 ECharts 实例**（左条形图 + 右地图），
共用 `COLOR_RANGE` 色阶。地图 GeoJSON 已本地化到 `public/geo/china.json`（离线可用，CDN 兜底）。

### 3.2 CollectView（数据采集）

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 页面↔API | ✅ start / status / upload | A |
| 两种采集方式 | ✅ el-tabs：在线采集 / 本机导入 CSV | A |
| 登录态选项 | ✅ checkbox → `collectMode: 'online-login'` | A |
| 文件上传 | ✅ el-upload 拖拽 + 扩展名校验 | A |
| 状态轮询 | ✅ `setInterval` 3 秒轮询 status | A |
| 轮询清理 | ⚠️ 仅在 SUCCESS/FAILED/异常时 `clearInterval`，**组件卸载时未清理** → 内存泄漏 + 持续请求 | C |
| 状态判定 | ✅ `s === 'SUCCESS'` / `s.startsWith('FAILED')` | A |
| 进度反馈 | ❌ 仅"采集中…"，无条数进度 | C |
| 多视频 | ❌ 单个输入框 | D |
| 视频选择 | ⚠️ 只能手填，无法从关键词发现 | D |

### 3.3 CleanView（数据清洗）

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 页面↔API | ✅ files / run / export | A |
| 文件列表 | ✅ el-table + 多选 | A |
| 分页 | ❌ **无分页**，一次加载全部文件 | C |
| 执行反馈 | ✅ el-alert + `<pre>` 展示 Python 输出 | A |
| 结果持久化 | ❌ 仅当次显示，刷新即失 | C |
| 加载状态 | ✅ `v-loading` + 按钮 loading | A |
| 空状态 | ✅ el-table `empty-text="暂无待处理文件"` | A |
| 文件大小显示 | ⚠️ `(f.length()/1024) + " KB"`，小于 1KB 显示 "0 KB" | 低 |

### 3.4 CommentListView（评论管理）

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 页面↔API | ✅ list / search / videos | A |
| 分页 | ✅ el-pagination | A |
| 搜索 | ✅ 内容关键词搜索（切换到 search 接口） | A |
| 筛选 | ✅ 地区（下拉分组：直辖市/自治区/省份/港澳台/其他）+ 情感 + 排序 | A |
| 自动选中视频 | ✅ 加载列表后自动选中第一个 | A |
| 空状态 | ⚠️ 依赖 el-table 默认空文案 | 低 |
| 加载状态 | ✅ `v-loading` | A |
| 表格列 | 昵称/抖音号、IP归属地、发布时间、评论内容、点赞、情感 | A |
| 大数据量 | ⚠️ `size` 固定 20，未提供页大小切换 | 低 |

### 3.5 LoginView / RegisterView / ProfileView / UserManageView

| 页面 | 检查项 | 结论 |
|------|--------|------|
| LoginView | 表单校验 | ✅ 手机号/密码必填校验 |
| LoginView | 登录后跳转 | ✅ 存 token → 跳 dashboard |
| RegisterView | 密码确认 | ✅ `confirmPassword` 校验 |
| ProfileView | 改密 | ✅ 需原密码，校验一致 |
| UserManageView | 管理员专属 | ✅ 路由 `meta.role=1` + 拦截器双重校验 |
| UserManageView | 列表分页 | ✅ |

---

## 4. 全局机制审计

### 4.1 路由守卫（`router/index.js`）

```javascript
const publicPages = ['/login', '/register', '/dashboard']
const needAuth = !publicPages.includes(to.path)
if (needAuth && !userStore.token) return next('/login')
if (to.meta.role && userStore.userInfo && userStore.userInfo.role < to.meta.role) return next('/dashboard')
```

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 未登录保护 | ✅ 除 3 个公共页外均需登录 | A |
| 角色权限 | ✅ `meta.role` 控制 | A |
| 缺陷 | ⚠️ `userStore.userInfo` 可能为 null（token 存在但 userInfo 丢失），此时 `userInfo.role` 报错或绕过 | C |
| 缺陷 | ⚠️ `/dashboard` 在 publicPages 中 → **未登录可看总览**，而 summary 接口也免鉴权，口径一致但意味着任意人可查任意视频概览 | C |

### 4.2 HTTP 拦截器（`request.js`）

```javascript
request.interceptors.request.use(config => {
  if (userStore.token) config.headers.Authorization = userStore.token
  return config
})
request.interceptors.response.use(
  response => { if (res.code !== 200) { ElMessage.error(...); return Promise.reject(...) } return res },
  error => { 401→登出; 403→权限不足; else→网络错误 }
)
```

| 检查项 | 结论 | 等级 |
|--------|------|------|
| Token 注入 | ✅ 裸 token（无 Bearer 前缀，与后端一致） | A |
| 业务错误处理 | ✅ `code !== 200` 统一 ElMessage 提示 | A |
| 401 处理 | ✅ 清 token + 跳登录 | A |
| 403 处理 | ✅ 提示权限不足 | A |
| 网络错误 | ✅ 提示"网络错误" | A |
| 缺陷 | ⚠️ **Blob 响应的 `res.code` 判断**：`responseType='blob'` 时响应是 Blob，`res.code` 为 undefined → `undefined !== 200` → 误判为错误并 reject！ | **C（潜在 Bug）** |

> 注：导出功能 `exportFiles` 使用 `responseType: 'blob'`，但拦截器在 `response.config.responseType === 'blob'` 时**提前 return response**，故实际不会触发该问题。代码中确有此保护分支（`if (response.config.responseType === 'blob') return response`）。**实际无 Bug**，但该分支位置靠前，易被误改。

### 4.3 状态管理（Pinia）

| 检查项 | 结论 | 等级 |
|--------|------|------|
| 登录态持久化 | ✅ `localStorage` 存 token + userInfo | A |
| 当前视频 | ⚠️ `useAppStore.currentVideo` **仅内存**，刷新丢失 → 已通过各页自动选中第一个修复 | A（已修） |
| 登出清理 | ✅ `logout()` 清 localStorage | A |

### 4.4 错误处理与加载状态汇总

| 页面 | 加载态 | 空状态 | 异常处理 |
|------|-------|-------|---------|
| Dashboard | ❌ | ✅ | ✅ |
| Collect | ✅ | — | ✅ |
| Clean | ✅ | ✅ | ✅ |
| CommentList | ✅ | ⚠️ 默认 | ✅ |
| Login/Register | ✅ | — | ✅ |
| UserManage | ✅ | ✅ | ✅ |

---

## 5. 图表统计口径核对

| 图表 | 数据源 | 口径 | 等级 |
|------|-------|------|------|
| StatsCards | `/analysis/summary` | totalComments / totalUsers / totalLikes / avgLikes | A |
| 地区分布 | `/analysis/region` | percentage = count/total | A |
| 情感分析 | `/analysis/sentiment` | 同上 | A |
| 时间趋势 | `/analysis/trend` | 按 publish_time 日期分组 | A |
| 高赞评论 | `/analysis/top-comments` | ORDER BY likes DESC LIMIT 20 | A |
| 词云 | `/analysis/wordcloud` → Flask jieba | 过滤停用词 + 长度≥2 + 纯数字排除 | A |
| 桑基图 | `/analysis/sankey` | 地区→情感→话题 | A |
| 核心话题 | `/analysis/topic` → DeepSeek | 5 维度统计特征 | A |

**统计口径一致性**：所有 analysis 接口均经 `applyUserFilter()`（管理员看全部，普通用户看自己的），
**但 `/api/analysis/summary` 免鉴权** → 未登录用户查询时 `RequestContext.getUserId()` 为 null，
**不加过滤** → 看到的是全量数据，而登录后的普通用户看到自己的数据。**同一页面不同用户口径不同**。

---

## 6. 大数据量渲染性能（静态分析，未压测）

| 关注点 | 现状 | 风险 |
|-------|------|------|
| 评论列表 | 服务端分页，每页 20 条 | ✅ 无风险 |
| 词云 | Top 100 词 | ✅ 无风险 |
| 桑基图 | 节点数随地区×情感×话题增长 | ⚠️ 中等 |
| 地图 | 35 个省份 | ✅ 无风险 |
| Dashboard 加载 | 串行 8 个接口 | ⚠️ 慢（可并行化） |
| 评论内容 | `show-overflow-tooltip` | ✅ |

**未压测**：本轮按任务卡要求未执行压力测试，以上为静态分析结论。

---

## 7. 前端问题清单

| 编号 | 问题 | 严重度 | 等级 |
|------|------|--------|------|
| FE-01 | 无关键词搜索页面与能力 | P1 | D |
| FE-02 | 无采集任务中心页面 | P1 | D |
| FE-03 | Dashboard 8 个接口串行加载 | P2 | C |
| FE-04 | Dashboard 无加载态，数据到达前显示空图 | P2 | C |
| FE-05 | CollectView 轮询定时器卸载时未清理 | P2 | C |
| FE-06 | 采集进度仅文字，无条数进度 | P2 | D |
| FE-07 | CleanView 文件列表无分页 | P3 | C |
| FE-08 | 清洗结果不持久化，刷新即失 | P3 | D |
| FE-09 | `/dashboard` 与 summary 接口免鉴权，口径与登录后不一致 | P2 | A |
| FE-10 | 路由守卫中 userInfo 可能为 null | P3 | C |
| FE-11 | 无关键词管理、视频管理、多视频对比页面 | P2 | D |
| FE-12 | 无前端单元测试 | P3 | A |

---

## 8. 后续需要新增的页面（对照任务卡判断）

| 页面 | 必要性 | 依赖的后端能力 |
|------|-------|-------------|
| **采集任务中心** | 高 | `collect_task` 表 + 任务列表/详情/日志 API |
| **关键词管理** | 高 | 关键词表 + 搜索 API |
| **视频管理** | 高 | `video` 表 + 视频 CRUD |
| **多视频对比** | 中 | 多视频联合分析 API |
| **AI 用户需求分析** | 高 | 评论级结构化分析 API |
| **分析报告中心** | 中 | 报告持久化 + 导出 |
| **系统管理**（日志/配置） | 低 | 任务日志 + 配置中心 |
