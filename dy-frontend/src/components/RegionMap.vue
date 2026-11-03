<template>
  <div class="region-map-wrap">
    <!-- 左侧：省份横向条形图（精确数值） -->
    <div ref="barRef" class="region-bar"></div>
    <!-- 右侧：中国地图（按评论数着色） -->
    <div ref="mapRef" class="region-map"></div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ data: { type: Array, default: () => [] } })
const barRef = ref()
const mapRef = ref()
let barChart = null
let mapChart = null
let geoJsonCache = null

/** 抖音返回的省名 → GeoJSON 中的全称 */
const NAME_MAP = {
  '北京': '北京市', '天津': '天津市', '河北': '河北省', '山西': '山西省', '内蒙古': '内蒙古自治区',
  '辽宁': '辽宁省', '吉林': '吉林省', '黑龙江': '黑龙江省', '上海': '上海市', '江苏': '江苏省',
  '浙江': '浙江省', '安徽': '安徽省', '福建': '福建省', '江西': '江西省', '山东': '山东省',
  '河南': '河南省', '湖北': '湖北省', '湖南': '湖南省', '广东': '广东省', '广西': '广西壮族自治区',
  '海南': '海南省', '重庆': '重庆市', '四川': '四川省', '贵州': '贵州省', '云南': '云南省',
  '陕西': '陕西省', '甘肃': '甘肃省', '青海': '青海省', '宁夏': '宁夏回族自治区', '新疆': '新疆维吾尔自治区',
  '西藏': '西藏自治区', '香港': '香港特别行政区', '澳门': '澳门特别行政区', '中国台湾': '台湾省',
  '港澳台': '香港特别行政区',
}

/** 把接口返回的地区名转成 GeoJSON 使用的名称 */
function toMapName(region) {
  if (!region) return ''
  if (NAME_MAP[region]) return NAME_MAP[region]
  if (region === '香港' || region === '澳门' || region === '台湾') return '香港特别行政区'
  return region
}

/** 拉取地图数据：优先本地打包（离线可用），失败回退 CDN */
async function fetchGeoJson() {
  if (geoJsonCache) return geoJsonCache
  try {
    const local = await fetch('/geo/china.json')
    if (local.ok) {
      geoJsonCache = await local.json()
      return geoJsonCache
    }
  } catch (e) { /* 回退 CDN */ }
  try {
    const res = await fetch('https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json')
    geoJsonCache = await res.json()
  } catch (e) { geoJsonCache = null }
  return geoJsonCache
}

/** 地图与条形图共用的色阶：颜色越深表示评论越多 */
const COLOR_RANGE = ['#E6FFFA', '#99F6E4', '#5EEAD4', '#2DD4BF', '#14B8A6', '#0D9488', '#0F766E']

/** 按数值在色阶中取色，保证"条形深浅"与"地图深浅"含义一致 */
function colorByValue(value, max) {
  if (!max) return COLOR_RANGE[0]
  const idx = Math.min(COLOR_RANGE.length - 1, Math.round((value / max) * (COLOR_RANGE.length - 1)))
  return COLOR_RANGE[idx]
}

/** 左侧条形图：省份 + 评论数 */
function renderBar() {
  if (!barRef.value) return
  if (barChart) barChart.dispose()
  barChart = echarts.init(barRef.value)

  const items = props.data.slice(0, 12)
  const names = items.map(d => d.region)
  const values = items.map(d => d.count)
  const max = Math.max(...props.data.map(d => d.count), 1)

  barChart.setOption({
    animationDuration: 600,
    tooltip: {
      trigger: 'axis', axisPointer: { type: 'shadow' },
      backgroundColor: '#fff', borderColor: '#E2E8F0', textStyle: { color: '#1E293B', fontSize: 12 },
    },
    grid: { left: '2%', right: '20%', top: '10%', bottom: '10%', containLabel: true },
    xAxis: { type: 'value', max: max, axisLabel: { show: false }, splitLine: { show: false }, axisLine: { show: false }, axisTick: { show: false } },
    yAxis: { type: 'category', data: [...names].reverse(), axisLabel: { show: false }, axisLine: { show: false }, axisTick: { show: false } },
    series: [{
      type: 'bar', data: [...values].reverse(), name: '评论数',
      barMaxWidth: 14,
      // 每根条按自身数值取色，与地图色阶一致
      itemStyle: {
        borderRadius: [0, 4, 4, 0],
        color: function (p) { return colorByValue(p.value, max) },
      },
      label: { show: true, position: 'right', fontSize: 12, color: '#64748B', formatter: '{b}  {c}' },
    }],
  })
}

/** 右侧地图：按评论数深浅着色 */
function renderMap() {
  if (!mapRef.value || !geoJsonCache) return
  if (mapChart) mapChart.dispose()
  mapChart = echarts.init(mapRef.value)

  const mapData = props.data.map(d => ({ name: toMapName(d.region), value: d.count }))
  const max = Math.max(...mapData.map(d => d.value), 1)

  mapChart.setOption({
    animationDuration: 600,
    tooltip: {
      trigger: 'item',
      backgroundColor: '#fff', borderColor: '#E2E8F0', textStyle: { color: '#1E293B', fontSize: 12 },
      formatter: function (p) {
        const v = p.value || 0
        return p.name + '<br/>评论数：' + v + ' 条'
      },
    },
    visualMap: {
      type: 'continuous',
      min: 0,
      max: max,
      left: 8,
      bottom: 4,
      itemWidth: 10,
      itemHeight: 80,
      calculable: false,
      text: ['多', '少'],
      textStyle: { color: '#64748B', fontSize: 11 },
      inRange: {
        // 与条形图共用同一色阶
        color: COLOR_RANGE,
      },
    },
    series: [{
      type: 'map',
      map: 'china',
      name: '地区分布',
      roam: false,
      zoom: 1.1,
      center: [104, 36],
      top: '4%',
      left: '4%',
      right: '4%',
      bottom: '10%',
      data: mapData,
      // 无数据省份浅灰底；有数据省份由 visualMap 按数值着色
      itemStyle: { areaColor: '#F1F5F9', borderColor: '#CBD5E1', borderWidth: 0.8 },
      emphasis: {
        itemStyle: { areaColor: '#0F766E', borderColor: '#0F766E' },
        label: { show: true, color: '#fff', fontSize: 11 },
      },
      label: { show: false },
    }],
  })
}

async function initAll() {
  renderBar()
  const geoJson = await fetchGeoJson()
  if (geoJson) {
    try {
      echarts.registerMap('china', geoJson)
      renderMap()
    } catch (e) { /* 地图失败时保留条形图 */ }
  }
}

function resize() {
  if (barChart) barChart.resize()
  if (mapChart) mapChart.resize()
}

watch(() => props.data, initAll, { deep: true })
onMounted(() => {
  initAll()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (barChart) barChart.dispose()
  if (mapChart) mapChart.dispose()
})
</script>

<style scoped>
.region-map-wrap {
  display: flex;
  width: 100%;
  height: 340px;
}
.region-bar {
  width: 42%;
  height: 100%;
}
.region-map {
  flex: 1;
  height: 100%;
  min-width: 0;
}
</style>
