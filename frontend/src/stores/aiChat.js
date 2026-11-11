import { defineStore } from 'pinia'

export const useAiChatStore = defineStore('aiChat', {
  state: () => ({
    isOpen: false,
    isLoading: false,
    messages: [],
    seq: 0, // 消息自增 id（timestamp 作 key 有同毫秒碰撞可能，优化方案 F-21）
    avatarState: 'idle',
    streamingText: '',
    /** 后台配置的助手消息头像；空字符串表示使用主页默认品牌图标。 */
    messageAvatarUrl: '',
    /** 当前会话 id（ULID）；空表示"还没建会话"，发第一条消息前会先建 */
    sessionId: '',
    /** 登录/注册弹窗显隐 */
    showLoginPrompt: false,
    /** 未登录被拦下的那条消息，登录成功后自动重发（方案 §4.5 R6） */
    pendingMessage: '',
    /** 历史抽屉显隐（§8.2：对话框内部左侧抽屉） */
    drawerOpen: false,
    /** 会话列表（倒序，最近的在最上面） */
    sessions: [],
    /** 会话列表是否还有下一页 */
    hasMoreSessions: false,
    /** 会话列表当前页，加载更多时用 page+1 */
    sessionPage: 1,
    /** 抽屉 / 列表加载态 */
    historyLoading: false,
    /** 抽屉里的错误提示（由 composable 写入，组件只负责渲染与清除） */
    historyError: '',
    /** 待确认清空的会话 { id, title }；null = 确认框关闭 */
    confirmTarget: null,
    /** 清空请求进行中：禁用按钮，防重复点 */
    clearing: false,
  }),

  getters: {
    isSpeaking: (state) => state.avatarState === 'speaking',
    isThinking: (state) => state.avatarState === 'thinking',
    hasMessages: (state) => state.messages.length > 0,
    hasSessions: (state) => state.sessions.length > 0,
  },

  actions: {
    toggleChat() {
      this.isOpen = !this.isOpen
    },

    openChat() {
      this.isOpen = true
    },

    closeChat() {
      this.isOpen = false
    },

    addUserMessage(content) {
      // ⚠️ clientMsgId 在【用户输入这一刻】生成并绑到这条消息上，而不是在发送时临时生成。
      // 它是后端的幂等键（方案 §4.5）：同一条消息重复提交（连点发送、登录后自动重发）
      // 必须带同一个 clientMsgId，后端才能认出「这条已经受理过」并走重放而不是重新生成。
      // 若在发送时才生成，每次都是新 UUID ⇒ 幂等键形同虚设 ⇒ 连点两次会生成两次、白烧两次 token。
      this.messages.push({
        id: ++this.seq,
        role: 'user',
        content,
        clientMsgId: crypto.randomUUID(),
        timestamp: Date.now(),
      })
      return this.messages[this.messages.length - 1]
    },

    addAssistantMessage(content) {
      this.messages.push({ id: ++this.seq, role: 'assistant', content, timestamp: Date.now() })
    },

    /**
     * 撤回最后一条本地用户消息。
     * 用在「后端回 401、消息其实没被受理」的场景：这条要交给登录弹窗去重发，
     * 留在列表里会出现「同一条问题两个气泡」。
     */
    removeLastUserMessage() {
      const last = this.messages[this.messages.length - 1]
      if (last?.role === 'user') this.messages.pop()
    },

    setStreamingText(text) {
      this.streamingText = text
    },

    appendStreamingText(chunk) {
      this.streamingText += chunk
    },

    finalizeStreaming() {
      if (this.streamingText) {
        this.addAssistantMessage(this.streamingText)
        this.streamingText = ''
      }
    },

    clearMessages() {
      this.messages = []
      this.streamingText = ''
    },

    /**
     * 开新对话：清空本地消息并丢掉 sessionId。
     * 下次发送前会重新 POST /api/ai/sessions —— 旧会话的历史仍在服务端（方案 §7.3 会话行不删）。
     */
    startNewSession({ clearHistory = true } = {}) {
      this.clearMessages()
      this.sessionId = ''
      this.pendingMessage = ''
      // ⚠️ 会话列表也要清：退出登录时若不清，store 里还留着上一个账号的会话标题，
      // 下次打开抽屉、而列表接口恰好失败时，就会把别人的会话标题显示出来。
      if (clearHistory) {
        this.sessions = []
        this.hasMoreSessions = false
        this.sessionPage = 1
      }
    },

    setSessionId(id) {
      this.sessionId = id || ''
    },

    openLoginPrompt(pendingMessage = '') {
      this.pendingMessage = pendingMessage
      this.showLoginPrompt = true
    },

    closeLoginPrompt() {
      this.showLoginPrompt = false
    },

    /** 自动重发前取出并清空，避免重复触发 */
    takePendingMessage() {
      const msg = this.pendingMessage
      this.pendingMessage = ''
      return msg
    },

    // ---- 历史抽屉（§8.2）----

    /** 清空对话的二次确认（§8.2：两个入口共用，抽屉里的垃圾桶与对话框右上角都是它） */
    openConfirm(session) {
      if (session?.id) {
        this.confirmTarget = { id: session.id, title: session.title || '' }
      }
    },

    closeConfirm() {
      if (!this.clearing) {
        this.confirmTarget = null
      }
    },

    openDrawer() {
      this.drawerOpen = true
      this.sessionPage = 1
    },

    closeDrawer() {
      this.drawerOpen = false
    },

    /** 首页替换（重新打开抽屉时刷新） */
    setSessions(list, hasMore) {
      this.sessions = list
      this.hasMoreSessions = hasMore
    },

    /** 加载更多时追加，滚到底触发 */
    appendSessions(list, hasMore) {
      this.sessions = this.sessions.concat(list)
      this.hasMoreSessions = hasMore
    },

    /** 清空成功后从列表移除；会话被清空后再点进来会 404，也靠它兜掉 */
    removeSession(id) {
      this.sessions = this.sessions.filter((s) => s.id !== id)
    },

    /**
     * 载入历史消息后整体替换气泡。
     * 用 seq 继续自增而不是拿后端 id 当 key：切换会话时两个列表的 id 会撞，
     * Vue 会复用错的气泡（same key 不同内容），表现为「切了会话但显示还是上一个」。
     */
    replaceMessages(list) {
      this.messages = []
      this.streamingText = ''
      for (const m of list) {
        this.messages.push({
          id: ++this.seq,
          role: m.role,
          content: m.content,
          timestamp: m.createdAt ? new Date(m.createdAt).getTime() : Date.now(),
        })
      }
    },
  },
})
