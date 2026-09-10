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
        class="flex-1 bg-white/5 border border-white/10 rounded-xl px-4 py-2.5 text-sm text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500/50 focus:ring-1 focus:ring-indigo-500/50 disabled:opacity-50 disabled:cursor-not-allowed transition"
        @keydown.enter.exact.prevent="onEnter"
      />
      <button
        type="submit"
        :disabled="disabled || !inputText.trim()"
        class="w-9 h-9 flex items-center justify-center rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:bg-gray-700 disabled:cursor-not-allowed text-white transition flex-shrink-0"
      >
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19V5m0 0l-7 7m7-7l7 7" />
        </svg>
      </button>
    </form>
    <p class="text-gray-500 text-[10px] mt-1.5 text-center">AI 生成内容仅供参考</p>
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
