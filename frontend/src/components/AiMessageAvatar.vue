<template>
  <span class="ai-message-avatar" data-ai-message-avatar>
    <img v-if="source && !failed" :src="source" alt="AI 助手头像" referrerpolicy="no-referrer" @error="failed = true" />
    <BrandMark v-else aria-hidden="true" />
  </span>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import BrandMark from './BrandMark.vue'
import { useAiChatStore } from '@/stores/aiChat'
import { mediaUrl } from '@/utils/links'

const store = useAiChatStore()
const source = computed(() => mediaUrl(store.messageAvatarUrl))
const failed = ref(false)
watch(source, () => { failed.value = false })
</script>

<style scoped>
.ai-message-avatar { display: grid; place-items: center; width: 28px; height: 28px; flex-shrink: 0; overflow: hidden; border-radius: 50%; color: var(--ai-panel-text); background: var(--ai-panel-hover); }
.ai-message-avatar img { display: block; width: 100%; height: 100%; object-fit: cover; }
.ai-message-avatar :deep(.brand-mark) { width: 22px; height: 22px; }
</style>
