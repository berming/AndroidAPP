import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi, type AdminUser } from '../api/auth'

/**
 * 当前登录 admin 的全局状态。
 *
 * - `user` ref：登录后从 /admin-auth/me 拿到的 AdminUser
 * - cookie 由浏览器自动管理（HttpOnly），前端永远拿不到 token——这是设计目的
 * - `hasPermission` 控制菜单可见性；服务端 requirePermission 是真正的权限闸门
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<AdminUser | null>(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => user.value !== null)

  const hasPermission = (perm: string): boolean =>
    user.value?.permissions.includes(perm) ?? false

  async function login(username: string, password: string): Promise<void> {
    loading.value = true
    try {
      const result = await authApi.login(username, password)
      user.value = result.user
    } finally {
      loading.value = false
    }
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      user.value = null
    }
  }

  async function fetchMe(): Promise<void> {
    try {
      user.value = await authApi.me()
    } catch (_e) {
      // 401 → user 保持 null；router 会把用户引到 /login
      user.value = null
    }
  }

  async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
    await authApi.changePassword(oldPassword, newPassword)
  }

  return { user, loading, isLoggedIn, hasPermission, login, logout, fetchMe, changePassword }
})
