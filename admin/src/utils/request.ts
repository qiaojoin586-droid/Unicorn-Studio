import axios, { type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

/**
 * axios 实例 + 拦截器：
 * - 请求带 Bearer token；
 * - 401 → 刷新队列（并发只发一次 refresh）→ 重放原请求；失败 → 清登录态跳转登录页。
 */
const service = axios.create({
  baseURL: '/',
  timeout: 30000,
})

service.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem('admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

let refreshPromise: Promise<string | null> | null = null

async function doRefresh(): Promise<string | null> {
  const refreshToken = localStorage.getItem('admin_refresh_token')
  if (!refreshToken) return null
  try {
    const res = await axios.post('/api/admin/auth/refresh', { refreshToken })
    const data = res.data?.data
    if (data?.accessToken) {
      localStorage.setItem('admin_token', data.accessToken)
      localStorage.setItem('admin_refresh_token', data.refreshToken)
      return data.accessToken
    }
    return null
  } catch {
    return null
  }
}

function refreshToken(): Promise<string | null> {
  refreshPromise ??= doRefresh().finally(() => {
    refreshPromise = null
  })
  return refreshPromise
}

function redirectToLogin() {
  localStorage.removeItem('admin_token')
  localStorage.removeItem('admin_refresh_token')
  localStorage.removeItem('admin_permissions')
  localStorage.removeItem('admin_user')
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.href = `/admin/login?redirect=${redirect}`
}

service.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) {
        return body.data
      }
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  async (error) => {
    const { response, config } = error
    // 401 → 尝试刷新重放一次
    if (response?.status === 401 && !config._retried) {
      config._retried = true
      const newToken = await refreshToken()
      if (newToken) {
        config.headers.Authorization = `Bearer ${newToken}`
        return service(config)
      }
      redirectToLogin()
      return Promise.reject(error)
    }
    if (response?.status === 429) {
      ElMessage.error('操作过于频繁，请稍后再试')
    } else if (response?.data?.message) {
      ElMessage.error(response.data.message)
    } else {
      ElMessage.error('网络异常，请稍后重试')
    }
    return Promise.reject(error)
  },
)

/** 带类型的请求封装（响应已解包为 data）。 */
export const http = {
  get<T = any>(url: string, params?: Record<string, any>, config?: AxiosRequestConfig): Promise<T> {
    return service.get(url, { params, ...config }) as Promise<T>
  },
  post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    return service.post(url, data, config) as Promise<T>
  },
  put<T = any>(url: string, data?: any): Promise<T> {
    return service.put(url, data) as Promise<T>
  },
  patch<T = any>(url: string, data?: any): Promise<T> {
    return service.patch(url, data) as Promise<T>
  },
  delete<T = any>(url: string): Promise<T> {
    return service.delete(url) as Promise<T>
  },
}

export default service
