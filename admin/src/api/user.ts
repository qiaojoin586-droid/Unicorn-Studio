import { http } from '@/utils/request'

export interface PublicUserEdit {
  nickname: string
  phone: string
  email: string
  avatar: string
  newPassword?: string
  status: number
  aiEnabled: boolean
  banReason: string
  banUntil: string | null
}

export interface PublicUserDetail {
  user: {
    id: number
    nickname: string
    phone: string | null
    email: string | null
    avatar: string | null
    status: number
    aiEnabled: boolean
    banReason: string | null
    banUntil: string | null
    registerIp: string | null
    createdAt: string
    lastLoginAt: string | null
  }
  commentCount: number
  messageCount: number
}

export const userApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/users', params),
  detail: (id: number) => http.get<PublicUserDetail>(`/api/admin/users/${id}`),
  update: (id: number, data: PublicUserEdit) => http.put(`/api/admin/users/${id}`, data),
  bindPhone: (id: number, phone: string) => http.patch(`/api/admin/users/${id}/phone`, { phone }),
  ban: (id: number, data: { duration: string; reason?: string }) => http.patch(`/api/admin/users/${id}/ban`, data),
  unban: (id: number) => http.patch(`/api/admin/users/${id}/unban`),
  remove: (id: number) => http.delete(`/api/admin/users/${id}`),
}
