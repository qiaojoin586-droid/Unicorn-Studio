import { http } from '@/utils/request'

export interface LearningForm {
  title?: string
  description?: string
  category?: string
  tags?: string
  coverUrl?: string
  attachmentUrl?: string
  url?: string
  sortOrder?: number
  enabled?: boolean
}

export interface LearningCategory {
  id: number
  name: string
  description?: string
  sortOrder?: number
  status?: number
  createdAt?: string
}

export interface LearningCategoryItem {
  category: LearningCategory
  resourceCount: number
}

export const learningApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/learning/resources', params),
  create: (data: LearningForm) => http.post('/api/admin/learning/resources', data),
  update: (id: number, data: LearningForm) => http.put(`/api/admin/learning/resources/${id}`, data),
  changeStatus: (id: number, status: number) =>
    http.patch(`/api/admin/learning/resources/${id}/status`, { status }),
  remove: (id: number) => http.delete(`/api/admin/learning/resources/${id}`),
  batchDelete: (ids: number[]) =>
    http.post<{ successCount: number; failCount: number }>('/api/admin/learning/resources/batch-delete', { ids }),

  categories: () => http.get<LearningCategoryItem[]>('/api/admin/learning/categories'),
  createCategory: (data: Partial<LearningCategory>) => http.post('/api/admin/learning/categories', data),
  updateCategory: (id: number, data: Partial<LearningCategory>) =>
    http.put(`/api/admin/learning/categories/${id}`, data),
  removeCategory: (id: number) => http.delete(`/api/admin/learning/categories/${id}`),
}
