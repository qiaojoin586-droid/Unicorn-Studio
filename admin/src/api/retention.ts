import { http } from '@/utils/request'

/** 保留策略配置（与后端 RetentionService.RetentionConfig 对应） */
export interface RetentionConfig {
  /** 当前生效的保留天数（7/15/30/90） */
  days: number
  /** 是否启用定时自动清理 */
  enabled: boolean
  /** 清理任务 cron（部署级参数 app.retention.cron） */
  cron: string
  /** 单轮最多删除行数 */
  maxDeletePerRun: number
  /** 可选档位 */
  options: number[]
}

/** 单次清理报告 */
export interface RetentionRunReport {
  trigger: string
  dryRun: boolean
  skipped: boolean
  message: string
  startedAt: string
  finishedAt: string
  retentionDays: number
  cutoff: string
  deletedAuditRows: number
  batches: number
  capped: boolean
  sqlMonitorReset: boolean
  deletedLogDirs: number
  durationMs: number
}

/** 状态总览 */
export interface RetentionStatus {
  config: RetentionConfig
  lastRun: RetentionRunReport | null
  nextRunAt: string | null
  sqlMonitorLastResetAt: string | null
}

/** 清理预览（无副作用） */
export interface RetentionPreview {
  cutoff: string
  totalAuditRows: number
  expiredAuditRows: number
  logDirsToDelete: number
  logFilesBytes: number
}

export const retentionApi = {
  status: () => http.get<RetentionStatus>('/api/admin/retention'),
  preview: () => http.get<RetentionPreview>('/api/admin/retention/preview'),
  update: (data: { days: number; enabled: boolean }) => http.put<void>('/api/admin/retention', data),
  cleanup: (params: { dryRun?: boolean; resetSqlMonitor?: boolean }) =>
    http.post<RetentionRunReport>('/api/admin/retention/cleanup', null, { params }),
}
