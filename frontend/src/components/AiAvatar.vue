<template>
  <div
    class="fixed bottom-8 right-8 z-50 select-none touch-none"
    @pointerdown="startDrag"
  >
    <div
      class="relative cursor-pointer"
      role="button"
      tabindex="0"
      aria-label="打开或收起 AI 助手"
      @click.stop="handleClick"
      @keydown.enter.prevent="handleClick"
      @keydown.space.prevent="handleClick"
    >
      <!-- Outer glow ring -->
      <div
        class="absolute -inset-2 rounded-full blur-xl transition-colors duration-500"
        :class="glowClass"
      />

      <!-- Avatar body -->
      <div
        class="relative w-16 h-16 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-3xl shadow-2xl transition-transform duration-300 hover:scale-110"
        :class="bodyClass"
      >
        <span class="drop-shadow-lg">{{ avatarEmoji }}</span>
      </div>

      <!-- Status dot -->
      <div
        v-if="store.avatarState !== 'idle'"
        class="absolute -top-0.5 -right-0.5 w-4 h-4 rounded-full border-2 border-white shadow-sm"
        :class="dotClass"
      />

      <!-- Tooltip -->
      <transition
        enter-active-class="transition duration-200 ease-out"
        enter-from-class="opacity-0 translate-y-1"
        enter-to-class="opacity-100 translate-y-0"
        leave-active-class="transition duration-150 ease-in"
        leave-from-class="opacity-100 translate-y-0"
        leave-to-class="opacity-0 translate-y-1"
      >
        <div
          v-if="showTooltip && !store.isOpen"
          class="absolute bottom-full right-0 mb-3 px-3 py-1.5 bg-gray-900 text-white text-xs rounded-lg shadow-lg whitespace-nowrap"
        >
          点击和我聊聊
          <div class="absolute top-full right-4 w-2 h-2 bg-gray-900 rotate-45 -mt-1" />
        </div>
      </transition>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useAiChatStore } from '@/stores/aiChat'

const store = useAiChatStore()

const isDragging = ref(false)
const showTooltip = ref(false)
let tooltipTimer = null

const avatarEmoji = computed(() => {
  if (store.isThinking) return '🤔'
  if (store.isSpeaking) return '💬'
  return '🦄'
})

const glowClass = computed(() => {
  if (store.isThinking) return 'bg-amber-500/30 animate-pulse'
  if (store.isSpeaking) return 'bg-green-500/30 animate-pulse'
  return 'bg-indigo-500/20'
})

const bodyClass = computed(() => {
  if (store.isThinking) return 'animate-bounce-subtle'
  return ''
})

const dotClass = computed(() => {
  if (store.isThinking) return 'bg-amber-400 animate-pulse'
  if (store.isSpeaking) return 'bg-green-400 animate-pulse'
  return 'bg-gray-400'
})

function handleClick() {
  if (isDragging.value) return
  store.toggleChat()
}

// Drag support
let dragStartX = 0
let dragStartY = 0
let elStartX = 0
let elStartY = 0
let hasMoved = false

function startDrag(e) {
  if (e.button !== 0) return
  const target = e.currentTarget
  const rect = target.getBoundingClientRect()

  dragStartX = e.clientX
  dragStartY = e.clientY
  elStartX = rect.left
  elStartY = rect.top
  hasMoved = false

  const onMove = (ev) => {
    const dx = ev.clientX - dragStartX
    const dy = ev.clientY - dragStartY
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
      hasMoved = true
      isDragging.value = true
      // 边界钳制：始终留在视口内（优化方案 F-19），Pointer Events 统一鼠标/触屏
      const width = target.offsetWidth
      const height = target.offsetHeight
      const left = Math.min(Math.max(8, elStartX + dx), window.innerWidth - width - 8)
      const top = Math.min(Math.max(8, elStartY + dy), window.innerHeight - height - 8)
      target.style.left = left + 'px'
      target.style.top = top + 'px'
      target.style.right = 'auto'
      target.style.bottom = 'auto'
    }
  }

  const onUp = () => {
    document.removeEventListener('pointermove', onMove)
    document.removeEventListener('pointerup', onUp)
    setTimeout(() => {
      isDragging.value = false
    }, 0)
  }

  document.addEventListener('pointermove', onMove)
  document.addEventListener('pointerup', onUp)
}

// Show tooltip after 3 seconds
onMounted(() => {
  tooltipTimer = setTimeout(() => {
    if (!store.isOpen) {
      showTooltip.value = true
      setTimeout(() => { showTooltip.value = false }, 4000)
    }
  }, 3000)
})

onBeforeUnmount(() => {
  if (tooltipTimer) clearTimeout(tooltipTimer)
})
</script>

<style scoped>
@keyframes bounce-subtle {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-3px); }
}
.animate-bounce-subtle {
  animation: bounce-subtle 1s ease-in-out infinite;
}
</style>
