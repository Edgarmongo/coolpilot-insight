<template>
  <div class="page-container">
    <h3 class="page-title">AI 结果复核</h3>

    <div class="card">
      <div class="card-header">
        <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;">
          <el-select v-model="videoId" placeholder="选择视频" style="width:280px" @change="reload">
            <el-option v-for="v in videos" :key="v.videoId" :value="v.videoId"
                       :label="`${v.videoId}（${v.comments} 条）`" />
          </el-select>
          <el-select v-model="status" placeholder="全部状态" clearable style="width:140px" @change="reload">
            <el-option label="待复核" value="PENDING" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
            <el-option label="已纠正" value="CORRECTED" />
          </el-select>
          <el-checkbox v-model="lowOnly" @change="reload">仅看低置信</el-checkbox>
        </div>
        <div class="stats">
          <span>待复核 <b>{{ stats.counts?.PENDING || 0 }}</b></span>
          <span>已通过 <b class="ok">{{ stats.counts?.APPROVED || 0 }}</b></span>
          <span>已纠正 <b class="warn">{{ stats.counts?.CORRECTED || 0 }}</b></span>
          <span>已驳回 <b class="bad">{{ stats.counts?.REJECTED || 0 }}</b></span>
        </div>
      </div>

      <div v-loading="loading">
        <div v-for="row in records" :key="row.analysisId" class="review-item">
          <div class="item-head">
            <span class="nickname">{{ row.nickname }}</span>
            <el-tag size="small" effect="plain">{{ row.region }}</el-tag>
            <span class="likes">👍 {{ row.likes }}</span>
            <el-tag v-if="row.isUncertain === 1" size="small" type="warning" effect="plain">低置信</el-tag>
            <el-tag size="small" :type="reviewType(row.reviewStatus)">{{ reviewText(row.reviewStatus) }}</el-tag>
            <span class="time">{{ fmt(row.publishTime) }}</span>
          </div>

          <div class="content">{{ row.content }}</div>

          <div class="labels">
            <span class="label-chip">情感：{{ sentimentZh(row.sentiment) }}</span>
            <span class="label-chip">意图：{{ intentZh(row.intent) }}</span>
            <span class="label-chip">意向：{{ purchaseZh(row.purchaseIntent) }}</span>
            <span class="label-chip">置信：{{ row.confidence ?? '-' }}</span>
            <span v-for="t in row.topics || []" :key="t" class="label-chip soft">#{{ t }}</span>
            <span v-for="p in row.painPoints || []" :key="p" class="label-chip danger">痛点：{{ p }}</span>
          </div>
          <div v-if="row.userNeed" class="need">需求：{{ row.userNeed }}</div>

          <div class="actions">
            <el-button size="small" type="success" plain @click="approve(row)">通过</el-button>
            <el-button size="small" type="danger" plain @click="reject(row)">驳回</el-button>
            <el-button size="small" type="primary" plain @click="openCorrect(row)">纠正</el-button>
          </div>
        </div>

        <el-empty v-if="!loading && !records.length" description="没有待复核的数据" />
      </div>

      <div class="pager" v-if="total > size">
        <el-pagination layout="prev, pager, next" :total="total" :page-size="size"
                       :current-page="page" @current-change="onPage" background small />
      </div>
    </div>

    <el-dialog v-model="correctDialog" title="纠正 AI 结论" width="560px">
      <el-form label-width="90px" v-if="current">
        <el-form-item label="评论原文">
          <div class="quote">{{ current.content }}</div>
        </el-form-item>
        <el-form-item label="情感">
          <el-select v-model="form.sentiment" style="width:200px">
            <el-option label="积极" value="positive" />
            <el-option label="中性" value="neutral" />
            <el-option label="消极" value="negative" />
            <el-option label="无法判断" value="unknown" />
          </el-select>
        </el-form-item>
        <el-form-item label="意图">
          <el-select v-model="form.intent" style="width:200px">
            <el-option label="抱怨" value="complaint" />
            <el-option label="称赞" value="praise" />
            <el-option label="提问" value="question" />
            <el-option label="购买咨询" value="purchase" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="购买意向">
          <el-select v-model="form.purchaseIntent" style="width:200px">
            <el-option label="无" value="none" />
            <el-option label="低" value="low" />
            <el-option label="中" value="medium" />
            <el-option label="高" value="high" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户需求">
          <el-input v-model="form.userNeed" type="textarea" :rows="2" placeholder="一句话概括真实需求" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="2" placeholder="纠正理由（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="correctDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCorrect">保存纠正</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { reviewApi, overviewApi } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAppStore } from '../stores/user'

const appStore = useAppStore()
const videos = ref([])
const videoId = ref(appStore.currentVideo || '')
const status = ref('PENDING')
const lowOnly = ref(false)
const records = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const stats = ref({})
const correctDialog = ref(false)
const current = ref(null)
const submitting = ref(false)
const form = ref({})

const SENTIMENT_ZH = { positive: '积极', neutral: '中性', negative: '消极', unknown: '无法判断' }
const INTENT_ZH = { complaint: '抱怨', praise: '称赞', question: '提问', purchase: '购买咨询', other: '其他' }
const PURCHASE_ZH = { none: '无', low: '低', medium: '中', high: '高' }
const REVIEW_ZH = { PENDING: '待复核', APPROVED: '已通过', REJECTED: '已驳回', CORRECTED: '已纠正' }

function sentimentZh(v) { return SENTIMENT_ZH[v] || v || '-' }
function intentZh(v) { return INTENT_ZH[v] || v || '-' }
function purchaseZh(v) { return PURCHASE_ZH[v] || v || '-' }
function reviewText(v) { return REVIEW_ZH[v] || v }
function reviewType(v) {
  return v === 'APPROVED' ? 'success' : v === 'REJECTED' ? 'danger' : v === 'CORRECTED' ? 'warning' : 'info'
}
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }

async function loadVideos() {
  const res = await overviewApi.videoOptions()
  videos.value = res.data || []
  if (!videoId.value && videos.value.length) {
    videoId.value = videos.value[0].videoId
  }
}

async function loadQueue() {
  if (!videoId.value) return
  loading.value = true
  try {
    const res = await reviewApi.queue({
      videoId: videoId.value,
      status: status.value || undefined,
      lowConfidence: lowOnly.value || undefined,
      page: page.value,
      size,
    })
    records.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  if (!videoId.value) return
  const res = await reviewApi.stats(videoId.value)
  stats.value = res.data || {}
}

function reload() {
  page.value = 1
  loadQueue()
  loadStats()
}

function onPage(p) { page.value = p; loadQueue() }

async function doSubmit(row, payload, successMsg) {
  try {
    await reviewApi.submit(row.analysisId, payload)
    ElMessage.success(successMsg)
    await loadQueue()
    loadStats()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  }
}

async function approve(row) {
  await doSubmit(row, { status: 'APPROVED' }, '已通过')
}

async function reject(row) {
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因（可选）', '驳回 AI 结论', {
      inputPlaceholder: '例如：AI 误判为积极，实际是反讽',
      confirmButtonText: '确认驳回',
      cancelButtonText: '取消',
    })
    await doSubmit(row, { status: 'REJECTED', note: value }, '已驳回')
  } catch (e) { /* 用户取消 */ }
}

function openCorrect(row) {
  current.value = row
  form.value = {
    sentiment: row.sentiment || 'neutral',
    intent: row.intent || 'other',
    purchaseIntent: row.purchaseIntent || 'none',
    userNeed: row.userNeed || '',
    note: '',
  }
  correctDialog.value = true
}

async function submitCorrect() {
  submitting.value = true
  try {
    await reviewApi.submit(current.value.analysisId, {
      status: 'CORRECTED',
      note: form.value.note,
      corrections: {
        sentiment: form.value.sentiment,
        intent: form.value.intent,
        purchaseIntent: form.value.purchaseIntent,
        userNeed: form.value.userNeed,
      },
    })
    ElMessage.success('已保存纠正')
    correctDialog.value = false
    await loadQueue()
    loadStats()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await loadVideos()
  reload()
})
</script>

<style scoped>
.stats { display: flex; gap: 18px; font-size: 13px; color: var(--text-secondary); }
.stats b { color: #0F172A; }
.stats b.ok { color: #059669; }
.stats b.warn { color: #D97706; }
.stats b.bad { color: #DC2626; }
.review-item { border: 1px solid #E2E8F0; border-radius: 10px; padding: 14px 16px; margin-bottom: 12px; }
.item-head { display: flex; gap: 8px; align-items: center; font-size: 12px; color: var(--text-secondary); }
.nickname { font-weight: 600; color: var(--text-primary); font-size: 13px; }
.likes { color: #94A3B8; }
.time { margin-left: auto; color: #94A3B8; }
.content { margin: 10px 0; font-size: 13.5px; line-height: 1.7; color: var(--text-primary); }
.labels { display: flex; flex-wrap: wrap; gap: 6px; }
.label-chip { font-size: 12px; background: #F1F5F9; color: #334155; border-radius: 4px; padding: 2px 8px; }
.label-chip.soft { background: #ECFDF5; color: #047857; }
.label-chip.danger { background: #FEF2F2; color: #B91C1C; }
.need { margin-top: 8px; font-size: 12px; color: #0F766E; }
.actions { margin-top: 10px; display: flex; gap: 8px; }
.pager { margin-top: 12px; display: flex; justify-content: center; }
.quote { background: #F8FAFC; border-radius: 6px; padding: 10px; font-size: 13px; line-height: 1.7; }
</style>
