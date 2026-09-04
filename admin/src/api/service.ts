import { http } from '@/utils/request'

export interface RecruitmentDirection {
  id: number
  title: string
  description: string
  icon?: string
  sortOrder: number
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

export interface RecruitmentDirectionForm {
  title: string
  description: string
  icon?: string
  sortOrder: number
  enabled: boolean
}

export const serviceApi = {
  list: (params: { page: number; size: number }) =>
    http.get<{ list: RecruitmentDirection[]; total: number }>('/api/admin/services', params),
  create: (data: RecruitmentDirectionForm) => http.post<{ id: number }>('/api/admin/services', data),
  update: (id: number, data: RecruitmentDirectionForm) =>
    http.put<void>(`/api/admin/services/${id}`, data),
  remove: (id: number) => http.delete<void>(`/api/admin/services/${id}`),
}
