import { http } from '@/utils/request'

export const newsApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/news', params),
  detail: (id: number) => http.get(`/api/admin/news/${id}`),
  create: (data: Record<string, any>) => http.post('/api/admin/news', data),
  update: (id: number, data: Record<string, any>) => http.put(`/api/admin/news/${id}`, data),
  remove: (id: number) => http.delete(`/api/admin/news/${id}`),
  changeStatus: (id: number, status: string) => http.patch(`/api/admin/news/${id}/status`, { status }),
  sort: (ids: number[]) => http.patch('/api/admin/news/sort', { ids }),
}
