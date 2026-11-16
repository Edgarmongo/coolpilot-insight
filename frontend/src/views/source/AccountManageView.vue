<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>平台账号</h2>
        <p class="sub">
          授权状态与启用状态相互独立：启用只是允许使用，授权有效才真正可用。
          凭证只保存引用，任何接口都不返回凭证明文。
        </p>
      </div>
      <el-button v-if="hasPerm('source:account:manage')" type="primary" @click="openCreate">添加账号</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" class="tbl">
      <el-table-column label="平台" width="110">
        <template #default="{ row }">{{ sourceName(row.sourceId) }}</template>
      </el-table-column>
      <el-table-column prop="accountName" label="账号" min-width="140" />
      <el-table-column prop="externalAccountId" label="平台标识" min-width="160" />
      <el-table-column label="授权状态" width="120">
        <template #default="{ row }">
          <el-tag :type="authType(row.authStatus)" size="small">{{ authText(row.authStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="过期时间" width="180">
        <template #default="{ row }">{{ row.expiresAt || '无已知期限' }}</template>
      </el-table-column>
      <el-table-column label="最近验证" width="180">
        <template #default="{ row }">{{ row.lastVerifiedAt || '从未验证' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button v-if="hasPerm('source:account:manage')" size="small" link type="primary"
                     @click="doVerify(row)">验证</el-button>
          <el-button v-if="hasPerm('source:account:manage')" size="small" link
                     @click="openEdit(row)">编辑</el-button>
          <el-button v-if="hasPerm('source:account:manage')" size="small" link
                     :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
                     @click="toggle(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert v-if="rows.length && !hasVerified" type="info" :closable="false" show-icon class="hint"
      title="所有账号均为「未验证」：授权验证服务尚未实现，账号暂不可用于需要登录态的采集。" />

    <el-dialog v-model="dialog" :title="form.id ? '编辑账号' : '添加账号'" width="520px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="数据源" required>
          <el-select v-model="form.sourceId" :disabled="!!form.id" placeholder="选择平台">
            <el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="账号名称" required>
          <el-input v-model="form.accountName" placeholder="便于识别的名称" />
        </el-form-item>
        <el-form-item label="平台标识" :required="!form.id">
          <el-input v-model="form.externalAccountId" :disabled="!!form.id"
                    placeholder="平台侧唯一标识，创建后不可改" />
        </el-form-item>
        <el-form-item label="凭证类型">
          <el-select v-model="form.authType">
            <el-option label="COOKIE" value="COOKIE" />
            <el-option label="TOKEN" value="TOKEN" />
          </el-select>
        </el-form-item>
        <el-form-item label="凭证引用">
          <el-input v-model="form.credentialRef" placeholder="local-secret:account/xxx（只存引用）" />
          <div class="tip">仅保存引用路径，凭证内容不进入数据库与接口。</div>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="form.expiresAt" type="datetime" placeholder="留空表示无已知期限"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
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
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { accountApi, sourceApi } from '../../api'
import { usePermission } from '../../composables/usePermission'

const { hasPerm } = usePermission()

const rows = ref([])
const sources = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const form = ref({})

const AUTH_MAP = {
  VALID: ['有效', 'success'],
  EXPIRED: ['已过期', 'danger'],
  INVALID: ['无效', 'danger'],
  REVOKED: ['已撤销', 'info'],
  UNKNOWN: ['未验证', 'info'],
  PENDING_VERIFY: ['待验证', 'warning'],
}
const authText = (s) => AUTH_MAP[s]?.[0] || s || '未验证'
const authType = (s) => AUTH_MAP[s]?.[1] || 'info'
const sourceName = (id) => (sources.value.find((s) => s.id === id) || {}).name || `#${id}`
const hasVerified = computed(() => rows.value.some((r) => r.authStatus === 'VALID'))

async function load() {
  loading.value = true
  try {
    const [acc, src] = await Promise.all([accountApi.list(), sourceApi.list()])
    rows.value = acc.data || []
    sources.value = src.data || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { sourceId: sources.value[0]?.id, authType: 'COOKIE' }
  dialog.value = true
}

function openEdit(row) {
  form.value = { ...row }
  dialog.value = true
}

async function save() {
  if (!form.value.sourceId || !form.value.accountName) {
    ElMessage.warning('数据源与账号名称必填')
    return
  }
  saving.value = true
  try {
    if (form.value.id) {
      await accountApi.update(form.value.id, form.value)
    } else {
      await accountApi.create(form.value)
    }
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function doVerify(row) {
  const res = await accountApi.verify(row.id)
  const d = res.data || {}
  // 明确告知验证范围，避免把「配置有效」误解为「平台确认有效」
  if (d.success) {
    ElMessage.success(`验证通过（范围：${d.scope === 'CONFIG_ONLY' ? '仅凭证配置' : d.scope}）`)
  } else {
    ElMessage.warning(`${d.message || '验证未通过'}${d.errorCode ? ' [' + d.errorCode + ']' : ''}`)
  }
  await load()
}

async function toggle(row) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  await accountApi.setStatus(row.id, next)
  ElMessage.success(next === 'ACTIVE' ? '已启用' : '已停用')
  await load()
}

onMounted(load)
</script>

<style scoped>
.page { padding: 4px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; gap: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; }
.sub { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; max-width: 760px; line-height: 1.6; }
.tbl { width: 100%; }
.tip { color: var(--el-text-color-placeholder); font-size: 12px; margin-top: 4px; }
.hint { margin-top: 12px; }
</style>
