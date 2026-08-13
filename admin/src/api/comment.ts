import { http } from '@/utils/request'

export const commentApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/comments', params),
  changeStatus: (id: number, status: string) => http.patch(`/api/admin/comments/${id}/status`, { status }),
  reply: (id: number, content: string) => http.post(`/api/admin/comments/${id}/reply`, { content }),
  batch: (ids: number[], action: string) => http.patch('/api/admin/comments/batch', { ids, action }),
  remove: (id: number) => http.delete(`/api/admin/comments/${id}`),
}
