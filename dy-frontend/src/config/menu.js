/**
 * 侧边栏信息架构（单一数据源）。
 *
 * 七个业务中心对应产品定位里的获客链路：数据采集 → 需求识别 → 用户画像 → 线索评分 → 客户转化。
 *
 * status 语义：
 *   ready    —— 已有页面，可点击
 *   planned  —— 目标形态中存在、尚未实现；菜单保留占位并禁用，避免「结构缺失」
 *
 * 说明两处对原始 IA 的补充（原 IA 未列出但页面已存在，无处安放会变成孤儿页）：
 *   1. 数据采集 → 数据清洗：属于采集流水线的一环
 *   2. 数据分析 → 分析报告：属于结果输出
 */

export const menuGroups = [
  {
    key: 'workbench',
    title: '工作台',
    icon: 'Odometer',
    items: [
      { title: '获客驾驶舱', path: '/dashboard', icon: 'DataAnalysis', status: 'ready',
        desc: '总量、地区、情感、趋势一屏总览' },
      { title: '今日需求', status: 'planned',
        desc: '当天新发现的需求信号' },
      { title: '待处理事项', path: '/review', icon: 'Finished', status: 'ready',
        desc: 'AI 分析结果的人工复核队列' },
    ],
  },
  {
    key: 'collection',
    title: '数据采集',
    icon: 'Download',
    items: [
      { title: '数据源管理', status: 'planned', desc: '平台接入与能力状态' },
      { title: '平台账号', status: 'planned', desc: '采集账号授权与登录态' },
      { title: '关键词管理', path: '/keywords', icon: 'Search', status: 'ready',
        desc: '关键词与发现任务' },
      { title: '采集任务', path: '/tasks', icon: 'List', status: 'ready',
        desc: '任务状态、重试、执行日志' },
      { title: '采集记录', path: '/collect', icon: 'Download', status: 'ready',
        desc: '提交视频链接并采集评论' },
      { title: '数据清洗', path: '/clean', icon: 'Operation', status: 'ready',
        desc: '去重、情感标注、结构化' },
    ],
  },
  {
    key: 'content',
    title: '内容洞察',
    icon: 'View',
    items: [
      { title: '内容库', path: '/overview', icon: 'TrendCharts', status: 'ready',
        desc: '视频清单与内容维度统计' },
      { title: '评论库', path: '/comments', icon: 'ChatLineSquare', status: 'ready',
        desc: '评论检索与楼中楼查看' },
      { title: '用户公开资料', status: 'planned', desc: '作者公开信息聚合' },
      { title: '需求信号', status: 'planned', desc: '从内容中抽出的需求点' },
      { title: '热点趋势', status: 'planned', desc: '话题与关键词走势' },
    ],
  },
  {
    key: 'ai',
    title: 'AI 分析',
    icon: 'Opportunity',
    items: [
      { title: '分析任务', path: '/insight', icon: 'Opportunity', status: 'ready',
        desc: '评论级分析、视频级洞察、多视频对比' },
      { title: '需求分类', path: '/insight?tab=comments', icon: 'Collection', status: 'ready',
        desc: '意图、痛点、话题的自动归类' },
      { title: '意向分析', status: 'planned', desc: '购买阶段与意向强度（当前合并在需求分类内）' },
      { title: '线索评分', status: 'planned', desc: '需求明确度、产品匹配度等加权评分' },
      { title: '产品匹配', status: 'planned', desc: '需求到课程/服务/源码的匹配' },
      { title: '分析规则', status: 'planned', desc: '分析档案与 Prompt 版本管理' },
    ],
  },
  {
    key: 'leads',
    title: '智能获客',
    icon: 'Aim',
    items: [
      { title: '线索池', status: 'planned', desc: '待跟进线索与分级' },
      { title: '线索审核', status: 'planned', desc: '人工确认与去重合并' },
      { title: '跟进记录', status: 'planned', desc: '联系渠道与结果' },
      { title: '转化记录', status: 'planned', desc: '成交与归因' },
    ],
  },
  {
    key: 'analytics',
    title: '数据分析',
    icon: 'PieChart',
    items: [
      { title: '渠道分析', status: 'planned', desc: '各来源的有效线索率' },
      { title: '需求趋势', status: 'planned', desc: '技术领域需求变化' },
      { title: '产品机会', status: 'planned', desc: '需求与产品目录的缺口' },
      { title: '获客效果', status: 'planned', desc: '漏斗、获客成本、转化率' },
      { title: '分析报告', path: '/reports', icon: 'Document', status: 'ready',
        desc: '报告版本、导出 PDF / XLSX' },
    ],
  },
  {
    key: 'system',
    title: '系统管理',
    icon: 'Setting',
    items: [
      { title: '用户与权限', path: '/admin/users', icon: 'User', status: 'ready', role: 1,
        desc: '账号管理与角色' },
      { title: '模型配置', status: 'planned', desc: '模型网关与调用额度' },
      { title: '任务配置', status: 'planned', desc: '调度与并发策略' },
      { title: '数据保留策略', status: 'planned', desc: '归档与清理周期' },
      { title: '操作审计', status: 'planned', desc: '关键操作留痕' },
    ],
  },
]

/** 扁平化，供面包屑与路由标题使用 */
export const flatItems = menuGroups.flatMap((g) =>
  g.items.map((it) => ({ ...it, groupKey: g.key, groupTitle: g.title })),
)

export function findMenuByPath(path) {
  return flatItems.find((it) => it.path && it.path.split('?')[0] === path) || null
}
