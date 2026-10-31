import { http } from '@/utils/request'

/**
 * AI 助手配置（与后端 AiConfigVO 对应）。
 * 安全约定：apiKeyMasked 是脱敏串，完整 key 永不返回前端。
 */
export interface AiConfig {
  /** 脱敏后的 API Key（形如 ab12••••••••cd34，未配置时为空串） */
  apiKeyMasked: string
  /** 是否已配置 key（用于「未配置」提示与占位文案） */
  apiKeySet: boolean
  /** 兼容 OpenAI 协议的 base URL */
  apiUrl: string
  /** 模型名 */
  model: string
  /** 系统提示词 */
  systemPrompt: string
  /** 单次回复最大 token */
  maxTokens: number
  /** 采样温度 */
  temperature: number
  /** 功能总开关 */
  enabled: boolean
  /** 当前配置是否足以调用模型 */
  usable: boolean
  /** 是否启用了加密存储（false = 无密钥降级模式，需提示运维配置 APP_CRYPTO_SECRET） */
  cryptoEnabled: boolean
}

/** 保存请求体：apiKey 留空或回传脱敏串均表示「未修改」 */
export interface AiConfigForm {
  apiKey?: string
  apiUrl?: string
  model?: string
  systemPrompt?: string
  maxTokens?: number
  temperature?: number
  enabled?: boolean
}

/** 测试连通性结果（与后端 AiTestResultVO 对应） */
export interface AiTestResult {
  success: boolean
  message: string
  model: string
  apiUrl: string
  latencyMs: number
  reply: string | null
}

export const aiApi = {
  /** 读取配置（key 已脱敏） */
  get: () => http.get<AiConfig>('/api/admin/ai-config'),

  /** 已注册的 AI 工具（工具层扩展位） */
  tools: () => http.get<{ registered: string[]; count: number }>('/api/admin/ai-config/tools'),

  /** 保存配置（服务端加密 API Key 后落库） */
  update: (data: AiConfigForm) => http.put<void>('/api/admin/ai-config', data),

  /** 测试连通性：带表单值则测表单值（先测再存），否则测已保存配置 */
  test: (data: { apiKey?: string; apiUrl?: string; model?: string }) =>
    http.post<AiTestResult>('/api/admin/ai-config/test', data),
}

/* ==================== AI 会话后台（§6.5 接口 1~5） ==================== */

/** 会话列表项（对应后端 AdminSessionVO） */
export interface AdminSession {
  /** ULID 会话 id */
  id: string
  /** 所属站点用户（public_user.id），游客会话为 null */
  userId: number | null
  title: string | null
  /** 含已软删除的消息数，所以不能用来判断「是否已清空」 */
  messageCount: number | null
  lastActiveAt: string | null
  createdAt: string | null
}

/** 消息项（对应后端 AdminMessageVO） */
export interface AdminMessage {
  id: number
  conversationId: string
  userId: number | null
  /** user / assistant */
  role: string
  /** 列表接口是 SQL 截断的 ~100 字摘要，详情接口是全文 */
  content: string
  /** completed / interrupted / empty / blocked */
  status: string
  /** 命中的敏感词（仅 status=blocked 时有值） */
  blockedWord: string | null
  tokenUsage: number | null
  /** 用户是否已清空这条消息 */
  deleted: boolean
  createdAt: string | null
}

/** 清理执行摘要（对应后端 CleanupStatus） */
export interface AiCleanupStatus {
  retentionDays: number
  /** cron 表达式原文 */
  cron: string
  nextRunAt: string | null
  lastRunAt: string | null
  /** scheduled / manual */
  lastRunTrigger: string | null
  lastRunDeleted: number | null
  /** 最近一次失败原因，空串表示没失败过 */
  lastError: string | null
}

/** 单次清理报告（对应后端 CleanupRunReport） */
export interface AiCleanupReport {
  trigger: string
  dryRun: boolean
  /** 是否因单轮删除上限被截断（还有数据留到下次） */
  capped: boolean
  message: string
  startedAt: string | null
  /** 保留截止时间：删的是 created_at < cutoff 的行 */
  cutoff: string | null
  /** 预演统计到的过期条数（dry-run 时就是实际会删的数量） */
  expiredMessages: number
  expiredConversations: number
  deletedMessages: number
  deletedConversations: number
  batches: number
  durationMs: number
}

export const aiAdminApi = {
  /** 会话列表：可按用户、时间区间筛；includeDeleted=false 只返回「还有可见消息」的会话 */
  conversations: (params: Record<string, any>) => http.get<{ list: AdminSession[]; total: number }>('/api/admin/ai/conversations', params),

  /** 某会话的全部消息（含用户已清空的，带 deleted 标记），content 是全文 */
  conversationMessages: (id: string, params: Record<string, any>) =>
    http.get<{ list: AdminMessage[]; total: number }>(`/api/admin/ai/conversations/${id}/messages`, params),

  /** 跨会话检索：status 传 blocked 即违规提问筛选；content 是 ~100 字摘要 */
  searchMessages: (params: Record<string, any>) => http.get<{ list: AdminMessage[]; total: number }>('/api/admin/ai/messages', params),

  /** 清理执行摘要 */
  cleanupStatus: () => http.get<AiCleanupStatus>('/api/admin/ai/cleanup/status'),

  /** 手动触发清理；dryRun=true 只统计不删除（dryRun 走 query 参数，后端是 @RequestParam） */
  cleanup: (dryRun: boolean) => http.post<AiCleanupReport>('/api/admin/ai/cleanup', undefined, { params: { dryRun } }),
}

/* ==================== AI 工具后台（§6.5 接口 6~9） ==================== */

/** 工具列表项（对应后端 AdminToolVO） */
export interface AdminTool {
  id: number
  /** 模型调用时引用的工具名（全局唯一） */
  toolName: string
  /** builtin / api / mcp */
  toolType: string
  displayName: string
  /** 用途描述（模型据此判断是否调用） */
  description: string
  /** public / admin / mcp */
  scope: string
  enabled: boolean
  /** 单轮装配优先级，越小越优先 */
  priority: number
  /** 超时（毫秒） */
  timeoutMs: number
  /** 目标地址（编辑弹窗回填用） */
  httpUrl: string | null
  /** 参数 JSON Schema（编辑弹窗回填用） */
  paramSchema: string | null
  fingerprint: string
  /** 描述已变更、待人工确认（仅 api/mcp 可能为 true） */
  pendingConfirm: boolean
  /** 熔断剩余秒数，0 表示未熔断 */
  circuitRemainingSeconds: number
  confirmedAt: string | null
  updatedAt: string | null
}

/** 工具配置表单（对应后端 ToolFormVO） */
export interface ToolForm {
  toolName: string
  displayName: string
  description: string
  httpMethod?: string
  httpUrl?: string
  httpHeaders?: string
  httpBodyTmpl?: string
  paramSchema?: string
  enabled?: number
  scope?: string
  priority?: number
  timeoutMs?: number
}

/** 连通性探测结果（对应后端 ApiToolInvoker.ProbeResult） */
export interface ToolProbeResult {
  /** 是否连通（拿到 HTTP 响应即算连通，4xx/5xx 也算地址能通） */
  reachable: boolean
  /** ok / timeout / upstream_error / forbidden / invalid_argument / http_xxx … */
  status: string
  httpStatus: number | null
  bytes: number
  elapsedMs: number
  /** 失败原因（成功时为 null） */
  message: string | null
}

export const aiToolApi = {
  /** 工具列表（含熔断倒计时与待确认标记） */
  list: () => http.get<AdminTool[]>('/api/admin/ai/tools'),

  /** 新建 API 工具（新建后处于「待确认」，确认后才可用） */
  create: (data: ToolForm) => http.post<number>('/api/admin/ai/tools', data),

  /** 改 API 工具（改完描述/参数会重新计算指纹 ⇒ 需再次确认） */
  update: (id: number, data: ToolForm) => http.put<void>(`/api/admin/ai/tools/${id}`, data),

  /** 删 API 工具 */
  remove: (id: number) => http.delete<void>(`/api/admin/ai/tools/${id}`),

  /** 确认当前 fingerprint：描述变更后恢复可用 */
  confirm: (id: number) => http.post<void>(`/api/admin/ai/tools/${id}/confirm`),

  /** 连通性测试：只发一次探测请求，SSRF 防护同样适用 */
  test: (id: number) => http.post<ToolProbeResult>(`/api/admin/ai/tools/${id}/test`),

  /**
   * 启用 / 停用工具（内置与 API 都可用）。
   * ⚠️ 内置工具的描述与参数由代码维护（编辑会被拒绝），但「要不要用它」是运营决策，
   *   所以停用单独走这个接口。
   */
  toggleEnabled: (id: number, enabled: boolean) =>
    http.post<void>(`/api/admin/ai/tools/${id}/enabled`, { enabled }),
}
