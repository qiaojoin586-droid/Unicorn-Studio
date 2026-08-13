import { http } from '@/utils/request'

export interface MeInfo {
  id: number
  username: string
  nickname?: string
  avatar?: string
  roles: { code: string; name: string }[]
  permissions: string[]
  mustChangePassword?: number
}

export const authApi = {
  me: () => http.get<MeInfo>('/api/admin/auth/me'),
  logout: () => http.post('/api/admin/auth/logout'),
  updateProfile: (data: { nickname?: string; avatar?: string }) => http.put('/api/admin/profile', data),
  changePassword: (data: { oldPassword: string; newPassword: string }) =>
    http.put('/api/admin/profile/password', data),
}
