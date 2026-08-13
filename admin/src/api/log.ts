import { http } from '@/utils/request'

export const logApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/logs', params),
}
