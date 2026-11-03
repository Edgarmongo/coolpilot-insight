<template>
  <div class="login-page">
    <div class="login-left">
      <h1>CoolPilot Insight</h1>
      <p>从内容数据到用户洞察，让 AI 发现真实需求。</p>
    </div>
    <div class="login-right">
      <div class="login-card">
        <h2>欢迎回来</h2>
        <p class="subtitle">使用手机号登录你的账号</p>
        <el-form :model="form" :rules="rules" ref="formRef" size="large">
          <el-form-item prop="phone">
            <el-input v-model="form.phone" placeholder="手机号" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" style="width:100%;" @click="doLogin" :loading="loading">登 录</el-button>
          </el-form-item>
        </el-form>
        <div class="login-link-text">
          还没有账号？<router-link to="/register">立即注册</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { userApi } from '../api'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)
const form = reactive({ phone: '', password: '' })
const rules = {
  phone: [{ required: true, message: '请输入手机号', trigger: 'blur' }, { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function doLogin() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const res = await userApi.login(form)
    userStore.setLogin(res.data)
    router.push('/dashboard')
  } finally { loading.value = false }
}
</script>
