<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>操作审计</h2>
        <p class="sub">关键操作留痕。审计写入失败不影响业务，因此这里查不到不等于操作没发生。</p>
      </div>
      <div class="filters">
        <el-input v-model="action" placeholder="按动作过滤，如 LOGIN" clearable style="width: 220px" @clear="load" @keyup.enter="load" />
        <el-button @click="load">查询</el-button>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" class="tbl">
      <el-table-column prop="createdAt" label="时间" width="180" />
      <el-table-column prop="action" label="动作" width="180" />
      <el-table-column prop="resourceType" label="对象类型" width="150" />
      <el-table-column prop="resourceId" label="对象 ID" width="110" />
      <el-table-column prop="userId" label="操作人" width="100" />
      <el-table-column prop="clientIp" label="IP" width="140" />
      <el-table-column prop="detailJson" label="详情" min-width="220" show-overflow-tooltip />
    </el-table>

    <div class="pager">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPage"
      />
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { auditApi } from '../../api'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const action = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await auditApi.page({
      action: action.value || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = res.data?.records || []
    total.value = res.data?.total || 0
  } finally {
    loading.value = false
  }
}

function onPage(p) {
  page.value = p
  load()
}

onMounted(load)
</script>

<style scoped>
.page { padding: 4px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; gap: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; }
.sub { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.filters { display: flex; gap: 8px; }
.tbl { width: 100%; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
