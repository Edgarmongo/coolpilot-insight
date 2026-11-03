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
  remove: (id) => request.delete(`/report/${id}`),
}

export const adminApi = {
  listUsers: (params) => request.get('/admin/users', { params }),
  deleteUser: (id) => request.delete('/admin/user/' + id),
}
