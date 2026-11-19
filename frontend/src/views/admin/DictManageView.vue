<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>字典管理</h2>
        <p class="sub">业务分类的单一来源。参与流程判断的固定状态仍由后端枚举控制，不在此维护。</p>
      </div>
    </div>

    <div class="layout">
      <el-card class="side" shadow="never">
        <div class="side-head">
          <span>字典类型</span>
          <el-button v-if="hasPerm('system:dict:manage')" size="small" link type="primary" @click="openType">新建</el-button>
        </div>
        <el-menu :default-active="activeCode" class="type-menu" @select="selectType">
          <el-menu-item v-for="t in types" :key="t.typeCode" :index="t.typeCode">
            <span>{{ t.typeName }}</span>
            <span class="code">{{ t.typeCode }}</span>
          </el-menu-item>
        </el-menu>
      </el-card>

      <el-card class="main" shadow="never">
        <div class="side-head">
          <span>{{ activeName || '请选择字典类型' }}</span>
          <el-button v-if="hasPerm('system:dict:manage')" size="small" type="primary" :disabled="!activeCode" @click="openItem">新增字典项</el-button>
        </div>
        <el-table :data="items" v-loading="loading" class="tbl">
          <el-table-column prop="itemValue" label="值" width="180" />
          <el-table-column prop="itemLabel" label="标签" />
          <el-table-column prop="sort" label="排序" width="90" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                {{ row.status === 'ENABLED' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-dialog v-model="typeDialog" title="新建字典类型" width="440px">
      <el-form :model="typeForm" label-width="90px">
        <el-form-item label="编码">
          <el-input v-model="typeForm.typeCode" placeholder="小写字母/数字/下划线，如 campaign" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="typeForm.typeName" placeholder="如 活动来源" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="typeForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialog = false">取消</el-button>
        <el-button type="primary" @click="submitType">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="itemDialog" title="新增字典项" width="440px">
      <el-form :model="itemForm" label-width="90px">
        <el-form-item label="值">
          <el-input v-model="itemForm.itemValue" placeholder="同一类型内唯一" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="itemForm.itemLabel" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="itemForm.sort" :min="0" :step="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog = false">取消</el-button>
        <el-button type="primary" @click="submitItem">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../api/request'
import { dictApi } from '../../api'
import { usePermission } from '../../composables/usePermission'

const { hasPerm } = usePermission()

const types = ref([])
const items = ref([])
const activeCode = ref('')
const loading = ref(false)
const typeDialog = ref(false)
const itemDialog = ref(false)
const typeForm = ref({ typeCode: '', typeName: '', remark: '' })
const itemForm = ref({ itemValue: '', itemLabel: '', sort: 10 })

const activeName = computed(() =>
  (types.value.find((t) => t.typeCode === activeCode.value) || {}).typeName)

async function loadTypes() {
  const res = await dictApi.types()
  types.value = res.data || []
  if (!activeCode.value && types.value.length) {
    await selectType(types.value[0].typeCode)
  }
}

async function selectType(code) {
  activeCode.value = code
  loading.value = true
  try {
    const res = await dictApi.items(code)
    items.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openType() {
  typeForm.value = { typeCode: '', typeName: '', remark: '' }
  typeDialog.value = true
}

async function submitType() {
  if (!typeForm.value.typeCode || !typeForm.value.typeName) {
    ElMessage.warning('编码与名称都要填')
    return
  }
  await request.post('/v1/system/dicts', typeForm.value)
  ElMessage.success('已创建')
  typeDialog.value = false
  await loadTypes()
}

function openItem() {
  itemForm.value = { itemValue: '', itemLabel: '', sort: 10 }
  itemDialog.value = true
}

async function submitItem() {
  if (!itemForm.value.itemValue || !itemForm.value.itemLabel) {
    ElMessage.warning('值与标签都要填')
    return
  }
  await request.post(`/v1/system/dicts/${activeCode.value}/items`, itemForm.value)
  ElMessage.success('已新增')
  itemDialog.value = false
  await selectType(activeCode.value)
}

onMounted(loadTypes)
</script>

<style scoped>
.page { padding: 4px; }
.page-head { margin-bottom: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; }
.sub { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.layout { display: flex; gap: 16px; align-items: flex-start; }
.side { width: 280px; flex: none; }
.main { flex: 1; min-width: 0; }
.side-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-weight: 600; }
.type-menu { border-right: none; }
.type-menu .code { margin-left: auto; color: var(--el-text-color-placeholder); font-size: 12px; }
.tbl { width: 100%; }
</style>
