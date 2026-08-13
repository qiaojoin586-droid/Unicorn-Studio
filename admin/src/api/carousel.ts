import { http } from '@/utils/request'

export const carouselApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/carousel', params),
  create: (data: Record<string, any>) => http.post('/api/admin/carousel', data),
  update: (id: number, data: Record<string, any>) => http.put(`/api/admin/carousel/${id}`, data),
  remove: (id: number) => http.delete(`/api/admin/carousel/${id}`),
}
