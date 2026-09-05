import { http } from '@/utils/request'

export interface Capability {
  id: number
  title: string
  description: string
  icon?: string
  sortOrder: number
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

export interface CapabilityForm {
  title: string
  description: string
  icon?: string
  sortOrder: number
  enabled: boolean
}

export const capabilityApi = {
  list: (params: { page: number; size: number }) =>
    http.get<{ list: Capability[]; total: number }>('/api/admin/capabilities', params),
  create: (data: CapabilityForm) => http.post<{ id: number }>('/api/admin/capabilities', data),
  update: (id: number, data: CapabilityForm) =>
    http.put<void>(`/api/admin/capabilities/${id}`, data),
  remove: (id: number) => http.delete<void>(`/api/admin/capabilities/${id}`),
}
