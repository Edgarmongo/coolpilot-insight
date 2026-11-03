<template>
  <div class="page-container">
    <h3 class="page-title">报告中心</h3>

    <div class="card">
      <div class="card-header">
        <span style="font-size:14px;color:var(--text-secondary)">
          报告基于已生成的视频洞察归档，不重复调用 AI，不产生额外费用
        </span>
        <el-button @click="load" :loading="loading">刷新</el-button>
      </div>

      <el-table :data="reports" v-loading="loading" border stripe empty-text="还没有报告，请先在「洞察」页生成视频级洞察">
        <el-table-column prop="reportNo" label="报告编号" width="200" />
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column prop="reportType" label="类型" width="100" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentVersion" label="版本" width="80" align="center" />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" min-width="320">
          <template #default="{ row }">
            <el-button link type="primary" @click="open(row)">查看</el-button>
            <el-button link @click="downloadFile(row, 'pdf')" :loading="downloading === row.id + 'pdf'">PDF</el-button>
            <el-button link @click="downloadFile(row, 'xlsx')" :loading="downloading === row.id + 'xlsx'">Excel</el-button>
            <el-button link @click="exportMd(row)">Markdown</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="drawer" size="760px" :title="detail?.title || '报告详情'">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" style="margin-bottom:16px;">
          <el-descriptions-item label="编号">{{ detail.reportNo }}</el-descriptions-item>
          <el-descriptions-item label="版本">v{{ detail.versionNo }}</el-descriptions-item>
          <el-descriptions-item label="证据引用">{{ detail.evidenceCount }} 条</el-descriptions-item>
          <el-descriptions-item label="生成时间">{{ detail.generatedAt }}</el-descriptions-item>
          <el-descriptions-item label="数据范围" :span="2">{{ JSON.stringify(detail.scope) }}</el-descriptions-item>
        </el-descriptions>
        <div class="md-box">{{ detail.contentMd || '（无内容）' }}</div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { reportApi } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const reports = ref([])
const loading = ref(false)
const drawer = ref(false)
const detail = ref(null)
const downloading = ref('')

async function load() {
  loading.value = true
  try {
    const res = await reportApi.list()
    reports.value = res.data || []
  } catch (e) {
    ElMessage.error('加载报告失败')
  } finally {
    loading.value = false
  }
}

async function open(row) {
  drawer.value = true
  detail.value = null
  try {
    const res = await reportApi.detail(row.id)
    detail.value = res.data
  } catch (e) {
    ElMessage.error('加载详情失败')
  }
}

/** PDF / Excel 由 Flask 渲染，二进制流下载 */
async function downloadFile(row, format) {
  downloading.value = row.id + format
  try {
    const res = await reportApi.file(row.id, format)
    const type = format === 'pdf'
      ? 'application/pdf'
      : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    const blob = new Blob([res.data], { type })
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${row.reportNo}.${format === 'pdf' ? 'pdf' : 'xlsx'}`
    a.click()
    window.URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '导出失败，请检查 Flask 渲染服务')
  } finally {
    downloading.value = ''
  }
}

async function exportMd(row) {  try {
    const res = await reportApi.exportMd(row.id)
    const blob = new Blob([res.data.content || ''], { type: 'text/markdown;charset=utf-8' })
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = res.data.fileName || `${row.reportNo}.md`
    a.click()
    window.URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error('导出失败')
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm('确定删除该报告？', '确认', { type: 'warning' })
    await reportApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) { /* 用户取消 */ }
}

onMounted(load)
</script>

<style scoped>
.md-box {
  white-space: pre-wrap;
  line-height: 1.9;
  font-size: 13px;
  color: var(--text-primary);
  background: #F8FAFC;
  border-radius: 10px;
  padding: 18px;
}
</style>
