<template>
  <div data-ai-icon class="ai-launcher" :style="iconStyle" @pointerdown="onPointerDown">
    <button type="button" data-ai-trigger class="ai-trigger" :class="{ 'is-busy': store.isLoading }" :aria-expanded="store.isOpen" aria-controls="ai-dialog" :aria-label="store.isOpen ? '收起 AI 助手' : '打开 AI 助手'" @click.stop="handleClick">
      <svg v-if="store.isOpen" viewBox="0 0 24 24" width="24" height="24" fill="none" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6" stroke="currentColor" stroke-width="1.5" /></svg>
      <BrandMark v-else class="ai-avatar-mark" aria-hidden="true" />
      <span v-if="store.isLoading" class="busy-ring" aria-hidden="true"></span>
    </button>
  </div>
</template>
<script setup>
import { onMounted } from 'vue'
import { useAiChatStore } from '@/stores/aiChat'
import BrandMark from './BrandMark.vue'
import { useFloatingPanelState } from '@/composables/useResizablePanel'
const store = useAiChatStore()
const { iconStyle, attach, startPairDrag, didDrag } = useFloatingPanelState()
function handleClick(event) { if (!didDrag.value || event.detail === 0) store.toggleChat() }
function onPointerDown(event) { if (window.innerWidth >= 1024) startPairDrag(event); else didDrag.value = false }
onMounted(attach)
</script>
<style scoped>
.ai-launcher { position: fixed; z-index: var(--z-tools); width: 64px; height: 64px; user-select: none; touch-action: none; }
.ai-trigger { position: relative; width: 64px; height: 64px; display: grid; place-items: center; color: var(--color-paper); background: var(--color-ink); border: 1px solid #ffffff32; border-radius: 50%; box-shadow: 0 5px 24px #0002; transition: background var(--duration-ui), transform var(--duration-ui) var(--ease-out); }
.ai-trigger:hover { background: #303136; transform: translateY(-2px); }
/* 头像用页头同一个独角兽标志（BrandMark），不用 "AI" 字样 */
.ai-avatar-mark { width: 34px; height: 34px; color: var(--color-paper); }
.ai-avatar-mark :deep(.brand-mark) { width: 34px; height: 34px; }
.busy-ring { position: absolute; inset: -4px; border: 1px solid transparent; border-top-color: var(--color-accent); border-radius: 50%; animation: busy 1.4s linear infinite; }
@keyframes busy { to { transform: rotate(360deg); } }
@media(max-width:1023px) {
  .ai-launcher { left: auto !important; top: calc((var(--header-height) - 44px) / 2) !important; right: calc(var(--page-gutter) + 76px); width: 44px; height: 44px; touch-action: manipulation; }
  .ai-trigger { width: 44px; height: 44px; border-color: #ffffff32; box-shadow: none; }
  .ai-avatar-mark { width: 24px; height: 24px; }
  .ai-avatar-mark :deep(.brand-mark) { width: 24px; height: 24px; }
  .ai-trigger:hover { transform: none; }
}
</style>
