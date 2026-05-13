import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('../layouts/AdminLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue') },
      { path: 'rooms', name: 'rooms', component: () => import('../views/Rooms.vue') },
      { path: 'rooms/:id', name: 'room-detail', component: () => import('../views/RoomDetail.vue') },
      { path: 'players', name: 'players', component: () => import('../views/Players.vue') },
      { path: 'sessions', name: 'sessions', component: () => import('../views/Sessions.vue') },
      { path: 'games', name: 'games', component: () => import('../views/Games.vue') },
      { path: 'alerts', name: 'alerts', component: () => import('../views/Alerts.vue') },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

export const router = createRouter({
  history: createWebHistory('/admin/'),
  routes,
})

// 全局守卫：未登录 → 跳 /login；已登录访问 /login → 跳 /dashboard
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    if (auth.user && to.path === '/login') return { path: '/dashboard' }
    return true
  }
  if (!auth.user) {
    // 还没拉 /me（App.vue mounted 期间）也走 login；登录成功后会回来
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})
