import { http } from '@/utils/request'

export const backupApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/backups', params),
  trigger: () => http.post('/api/admin/backups'),
  downloadUrl: (id: number) => `/api/admin/backups/${id}/download`,
  remove: (id: number) => http.delete(`/api/admin/backups/${id}`),
}
