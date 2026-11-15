<template>
  <div ref="chartRef" class="chart-box"></div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import 'echarts-wordcloud'

const props = defineProps({ data: { type: Array, default: () => [] } })
const chartRef = ref()
let chart = null

const COLORS = ['#0D9488','#14B8A6','#2DD4BF','#5EEAD4','#0F766E','#115E59','#99F6E4','#CCFBF1']

function initChart() {
  if (!chartRef.value) return
  if (chart) chart.dispose()
  chart = echarts.init(chartRef.value)

  if (!props.data || props.data.length === 0) {
    chart.setOption({
      title: { text: '暂无数据\n请先采集清洗后刷新分析', left: 'center', top: 'center',
        textStyle: { color: '#94A3B8', fontSize: 14, fontWeight: 'normal' } }
    })
    return
  }

  const words = props.data.map(d => ({ name: d.name, value: d.value }))
  chart.setOption({
    tooltip: { show: true, formatter: '{b}: {c} 次' },
    series: [{
      type: 'wordCloud', shape: 'circle',
      left: 'center', top: 'center', width: '90%', height: '90%',
      sizeRange: [14, 52], rotationRange: [-30, 30], rotationStep: 15,
      gridSize: 8, drawOutOfBound: false,
      textStyle: {
        fontFamily: '"PingFang SC","Microsoft YaHei",sans-serif',
        fontWeight: 'normal',
        color: () => COLORS[Math.floor(Math.random() * COLORS.length)]
      },
      emphasis: { textStyle: { fontWeight: 'bold' } },
      data: words.sort((a, b) => b.value - a.value)
    }]
  })
}

watch(() => props.data, initChart, { deep: true })
onMounted(initChart)
onBeforeUnmount(() => { if (chart) chart.dispose() })
</script>
