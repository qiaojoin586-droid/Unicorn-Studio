<template>
  <button v-if="visible && !store.isOpen" type="button" aria-label="回到顶部" title="回到顶部" class="back-to-top" @click="scrollToTop"><svg width="19" height="19" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m6 13 6-6 6 6M12 7v13" stroke="currentColor" stroke-width="1.5" /></svg></button>
</template>
<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useAiChatStore } from '@/stores/aiChat'
const store = useAiChatStore()
const visible = ref(false)
function onScroll() { visible.value = window.scrollY > 600 }
function scrollToTop() { window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' }) }
onMounted(() => { onScroll(); window.addEventListener('scroll', onScroll, { passive: true }) })
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
</script>
<style scoped>
.back-to-top { position: fixed; z-index: calc(var(--z-tools) - 1); right: 42px; bottom: calc(112px + env(safe-area-inset-bottom, 0px)); width: 44px; height: 44px; display: grid; place-items: center; color: var(--color-ink); background: var(--color-paper); border: 1px solid var(--color-line); border-radius: 50%; transition: background var(--duration-ui), transform var(--duration-ui); }
.back-to-top:hover { background: white; transform: translateY(-3px); }
</style>
