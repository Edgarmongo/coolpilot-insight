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
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-popconfirm title="确定删除？" @confirm="doDelete(row.id)">
              <template #reference>
                <el-button type="danger" size="small" :disabled="row.role === 1">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top:16px;display:flex;justify-content:flex-end;">
        <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="prev, pager, next, total" @current-change="loadData" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api'
import { ElMessage } from 'element-plus'

const tableData = ref([]); const loading = ref(false); const page = ref(1); const size = ref(20); const total = ref(0)
async function loadData() { loading.value = true; try { const res = await adminApi.listUsers({ page: page.value, size: size.value }); tableData.value = res.data.records || []; total.value = res.data.total || 0 } finally { loading.value = false } }
async function doDelete(id) { try { await adminApi.deleteUser(id); ElMessage.success('已删除'); loadData() } catch (e) { /* */ } }
onMounted(loadData)
</script>
