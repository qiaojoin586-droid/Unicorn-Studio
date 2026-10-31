<template>
  <button v-if="visible && !store.isOpen" type="button" aria-label="回到顶部" title="回到顶部" class="back-to-top" :style="positionStyle" @click="scrollToTop"><svg width="19" height="19" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m6 13 6-6 6 6M12 7v13" stroke="currentColor" stroke-width="1.5" /></svg></button>
</template>
<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useAiChatStore } from '@/stores/aiChat'
import { useFloatingPanelState } from '@/composables/useResizablePanel'
const { iconStyle, attach } = useFloatingPanelState()
const positionStyle = computed(() => {
  const x = parseFloat(iconStyle.value.left)
  const y = parseFloat(iconStyle.value.top)
  return { left: (x >= 68 ? x - 60 : x + 80) + 'px', top: (y + 10) + 'px' }
})
const store = useAiChatStore()
const visible = ref(false)
function onScroll() { visible.value = window.scrollY > 600 }
function scrollToTop() { window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' }) }
onMounted(() => { attach(); onScroll(); window.addEventListener('scroll', onScroll, { passive: true }) })
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
</script>
<style scoped>
.back-to-top { position: fixed; z-index: calc(var(--z-tools) - 1);  width: 44px; height: 44px; display: grid; place-items: center; color: var(--color-ink); background: var(--color-paper); border: 1px solid var(--color-line); border-radius: 12px; box-shadow: 0 4px 16px #0000000d; transition: background var(--duration-ui), transform var(--duration-ui); }
.back-to-top:hover { transform: translateY(-3px); border-color: var(--color-accent); }
.back-to-top:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 3px; }
@media(max-width:1023px) { .back-to-top { left: auto !important; top: auto !important; right: var(--page-gutter); bottom: calc(24px + env(safe-area-inset-bottom, 0px)); } }
</style>
