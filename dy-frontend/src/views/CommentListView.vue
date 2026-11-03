<template>
  <div class="page-container">
    <h3 class="page-title">评论数据</h3>
    <div class="card">
      <div class="filter-row">
        <el-select v-model="appStore.currentVideo" @change="loadData" style="width:200px;" size="large">
          <el-option v-for="v in videoList" :key="v.videoId" :label="v.videoId" :value="v.videoId" />
        </el-select>
        <el-input v-model="searchKeyword" placeholder="搜索评论…" style="width:220px;" clearable size="large" />
        <el-select v-model="filterRegion" placeholder="地区" style="width:130px;" clearable size="large">
          <el-option-group label="直辖市">
            <el-option label="北京" value="北京" /><el-option label="天津" value="天津" />
            <el-option label="上海" value="上海" /><el-option label="重庆" value="重庆" />
          </el-option-group>
          <el-option-group label="自治区">
            <el-option label="内蒙古" value="内蒙古" /><el-option label="广西" value="广西" />
            <el-option label="西藏" value="西藏" /><el-option label="宁夏" value="宁夏" />
            <el-option label="新疆" value="新疆" />
          </el-option-group>
          <el-option-group label="省份">
            <el-option label="河北" value="河北" /><el-option label="山西" value="山西" />
            <el-option label="辽宁" value="辽宁" /><el-option label="吉林" value="吉林" />
            <el-option label="黑龙江" value="黑龙江" /><el-option label="江苏" value="江苏" />
            <el-option label="浙江" value="浙江" /><el-option label="安徽" value="安徽" />
            <el-option label="福建" value="福建" /><el-option label="江西" value="江西" />
            <el-option label="山东" value="山东" /><el-option label="河南" value="河南" />
            <el-option label="湖北" value="湖北" /><el-option label="湖南" value="湖南" />
            <el-option label="广东" value="广东" /><el-option label="海南" value="海南" />
            <el-option label="四川" value="四川" /><el-option label="贵州" value="贵州" />
            <el-option label="云南" value="云南" /><el-option label="陕西" value="陕西" />
            <el-option label="甘肃" value="甘肃" /><el-option label="青海" value="青海" />
            <el-option label="台湾" value="中国台湾" />
          </el-option-group>
          <el-option-group label="港澳台">
            <el-option label="中国台湾" value="中国台湾" />
            <el-option label="中国香港" value="中国香港" />
            <el-option label="中国澳门" value="中国澳门" />
          </el-option-group>
          <el-option-group label="其他">
            <el-option label="海外" value="海外" /><el-option label="未知" value="未知" />
          </el-option-group>
        </el-select>
        <el-select v-model="filterSentiment" placeholder="情感" style="width:110px;" clearable size="large">
          <el-option label="积极" :value="1" /><el-option label="中性" :value="0" /><el-option label="消极" :value="-1" />
        </el-select>
        <el-select v-model="sortBy" style="width:130px;" size="large">
          <el-option label="时间倒序" value="time_desc" /><el-option label="时间正序" value="time_asc" />
          <el-option label="点赞倒序" value="likes_desc" /><el-option label="点赞正序" value="likes_asc" />
        </el-select>
        <el-button type="primary" @click="loadData" size="large">查询</el-button>
        <el-button @click="openTree" size="large">回复树</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" stripe :row-class-name="() => 'el-table__row--striped'" border style="width:100%;">
        <el-table-column label="昵称 / 抖音号" width="200">
          <template #default="{ row }">
            <div style="line-height:1.5;">
              <div>{{ row.nickname }}</div>
              <div v-if="row.douyinId" style="font-size:12px;color:var(--text-tertiary);font-family:var(--font-mono);">
                @{{ row.douyinId }}
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="region" label="IP归属地" width="100" />
        <el-table-column prop="publishTime" label="发布时间" width="170" />
        <el-table-column prop="content" label="评论内容" min-width="260" show-overflow-tooltip />
        <el-table-column prop="likes" label="点赞" width="80" align="center" />
        <el-table-column label="情感" width="90" align="center">
          <template #default="{ row }">
            <span class="sentiment-pill" :class="row.sentiment === 1 ? 'positive' : row.sentiment === -1 ? 'negative' : 'neutral'">
              {{ row.sentiment === 1 ? '积极' : row.sentiment === -1 ? '消极' : '中性' }}
            </span>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top:16px;display:flex;justify-content:flex-end;">
        <el-pagination v-model:current-page="page" :page-size="size" :total="total"
          layout="prev, pager, next, total" @current-change="loadData" />
      </div>
    </div>

    <el-dialog v-model="treeDialog" title="评论回复树" width="820px" top="6vh">
      <div v-if="treeData" class="tree-summary">
        共 {{ treeData.totalComments }} 条评论 / {{ treeData.totalRoots }} 个主楼，
        当前展示前 {{ treeData.shownRoots }} 个主楼
      </div>
      <div class="tree-box" v-loading="treeLoading">
        <div v-for="root in treeData?.roots || []" :key="root.cid || root.id" class="tree-root">
          <div class="tree-line">
            <span class="tree-nick">{{ root.nickname }}</span>
            <span class="tree-region">{{ root.region }}</span>
            <span class="tree-likes">👍 {{ root.likes }}</span>
          </div>
          <div class="tree-content">{{ root.content }}</div>
          <div v-for="rep in root.replies || []" :key="rep.cid || rep.id" class="tree-reply">
            <span class="tree-nick">{{ rep.nickname }}</span>
            <span class="tree-region">{{ rep.region }}</span>
            <span class="tree-likes">👍 {{ rep.likes }}</span>
            <span class="tree-content inline">{{ rep.content }}</span>
          </div>
        </div>
        <el-empty v-if="!treeLoading && !(treeData?.roots || []).length" description="暂无评论" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAppStore } from '../stores/user'
import { commentApi, analysisApi } from '../api'

const appStore = useAppStore()
const searchKeyword = ref(''); const filterRegion = ref('')
const filterSentiment = ref(null); const sortBy = ref('time_desc')
const videoList = ref([]); const tableData = ref([])
const loading = ref(false); const page = ref(1); const size = ref(20); const total = ref(0)

async function loadVideoList() {
  try {
    const res = await analysisApi.videoList()
    videoList.value = res.data || []
    // 自动选中第一个视频，否则 currentVideo 为空导致列表无数据
    if (!appStore.currentVideo && videoList.value.length > 0) {
      appStore.currentVideo = videoList.value[0].videoId
    }
  } catch (e) { /* */ }
}

async function loadData() {
  if (!appStore.currentVideo) return
  loading.value = true
  try {
    let res
    if (searchKeyword.value) {
      res = await commentApi.search({ keyword: searchKeyword.value, videoId: appStore.currentVideo, page: page.value, size: size.value })
    } else {
      res = await commentApi.list({ videoId: appStore.currentVideo, page: page.value, size: size.value, region: filterRegion.value || undefined, sentiment: filterSentiment.value, sort: sortBy.value })
    }
    tableData.value = res.data.records || []
    total.value = res.data.total || 0
  } finally { loading.value = false }
}

onMounted(async () => {
  await loadVideoList()
  if (appStore.currentVideo) loadData()
})

// ---- 回复树（移植自 DYP 的评论树视图）----
const treeDialog = ref(false)
const treeLoading = ref(false)
const treeData = ref(null)

async function openTree() {
  if (!appStore.currentVideo) return
  treeDialog.value = true
  treeLoading.value = true
  treeData.value = null
  try {
    const res = await commentApi.tree({ videoId: appStore.currentVideo, limit: 1000, rootLimit: 50 })
    treeData.value = res.data
  } finally {
    treeLoading.value = false
  }
}
</script>

<style scoped>
.tree-summary { font-size: 12px; color: var(--text-secondary); margin-bottom: 12px; }
.tree-box { max-height: 62vh; overflow: auto; padding-right: 6px; }
.tree-root { border-bottom: 1px dashed #E2E8F0; padding: 10px 0; }
.tree-line { display: flex; gap: 8px; align-items: center; font-size: 12px; }
.tree-nick { font-weight: 600; color: var(--text-primary); }
.tree-region { color: #94A3B8; }
.tree-likes { color: #94A3B8; }
.tree-content { font-size: 13px; line-height: 1.7; margin-top: 4px; }
.tree-content.inline { display: inline; margin-top: 0; }
.tree-reply {
  margin: 8px 0 0 18px; padding-left: 10px; border-left: 2px solid #E2E8F0;
  font-size: 12.5px; color: #334155; line-height: 1.7;
}
</style>
