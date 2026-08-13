import { http } from '@/utils/request'

export const messageApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/messages', params),
  detail: (id: number) => http.get(`/api/admin/messages/${id}`),
  reply: (id: number, content: string) => http.post(`/api/admin/messages/${id}/reply`, { content }),
  changeStatus: (id: number, status: string) => http.patch(`/api/admin/messages/${id}/status`, { status }),
  remove: (id: number) => http.delete(`/api/admin/messages/${id}`),
}
