<template>
  <div class="app-layout">
    <header class="navbar">
      <span class="navbar-logo">CoolPilot Insight</span>
      <nav class="navbar-links">
        <router-link to="/dashboard" class="nav-item" :class="{ active: route.path === '/dashboard' }">
          <el-icon><DataAnalysis /></el-icon><span>总览</span>
        </router-link>
        <router-link to="/comments" class="nav-item" v-if="userStore.token" :class="{ active: route.path === '/comments' }">
          <el-icon><ChatLineSquare /></el-icon><span>评论</span>
        </router-link>
        <router-link to="/collect" class="nav-item" v-if="userStore.token" :class="{ active: route.path === '/collect' }">
          <el-icon><Download /></el-icon><span>采集</span>
        </router-link>
        <router-link to="/clean" class="nav-item" v-if="userStore.token" :class="{ active: route.path === '/clean' }">
          <el-icon><Operation /></el-icon><span>清洗</span>
        </router-link>
        <a :href="dypUrl" target="_blank" rel="noopener" class="nav-item" v-if="userStore.token"
           title="打开 DouYin Intelligence Platform（AI 洞察 / 报告导出）">
          <el-icon><TrendCharts /></el-icon><span>深度分析</span>
        </a>
        <router-link to="/profile" class="nav-item" v-if="userStore.token" :class="{ active: route.path === '/profile' }">
          <el-icon><User /></el-icon><span>用户</span>
        </router-link>
        <router-link to="/admin/users" class="nav-item" v-if="userStore.userInfo?.role === 1" :class="{ active: route.path === '/admin/users' }">
          <el-icon><Setting /></el-icon><span>管理</span>
        </router-link>
      </nav>
      <div class="navbar-right">
        <template v-if="userStore.token">
          <span class="role-tag" :class="userStore.userInfo?.role === 1 ? 'admin' : 'user'">
            {{ userStore.userInfo?.role === 1 ? '管理员' : '用户' }}
          </span>
          <span class="phone-text">{{ userStore.userInfo?.phone }}</span>
          <button class="logout-btn" @click="doLogout">退出</button>
        </template>
        <template v-else>
          <router-link to="/login" class="login-link">登录</router-link>
        </template>
      </div>
    </header>
    <main class="content">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'
import { userApi } from '../api'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

// 深度分析平台（DYP）地址，可通过构建变量 VITE_DYP_URL 覆盖
const dypUrl = import.meta.env.VITE_DYP_URL || 'http://localhost:8088'

async function doLogout() {
  try { await userApi.logout() } catch (e) { /* */ }
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.app-layout { display: flex; flex-direction: column; height: 100%; }
.navbar {
  height: 52px; min-height: 52px;
  background: linear-gradient(90deg, #0D9488 0%, #14B8A6 70%, #5EEAD4 100%);
  display: flex; align-items: center; padding: 0 20px; gap: 8px;
}
.navbar-logo { font-size: 16px; font-weight: 700; color: #fff; letter-spacing: 1px; white-space: nowrap; }
.navbar-links { display: flex; align-items: center; flex: 1; justify-content: space-evenly; padding: 0 60px; }
.nav-item {
  display: flex; align-items: center; gap: 6px; padding: 6px 14px; border-radius: 8px;
  color: rgba(255,255,255,0.8); text-decoration: none; font-size: 14px; transition: all .15s ease; white-space: nowrap;
}
.nav-item:hover { background: rgba(255,255,255,0.12); color: #fff; }
.nav-item.active { background: rgba(255,255,255,0.2); color: #fff; font-weight: 600; }
.nav-item .el-icon { font-size: 17px; }
.navbar-right { display: flex; align-items: center; gap: 12px; margin-left: auto; }
.role-tag { padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 500; }
.role-tag.user { background: rgba(255,255,255,0.25); color: #fff; }
.role-tag.admin { background: #FEE2E2; color: #991B1B; }
.phone-text { font-size: 13px; color: rgba(255,255,255,0.8); }
.logout-btn, .login-link { background: none; border: none; font-size: 13px; color: rgba(255,255,255,0.7); cursor: pointer; text-decoration: none; }
.logout-btn:hover, .login-link:hover { color: #fff; }
.content { flex: 1; overflow: hidden; background: var(--page-bg); }
</style>
