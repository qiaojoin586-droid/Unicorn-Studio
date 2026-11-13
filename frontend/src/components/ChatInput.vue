<template>
  <div class="chat-composer flex-shrink-0 px-4 py-3 border-t border-[var(--ai-panel-line)]">
    <form @submit.prevent="handleSubmit" class="flex items-center gap-2">
      <input
        v-model="inputText"
        type="text"
        :disabled="disabled"
        placeholder="输入你的问题..."
        maxlength="2000"
        aria-label="输入你的问题"
        class="chat-field flex-1 min-w-0 bg-[var(--ai-field-bg)] border border-[var(--ai-field-border)] rounded-lg px-4 py-2.5 text-sm text-[var(--ai-panel-text)] placeholder-gray-400 disabled:opacity-50 disabled:cursor-not-allowed transition"
        @keydown.enter.exact.prevent="onEnter"
      />
      <button
        :type="loading ? 'button' : 'submit'"
        :data-stop-generation="loading ? '' : null"
        :aria-label="loading ? '停止生成' : '发送消息'"
        :title="loading ? '停止生成' : '发送消息'"
        :disabled="!loading && (disabled || !inputText.trim())"
        :class="loading ? 'chat-stop' : 'chat-send'"
        class="chat-action w-11 h-11 flex items-center justify-center rounded-lg disabled:opacity-40 disabled:cursor-not-allowed text-[var(--ai-panel-text)] transition flex-shrink-0"
        @click="loading && emit('stop')"
      >
        <span v-if="loading" class="w-3 h-3 rounded-[2px] bg-current inline-block" aria-hidden="true"></span>
        <svg v-else class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19V5m0 0l-7 7m7-7l7 7" />
        </svg>
      </button>
    </form>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  /** 输入框是否禁用（生成中禁编辑） */
  disabled: {
    type: Boolean,
    default: false,
  },
  /** 是否正在生成：决定右侧按钮是「发送」还是「停止」 */
  loading: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['send', 'stop'])
const inputText = ref('')

function handleSubmit() {
  const text = inputText.value.trim()
  if (!text || props.disabled || props.loading) return

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
.chat-composer { background: var(--ai-panel); padding-bottom: max(12px, env(safe-area-inset-bottom)); }
.chat-action:focus-visible { outline: 2px solid var(--color-accent-on-dark); outline-offset: 3px; }
.chat-field:focus-visible { outline: 2px solid var(--color-accent-on-dark); outline-offset: 1px; }
.chat-send { background: var(--color-accent); }
.chat-send:hover:not(:disabled) { background: #ab2431; }
/* 停止键：不用强调色（那是「发送」的颜色）⇒ 用中性深色 + 悬停才变红。
   红色在这套 UI 里是品牌强调色、不是危险提示色，全红会让人以为是可点的发送键。 */
.chat-stop { background: var(--ai-panel-raised); color: var(--ai-panel-text); box-shadow: inset 0 0 0 1px var(--ai-panel-line-strong); }
.chat-stop:hover { background: var(--color-accent); color: #fff; }
</style>
