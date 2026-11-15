<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>角色管理</h2>
        <p class="sub">RBAC 角色与权限分配。内置角色不可删除，仍有用户关联的角色需先解除绑定。</p>
      </div>
      <el-button type="primary" @click="openCreate">新建角色</el-button>
    </div>

    <el-table :data="roles" v-loading="loading" class="tbl">
      <el-table-column prop="code" label="编码" width="160" />
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag :type="row.builtin ? 'warning' : 'info'" size="small">
            {{ row.builtin ? '内置' : '自定义' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="userCount" label="关联用户" width="110" />
      <el-table-column label="权限" min-width="280">
        <template #default="{ row }">
          <span class="perm-count">{{ row.permissionIds ? row.permissionIds.length : 0 }} 项</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openPerm(row)">分配权限</el-button>
          <el-button
            size="small"
            type="danger"
            link
            :disabled="row.builtin"
            :title="row.builtin ? '内置角色不可删除' : ''"
            @click="remove(row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="permDrawer" :title="'分配权限 · ' + (permRole.name || '')" size="420px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="勾选即授权：菜单决定能否进入页面，按钮权限由后端接口独立校验。"
        style="margin-bottom: 12px"
      />
      <el-tree
        ref="treeRef"
        :data="menuTree"
        node-key="id"
        show-checkbox
        default-expand-all
        :props="{ label: 'name', children: 'children' }"
      />
      <template #footer>
        <div class="drawer-foot">
          <el-button @click="permDrawer = false">取消</el-button>
          <el-button type="primary" :loading="savingPerm" @click="savePerm">保存</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="dialog" title="新建角色" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="编码">
          <el-input v-model="form.code" placeholder="大写字母/数字/下划线，如 OPS" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="如 运营" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { roleApi, menuApi } from '../../api'

const roles = ref([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const form = ref({ code: '', name: '' })

const permDrawer = ref(false)
const permRole = ref({})
const menuTree = ref([])
const treeRef = ref()
const savingPerm = ref(false)

async function openPerm(row) {
  permRole.value = row
  const [tree, granted] = await Promise.all([menuApi.tree(), roleApi.menuIds(row.id)])
  menuTree.value = tree.data || []
  permDrawer.value = true
  // 等树渲染完再设勾选，否则 setCheckedKeys 找不到节点
  await nextTick()
  treeRef.value.setCheckedKeys(granted.data || [])
}

async function savePerm() {
  savingPerm.value = true
  try {
    // 半选的父级也要存：否则子项授权后父级菜单不可见
    const ids = [
      ...treeRef.value.getCheckedKeys(),
      ...treeRef.value.getHalfCheckedKeys(),
    ]
    await roleApi.grantMenus(permRole.value.id, ids)
    ElMessage.success('已保存，权限立即生效')
    permDrawer.value = false
    await load()
  } finally {
    savingPerm.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const res = await roleApi.list()
    roles.value = res.data || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { code: '', name: '' }
  dialog.value = true
}

async function submit() {
  if (!form.value.code || !form.value.name) {
    ElMessage.warning('编码与名称都要填')
    return
  }
  saving.value = true
  try {
    await roleApi.create(form.value)
    ElMessage.success('已创建')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  // 前端限制只是提示，真正拦截在后端（RoleCommandService 校验 builtin 与关联用户）
  try {
    await ElMessageBox.confirm(`确定删除角色「${row.name}」？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  await roleApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.page { padding: 4px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; }
.sub { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.tbl { width: 100%; }
.perm-count { color: var(--el-text-color-secondary); }
.drawer-foot { display: flex; justify-content: flex-end; gap: 8px; }
</style>
