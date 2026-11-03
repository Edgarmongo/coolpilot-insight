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
      { path: 'collect', name: 'Collect', component: () => import('../views/CollectView.vue') },
      { path: 'tasks', name: 'TaskCenter', component: () => import('../views/TaskCenterView.vue') },
      { path: 'clean', name: 'Clean', component: () => import('../views/CleanView.vue') },
      { path: 'comments', name: 'Comments', component: () => import('../views/CommentListView.vue') },
      { path: 'keywords', name: 'Keywords', component: () => import('../views/KeywordView.vue') },
      { path: 'insight', name: 'Insight', component: () => import('../views/InsightView.vue') },
      { path: 'reports', name: 'Reports', component: () => import('../views/ReportView.vue') },
      { path: 'profile', name: 'Profile', component: () => import('../views/ProfileView.vue') },
      { path: 'admin/users', name: 'AdminUsers', component: () => import('../views/admin/UserManageView.vue'), meta: { role: 1 } },
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
