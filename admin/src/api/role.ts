import { http } from '@/utils/request'

export interface RoleItem {
  role: {
    id: number
    code: string
    name: string
    description?: string
    builtin: number
    status: number
  }
  permissionIds: number[]
}

export const roleApi = {
  list: () => http.get<RoleItem[]>('/api/admin/roles'),
  create: (data: Record<string, any>) => http.post('/api/admin/roles', data),
  update: (id: number, data: Record<string, any>) => http.put(`/api/admin/roles/${id}`, data),
  remove: (id: number) => http.delete(`/api/admin/roles/${id}`),
  permissions: () => http.get<Record<string, { id: number; code: string; name: string; type: string }[]>>('/api/admin/permissions'),
}
