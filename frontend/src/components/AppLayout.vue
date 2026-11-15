<template>
  <div class="app-layout">
    <!-- 左侧信息架构：七个业务中心 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="brand" @click="router.push('/dashboard')">
        <div class="brand-mark">CP</div>
        <div class="brand-text" v-show="!collapsed">
          <div class="brand-name">CoolPilot Insight</div>
          <div class="brand-sub">内容洞察 · 智能获客</div>
        </div>
      </div>

      <el-scrollbar class="menu-scroll">
        <el-menu
          :default-active="activeIndex"
          :collapse="collapsed"
          :collapse-transition="false"
          :default-openeds="openeds"
          class="side-menu"
          @select="onSelect"
        >
          <el-sub-menu v-for="g in menuGroups" :key="g.key" :index="g.key">
            <template #title>
              <el-icon><component :is="g.icon" /></el-icon>
              <span>{{ g.title }}</span>
            </template>

            <el-menu-item
              v-for="it in visibleItems(g)"
              :key="it.title"
              :index="itemIndex(it)"
              :disabled="it.status === 'planned'"
            >
              <el-icon v-if="it.icon"><component :is="it.icon" /></el-icon>
              <span class="item-title">{{ it.title }}</span>
              <span v-if="it.status === 'planned'" class="item-badge">规划中</span>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-scrollbar>

      <div class="sidebar-foot" v-show="!collapsed">
        <span class="dot" />本地自用版
      </div>
    </aside>

    <!-- 右侧主体 -->
    <div class="body">
      <header class="topbar">
        <button class="icon-btn" :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggleCollapse">
          <el-icon><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
        </button>

        <el-breadcrumb separator="/" class="crumb">
          <el-breadcrumb-item>{{ current.groupTitle || '工作台' }}</el-breadcrumb-item>
          <el-breadcrumb-item>{{ current.title || '获客驾驶舱' }}</el-breadcrumb-item>
        </el-breadcrumb>

        <div class="topbar-right">
          <template v-if="userStore.token">
            <span class="role-tag" :class="userStore.userInfo?.role === 1 ? 'admin' : 'user'">
              {{ userStore.userInfo?.role === 1 ? '管理员' : '用户' }}
            </span>
            <el-dropdown trigger="click" @command="onUserCommand">
              <span class="user-entry">
                <el-icon><UserFilled /></el-icon>
                <span class="phone">{{ userStore.userInfo?.phone }}</span>
                <el-icon><CaretBottom /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                  <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <router-link v-else to="/login" class="login-link">登录</router-link>
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
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { userApi, menuApi } from '../api'
import { menuGroups as staticGroups, findMenuByPath } from '../config/menu'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const collapsed = ref(localStorage.getItem('cp.sidebar.collapsed') === '1')

/**
 * 菜单数据源：优先用后端返回（按权限过滤），失败回退到静态配置。
 *
 * 回退是必要的——后端未启动或接口异常时，侧边栏不能整块消失，
 * 否则用户连"从哪儿进"都看不到，比多显示几个无权菜单更糟。
 */
const menuGroups = ref(staticGroups)

/** 后端 ENABLED/PLANNED 映射为前端 ready/planned */
function toGroups(nodes) {
  return nodes.map((g) => ({
    key: 'g' + g.id,
    title: g.name,
    icon: g.icon || 'Menu',
    items: (g.children || []).map((it) => ({
      title: it.name,
      path: it.path || undefined,
      icon: it.icon || undefined,
      status: it.status === 'PLANNED' ? 'planned' : 'ready',
    })),
  }))
}

onMounted(async () => {
  try {
    const res = await menuApi.current()
    const groups = toGroups(res.data || [])
    if (groups.length) menuGroups.value = groups
  } catch (e) {
    console.warn('[menu] 接口不可用，使用静态菜单', e?.message)
  }
})

function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem('cp.sidebar.collapsed', collapsed.value ? '1' : '0')
}

/** 当前菜单项：路径 + tab 参数共同决定，避免 /insight 与 /insight?tab=comments 互相抢高亮 */
const activeIndex = computed(() => {
  const tab = route.query.tab
  return tab ? `${route.path}?tab=${tab}` : route.path
})

function itemIndex(it) {
  return it.path || it.title
}

/** 面包屑：优先匹配带 tab 的入口 */
const current = computed(() => {
  const tab = route.query.tab
  if (tab) {
    const withTab = menuGroups.value
      .flatMap((g) => g.items.map((it) => ({ ...it, groupTitle: g.title })))
      .find((it) => it.path === `${route.path}?tab=${tab}`)
    if (withTab) return withTab
  }
  if (route.path === '/profile') return { groupTitle: '账号', title: '个人中心' }
  return findMenuByPath(route.path) || {}
})

/** 首次进入时展开当前页所在分组，否则落地页看不到选中项 */
const openeds = computed(() => {
  const tab = route.query.tab
  for (const g of menuGroups.value) {
    const hit = g.items.some((it) => {
      if (!it.path) return false
      const [p, q] = it.path.split('?')
      if (p !== route.path) return false
      return q ? `tab=${tab}` === q : !tab
    })
    if (hit) return [g.key]
  }
  return ['workbench']
})

/** 按角色过滤（如「用户与权限」仅管理员可见） */
function visibleItems(group) {
  return group.items.filter((it) => !(it.role && userStore.userInfo?.role !== it.role))
}

function onSelect(index) {
  if (!index.startsWith('/')) return
  const [path, query] = index.split('?')
  const q = {}
  if (query) {
    for (const kv of query.split('&')) {
      const [k, v] = kv.split('=')
      q[k] = v
    }
  }
  router.push({ path, query: q })
}

async function onUserCommand(cmd) {
  if (cmd === 'profile') return router.push('/profile')
  if (cmd === 'logout') {
    try { await userApi.logout() } catch (e) { /* 忽略登出接口异常 */ }
    userStore.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.app-layout { display: flex; height: 100%; background: var(--page-bg); }

/* ---------- 侧边栏 ---------- */
.sidebar {
  width: 232px; flex-shrink: 0; display: flex; flex-direction: column;
  background: #fff; border-right: 1px solid #E6EAEF; transition: width .18s ease;
}
.sidebar.collapsed { width: 64px; }

.brand { display: flex; align-items: center; gap: 10px; height: 60px; padding: 0 16px; cursor: pointer; border-bottom: 1px solid #EEF2F6; }
.brand-mark {
  width: 32px; height: 32px; flex-shrink: 0; border-radius: 9px;
  background: linear-gradient(135deg, #0D9488, #14B8A6);
  color: #fff; font-size: 13px; font-weight: 700;
  display: flex; align-items: center; justify-content: center; letter-spacing: .5px;
}
.brand-name { font-size: 14px; font-weight: 700; color: #0F172A; line-height: 1.2; white-space: nowrap; }
.brand-sub { font-size: 11px; color: #94A3B8; white-space: nowrap; }

.menu-scroll { flex: 1; overflow: hidden; }

.side-menu { border-right: none; --el-menu-item-height: 42px; --el-menu-sub-item-height: 38px; }
.side-menu :deep(.el-sub-menu__title) { font-weight: 600; color: #334155; }
.side-menu :deep(.el-menu-item) { color: #475569; font-size: 13px; }
.side-menu :deep(.el-menu-item.is-active) {
  color: #0D9488; background: #ECFDF5; font-weight: 600;
  border-right: 3px solid #0D9488;
}
.side-menu :deep(.el-menu-item.is-disabled) { opacity: .55; }
.item-title { flex: 1; }
.item-badge {
  font-size: 10px; color: #94A3B8; border: 1px solid #E2E8F0;
  border-radius: 4px; padding: 0 4px; line-height: 16px; flex-shrink: 0;
}

.sidebar-foot {
  height: 34px; display: flex; align-items: center; gap: 6px;
  padding: 0 18px; font-size: 11px; color: #94A3B8; border-top: 1px solid #EEF2F6;
}
.dot { width: 6px; height: 6px; border-radius: 50%; background: #10B981; }

/* ---------- 顶栏 ---------- */
.body { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar {
  height: 60px; flex-shrink: 0; display: flex; align-items: center; gap: 14px;
  padding: 0 20px; background: #fff; border-bottom: 1px solid #E6EAEF;
}
.icon-btn {
  border: none; background: #F1F5F9; width: 30px; height: 30px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center; cursor: pointer; color: #475569;
}
.icon-btn:hover { background: #E2E8F0; }
.crumb { font-size: 13px; }
.topbar-right { margin-left: auto; display: flex; align-items: center; gap: 14px; }
.role-tag { padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 500; white-space: nowrap; }
.role-tag.user { background: #E0F2FE; color: #075985; }
.role-tag.admin { background: #FEE2E2; color: #991B1B; }
.user-entry { display: flex; align-items: center; gap: 6px; cursor: pointer; color: #334155; font-size: 13px; }
.phone { white-space: nowrap; }
.login-link { font-size: 13px; color: #0D9488; text-decoration: none; }

.content { flex: 1; overflow: auto; }
</style>
