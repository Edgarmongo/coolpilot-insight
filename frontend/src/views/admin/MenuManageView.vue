<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>菜单管理</h2>
        <p class="sub">菜单只决定「显示与导航」。隐藏菜单不等于禁止调用接口，权限由后端 @RequirePermission 独立校验。</p>
      </div>
      <div class="filters">
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-table
      :data="tree"
      v-loading="loading"
      row-key="id"
      :tree-props="{ children: 'children' }"
      default-expand-all
      class="tbl"
    >
      <el-table-column prop="name" label="名称" min-width="220" />
      <el-table-column prop="path" label="路由" min-width="180">
        <template #default="{ row }">
          <span class="mono">{{ row.path || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="menuType" label="类型" width="100" />
      <el-table-column prop="permissionCode" label="所需权限" min-width="180">
        <template #default="{ row }">
          <span v-if="row.permissionCode" class="mono">{{ row.permissionCode }}</span>
          <span v-else class="dim">登录可见</span>
        </template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="90" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
            {{ row.status === 'ENABLED' ? '已上线' : '规划中' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            link
            type="danger"
            :disabled="row.children && row.children.length > 0"
            :title="row.children && row.children.length ? '请先删除子菜单' : ''"
            @click="remove(row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { menuApi } from '../../api'

const tree = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await menuApi.tree()
    tree.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除菜单「${row.name}」？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  await menuApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
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
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.dim { color: var(--el-text-color-placeholder); font-size: 12px; }
</style>
