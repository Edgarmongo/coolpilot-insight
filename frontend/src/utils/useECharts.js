import { ref, watch, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'

export function useECharts() {
  const chartRef = ref(null)
  let chart = null

  function initChart(setOptionFn) {
    if (!chartRef.value) return
    if (chart) chart.dispose()
    chart = echarts.init(chartRef.value)
    if (setOptionFn) setOptionFn(chart)
  }

  function disposeChart() {
    if (chart) { chart.dispose(); chart = null }
  }

  return { chartRef, initChart, disposeChart }
}

export function useEChartsWithData(getData, setOption) {
  const { chartRef, initChart, disposeChart } = useECharts()

  watch(getData, () => { initChart(setOption) }, { deep: true })

  onBeforeUnmount(disposeChart)

  return { chartRef, initChart: () => initChart(setOption) }
}
