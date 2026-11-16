import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/LoginView.vue') },
  { path: '/register', name: 'Register', component: () => import('../views/RegisterView.vue') },
  {
    path: '/',
    component: () => import('../components/AppLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('../views/DashboardView.vue') },
      { path: 'overview', name: 'Overview', component: () => import('../views/OverviewView.vue') },
      { path: 'collect', name: 'Collect', component: () => import('../views/CollectView.vue') },
      { path: 'tasks', name: 'TaskCenter', component: () => import('../views/TaskCenterView.vue') },
      { path: 'clean', name: 'Clean', component: () => import('../views/CleanView.vue') },
      { path: 'comments', name: 'Comments', component: () => import('../views/CommentListView.vue') },
      { path: 'accounts', name: 'SourceAccounts', component: () => import('../views/source/AccountManageView.vue') },
      { path: 'keywords', name: 'Keywords', component: () => import('../views/KeywordView.vue') },
      { path: 'insight', name: 'Insight', component: () => import('../views/InsightView.vue') },
      { path: 'review', name: 'Review', component: () => import('../views/ReviewView.vue') },
      { path: 'reports', name: 'Reports', component: () => import('../views/ReportView.vue') },
      { path: 'profile', name: 'Profile', component: () => import('../views/ProfileView.vue') },
      { path: 'admin/users', name: 'AdminUsers', component: () => import('../views/admin/UserManageView.vue'), meta: { role: 1 } },
      { path: 'system/roles', name: 'SystemRoles', component: () => import('../views/admin/RoleManageView.vue'), meta: { role: 1 } },
      { path: 'system/dicts', name: 'SystemDicts', component: () => import('../views/admin/DictManageView.vue'), meta: { role: 1 } },
      { path: 'system/audits', name: 'SystemAudits', component: () => import('../views/admin/AuditLogView.vue'), meta: { role: 1 } },
      { path: 'system/configs', name: 'SystemConfigs', component: () => import('../views/admin/ConfigManageView.vue'), meta: { role: 1 } },
      { path: 'system/menus', name: 'SystemMenus', component: () => import('../views/admin/MenuManageView.vue'), meta: { role: 1 } },
    ]
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, from, next) => {
  const userStore = useUserStore()
  const publicPages = ['/login', '/register', '/dashboard']
  const needAuth = !publicPages.includes(to.path)

  if (needAuth && !userStore.token) {
    return next('/login')
  }

  if (to.meta.role && userStore.userInfo && userStore.userInfo.role < to.meta.role) {
    return next('/dashboard')
  }

  next()
})

export default router
