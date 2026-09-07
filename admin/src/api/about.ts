import { http } from '@/utils/request'

export interface AboutStat {
  value: string
  label: string
}

export interface AboutPage {
  eyebrow: string
  title: string
  subtitle: string
  content: string
  imageUrl: string
  stats: AboutStat[]
}

export const aboutApi = {
  get: () => http.get<AboutPage>('/api/admin/about'),
  update: (data: AboutPage) => http.put<void>('/api/admin/about', data),
}
