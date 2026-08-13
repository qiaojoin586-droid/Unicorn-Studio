import { http } from '@/utils/request'

export const userApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/users', params),
  detail: (id: number) => http.get(`/api/admin/users/${id}`),
  ban: (id: number, data: { duration: string; reason?: string }) => http.patch(`/api/admin/users/${id}/ban`, data),
  unban: (id: number) => http.patch(`/api/admin/users/${id}/unban`),
  remove: (id: number) => http.delete(`/api/admin/users/${id}`),
}
