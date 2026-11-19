<template>
  <div class="page-container">
    <h3 class="page-title">数据清洗</h3>
    <div class="card">
      <div class="card-header">
        <span style="font-size:14px;color:var(--text-secondary);">{{ pendingFiles.length }} 个文件待处理 | 已选 {{ selected.length }} 个</span>
        <div style="display:flex;gap:8px;">
          <el-button @click="doExport" :disabled="selected.length === 0">导出原始数据</el-button>
          <el-button type="primary" @click="runClean" :loading="cleaning" :disabled="selected.length === 0">执行清洗入库</el-button>
        </div>
      </div>
      <el-table :data="pendingFiles" v-loading="fileLoading" border stripe
        @selection-change="onSelect" ref="tableRef" empty-text="暂无待处理文件">
        <el-table-column type="selection" width="40" />
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="fileName" label="文件名" />
        <el-table-column prop="size" label="大小" width="100" align="center" />
      </el-table>
      <div v-if="cleanResult" style="margin-top:16px;">
        <el-alert :title="cleanResult.success ? '清洗入库成功' : '清洗失败'"
          :type="cleanResult.success ? 'success' : 'error'" show-icon :closable="false" />
        <pre style="background:#F8FAFC;padding:12px;border-radius:8px;margin-top:8px;max-height:200px;overflow:auto;font-size:12px;color:var(--text-secondary);line-height:1.6;">{{ cleanResult.output }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { cleanApi } from '../api'
import { ElMessage } from 'element-plus'

const pendingFiles = ref([])
const selected = ref([])
const fileLoading = ref(false)
const cleaning = ref(false)
const cleanResult = ref(null)

function onSelect(rows) { selected.value = rows.map(r => r.fileName) }

async function loadFiles() {
  fileLoading.value = true
  try { const res = await cleanApi.getFiles(); pendingFiles.value = res.data || []; selected.value = [] } finally { fileLoading.value = false }
}

async function runClean() {
  cleaning.value = true; cleanResult.value = null
  try {
    const res = await cleanApi.run(selected.value)
    cleanResult.value = res.data
    if (res.data.success) loadFiles()
  } finally { cleaning.value = false }
}

async function doExport() {
  try {
    const res = await cleanApi.exportFiles(selected.value)
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const a = document.createElement('a')
    a.href = url
    a.download = selected.value.length === 1 ? selected.value[0] : 'datas_export.zip'
    a.click()
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) { /* */ }
}

onMounted(loadFiles)
</script>
