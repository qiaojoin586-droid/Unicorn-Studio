<template>
  <div class="absolute inset-0 overflow-hidden">
    <img
      :src="poster"
      alt=""
      aria-hidden="true"
      fetchpriority="high"
      class="absolute inset-0 h-full w-full object-cover"
    />
    <video
      v-if="shouldPlay"
      ref="videoEl"
      class="absolute inset-0 h-full w-full object-cover"
      autoplay
      muted
      loop
      playsinline
      preload="metadata"
      :poster="poster"
      aria-hidden="true"
      @error="shouldPlay = false"
    >
      <source :src="src" type="video/mp4" />
    </video>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'

defineProps({
  src: {
    type: String,
    required: true,
  },
  poster: {
    type: String,
    required: true,
  },
})

const shouldPlay = ref(false)
const videoEl = ref(null)
let videoVisible = true
let pageHidden = false
let observer = null

// 播放同步（优化方案 F-08）：滚出视口/页面隐藏即暂停，回来自动恢复；
// play() 返回 Promise，可能因自动播放策略 reject，需吞掉
function syncPlayback() {
  const video = videoEl.value
  if (!video) return
  if (videoVisible && !pageHidden) {
    video.play().catch(() => {})
  } else if (!video.paused) {
    video.pause()
  }
}

function onVisibilityChange() {
  pageHidden = document.hidden
  syncPlayback()
}

onMounted(() => {
  const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const prefersReducedData = navigator.connection?.saveData === true
  shouldPlay.value = !prefersReducedMotion && !prefersReducedData

  if (shouldPlay.value && videoEl.value) {
    observer = new IntersectionObserver(([entry]) => {
      videoVisible = entry.isIntersecting
      syncPlayback()
    })
    observer.observe(videoEl.value)
  }
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  if (observer) observer.disconnect()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>
