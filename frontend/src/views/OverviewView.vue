<template>
  <div class="page-container">
    <h3 class="page-title">运营概览</h3>

    <div class="stat-row">
      <div class="stat"><div class="stat-value">{{ data.videos }}</div><div class="stat-label">视频总数</div></div>
      <div class="stat"><div class="stat-value">{{ data.comments }}</div><div class="stat-label">评论总数</div></div>
      <div class="stat"><div class="stat-value">{{ data.users }}</div><div class="stat-label">参与用户</div></div>
      <div class="stat"><div class="stat-value">{{ data.analyzed }}</div><div class="stat-label">已分析</div></div>
      <div class="stat highlight"><div class="stat-value">{{ data.pendingReview }}</div><div class="stat-label">待复核</div></div>
    </div>

    <div class="grid2">
      <div class="card">
        <div class="card-header"><span class="card-title">近 14 天评论趋势</span></div>
        <div ref="trendRef" class="chart"></div>
      </div>
      <div class="card">
        <div class="card-header"><span class="card-title">高频痛点 Top12</span></div>
        <div ref="painRef" class="chart"></div>
      </div>
    </div>

    <div class="grid2" style="margin-top:16px;">
      <div class="card">
        <div class="card-header"><span class="card-title">评论量 Top10 视频</span></div>
        <el-table :data="data.topVideos" border size="small" empty-text="暂无数据">
          <el-table-column prop="videoId" label="视频ID" width="190" />
          <el-table-column prop="title" label="标题" show-overflow-tooltip />
          <el-table-column prop="comments" label="评论数" width="90" align="center" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="openVideo(row)">看板</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="card">
        <div class="card-header">
          <span class="card-title">复核与成本</span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
        <div class="review-grid">
          <div class="mini"><span class="mini-label">已通过</span><span class="mini-value">{{ data.approved }}</span></div>
          <div class="mini"><span class="mini-label">已纠正</span><span class="mini-value">{{ data.corrected }}</span></div>
          <div class="mini"><span class="mini-label">待复核</span><span class="mini-value">{{ data.pendingReview }}</span></div>
        </div>
        <div class="cost">
          今日 Token <b>{{ data.todayTokens }}</b> ｜ 今日成本 <b>¥{{ data.todayCost }}</b>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { overviewApi } from '../api'
import { useRouter } from 'vue-router'
import { useAppStore } from '../stores/user'

const router = useRouter()
const appStore = useAppStore()
const data = ref({
  videos: 0, comments: 0, users: 0, analyzed: 0, pendingReview: 0,
  approved: 0, corrected: 0, todayTokens: 0, todayCost: 0,
  trend: [], topVideos: [], painPointTop: [],
})
const trendRef = ref()
const painRef = ref()
let trendChart = null
let painChart = null

async function load() {
  const res = await overviewApi.get()
  data.value = { ...data.value, ...res.data }
  await nextTick()
  renderTrend()
  renderPain()
}

function renderTrend() {
  if (!trendRef.value) return
  if (trendChart) trendChart.dispose()
  trendChart = echarts.init(trendRef.value)
  const rows = data.value.trend || []
  if (!rows.length) {
    trendChart.setOption(emptyOption('暂无评论时间数据'))
    return
  }
  trendChart.setOption({
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: rows.map(r => r.date), axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#F1F5F9' } } },
    series: [{
      type: 'line', smooth: true, data: rows.map(r => r.count),
      itemStyle: { color: '#0D9488' },
      areaStyle: { color: 'rgba(13,148,136,0.12)' },
    }],
  })
}

function renderPain() {
  if (!painRef.value) return
  if (painChart) painChart.dispose()
  painChart = echarts.init(painRef.value)
  const rows = [...(data.value.painPointTop || [])].reverse()
  if (!rows.length) {
    painChart.setOption(emptyOption('暂无结构化痛点\n需先在「洞察」页运行 AI 评论级分析'))
    return
  }
  painChart.setOption({
    grid: { left: 90, right: 30, top: 10, bottom: 20 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#F1F5F9' } } },
    yAxis: { type: 'category', data: rows.map(r => r.name), axisLabel: { fontSize: 11 } },
    series: [{
      type: 'bar', data: rows.map(r => r.value), barMaxWidth: 14,
      itemStyle: { color: '#F97316', borderRadius: [0, 4, 4, 0] },
    }],
  })
}

function openVideo(row) {
  appStore.currentVideo = row.videoId
  router.push('/dashboard')
}

/** 图表空状态：避免空白画布被误读为渲染失败 */
function emptyOption(text) {
  return {
    title: {
      text,
      left: 'center',
      top: 'middle',
      textStyle: { color: '#94A3B8', fontSize: 12, lineHeight: 20, align: 'center' },
    },
  }
}

function onResize() {
  trendChart?.resize()
  painChart?.resize()
}

onMounted(() => { load(); window.addEventListener('resize', onResize) })
onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  trendChart?.dispose()
  painChart?.dispose()
})
</script>

<style scoped>
.stat-row { display: flex; gap: 14px; margin-bottom: 16px; }
.stat { flex: 1; background: #fff; border: 1px solid #E2E8F0; border-radius: 10px; padding: 18px; text-align: center; }
.stat.highlight { border-color: #FDBA74; background: #FFF7ED; }
.stat-value { font-size: 26px; font-weight: 600; color: #0D9488; }
.stat.highlight .stat-value { color: #EA580C; }
.stat-label { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.card-title { font-size: 14px; font-weight: 600; }
.chart { height: 260px; }
.review-grid { display: flex; gap: 12px; }
.mini { flex: 1; background: #F8FAFC; border-radius: 8px; padding: 14px; text-align: center; }
.mini-label { display: block; font-size: 12px; color: var(--text-secondary); }
.mini-value { display: block; font-size: 20px; font-weight: 600; color: #0F172A; margin-top: 4px; }
.cost { margin-top: 16px; font-size: 13px; color: var(--text-secondary); }
</style>
