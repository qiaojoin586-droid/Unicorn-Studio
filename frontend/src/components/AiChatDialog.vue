<template>
  <div
    v-if="store.isOpen"
    class="fixed bottom-28 right-8 z-50 w-96 max-w-[calc(100vw-4rem)]"
  >
    <transition
      enter-active-class="transition duration-300 ease-out"
      enter-from-class="opacity-0 translate-y-4 scale-95"
      enter-to-class="opacity-100 translate-y-0 scale-100"
      leave-active-class="transition duration-200 ease-in"
      leave-from-class="opacity-100 translate-y-0 scale-100"
      leave-to-class="opacity-0 translate-y-4 scale-95"
    >
      <div class="bg-gray-900/95 backdrop-blur-xl border border-white/10 rounded-2xl shadow-2xl overflow-hidden flex flex-col" style="height: 500px; max-height: calc(100vh - 10rem);">
        <!-- Header -->
        <div class="flex items-center justify-between px-5 py-4 border-b border-white/10">
          <div class="flex items-center gap-3">
            <div class="w-8 h-8 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-sm">
              🦄
            </div>
            <div>
              <h3 class="text-white font-semibold text-sm">AI 助手</h3>
              <p class="text-gray-500 text-xs">独角兽工作室智能助手</p>
            </div>
          </div>
          <div class="flex items-center gap-2">
            <button
              v-if="store.hasMessages"
              @click="store.clearMessages()"
              class="text-gray-500 hover:text-gray-300 transition p-1"
              title="清空对话"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
              </svg>
            </button>
            <button
              @click="store.closeChat()"
              class="text-gray-500 hover:text-gray-300 transition p-1"
              title="关闭"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        <!-- Messages area -->
        <div ref="messagesContainer" class="flex-1 overflow-y-auto px-5 py-4 space-y-4 scroll-smooth">
          <!-- Welcome message -->
          <div v-if="!store.hasMessages && !store.streamingText" class="text-center py-8">
            <div class="text-4xl mb-3">🦄</div>
            <p class="text-gray-400 text-sm leading-relaxed">
              你好！我是独角兽工作室的 AI 助手。<br />
              有什么可以帮你的吗？
            </p>
          </div>

          <!-- Message bubbles -->
          <ChatMessage
            v-for="msg in store.messages"
            :key="msg.timestamp"
            :role="msg.role"
            :content="msg.content"
          />

          <!-- Streaming message -->
          <div v-if="store.streamingText" class="flex gap-3">
            <div class="w-7 h-7 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-xs flex-shrink-0 mt-0.5">
              🦄
            </div>
            <div class="bg-white/5 border border-white/10 rounded-2xl rounded-tl-sm px-4 py-3 max-w-[80%]">
              <p class="text-gray-200 text-sm leading-relaxed whitespace-pre-wrap">{{ store.streamingText }}</p>
              <span class="inline-block w-1.5 h-4 bg-indigo-400 animate-pulse ml-0.5 align-middle" />
            </div>
          </div>

          <!-- Thinking indicator -->
          <div v-if="store.isLoading && !store.streamingText" class="flex gap-3">
            <div class="w-7 h-7 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-xs flex-shrink-0">
              🦄
            </div>
            <div class="bg-white/5 border border-white/10 rounded-2xl rounded-tl-sm px-4 py-3">
              <div class="flex gap-1">
                <span class="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style="animation-delay: 0ms" />
                <span class="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style="animation-delay: 150ms" />
                <span class="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style="animation-delay: 300ms" />
              </div>
            </div>
          </div>
        </div>

        <!-- Input area -->
        <ChatInput @send="handleSend" :disabled="store.isLoading" />
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { useAiChatStore } from '@/stores/aiChat'
import { useAiChat } from '@/composables/useAiChat'
import ChatMessage from './ChatMessage.vue'
import ChatInput from './ChatInput.vue'

const store = useAiChatStore()
const { streamChat } = useAiChat()
const messagesContainer = ref(null)

function handleSend(message) {
  streamChat(message)
}

// Auto-scroll to bottom when new messages arrive
watch(
  () => [store.messages.length, store.streamingText],
  async () => {
    await nextTick()
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  },
)
</script>
