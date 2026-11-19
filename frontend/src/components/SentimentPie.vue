<template>
  <div ref="chartRef" class="chart-box"></div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ data: { type: Array, default: () => [] } })
const chartRef = ref()
let chart = null

function initChart() {
  if (!chartRef.value) return
  if (chart) chart.dispose()
  chart = echarts.init(chartRef.value)

  const colors = { '积极': '#10B981', '中性': '#64748B', '消极': '#EF4444' }
  const pieData = props.data.map(d => ({ name: d.sentiment, value: d.count }))
  const total = pieData.reduce((s, d) => s + d.value, 0)

  chart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 条 ({d}%)' },
    legend: { bottom: 0, textStyle: { fontSize: 12, color: '#64748B' } },
    graphic: total > 0 ? [{
      type: 'text', left: 'center', top: 'center',
      style: { text: total + '\n评论总数', textAlign: 'center', fill: '#1E293B', fontSize: 16, fontWeight: 700, lineHeight: 22 },
    }] : [],
    series: [{
      type: 'pie', radius: ['55%', '75%'], center: ['50%', '45%'],
      avoidLabelOverlap: false,
      itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      emphasis: { label: { show: true, fontWeight: 'bold' } },
      data: pieData,
      color: pieData.map(d => colors[d.name] || '#ccc'),
    }]
  })
}

watch(() => props.data, initChart, { deep: true })
onMounted(initChart)
onBeforeUnmount(() => { if (chart) chart.dispose() })
</script>
