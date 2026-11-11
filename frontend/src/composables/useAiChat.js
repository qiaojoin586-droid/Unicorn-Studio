import { useAiChatStore } from '@/stores/aiChat'
import { useUserStore } from '@/stores/user'
import { createSseParser } from '@/utils/sse'

const BASE_URL = import.meta.env.VITE_API_BASE || ''
let controller = null
let streamCompletion = Promise.resolve()
let appearanceRequest = null

export function useAiChat() {
  const store = useAiChatStore()
  const userStore = useUserStore()

  /**
   * 取会话 id：没有就现建一个。
   * 方案 §6.1 明确要求「发送前就知道 sessionId」——不能靠后端在 SSE 里隐式创建
   * （那要么改帧格式、要么多一次往返），所以这里是独立的一次 POST。
   * @param firstMessage 本轮要发的消息。带上它建会话，后端才会拿它当会话标题
   *   （CreateSessionRequest.firstMessage → AiSessionService.digestTitle）；
   *   不带的话每个会话标题都是"新会话"，而且后端不会在首条消息落库后回填。
   */
  async function ensureSessionId(firstMessage = '') {
    if (store.sessionId) return store.sessionId
    const res = await fetch(BASE_URL + '/api/ai/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      credentials: 'include',
      body: JSON.stringify({ firstMessage }),
    })
    if (res.status === 401) {
      // 建会话本身就要登录：抛出去交给上层转登录弹窗
      throw new Error('UNAUTHORIZED')
    }
    if (!res.ok) throw new Error('新建会话失败，请稍后重试')
    const body = await res.json()
    const id = body?.data?.id
    if (!id) throw new Error('新建会话失败，请稍后重试')
    store.setSessionId(id)
    return id
  }

  /** 前台 token 走 Authorization 头（方案 §2：前端 token 存 localStorage，非 cookie） */
  function authHeaders() {
    return userStore.token ? { Authorization: `Bearer ${userStore.token}` } : {}
  }

  /** 外观来自公开能力接口；读取失败时保留当前头像或默认图标，不阻断聊天。 */
  function loadAssistantAppearance() {
    if (!appearanceRequest) {
      appearanceRequest = fetch(BASE_URL + '/api/ai/capabilities')
        .then(async (response) => {
          if (!response.ok) return
          const body = await response.json()
          store.messageAvatarUrl = typeof body?.data?.messageAvatarUrl === 'string' ? body.data.messageAvatarUrl : ''
        })
        .catch(() => {})
        .finally(() => { appearanceRequest = null })
    }
    return appearanceRequest
  }

  async function streamChat(userMessage) {
    if (!userMessage.trim() || store.isLoading || store.clearing) return
    void loadAssistantAppearance()

    // 本地先判一次登录：省掉一次必然 401 的请求，用户也不会被"网络错误"误导。
    // 后端仍会独立拦（@RequireSiteUser），这里只是体验优化（方案 §4.2）。
    if (!userStore.isLoggedIn) {
      store.openLoginPrompt(userMessage)
      return
    }

    controller = new AbortController()
    // ⚠️ clientMsgId 绑在【这条消息】上（addUserMessage 里生成），发送时从消息对象取。
    // 必须是「同一条消息同一个键」——它是后端的幂等键（方案 §4.5）：
    // 连点两次发送时，后端靠它认出「这条已受理」并重放，而不是重新生成一遍、白烧一次 token。
    const pendingMsg = store.addUserMessage(userMessage)
    store.isLoading = true
    store.avatarState = 'thinking'
    store.setStreamingText('')

    let finishStream
    streamCompletion = new Promise((resolve) => { finishStream = resolve })
    try {
      const sessionId = await ensureSessionId(userMessage)

      const response = await fetch(BASE_URL + '/api/ai/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeaders() },
        credentials: 'include',
        // clientMsgId 是幂等键：连点发送 / 登录后自动重发都靠它去重（方案 §4.5）
        body: JSON.stringify({
          sessionId,
          message: userMessage,
          clientMsgId: pendingMsg.clientMsgId,
        }),
        signal: controller.signal,
      })

      // 双保险：前端漏判、或 token 恰好过期，后端会回 401 → 同样转登录弹窗
      if (response.status === 401) {
        store.removeLastUserMessage()
        store.openLoginPrompt(userMessage)
        return
      }

      if (!response.ok) {
        // 429 = 限流（后端 /api/ai/chat 默认 5 次/分/IP），与「服务挂了」要给不同提示
        // 403 = ai_enabled=0，账号被单独限制使用 AI
        const message =
          response.status === 429
            ? '提问太频繁了，请等一分钟再试'
            : response.status === 403
              ? '你的账号已被限制使用 AI 助手'
              : 'AI 服务暂不可用，请稍后重试'
        throw new Error(message)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      store.avatarState = 'speaking'

      // SSE 规范解析（优化方案 F-06）已抽到 utils/sse.js：
      // - 跨分片行缓冲：事件边界与 TCP 分包不对齐时半行留到下一分片，弱网不丢字
      // - 兼容 \r\n 行尾；事件内多行 data: 按规范以 \n 合并
      // - ⚠️ 2026-09-14 修复：多行合并必须用**独立首行标志**判断，不能用「累积值是否为空」——
      //   服务端会把模型输出的换行按规范拆成空 `data:` 行，用累积值判断会把换行全部吃掉，
      //   导致整条 Markdown 塌成一行（详细复盘见 utils/sse.js 文件头）
      const parser = createSseParser()
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        for (const data of parser.push(decoder.decode(value, { stream: true }))) {
          store.appendStreamingText(data)
        }
        if (parser.isDone) break
      }
      // 流被截断（没等到 [DONE]）时消费缓冲区里最后一个未收尾的事件，保住已到达的尾部内容
      if (!parser.isDone) {
        for (const data of parser.flush()) store.appendStreamingText(data)
      }

      // 兜底：HTTP 200 但一个分片都没收到（供应商侧空回复、上游异常被吞等）。
      // 不做兜底的话界面表现是「用户问完，loading 结束，然后什么都没有」——
      // 即用户报的「打不出 AI 助手回复的信息」，且没有任何可排查线索。
      if (store.streamingText.trim()) {
        store.finalizeStreaming()
      } else {
        store.setStreamingText('')
        store.addAssistantMessage('抱歉，这次没有收到有效回复，请重新提问一次。')
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        // 用户主动停止：保留已生成的部分内容
        store.finalizeStreaming()
      } else {
        store.setStreamingText('')
        store.addAssistantMessage(err.message || 'AI 连接失败，请稍后重试')
      }
    } finally {
      store.isLoading = false
      store.avatarState = 'idle'
      controller = null
      finishStream()
    }
  }

  function stopStream() {
    if (controller) controller.abort()
  }

  /**
   * 登录成功后把被拦下的那条消息接着问完（方案 §4.5）。
   * 没有待重发消息时只关弹窗，让 AiLoginPrompt 的调用点保持单一出口。
   */
  async function resendPending() {
    store.closeLoginPrompt()
    const pending = store.takePendingMessage()
    if (pending) await streamChat(pending)
  }

  /**
   * 拉会话列表（§8.2：倒序 + 滚到底加载更多）。
   * 未登录先本地拦一道：省掉一次必然 401 的请求，也免得用户被"网络错误"误导（同 streamChat 口径）。
   */
  async function loadSessions(page = 1) {
    if (!userStore.isLoggedIn) {
      store.openLoginPrompt()
      return
    }
    store.historyLoading = true
    try {
      const res = await fetch(`${BASE_URL}/api/ai/sessions?page=${page}&pageSize=20`, {
        headers: authHeaders(),
        credentials: 'include',
      })
      if (res.status === 401) {
        store.openLoginPrompt()
        return
      }
      if (!res.ok) throw new Error('加载历史对话失败，请稍后重试')
      const body = await res.json()
      const list = body?.data?.list || []
      const total = body?.data?.total || 0
      const loaded = (page - 1) * 20 + list.length
      const hasMore = loaded < total
      if (page === 1) {
        store.setSessions(list, hasMore)
      } else {
        store.appendSessions(list, hasMore)
      }
      store.sessionPage = page
      store.historyError = ''
    } catch (err) {
      // 抽屉里不塞对话气泡（那是聊天区的职责），错误交给组件渲染提示条
      store.historyError = err.message || '加载历史对话失败，请稍后重试'
    } finally {
      store.historyLoading = false
    }
  }

  /**
   * 载入某会话的历史消息（§6.3 游标接口）。beforeId=0 取最近一页。
   * @returns 翻正序后的数组；会话已被清空（404）时返回 null，调用方不要继续切会话
   */
  async function loadHistoryMessages(sessionId, beforeId = 0) {
    const res = await fetch(
      `${BASE_URL}/api/ai/sessions/${sessionId}/messages?beforeId=${beforeId}&limit=20`,
      { headers: authHeaders(), credentials: 'include' },
    )
    if (res.status === 401) {
      store.openLoginPrompt()
      return null
    }
    if (res.status === 404) {
      // 消息全被软删了（后端对"查不到就 404"）⇒ 这个会话已经没有可见内容
      store.removeSession(sessionId)
      return null
    }
    if (!res.ok) throw new Error('加载对话内容失败，请稍后重试')
    const body = await res.json()
    const items = body?.data?.items || []
    // 后端游标是「新 → 旧」倒序返回，聊天区要按时间正序展示
    return [...items].reverse()
  }

  /**
   * 清空对话（§7.3）：软删，服务端最多保留 90 天。
   * @returns 是否成功——失败时调用方要提示，不能静默从列表移除
   */
  async function clearSession(sessionId) {
    // 删除当前会话前等流式请求退出，避免迟到的分片写回已经清空的界面。
    if (store.sessionId === sessionId && controller) {
      stopStream()
      await streamCompletion
    }
    const res = await fetch(`${BASE_URL}/api/ai/sessions/${sessionId}`, {
      method: 'DELETE',
      headers: authHeaders(),
      credentials: 'include',
    })
    if (res.status === 401) {
      store.openLoginPrompt()
      return false
    }
    if (!res.ok && res.status !== 404) return false
    // 当前正开着这个会话时一并清本地并丢掉 sessionId：下次发送会新建会话，
    // 否则会继续往一个已被清空的会话里写消息
    if (store.sessionId === sessionId) {
      store.startNewSession({ clearHistory: false })
    }
    store.removeSession(sessionId)
    return true
  }

  /**
   * 打开历史对话抽屉。
   * <p>
   * ⚠️ 未登录时【只弹登录、不开抽屉】：抽屉一开就会 mounted → loadSessions()，
   * 而 loadSessions() 里的未登录判断是「弹登录后 return」——抽屉此时已经开着了，
   * 里面还留着 store 里上一次（可能是别人的）会话标题。
   * 用户关掉登录弹窗后看到的仍是历史记录，��觉上等于"没登录也能看历史"。
   */
  function openHistory() {
    if (!userStore.isLoggedIn) {
      store.openLoginPrompt()
      return
    }
    store.openDrawer()
  }

  return {
    streamChat,
    stopStream,
    resendPending,
    openHistory,
    loadSessions,
    loadHistoryMessages,
    clearSession,
    loadAssistantAppearance,
  }
}
