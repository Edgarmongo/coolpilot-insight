<template>
  <div class="page-container">
    <h3 class="page-title">AI 需求洞察</h3>

    <div class="card">
      <div class="card-header">
        <div style="display:flex;gap:10px;align-items:center;">
          <span style="font-size:14px;color:var(--text-secondary)">当前视频</span>
          <el-select v-model="videoId" placeholder="请选择视频" style="width:300px" @change="onVideoChange">
            <el-option v-for="v in videos" :key="v.videoId" :value="v.videoId"
                       :label="`${v.videoId}（${v.comments} 条）`" />
          </el-select>
        </div>
        <div class="usage">
          今日 Token {{ usage.todayTokens }} / {{ usage.dailyTokenBudget }} ｜ 成本 ¥{{ usage.todayCost }}
        </div>
      </div>

      <el-tabs v-model="tab">
        <!-- ================= 评论级 ================= -->
        <el-tab-pane label="评论级分析" name="comments">
          <div class="toolbar">
            <el-input-number v-model="limit" :min="10" :max="300" :step="10" />
            <el-button type="primary" :loading="running" @click="runCommentAnalysis">开始结构化分析</el-button>
            <span v-if="commentTask" class="hint">
              任务 {{ commentTask.taskNo }}：{{ commentTask.status }}
              （成功 {{ commentTask.successItems }} / 失败 {{ commentTask.failedItems }}）
            </span>
          </div>

          <div v-if="summary.analyzedComments" class="stat-row">
            <div class="stat"><div class="stat-value">{{ summary.analyzedComments }}</div><div class="stat-label">已分析评论</div></div>
            <div class="stat"><div class="stat-value">{{ summary.totalComments }}</div><div class="stat-label">评论总数</div></div>
            <div class="stat"><div class="stat-value">{{ summary.needs?.length || 0 }}</div><div class="stat-label">需求条目</div></div>
          </div>

          <div v-if="summary.analyzedComments" class="grid2">
            <div class="panel">
              <h4>意图分布</h4>
              <div v-for="x in summary.intentDistribution" :key="x.name" class="bar-row">
                <span class="bar-label">{{ intentZh(x.name) }}</span>
                <div class="bar-track"><div class="bar-fill" :style="{ width: barWidth(x.value, summary.analyzedComments) }"></div></div>
                <span class="bar-value">{{ x.value }}</span>
              </div>
            </div>
            <div class="panel">
              <h4>购买意向</h4>
              <div v-for="x in summary.purchaseIntentDistribution" :key="x.name" class="bar-row">
                <span class="bar-label">{{ purchaseZh(x.name) }}</span>
                <div class="bar-track"><div class="bar-fill alt" :style="{ width: barWidth(x.value, summary.analyzedComments) }"></div></div>
                <span class="bar-value">{{ x.value }}</span>
              </div>
            </div>
            <div class="panel">
              <h4>高频主题</h4>
              <div class="tag-wrap">
                <el-tag v-for="x in summary.topicTop" :key="x.name" size="small" effect="plain">
                  {{ x.name }} · {{ x.value }}
                </el-tag>
                <span v-if="!summary.topicTop?.length" class="hint">暂无</span>
              </div>
            </div>
            <div class="panel">
              <h4>高频痛点</h4>
              <div class="tag-wrap">
                <el-tag v-for="x in summary.painPointTop" :key="x.name" size="small" type="danger" effect="plain">
                  {{ x.name }} · {{ x.value }}
                </el-tag>
                <span v-if="!summary.painPointTop?.length" class="hint">暂无</span>
              </div>
            </div>
          </div>

          <div v-if="summary.needs?.length" class="panel" style="margin-top:16px;">
            <h4>用户需求（可追溯到评论）</h4>
            <el-table :data="summary.needs" size="small" border>
              <el-table-column prop="userNeed" label="需求描述" />
              <el-table-column prop="confidence" label="置信度" width="100" align="center" />
              <el-table-column prop="commentId" label="评论ID" width="100" align="center" />
            </el-table>
          </div>

          <el-empty v-if="!summary.analyzedComments" description="还没有结构化分析结果，点击上方按钮开始" />
        </el-tab-pane>

        <!-- ================= 视频级 ================= -->
        <el-tab-pane label="视频级洞察" name="insight">
          <div class="toolbar">
            <el-button type="primary" :loading="insightRunning" @click="runInsight">
              生成视频级洞察
            </el-button>
            <el-button :disabled="!latestInsight" @click="createReport">生成报告</el-button>
            <span class="hint">洞察会保存为独立版本，不会覆盖历史记录</span>
          </div>

          <template v-if="latestInsight">
            <el-alert :title="`样本 ${latestInsight.sampleCount} 条评论 ｜ 证据 ${latestInsight.evidenceCount} 条 ｜ 生成于 ${latestInsight.createdAt}`"
                      type="info" :closable="false" show-icon style="margin-bottom:16px;" />
            <div class="panel">
              <h4>整体洞察</h4>
              <p class="summary-text">{{ latestInsight.result?.summary || '（无）' }}</p>
              <div v-if="latestInsight.result?.purchase_intent" class="purchase">
                购买意向：<b>{{ purchaseZh(latestInsight.result.purchase_intent.level) }}</b>
                <span class="hint"> — {{ latestInsight.result.purchase_intent.reason }}</span>
              </div>
            </div>

            <div class="grid2" style="margin-top:16px;">
              <div class="panel" v-for="block in insightBlocks" :key="block.key">
                <h4>{{ block.label }}</h4>
                <div v-for="(item, i) in latestInsight.result?.[block.key] || []" :key="i" class="insight-item">
                  <div class="insight-title">{{ item.title }}</div>
                  <div class="insight-desc">{{ item.description }}</div>
                  <div class="insight-meta">
                    置信度 {{ item.confidence ?? '-' }} ｜ 证据 {{ item.evidence_comment_ids?.length || 0 }} 条
                  </div>
                </div>
                <span v-if="!(latestInsight.result?.[block.key] || []).length" class="hint">未识别到该维度结论</span>
              </div>
            </div>
          </template>
          <el-empty v-else description="还没有洞察结果" />
        </el-tab-pane>

        <!-- ================= 多视频对比 ================= -->
        <el-tab-pane label="多视频对比" name="compare">
          <div class="toolbar">
            <el-select v-model="compareIds" multiple placeholder="选择 2-10 个视频" style="min-width:420px">
              <el-option v-for="v in videos" :key="v.videoId" :value="v.videoId"
                         :label="`${v.videoId}（${v.comments} 条）`" />
            </el-select>
            <el-button type="primary" :loading="comparing" @click="runCompare">对比</el-button>
          </div>

          <template v-if="compareResult">
            <el-table :data="compareResult.videos" border size="small">
              <el-table-column prop="videoId" label="视频ID" width="200" />
              <el-table-column prop="totalComments" label="评论数" width="90" align="center" />
              <el-table-column label="需求" min-width="200">
                <template #default="{ row }">
                  <el-tag v-for="n in row.needs" :key="n" size="small" effect="plain" style="margin:2px;">{{ n }}</el-tag>
                  <span v-if="!row.hasInsight" class="hint">未生成洞察</span>
                </template>
              </el-table-column>
              <el-table-column label="痛点" min-width="200">
                <template #default="{ row }">
                  <el-tag v-for="n in row.painPoints" :key="n" size="small" type="danger" effect="plain" style="margin:2px;">{{ n }}</el-tag>
                </template>
              </el-table-column>
            </el-table>

            <div class="panel" style="margin-top:16px;">
              <h4>共性痛点（出现在 2 个以上视频）</h4>
              <div class="tag-wrap">
                <el-tag v-for="p in compareResult.commonPainPoints" :key="p.name" type="danger" effect="plain">
                  {{ p.name }} · {{ p.videoCount }} 个视频
                </el-tag>
                <span v-if="!compareResult.commonPainPoints.length" class="hint">暂未发现共性痛点</span>
              </div>
            </div>
          </template>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { analysisApi, aiApi, reportApi } from '../api'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/user'

const router = useRouter()
const appStore = useAppStore()

const videos = ref([])
const videoId = ref(appStore.currentVideo || '')
const tab = ref('comments')
const limit = ref(100)
const running = ref(false)
const insightRunning = ref(false)
const comparing = ref(false)
const commentTask = ref(null)
const summary = ref({})
const latestInsight = ref(null)
const compareIds = ref([])
const compareResult = ref(null)
const usage = ref({ todayTokens: 0, dailyTokenBudget: 0, todayCost: 0 })
let pollTimer = null

const insightBlocks = [
  { key: 'needs', label: '用户需求' },
  { key: 'pain_points', label: '主要痛点' },
  { key: 'product_opportunities', label: '产品机会' },
  { key: 'highlights', label: '正面亮点' },
]

const INTENT_ZH = { complaint: '抱怨', praise: '称赞', question: '提问', purchase: '购买咨询', other: '其他' }
const PURCHASE_ZH = { none: '无意向', low: '低', medium: '中', high: '高' }
function intentZh(v) { return INTENT_ZH[v] || v }
function purchaseZh(v) { return PURCHASE_ZH[v] || v || '未知' }
function barWidth(v, total) { return total ? `${Math.round(v / total * 100)}%` : '0%' }

async function loadVideos() {
  try {
    const res = await analysisApi.videoList()
    videos.value = res.data || []
    if (!videoId.value && videos.value.length) {
      videoId.value = videos.value[0].videoId
      appStore.currentVideo = videoId.value
      onVideoChange(videoId.value)
    }
  } catch (e) { /* 忽略 */ }
}

async function loadUsage() {
  try {
    const res = await aiApi.usage()
    usage.value = res.data || usage.value
  } catch (e) { /* 忽略 */ }
}

async function onVideoChange(v) {
  appStore.currentVideo = v
  commentTask.value = null
  summary.value = {}
  latestInsight.value = null
  if (!v) return
  loadSummary()
  loadInsights()
}

async function loadSummary() {
  if (!videoId.value) return
  try {
    const res = await aiApi.commentSummary(videoId.value)
    summary.value = res.data || {}
  } catch (e) { /* 忽略 */ }
}

async function loadInsights() {
  if (!videoId.value) return
  try {
    const res = await aiApi.insights(videoId.value)
    latestInsight.value = (res.data || [])[0] || null
  } catch (e) { /* 忽略 */ }
}

async function runCommentAnalysis() {
  if (!videoId.value) return ElMessage.warning('请先选择视频')
  running.value = true
  try {
    const res = await aiApi.analyzeComments(videoId.value, limit.value)
    if (!res.data?.taskId) {
      ElMessage.info(res.data?.message || '无需分析')
      return
    }
    ElMessage.success(res.data.message)
    pollTask(res.data.taskId)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  } finally {
    running.value = false
  }
}

function pollTask(taskId) {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    try {
      const res = await aiApi.task(taskId)
      commentTask.value = res.data
      if (['SUCCESS', 'FAILED', 'PARTIAL_SUCCESS'].includes(res.data.status)) {
        clearInterval(pollTimer)
        pollTimer = null
        loadSummary()
        loadUsage()
      }
    } catch (e) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }, 3000)
}

async function runInsight() {
  if (!videoId.value) return ElMessage.warning('请先选择视频')
  insightRunning.value = true
  try {
    const res = await aiApi.videoInsight(videoId.value)
    ElMessage.success(`洞察已生成，引用 ${res.data.evidenceCount} 条证据`)
    await loadInsights()
    loadUsage()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '生成失败（若未配置 DeepSeek API Key 会失败）')
  } finally {
    insightRunning.value = false
  }
}

async function createReport() {
  try {
    const res = await reportApi.create(videoId.value, `视频洞察报告 · ${videoId.value}`)
    ElMessage.success('报告已生成')
    router.push('/reports')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '生成报告失败')
  }
}

async function runCompare() {
  if (compareIds.value.length < 2) return ElMessage.warning('请至少选择 2 个视频')
  comparing.value = true
  try {
    const res = await analysisApi.compare(compareIds.value)
    compareResult.value = res.data
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '对比失败')
  } finally {
    comparing.value = false
  }
}

onMounted(() => { loadVideos(); loadUsage() })
onUnmounted(() => pollTimer && clearInterval(pollTimer))
</script>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 16px; flex-wrap: wrap; }
.hint { font-size: 12px; color: var(--text-secondary); }
.usage { font-size: 12px; color: var(--text-secondary); }
.stat-row { display: flex; gap: 16px; margin-bottom: 16px; }
.stat { flex: 1; background: #F8FAFC; border-radius: 10px; padding: 16px; text-align: center; }
.stat-value { font-size: 26px; font-weight: 600; color: #0D9488; }
.stat-label { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.panel { background: #fff; border: 1px solid #E2E8F0; border-radius: 10px; padding: 16px; }
.panel h4 { margin: 0 0 12px; font-size: 14px; }
.bar-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.bar-label { flex: 0 0 72px; font-size: 12px; color: var(--text-secondary); }
.bar-track { flex: 1; height: 8px; background: #F1F5F9; border-radius: 4px; overflow: hidden; }
.bar-fill { height: 100%; background: linear-gradient(90deg, #5EEAD4, #0D9488); }
.bar-fill.alt { background: linear-gradient(90deg, #BFDBFE, #3B82F6); }
.bar-value { flex: 0 0 40px; text-align: right; font-size: 12px; color: var(--text-secondary); }
.tag-wrap { display: flex; flex-wrap: wrap; gap: 6px; }
.summary-text { line-height: 1.8; color: var(--text-primary); margin: 0 0 12px; }
.purchase { font-size: 13px; color: var(--text-secondary); }
.insight-item { border-left: 3px solid #0D9488; padding-left: 10px; margin-bottom: 12px; }
.insight-title { font-size: 13px; font-weight: 600; }
.insight-desc { font-size: 12px; color: var(--text-secondary); margin-top: 2px; line-height: 1.6; }
.insight-meta { font-size: 11px; color: #94A3B8; margin-top: 4px; }
</style>
