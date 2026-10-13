<template>
  <div class="px-4 py-3 border-t border-white/10">
    <form @submit.prevent="handleSubmit" class="flex items-center gap-2">
      <input
        v-model="inputText"
        type="text"
        :disabled="disabled"
        placeholder="输入你的问题..."
        maxlength="2000"
        aria-label="输入你的问题"
        class="chat-field flex-1 min-w-0 bg-white/5 border border-white/20 rounded-lg px-4 py-3 text-sm text-white placeholder-gray-400 disabled:opacity-50 disabled:cursor-not-allowed transition"
        @keydown.enter.exact.prevent="onEnter"
      />
      <button
        type="submit"
        aria-label="发送消息"
        :disabled="disabled || !inputText.trim()"
        class="chat-send w-11 h-11 flex items-center justify-center rounded-lg disabled:opacity-40 disabled:cursor-not-allowed text-white transition flex-shrink-0"
      >
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19V5m0 0l-7 7m7-7l7 7" />
        </svg>
      </button>
    </form>
    <p class="text-gray-400 text-xs mt-2 text-center">AI 生成内容仅供参考</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  disabled: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['send'])
const inputText = ref('')

function handleSubmit() {
  const text = inputText.value.trim()
  if (!text || props.disabled) return

  emit('send', text)
  inputText.value = ''
}

// 中文输入法选词回车不发送（isComposing/229），避免半截话误发（优化方案 F-05）
function onEnter(e) {
  if (e.isComposing || e.keyCode === 229) return
  handleSubmit()
}
</script>
<style scoped>
.chat-field:focus-visible { outline: 2px solid var(--color-accent-on-dark); outline-offset: 1px; }
.chat-send { background: var(--color-accent); }
.chat-send:hover:not(:disabled) { background: #ab2431; }
</style>
