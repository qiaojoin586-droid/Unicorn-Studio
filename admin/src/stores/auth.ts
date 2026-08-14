import { defineStore } from 'pinia'
import { http } from '@/utils/request'

interface RoleInfo {
  code: string
  name: string
}

interface UserInfo {
  id: number
  username: string
  nickname?: string
  avatar?: string
  roles: RoleInfo[]
  permissions: string[]
  mustChangePassword?: number
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('admin_token') || '',
    refreshToken: localStorage.getItem('admin_refresh_token') || '',
    user: null as UserInfo | null,
    permissions: [] as string[],
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
  },

  actions: {
    async login(username: string, password: string) {
      const data = await http.post<{
        accessToken: string
        refreshToken: string
        expiresIn: number
        mustChangePassword: number
      }>('/api/admin/auth/login', { username, password }, { silent: true })
      this.token = data.accessToken
      this.refreshToken = data.refreshToken
      localStorage.setItem('admin_token', data.accessToken)
      localStorage.setItem('admin_refresh_token', data.refreshToken)
      await this.fetchMe()
      return data
    },

    async fetchMe() {
      const me = await http.get<UserInfo>('/api/admin/auth/me')
      this.user = me
      this.permissions = me.permissions || []
      localStorage.setItem('admin_permissions', JSON.stringify(this.permissions))
      localStorage.setItem('admin_user', JSON.stringify(me))
      return me
    },

    hasPerm(code: string): boolean {
      return this.permissions.includes(code)
    },

    hasAnyPerm(codes: string[]): boolean {
      return codes.some((c) => this.permissions.includes(c))
    },

    async logout() {
      try {
        await http.post('/api/admin/auth/logout')
      } catch {
        // 忽略登出接口失败（token 可能已过期）
      }
      this.clear()
    },

    clear() {
      this.token = ''
      this.refreshToken = ''
      this.user = null
      this.permissions = []
      localStorage.removeItem('admin_token')
      localStorage.removeItem('admin_refresh_token')
      localStorage.removeItem('admin_permissions')
      localStorage.removeItem('admin_user')
    },
  },
})
