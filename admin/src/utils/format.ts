import dayjs from 'dayjs'

export function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD HH:mm')
}

export function formatDate(value?: string | null): string {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD')
}

export function formatSize(bytes?: number | null): string {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function statusText(status?: string | number | null): string {
  const map: Record<string, string> = {
    draft: '草稿',
    published: '已发布',
    offline: '已下线',
    pending: '待审核',
    approved: '已通过',
    rejected: '已拒绝',
    new: '未回复',
    replied: '已回复',
    closed: '已关闭',
    running: '进行中',
    success: '成功',
    failed: '失败',
  }
  return map[String(status ?? '')] ?? String(status ?? '-')
}
