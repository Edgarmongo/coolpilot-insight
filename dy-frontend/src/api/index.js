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
}

export const adminApi = {
  listUsers: (params) => request.get('/admin/users', { params }),
  deleteUser: (id) => request.delete('/admin/user/' + id),
}
