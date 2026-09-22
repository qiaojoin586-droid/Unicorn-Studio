import { http } from '@/utils/request'

export interface WorkForm {
  title?: string
  slug?: string
  category?: string
  coverUrl?: string
  summary?: string
  content?: string
  clientName?: string
  externalLink?: string
  featured?: boolean
  sortOrder?: number
  status?: string
  /** 卡片上显示的日期文案（自由文本，如 2026-03）；后端字段 date_label */
  dateLabel?: string
  /** 卡片上显示的作者（如 张三、李四）；后端字段 authors */
  authors?: string
  seo?: { title?: string; description?: string; keywords?: string }
}

export const workApi = {
  list: (params: Record<string, any>) => http.get('/api/admin/works', params),
  detail: (id: number) => http.get(`/api/admin/works/${id}`),
  create: (data: WorkForm) => http.post('/api/admin/works', data),
  update: (id: number, data: WorkForm) => http.put(`/api/admin/works/${id}`, data),
  remove: (id: number) => http.delete(`/api/admin/works/${id}`),
  changeStatus: (id: number, status: string) => http.patch(`/api/admin/works/${id}/status`, { status }),
  sort: (ids: number[]) => http.patch('/api/admin/works/sort', { ids }),
}
