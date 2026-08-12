import { useAiChatStore } from '@/stores/aiChat'

const BASE_URL = import.meta.env.VITE_API_BASE || ''

export function useAiChat() {
  const store = useAiChatStore()

  async function streamChat(userMessage) {
    if (!userMessage.trim() || store.isLoading) return

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
      })

      if (!response.ok) {
        throw new Error('AI 服务暂不可用，请稍后重试')
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      store.avatarState = 'speaking'

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        const chunk = decoder.decode(value, { stream: true })
        const lines = chunk.split('\n')
          .filter((l) => l.startsWith('data: '))
          .map((l) => l.slice(6))

        for (const data of lines) {
          if (data === '[DONE]') break
          store.appendStreamingText(data)
        }
      }

      store.finalizeStreaming()
    } catch (err) {
      store.setStreamingText('')
      store.addAssistantMessage(err.message || 'AI 连接失败，请稍后重试')
    } finally {
      store.isLoading = false
      store.avatarState = 'idle'
    }
  }

  return { streamChat }
}
