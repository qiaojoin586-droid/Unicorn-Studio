import { http } from '@/utils/request'

export const settingApi = {
  list: () => http.get<Record<string, Record<string, string>>>('/api/admin/settings'),
  update: (items: { group: string; key: string; value: string }[]) => http.put('/api/admin/settings', items),
}
