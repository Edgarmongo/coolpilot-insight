<template>
  <div class="page-container">
    <h3 class="page-title">数据采集</h3>

    <el-tabs v-model="activeTab" class="collect-tabs">
      <!-- ============ 方式一：在线采集 ============ -->
      <el-tab-pane label="在线采集" name="online">
        <div class="card">
          <div style="max-width:720px;">
            <div style="margin-bottom:12px;">
              <el-input
                v-model="videoInput"
                type="textarea"
                :rows="4"
                size="large"
                placeholder="输入视频链接或编号，支持多个：每行一个，或用逗号分隔"
              />
            </div>
            <div style="display:flex;gap:12px;margin-bottom:12px;flex-wrap:wrap;align-items:center;">
              <el-input-number v-model="maxComments" :min="10" :max="1000" :step="50" size="large" style="width:130px;" />
              <el-button type="primary" @click="startCollect" :loading="collecting" size="large">开始采集</el-button>
              <span style="font-size:12px;color:var(--text-secondary)">
                单个视频按排队串行执行，可在「任务」页看进度
              </span>
            </div>

            <div style="margin-bottom:12px;">
              <el-checkbox v-model="useLogin" border>使用登录态采集（复用容器内已登录的抖音会话）</el-checkbox>
            </div>

            <div v-if="statusMsg" style="margin-top:12px;">
              <el-alert :title="statusMsg" :type="statusType" show-icon :closable="false" />
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ============ 方式二：本机导入 ============ -->
      <el-tab-pane label="本机导入 CSV" name="upload">
        <div class="card">
          <div style="max-width:640px;">
            <p style="font-size:13px;color:var(--text-secondary);margin:0 0 12px;">
              在本机已登录抖音的浏览器里采集好 CSV 后，直接拖入上传，文件会进入「数据清洗」待处理列表。
            </p>
            <el-upload
              drag
              multiple
              accept=".csv"
              :auto-upload="false"
              :limit="20"
              v-model:file-list="fileList"
              :on-change="onFileChange"
            >
              <div style="padding:20px;">
                <div style="font-size:16px;color:var(--text-primary);">拖拽 CSV 文件到此处，或点击选择</div>
                <div style="font-size:12px;color:var(--text-tertiary);margin-top:8px;">
                  文件名需为纯数字视频ID（如 7613237683142223214.csv），可一次上传多个
                </div>
              </div>
            </el-upload>

            <div style="margin-top:12px;">
              <el-button type="primary" @click="doUpload" :loading="uploading" :disabled="fileList.length === 0">
                上传到待处理列表
              </el-button>
            </div>

            <div v-if="uploadResult" style="margin-top:12px;">
              <el-alert :title="uploadResult" type="success" show-icon :closable="false" />
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <div class="card" style="color:var(--text-secondary);font-size:13px;line-height:2;">
      <div class="card-header"><h3>说明</h3></div>
      <ul style="padding-left:18px;">
        <li><b>在线采集</b>：由容器内浏览器自动抓取评论。未登录为匿名态，抖音每次约返回 15 条左右；勾选「登录态」可复用容器内会话绕过部分限制。</li>
        <li><b>本机导入</b>：在本机已登录抖音的真实浏览器里采集 CSV（推荐，最稳定），上传后自动进入「数据清洗」列表。</li>
        <li>两种方式的数据最终都会出现在「数据清洗」页，勾选后执行入库。</li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { collectApi } from '../api'
import { ElMessage } from 'element-plus'

const activeTab = ref('online')

// ---- 在线采集 ----
const videoInput = ref('')
const maxComments = ref(100)
const useLogin = ref(false)
const collecting = ref(false)
const statusMsg = ref('')
const statusType = ref('info')

async function startCollect() {
  const inputs = videoInput.value.split(/[\r\n,;]+/).map(s => s.trim()).filter(Boolean)
  if (!inputs.length) { statusMsg.value = '请输入视频链接或编号'; statusType.value = 'warning'; return }
  collecting.value = true
  statusMsg.value = '任务已提交，正在后台串行执行…'
  statusType.value = 'info'
  try {
    const res = await collectApi.start({
      videoIds: inputs,
      maxComments: maxComments.value,
      collectMode: useLogin.value ? 'online-login' : 'online',
    })
    const d = res.data || {}
    if (!d.taskId) {
      statusMsg.value = d.message || '提交未创建任务'
      statusType.value = 'warning'
    } else {
      statusMsg.value = `任务 ${d.taskNo} 已创建，共 ${d.totalItems} 个视频${
        d.alreadyRunning?.length ? `；${d.alreadyRunning.length} 个视频已有任务在跑` : ''}`
      statusType.value = 'success'
      setTimeout(() => { window.location.href = '/tasks' }, 1200)
    }
  } catch (e) {
    statusMsg.value = e?.response?.data?.message || '启动失败'
    statusType.value = 'error'
  } finally {
    collecting.value = false
  }
}

// ---- 本机导入 ----
const fileList = ref([])
const uploading = ref(false)
const uploadResult = ref('')

function onFileChange(file, files) {
  // 仅校验扩展名
  const name = file.name.toLowerCase()
  if (!name.endsWith('.csv')) {
    ElMessage.warning('仅支持 .csv 文件')
    const idx = fileList.value.findIndex(f => f.uid === file.uid)
    if (idx >= 0) fileList.value.splice(idx, 1)
  }
}

async function doUpload() {
  if (fileList.value.length === 0) return
  uploading.value = true
  uploadResult.value = ''
  const formData = new FormData()
  fileList.value.forEach(f => {
    if (f.raw) formData.append('files', f.raw)
  })
  try {
    const res = await collectApi.upload(formData)
    const names = (res.data || []).map(x => x.fileName).join('、')
    uploadResult.value = `已上传 ${(res.data || []).length} 个文件：${names}，请前往「数据清洗」处理`
    ElMessage.success('上传成功')
    fileList.value = []
  } catch (e) {
    ElMessage.error('上传失败')
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
.collect-tabs :deep(.el-tabs__content) { padding-top: 12px; }
</style>
