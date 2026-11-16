import request from './request'

export const userApi = {
  register: (data) => request.post('/user/register', data),
  login: (data) => request.post('/user/login', data),
  info: () => request.get('/user/info'),
  updatePassword: (data) => request.put('/user/password', data),
  logout: () => request.post('/user/logout'),
}

export const collectApi = {
  start: (data) => request.post('/collect/start', data),
  status: (videoId) => request.get('/collect/status', { params: { videoId } }),
  upload: (formData) => request.post('/collect/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }),
  // 任务中心
  tasks: (params) => request.get('/collect/tasks', { params }),
  taskDetail: (id) => request.get(`/collect/tasks/${id}`),
  taskLogs: (id) => request.get(`/collect/tasks/${id}/logs`),
  cancel: (id) => request.post(`/collect/tasks/${id}/cancel`),
  retry: (id) => request.post(`/collect/tasks/${id}/retry`),
}

export const cleanApi = {
  getFiles: () => request.get('/clean/files'),
  run: (fileNames) => request.post('/clean/run', fileNames),
  exportFiles: (fileNames) => request.post('/clean/export', fileNames, { responseType: 'blob' }),
}

export const commentApi = {
  list: (params) => request.get('/comment/list', { params }),
  search: (params) => request.get('/comment/search', { params }),
  tree: (params) => request.get('/comment/tree', { params }),
}

export const reviewApi = {
  queue: (params) => request.get('/review/queue', { params }),
  stats: (videoId) => request.get('/review/stats', { params: { videoId } }),
  submit: (analysisId, data) => request.post(`/review/${analysisId}`, data),
  history: (analysisId) => request.get(`/review/${analysisId}/history`),
}

export const overviewApi = {
  get: () => request.get('/overview'),
  videoOptions: () => request.get('/overview/video-options'),
}

export const analysisApi = {
  summary: (videoId) => request.get('/analysis/summary', { params: { videoId } }),
  region: (videoId) => request.get('/analysis/region', { params: { videoId } }),
  sentiment: (videoId) => request.get('/analysis/sentiment', { params: { videoId } }),
  trend: (videoId) => request.get('/analysis/trend', { params: { videoId } }),
  topComments: (videoId) => request.get('/analysis/top-comments', { params: { videoId } }),
  wordcloud: (videoId) => request.get('/analysis/wordcloud', { params: { videoId } }),
  sankey: (videoId) => request.get('/analysis/sankey', { params: { videoId } }),
  topic: (videoId) => request.get('/analysis/topic', { params: { videoId } }),
  videoList: () => request.get('/analysis/videos'),
  refresh: (videoId) => request.post('/analysis/refresh', null, { params: { videoId } }),
  compare: (videoIds) => request.get('/analysis/compare', { params: { videoIds: videoIds.join(',') } }),
}

export const keywordApi = {
  list: () => request.get('/keyword'),
  create: (data) => request.post('/keyword', data),
  disable: (id) => request.delete(`/keyword/${id}`),
  discover: (id, max = 50) => request.post(`/keyword/${id}/discover?max=${max}`),
  videos: (id) => request.get(`/keyword/${id}/videos`),
  tasks: () => request.get('/keyword/discovery-tasks'),
}

export const aiApi = {
  analyzeComments: (videoId, limit = 100) =>
    request.post('/ai/analyze-comments', null, { params: { videoId, limit } }),
  videoInsight: (videoId) => request.post('/ai/video-insight', null, { params: { videoId } }),
  insights: (videoId) => request.get('/ai/video-insights', { params: { videoId } }),
  commentSummary: (videoId) => request.get('/ai/comment-summary', { params: { videoId } }),
  tasks: (params) => request.get('/ai/tasks', { params }),
  task: (id) => request.get(`/ai/tasks/${id}`),
  usage: () => request.get('/ai/usage'),
}

export const reportApi = {
  create: (videoId, title) => request.post('/report', null, { params: { videoId, title } }),
  list: () => request.get('/report'),
  detail: (id) => request.get(`/report/${id}`),
  versions: (id) => request.get(`/report/${id}/versions`),
  exportMd: (id) => request.get(`/report/${id}/export`),
  file: (id, format) => request.get(`/report/${id}/file`, {
    params: { format },
    responseType: 'blob',
  }),
  remove: (id) => request.delete(`/report/${id}`),
}

export const adminApi = {
  listUsers: (params) => request.get('/admin/users', { params }),
  deleteUser: (id) => request.delete('/admin/user/' + id),
}

export const menuApi = {
  /** 当前用户可见菜单（后端按权限过滤） */
  current: () => request.get('/v1/system/menus/current'),
  /** 全量树（需 system:menu:list） */
  tree: () => request.get('/v1/system/menus/tree'),
  create: (data) => request.post('/v1/system/menus', data),
  remove: (id) => request.delete('/v1/system/menus/' + id),
}

export const roleApi = {
  list: () => request.get('/v1/system/roles'),
  create: (data) => request.post('/v1/system/roles', data),
  remove: (id) => request.delete('/v1/system/roles/' + id),
  assign: (roleId, userId) => request.post(`/v1/system/roles/${roleId}/users/${userId}`),
  revoke: (roleId, userId) => request.delete(`/v1/system/roles/${roleId}/users/${userId}`),
  rolesOfUser: (userId) => request.get(`/v1/system/roles/users/${userId}`),
  menuIds: (roleId) => request.get(`/v1/system/roles/${roleId}/menus`),
  grantMenus: (roleId, menuIds) => request.put(`/v1/system/roles/${roleId}/menus`, { menuIds }),
}

export const dictApi = {
  types: () => request.get('/v1/system/dicts'),
  items: (code) => request.get(`/v1/system/dicts/${code}/items`),
}

export const auditApi = {
  page: (params) => request.get('/v1/system/audit-logs', { params }),
}

export const configApi = {
  list: () => request.get('/v1/system/configs'),
  update: (key, value) => request.put('/v1/system/configs/' + key, { value }),
}

export const accountApi = {
  list: () => request.get('/v1/source/accounts'),
  detail: (id) => request.get(`/v1/source/accounts/${id}`),
  create: (data) => request.post('/v1/source/accounts', data),
  update: (id, data) => request.put(`/v1/source/accounts/${id}`, data),
  setStatus: (id, status) => request.patch(`/v1/source/accounts/${id}/status`, { status }),
  usage: (id) => request.get(`/v1/source/accounts/${id}/usage`),
  verify: (id) => request.post(`/v1/source/accounts/${id}/verify`),
}

export const sourceApi = {
  list: () => request.get('/v1/source/sources'),
  /** 三态明细：desired / observed / effective（SOURCE-04-06） */
  capabilities: (code) => request.get(`/v1/source/sources/${code}/capabilities`),
  /** 覆盖写 Desired（只接受 v2 文档） */
  saveCapabilities: (code, doc) => request.put(`/v1/source/sources/${code}/capabilities`, doc),
}

/** 分层健康检查（SOURCE-05）：每层给出状态、耗时与原因 */
export const systemApi = {
  healthLayers: () => request.get('/system/health/layers'),
}
