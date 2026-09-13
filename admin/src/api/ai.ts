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
