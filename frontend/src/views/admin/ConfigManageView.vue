<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>系统配置</h2>
        <p class="sub">密钥类配置（如 API Key）永不回显，只显示是否已配置。修改后 60 秒内全节点生效。</p>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" class="tbl">
      <el-table-column prop="key" label="配置项" width="280" />
      <el-table-column prop="description" label="说明" width="200" />
      <el-table-column label="当前值" min-width="240">
        <template #default="{ row }">
          <template v-if="row.secret">
            <el-tag :type="row.configured ? 'success' : 'info'" size="small">
              {{ row.configured ? '已配置（不回显）' : '未配置' }}
            </el-tag>
          </template>
          <span v-else class="val">{{ row.value }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="valueType" label="类型" width="110" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">修改</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="'修改 ' + (editing.key || '')" width="480px">
      <el-form label-width="90px">
        <el-form-item label="说明">
          <span class="val">{{ editing.description }}</span>
        </el-form-item>
        <el-form-item label="新值">
          <el-input
            v-model="newValue"
            :type="editing.secret ? 'password' : 'text'"
            :placeholder="editing.secret ? '输入新密钥（留空表示不修改）' : '输入新值'"
            show-password
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-tag size="small">{{ editing.valueType }}</el-tag>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { configApi } from '../../api'

const rows = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const editing = ref({})
const newValue = ref('')

async function load() {
  loading.value = true
  try {
    const res = await configApi.list()
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  editing.value = row
  newValue.value = row.secret ? '' : row.value
  dialog.value = true
}

async function save() {
  if (!newValue.value) {
    ElMessage.warning('请输入新值')
    return
  }
  saving.value = true
  try {
    await configApi.update(editing.value.key, newValue.value)
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 4px; }
.page-head { margin-bottom: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; }
.sub { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.tbl { width: 100%; }
.val { color: var(--el-text-color-regular); }
</style>
