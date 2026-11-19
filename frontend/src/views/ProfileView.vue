<template>
  <div class="page-container">
    <h3 class="page-title">个人中心</h3>
    <div class="card" style="max-width:480px;">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="用户名">{{ userInfo.username || '-' }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ userStore.userInfo?.phone }}</el-descriptions-item>
        <el-descriptions-item label="角色">
          <span class="role-tag" :class="userStore.userInfo?.role === 1 ? 'admin' : 'user'">
            {{ userStore.userInfo?.role === 1 ? '管理员' : '普通用户' }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ userInfo.createdAt || '-' }}</el-descriptions-item>
      </el-descriptions>
    </div>
    <div class="card" style="max-width:480px;margin-top:16px;">
      <div class="card-header"><h3>修改密码</h3></div>
      <el-form :model="pwdForm" :rules="pwdRules" ref="pwdFormRef" label-width="80px" size="large">
        <el-form-item label="原密码" prop="oldPassword"><el-input v-model="pwdForm.oldPassword" type="password" show-password /></el-form-item>
        <el-form-item label="新密码" prop="newPassword"><el-input v-model="pwdForm.newPassword" type="password" show-password /></el-form-item>
        <el-form-item label="确认" prop="confirmPassword"><el-input v-model="pwdForm.confirmPassword" type="password" show-password /></el-form-item>
        <el-form-item><el-button type="primary" @click="doUpdatePwd" :loading="pwdLoading">确认修改</el-button></el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useUserStore } from '../stores/user'
import { userApi } from '../api'
import { ElMessage } from 'element-plus'

const userStore = useUserStore()
const userInfo = ref({})
const pwdFormRef = ref()
const pwdLoading = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const validateConfirm = (rule, value, callback) => { if (value !== pwdForm.newPassword) callback(new Error('两次密码输入不一致')); else callback() }
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { min: 6, max: 20, message: '6-20位', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请确认', trigger: 'blur' }, { validator: validateConfirm, trigger: 'blur' }],
}
async function doUpdatePwd() { const v = await pwdFormRef.value.validate().catch(() => false); if (!v) return; pwdLoading.value = true; try { await userApi.updatePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword }); ElMessage.success('密码修改成功'); pwdFormRef.value.resetFields() } finally { pwdLoading.value = false } }
onMounted(async () => { try { userInfo.value = (await userApi.info()).data || {} } catch (e) { /* */ } })
</script>
