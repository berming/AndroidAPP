import { http } from './http'

/** 服务端 AdminUserDto 的 TS 镜像 */
export interface AdminUser {
  id: number
  username: string
  role: string
  permissions: string[]
  lastLoginAt: string | null
}

interface LoginResponse {
  user: AdminUser
}

export const authApi = {
  async login(username: string, password: string): Promise<LoginResponse> {
    const resp = await http.post<LoginResponse>('/admin-auth/login', { username, password })
    return resp.data
  },
  async logout(): Promise<void> {
    await http.post('/admin-auth/logout')
  },
  async me(): Promise<AdminUser> {
    const resp = await http.get<AdminUser>('/admin-auth/me')
    return resp.data
  },
  async changePassword(oldPassword: string, newPassword: string): Promise<void> {
    await http.post('/admin-auth/change-password', { oldPassword, newPassword })
  },
}
