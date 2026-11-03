<template>
  <div class="page-container">
    <h3 class="page-title">采集任务中心</h3>

    <div class="card">
      <div class="card-header">
        <div style="display:flex;gap:8px;align-items:center;">
          <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width:170px" @change="load">
            <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
          </el-select>
          <el-switch v-model="autoRefresh" active-text="自动刷新" />
        </div>
        <div style="display:flex;gap:8px;">
          <el-button @click="load" :loading="loading">刷新</el-button>
          <el-button type="primary" @click="$router.push('/collect')">新建采集</el-button>
        </div>
      </div>

      <el-table :data="tasks" v-loading="loading" border stripe empty-text="暂无采集任务">
        <el-table-column prop="id" label="#" width="60" align="center" />
        <el-table-column prop="taskNo" label="任务编号" width="200" />
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="180">
          <template #default="{ row }">
            <span style="font-size:13px;color:var(--text-secondary)">
              成功 {{ row.successItems }} / 失败 {{ row.failedItems }} / 共 {{ row.totalItems }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column prop="finishedAt" label="结束时间" width="180" />
        <el-table-column label="操作" min-width="240">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link v-if="['PENDING','RUNNING','RETRY_WAIT'].includes(row.status)"
                       type="warning" @click="cancel(row)">取消</el-button>
            <el-button link v-if="['FAILED','PARTIAL_SUCCESS'].includes(row.status)"
                       type="success" @click="retry(row)">重试</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="drawer" size="720px" :title="`任务 ${current?.taskNo || ''}`">
      <template v-if="current">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(current.status)" size="small">{{ statusText(current.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="进度">
            {{ current.successItems }} 成功 / {{ current.failedItems }} 失败 / 共 {{ current.totalItems }}
          </el-descriptions-item>
          <el-descriptions-item label="创建">{{ current.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="结束">{{ current.finishedAt || '—' }}</el-descriptions-item>
          <el-descriptions-item label="参数" :span="2">{{ current.params }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin:20px 0 10px;font-size:14px;">子任务</h4>
        <el-table :data="current.items" border size="small">
          <el-table-column prop="videoId" label="视频ID" width="200" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="collectedCount" label="库内条数" width="100" align="center" />
          <el-table-column prop="errorMessage" label="错误" show-overflow-tooltip />
        </el-table>

        <h4 style="margin:20px 0 10px;font-size:14px;">执行日志</h4>
        <div class="log-box">
          <div v-for="l in logs" :key="l.id" class="log-line">
            <span class="log-time">{{ l.createdAt?.replace('T', ' ') }}</span>
            <span :class="['log-level', (l.eventLevel || '').toLowerCase()]">{{ l.eventLevel }}</span>
            <span class="log-msg">{{ l.message }}</span>
          </div>
          <div v-if="!logs.length" class="log-empty">暂无日志</div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { collectApi } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const statuses = ['PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED']
const tasks = ref([])
const loading = ref(false)
const statusFilter = ref('')
const autoRefresh = ref(true)
const drawer = ref(false)
const current = ref(null)
const logs = ref([])
let timer = null

const STATUS_MAP = {
  PENDING: ['等待中', 'info'],
  RUNNING: ['执行中', 'warning'],
  RETRY_WAIT: ['等待重试', 'warning'],
  SUCCESS: ['成功', 'success'],
  PARTIAL_SUCCESS: ['部分成功', 'warning'],
  FAILED: ['失败', 'danger'],
  CANCEL_REQUESTED: ['取消中', 'info'],
  CANCELLED: ['已取消', 'info'],
}
function statusText(s) { return STATUS_MAP[s]?.[0] || s }
function statusType(s) { return STATUS_MAP[s]?.[1] || 'info' }

async function load() {
  loading.value = true
  try {
    const res = await collectApi.tasks({ status: statusFilter.value || undefined, page: 1, size: 50 })
    tasks.value = res.data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '加载任务失败')
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  drawer.value = true
  current.value = null
  logs.value = []
  try {
    const [d, l] = await Promise.all([collectApi.taskDetail(row.id), collectApi.taskLogs(row.id)])
    current.value = d.data
    logs.value = l.data || []
  } catch (e) {
    ElMessage.error('加载详情失败')
  }
}

async function cancel(row) {
  try {
    await ElMessageBox.confirm('确定取消该采集任务？已入库的数据不会回滚。', '确认', { type: 'warning' })
    await collectApi.cancel(row.id)
    ElMessage.success('已请求取消')
    load()
  } catch (e) { /* 用户取消 */ }
}

async function retry(row) {
  try {
    await collectApi.retry(row.id)
    ElMessage.success('已重新入队')
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '重试失败')
  }
}

function tick() {
  if (!autoRefresh.value) return
  load()
  if (drawer.value && current.value) {
    collectApi.taskDetail(current.value.id).then(r => { current.value = r.data }).catch(() => {})
    collectApi.taskLogs(current.value.id).then(r => { logs.value = r.data || [] }).catch(() => {})
  }
}

onMounted(() => { load(); timer = setInterval(tick, 5000) })
onUnmounted(() => timer && clearInterval(timer))
watch(statusFilter, load)
</script>

<style scoped>
.log-box {
  background: #0F172A;
  border-radius: 8px;
  padding: 12px;
  max-height: 320px;
  overflow: auto;
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 12px;
}
.log-line { display: flex; gap: 8px; padding: 3px 0; line-height: 1.5; }
.log-time { color: #64748B; flex: 0 0 138px; }
.log-level { flex: 0 0 46px; color: #94A3B8; }
.log-level.error { color: #F87171; }
.log-level.warn { color: #FBBF24; }
.log-level.info { color: #34D399; }
.log-msg { color: #E2E8F0; word-break: break-all; }
.log-empty { color: #64748B; text-align: center; padding: 12px; }
</style>
