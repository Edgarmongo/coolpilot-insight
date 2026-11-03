<template>
  <div class="page-container">
    <h3 class="page-title">关键词与视频发现</h3>

    <div class="card">
      <div class="card-header">
        <span style="font-size:14px;color:var(--text-secondary)">用关键词发现候选视频，再挑选进入采集</span>
        <div style="display:flex;gap:8px;align-items:center;">
          <el-input v-model="newKeyword" placeholder="输入关键词，例如 露营装备" style="width:240px" @keyup.enter="createKeyword" />
          <el-button type="primary" @click="createKeyword" :loading="creating">添加</el-button>
        </div>
      </div>

      <el-table :data="keywords" v-loading="loading" border stripe empty-text="还没有关键词，先添加一个">
        <el-table-column prop="id" label="#" width="60" align="center" />
        <el-table-column prop="keyword" label="关键词" width="200" />
        <el-table-column prop="videoCount" label="已关联视频" width="110" align="center" />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="260">
          <template #default="{ row }">
            <el-button link type="primary" @click="discover(row)" :loading="discoveringId === row.id">发现视频</el-button>
            <el-button link @click="showVideos(row)">查看视频</el-button>
            <el-button link type="danger" @click="disable(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="card" style="margin-top:16px;">
      <div class="card-header">
        <span style="font-size:14px;color:var(--text-secondary)">发现任务记录</span>
        <el-button @click="loadTasks" size="small">刷新</el-button>
      </div>
      <el-table :data="discoveryTasks" border size="small" empty-text="暂无发现任务">
        <el-table-column prop="taskNo" label="任务编号" width="200" />
        <el-table-column prop="keywordId" label="关键词ID" width="100" align="center" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="foundCount" label="发现数" width="90" align="center" />
        <el-table-column prop="newVideoCount" label="新增视频" width="90" align="center" />
        <el-table-column prop="errorMessage" label="错误" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" width="180" />
      </el-table>
    </div>

    <el-dialog v-model="videoDialog" :title="`关键词「${currentKeyword}」关联视频`" width="880px">
      <el-table :data="videos" v-loading="videoLoading" border size="small" empty-text="暂无关联视频">
        <el-table-column prop="videoId" label="视频ID" width="200" />
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column prop="authorName" label="作者" width="140" />
        <el-table-column prop="diggCount" label="点赞" width="90" align="center" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" @click="collect(row)">去采集</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { keywordApi, collectApi } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

const router = useRouter()
const keywords = ref([])
const discoveryTasks = ref([])
const loading = ref(false)
const creating = ref(false)
const newKeyword = ref('')
const discoveringId = ref(null)
const videoDialog = ref(false)
const videoLoading = ref(false)
const videos = ref([])
const currentKeyword = ref('')

async function load() {
  loading.value = true
  try {
    const res = await keywordApi.list()
    keywords.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadTasks() {
  try {
    const res = await keywordApi.tasks()
    discoveryTasks.value = res.data || []
  } catch (e) { /* 忽略 */ }
}

async function createKeyword() {
  const kw = newKeyword.value.trim()
  if (!kw) return
  creating.value = true
  try {
    await keywordApi.create({ keyword: kw })
    newKeyword.value = ''
    ElMessage.success('已添加关键词')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '添加失败')
  } finally {
    creating.value = false
  }
}

async function discover(row) {
  discoveringId.value = row.id
  try {
    const res = await keywordApi.discover(row.id, 50)
    ElMessage.success(res.data?.message || '发现任务已提交')
    setTimeout(loadTasks, 2000)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  } finally {
    discoveringId.value = null
  }
}

async function showVideos(row) {
  currentKeyword.value = row.keyword
  videoDialog.value = true
  videoLoading.value = true
  try {
    const res = await keywordApi.videos(row.id)
    videos.value = res.data || []
  } finally {
    videoLoading.value = false
  }
}

async function disable(row) {
  try {
    await ElMessageBox.confirm(`停用关键词「${row.keyword}」？`, '确认', { type: 'warning' })
    await keywordApi.disable(row.id)
    ElMessage.success('已停用')
    load()
  } catch (e) { /* 用户取消 */ }
}

async function collect(row) {
  try {
    await collectApi.start({ videoIds: [row.videoId], maxComments: 100 })
    ElMessage.success('已提交采集，可在任务中心查看进度')
    router.push('/tasks')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  }
}

onMounted(() => { load(); loadTasks() })
</script>
