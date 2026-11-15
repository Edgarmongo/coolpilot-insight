<template>
  <div ref="chartRef" style="width:100%;height:700px;"></div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ data: { type: Object, default: () => ({ nodes: [], links: [] }) } })
const chartRef = ref()
let chart = null

const NODE_COLORS = { '积极': '#10B981', '中性': '#94A3B8', '消极': '#EF4444' }

function initChart() {
  if (!chartRef.value) return
  if (!props.data.nodes || props.data.nodes.length === 0) return
  if (chart) chart.dispose()
  chart = echarts.init(chartRef.value)

  const nodes = props.data.nodes.map(n => {
    if (NODE_COLORS[n.name]) return { ...n, itemStyle: { color: NODE_COLORS[n.name], borderWidth: 0 } }
    return n
  })

  chart.setOption({
    tooltip: { trigger: 'item', triggerOn: 'mousemove', formatter: '{b}: {c} 点赞' },
    series: [{
      type: 'sankey', layoutIterations: 128, nodeWidth: 16, nodeGap: 8,
      top: '3%', bottom: '3%', left: '10%', right: '15%',
      nodeAlign: 'justify', emphasis: { focus: 'adjacency' },
      data: nodes, links: props.data.links,
      lineStyle: { color: 'gradient', curveness: 0.5, opacity: 0.2 },
      label: { fontSize: 11, color: '#475569' }
    }]
  })
}

watch(() => props.data, initChart, { deep: true })
onMounted(initChart)
onBeforeUnmount(() => { if (chart) chart.dispose() })
</script>
