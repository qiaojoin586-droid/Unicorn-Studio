const BASE_URL = import.meta.env.VITE_API_BASE || ''
const DEFAULT_TIMEOUT = 15000

export function useApi() {
  async function request(url, options = {}) {
    try {
      const res = await fetch(BASE_URL + url, {
        // 先展开调用方配置，再统一合并 headers（修复：调用方自定义 headers 会整体覆盖 Content-Type）
        ...options,
        headers: {
          'Content-Type': 'application/json',
          ...options.headers,
        },
        credentials: 'include',
        // 默认 15s 超时（AbortSignal.timeout），防后端挂起时加载态永久转圈
        signal: options.signal ?? AbortSignal.timeout(DEFAULT_TIMEOUT),
      })

      if (res.status === 401) {
        return { error: true, status: 401, message: '请先验证访问权限' }
      }

      if (!res.ok) {
        let message = '请求失败，请稍后重试'
        try {
          const data = await res.json()
          message = data.message || message
        } catch {
          // ignore parse error
        }
        return { error: true, status: res.status, message }
      }

      return res.json()
    } catch (err) {
      // TimeoutError/AbortError 归一为统一错误对象，避免 DOMException 文案穿透到调用方
      if (err.name === 'TimeoutError') {
        return { error: true, status: 0, message: '请求超时，请检查网络后重试' }
      }
      if (err.name === 'AbortError') {
        return { error: true, status: 0, message: '请求已取消', aborted: true }
      }
      return { error: true, status: 0, message: '网络异常，请稍后重试' }
    }
  }

  async function get(url) {
    return request(url, { method: 'GET' })
  }

  async function post(url, body) {
    return request(url, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  }

  async function del(url) {
    return request(url, { method: 'DELETE' })
  }

  /**
   * 前台站点用户登录/注册（/api/auth/*，与后台 sys_user 是两套体系）。
   * 提前固定好请求体字段名，调用方只传值，避免各处手写 body 写漂。
   */
  function login(email, password) {
    return post('/api/auth/login', { email, password })
  }

  function register(nickname, email, password) {
    return post('/api/auth/register', { nickname, email, password })
  }

  /**
   * 退出登录。<b>必须带 token</b>：后端据此把它记进 Redis 撤销名单，
   * 否则纯无状态 JWT 删了本地也仍然可用（没有 logout 接口时的既有限制）。
   */
  function logout(token) {
    return request('/api/auth/logout', {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
  }

  return { request, get, post, del, login, register, logout }
}
