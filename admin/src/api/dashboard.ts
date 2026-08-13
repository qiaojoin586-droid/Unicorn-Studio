import { http } from '@/utils/request'

export interface DashboardSummary {
  todayPv: number
  todayUv: number
  totalPv: number
  totalUv: number
  workCount: number
  publishedWorks: number
  newsCount: number
  pendingComments: number
  newMessages: number
  userCount: number
  todayNewUsers: number
  trend: { date: string; pv: number; uv: number }[]
  sourceDist: { name: string; value: number }[]
  recentMessages: { id: number; name: string; content: string; created_at?: string; createdAt?: string }[]
}

export const dashboardApi = {
  summary: (days = 30) => http.get<DashboardSummary>('/api/admin/dashboard/summary', { days }),
}
