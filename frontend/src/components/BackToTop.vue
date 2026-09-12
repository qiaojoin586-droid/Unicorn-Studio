<template>
  <button
    type="button"
    aria-label="回到顶部"
    title="回到顶部"
    class="fixed right-6 bottom-[120px] z-[999] w-10 h-10 sm:w-11 sm:h-11 rounded-full bg-gradient-to-br from-[#6C5CE7] to-[#A29BFE] text-white flex items-center justify-center shadow-lg hover:shadow-xl hover:scale-105 cursor-pointer transition-all duration-200"
    :class="visible ? 'opacity-100' : 'opacity-0 pointer-events-none'"
    @click="scrollToTop"
  >
    <svg
      class="w-5 h-5"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <path d="M19 15l-7-7-7 7" />
    </svg>
  </button>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

/** 滚动超过该阈值（首屏 Hero 之后）才显示 */
const SCROLL_THRESHOLD = 600

const visible = ref(false)

function handleScroll() {
  const shouldShow = window.scrollY > SCROLL_THRESHOLD
  // 仅在状态翻转时赋值，避免高频 scroll 触发无谓的响应式更新
  if (shouldShow !== visible.value) {
    visible.value = shouldShow
  }
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => {
  window.addEventListener('scroll', handleScroll, { passive: true })
  // 初始化同步：处理刷新/路由切换后浏览器恢复滚动位置的情况
  handleScroll()
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
})
</script>
