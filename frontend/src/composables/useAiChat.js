import { useAiChatStore } from '@/stores/aiChat'
import { createSseParser } from '@/utils/sse'

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
        // 429 = 限流（后端 /api/ai/chat 默认 5 次/分/IP），与「服务挂了」要给不同提示
        throw new Error(
          response.status === 429
            ? '提问太频繁了，请等一分钟再试'
            : 'AI 服务暂不可用，请稍后重试'
        )
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
    }
  }

  function stopStream() {
    if (controller) controller.abort()
  }

  return { streamChat, stopStream }
}
