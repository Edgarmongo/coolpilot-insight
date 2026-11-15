<template>
  <div class="page-container">
    <h3 class="page-title">用户管理</h3>
    <div class="card">
      <el-table :data="tableData" v-loading="loading" stripe border style="width:100%;">
        <el-table-column prop="id" label="ID" width="60" align="center" />
        <el-table-column prop="username" label="用户名" width="150" />
        <el-table-column prop="phone" label="手机号" width="140" />
        <el-table-column label="角色" width="100" align="center">
          <template #default="{ row }">
            <span class="role-tag" :class="row.role === 1 ? 'admin' : 'user'">{{ row.role === 1 ? '管理员' : '用户' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="注册时间" width="170" />
        <el-table-column label="操作" width="170" align="center">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openRoles(row)">分配角色</el-button>
            <el-popconfirm title="确定删除？" @confirm="doDelete(row.id)">
              <template #reference>
                <el-button type="danger" size="small" :disabled="row.role === 1">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <el-dialog v-model="roleDialog" :title="'分配角色 · ' + (roleUser.phone || '')" width="420px">
        <el-checkbox-group v-model="selectedRoleIds">
          <el-checkbox v-for="r in allRoles" :key="r.id" :value="r.id" :label="r.id">
            {{ r.name }}（{{ r.code }}）
          </el-checkbox>
        </el-checkbox-group>
        <p class="hint">保存后立即生效。角色决定可见菜单与可调接口。</p>
        <template #footer>
          <el-button @click="roleDialog = false">取消</el-button>
          <el-button type="primary" :loading="savingRoles" @click="saveRoles">保存</el-button>
        </template>
      </el-dialog>

      <div style="margin-top:16px;display:flex;justify-content:flex-end;">
        <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="prev, pager, next, total" @current-change="loadData" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { adminApi, roleApi } from '../../api'
import { ElMessage } from 'element-plus'

const tableData = ref([]); const loading = ref(false); const page = ref(1); const size = ref(20); const total = ref(0)
async function loadData() { loading.value = true; try { const res = await adminApi.listUsers({ page: page.value, size: size.value }); tableData.value = res.data.records || []; total.value = res.data.total || 0 } finally { loading.value = false } }
const roleDialog = ref(false)
const roleUser = ref({})
const allRoles = ref([])
const selectedRoleIds = ref([])
const savingRoles = ref(false)

async function openRoles(row) {
  roleUser.value = row
  const [roles, mine] = await Promise.all([roleApi.list(), roleApi.rolesOfUser(row.id)])
  allRoles.value = roles.data || []
  selectedRoleIds.value = (mine.data || []).map((r) => r.id)
  roleDialog.value = true
}

/** 差量提交：只对新增/移除的角色发请求，避免把未变动的也重写一遍 */
async function saveRoles() {
  savingRoles.value = true
  try {
    const before = new Set((await roleApi.rolesOfUser(roleUser.value.id)).data.map((r) => r.id))
    const after = new Set(selectedRoleIds.value)
    const toAdd = [...after].filter((id) => !before.has(id))
    const toDel = [...before].filter((id) => !after.has(id))

    for (const id of toAdd) await roleApi.assign(id, roleUser.value.id)
    for (const id of toDel) await roleApi.revoke(id, roleUser.value.id)

    ElMessage.success('已保存')
    roleDialog.value = false
    loadData()
  } finally {
    savingRoles.value = false
  }
}

async function doDelete(id) { try { await adminApi.deleteUser(id); ElMessage.success('已删除'); loadData() } catch (e) { /* */ } }
onMounted(loadData)
</script>
