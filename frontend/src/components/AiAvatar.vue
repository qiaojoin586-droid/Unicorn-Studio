<template>
  <!--
    位置由 useResizablePanel 的共享布局状态给出（`:style` 里的 left/top），
    不再写死 `bottom-8 right-8` —— 因为图标与对话框是「刚性配对」，
    拖动任意一方都要让另一方同步位移，必须由同一份坐标驱动。
    默认值仍等于 bottom-8 right-8，视觉无变化。
  -->
  <div
    data-ai-icon
    class="fixed z-50 select-none touch-none"
    :style="iconStyle"
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
import { useFloatingPanelState } from '@/composables/useResizablePanel'

const store = useAiChatStore()

const showTooltip = ref(false)
let tooltipTimer = null

// 图标与对话框共享同一份坐标/尺寸状态：拖动图标 ⇒ 对话框同步位移。
// attach() 负责视口变化时把两者一起拉回可视区（引用计数，对话框卸载不会摘掉这里的监听）。
const { iconStyle, attach, startPairDrag, didDrag } = useFloatingPanelState()

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
  // 刚拖动过（超过阈值）就不当成点击，否则拖完图标会把对话关掉 / 打开
  if (didDrag.value) return
  store.toggleChat()
}

// 拖动逻辑在 composable 里（图标和对话框共用同一套「成对位移」算法），
// 这里只做转发。
function startDrag(e) {
  startPairDrag(e)
}

// Show tooltip after 3 seconds
onMounted(() => {
  attach()
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
