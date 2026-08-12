import { defineStore } from 'pinia'

export const useAiChatStore = defineStore('aiChat', {
  state: () => ({
    isOpen: false,
    isLoading: false,
    messages: [],
    avatarState: 'idle',
    streamingText: '',
  }),

  getters: {
    isSpeaking: (state) => state.avatarState === 'speaking',
    isThinking: (state) => state.avatarState === 'thinking',
    hasMessages: (state) => state.messages.length > 0,
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
      this.messages.push({ role: 'user', content, timestamp: Date.now() })
    },

    addAssistantMessage(content) {
      this.messages.push({ role: 'assistant', content, timestamp: Date.now() })
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
  },
})
