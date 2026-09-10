import { useAiChatStore } from '@/stores/aiChat'

const BASE_URL = import.meta.env.VITE_API_BASE || ''
let controller = null

export function useAiChat() {
  const store = useAiChatStore()

  async function streamChat(userMessage) {
    if (!userMessage.trim() || store.isLoading) return

    controller = new AbortController()
    store.addUserMessage(userMessage)
    store.isLoading = true
    store.avatarState = 'thinking'
    store.setStreamingText('')

    try {
      const response = await fetch(BASE_URL + '/api/ai/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ message: userMessage }),
        signal: controller.signal,
      })

      if (!response.ok) {
        throw new Error('AI 服务暂不可用，请稍后重试')
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      store.avatarState = 'speaking'

      // SSE 规范解析（优化方案 F-06）：
      // - 跨分片行缓冲：事件边界与 TCP 分包不对齐时半行留到下一分片，弱网不丢字
      // - 兼容 \r\n 行尾；事件内多行 data: 按规范以 \n 合并（AI 回复含换行不丢行）
      let buffer = ''
      let finished = false
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        buffer = buffer.replace(/\r\n/g, '\n')
        const events = buffer.split('\n\n')
        buffer = events.pop() ?? ''
        for (const event of events) {
          let data = ''
          for (const line of event.split('\n')) {
            if (!line.startsWith('data:')) continue
            data += (data ? '\n' : '') + line.slice(5).replace(/^ /, '')
          }
          if (data === '[DONE]') {
            finished = true
            break
          }
          if (data) store.appendStreamingText(data)
        }
        if (finished) break
      }

      store.finalizeStreaming()
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
    }
  }

  function stopStream() {
    if (controller) controller.abort()
  }

  return { streamChat, stopStream }
}
