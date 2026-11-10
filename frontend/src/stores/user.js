import { defineStore } from 'pinia'

/** 前台站点用户会话的持久化键（与面板尺寸等 UI 偏好分开存放） */
const STORAGE_KEY = 'unicorn.auth.public'

/**
 * 读取存档。隐私模式禁用 localStorage、或存档被手改坏时一律当"未登录"，
 * 不向上抛 —— 否则应用启动阶段就会白屏。
 */
function readSession() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

/**
 * 前台站点用户（public_user）会话状态。
 *
 * 与后台管理员体系完全独立：两套 token、两个 secret、两个接口前缀
 * （前台 /api/auth/*，后台 /api/admin/*）。本 store 只服务前台，
 * 目前唯一的消费者是 AI 助手对话。
 */
export const useUserStore = defineStore('user', {
  state: () => {
    const saved = readSession()
    return {
      // 前台 token 走 Authorization: Bearer 头（不是 cookie），有效期由后端控制（7 天）
      token: saved?.token || '',
      user: saved?.user ? { id: saved.user.id, nickname: saved.user.nickname, phone: saved.user.phone } : null,
    }
  },

  getters: {
    isLoggedIn: (state) => !!state.token,
    displayName: (state) => state.user?.nickname || '',
    maskedPhone: (state) => state.user?.phone?.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2') || '',
  },

  actions: {
    /** 登录 / 注册成功后都由这里落地，保证内存与 localStorage 不会各说各话 */
    setSession(token, user) {
      this.token = token || ''
      this.user = user || null
      if (typeof window === 'undefined') return
      try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: this.token, user: this.user }))
      } catch {
        /* 写入失败（隐私模式 / 配额满）：本次会话仍可用，只是刷新后要重新登录 */
      }
    },

    clearSession() {
      this.token = ''
      this.user = null
      if (typeof window === 'undefined') return
      try {
        window.localStorage.removeItem(STORAGE_KEY)
      } catch {
        /* 同上，静默忽略 */
      }
    },
  },
})
