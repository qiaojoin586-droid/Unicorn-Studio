const BASE_URL = import.meta.env.VITE_API_BASE || ''

export function useApi() {
  async function request(url, options = {}) {
    const res = await fetch(BASE_URL + url, {
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        ...options.headers,
      },
      ...options,
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

  return { request, get, post }
}
