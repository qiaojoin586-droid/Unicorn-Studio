import { http } from '@/utils/request'

export interface JoinPage {
  eyebrow: string
  title: string
  subtitle: string
  /** 正文段落列表（后端按空行分段返回） */
  paragraphs: string[]
  qrImage: string
  qrCaption: string
}

/**
 * 后台表单形态：正文用多行文本框编辑（段落之间空行分隔），
 * 提交时再切分为 paragraphs 数组。
 */
export interface JoinPageForm {
  eyebrow: string
  title: string
  subtitle: string
  content: string
  qrImage: string
  qrCaption: string
}

export const joinApi = {
  get: () => http.get<JoinPage>('/api/admin/join'),
  update: (data: JoinPage) => http.put<void>('/api/admin/join', data),
}
