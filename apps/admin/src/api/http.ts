import axios from 'axios'

/**
 * 全局 axios 实例。
 *
 * - `baseURL: '/'`：所有 admin 接口走同源（生产 = bermin.cn，dev = vite proxy）
 * - `withCredentials: true`：让浏览器自动带 cookie（HttpOnly + Secure + SameSite=Lax）
 * - 401 拦截：清 store 后跳 /login（router 守卫接管）
 */
export const http = axios.create({
  baseURL: '/',
  withCredentials: true,
  timeout: 15000,
})

http.interceptors.response.use(
  (resp) => resp,
  async (error) => {
    if (error.response?.status === 401) {
      // 动态 import 避免循环依赖（auth.ts → http.ts → router → auth.ts）
      const { useAuthStore } = await import('../stores/auth')
      const { router } = await import('../router')
      const auth = useAuthStore()
      auth.$patch({ user: null })
      if (router.currentRoute.value.path !== '/login') {
        router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      }
    }
    return Promise.reject(error)
  },
)
