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

  const dates = props.data.map(d => d.date)
  const values = props.data.map(d => d.count)

  chart.setOption({
    tooltip: { trigger: 'axis', backgroundColor: '#fff', borderColor: '#E2E8F0', textStyle: { color: '#1E293B', fontSize: 12 } },
    grid: { left: '3%', right: '5%', top: '8%', bottom: '3%', containLabel: true },
    xAxis: {
      type: 'category', data: dates, axisLine: { lineStyle: { color: '#E2E8F0' } },
      axisTick: { show: false }, axisLabel: { color: '#94A3B8', fontSize: 11, rotate: dates.length > 10 ? 30 : 0 },
    },
    yAxis: {
      type: 'value', splitLine: { show: false }, axisLine: { show: false },
      axisTick: { show: false }, axisLabel: { color: '#94A3B8', fontSize: 11 },
    },
    series: [{
      type: 'line', data: values, smooth: true, symbol: 'circle', symbolSize: 4,
      lineStyle: { color: '#0D9488', width: 2 },
      itemStyle: { color: '#0D9488' },
      areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: 'rgba(13,148,136,0.15)' }, { offset: 1, color: 'rgba(13,148,136,0.01)' }
      ]) },
    }]
  })
}

watch(() => props.data, initChart, { deep: true })
onMounted(initChart)
onBeforeUnmount(() => { if (chart) chart.dispose() })
</script>
