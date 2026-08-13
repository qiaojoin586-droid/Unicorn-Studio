import { http } from '@/utils/request'

export const adminApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/admins', params),
  create: (data: { username: string; password: string; nickname?: string; roleIds: number[] }) =>
    http.post('/api/admin/admins', data),
  update: (id: number, data: { nickname?: string; status?: number; roleIds?: number[] }) =>
    http.put(`/api/admin/admins/${id}`, data),
  remove: (id: number) => http.delete(`/api/admin/admins/${id}`),
}
