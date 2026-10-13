<template>
  <div class="absolute inset-0 overflow-hidden">
    <img
      :src="poster"
      alt=""
      aria-hidden="true"
      decoding="async"
      class="absolute inset-0 h-full w-full object-cover transform-gpu"
    />
    <video
      v-if="shouldLoad"
      ref="videoEl"
      class="absolute inset-0 h-full w-full object-cover transform-gpu"
      autoplay
      muted
      loop
      playsinline
      preload="metadata"
      :poster="poster"
      aria-hidden="true"
      @error="shouldLoad = false"
    >
      <source :src="src" type="video/mp4" />
    </video>
    <button v-if="shouldLoad" type="button" class="video-toggle" :aria-label="userPaused ? '播放背景动态' : '暂停背景动态'" :aria-pressed="userPaused" @click="togglePlayback"><span aria-hidden="true">{{ userPaused ? '▷' : 'Ⅱ' }}</span></button>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, nextTick } from 'vue'

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

const shouldLoad = ref(false)
const userPaused = ref(false)
const videoEl = ref(null)
let videoVisible = true
let pageHidden = false
let observer = null
let motionQuery = null
let mounted = true

// 播放同步（优化方案 F-08）：滚出视口/页面隐藏即暂停，回来自动恢复；
// play() 返回 Promise，可能因自动播放策略 reject，需吞掉
function syncPlayback() {
  const video = videoEl.value
  if (!video) return
  if (videoVisible && !pageHidden && !userPaused.value) {
    video.play().catch(() => {})
  } else if (!video.paused) {
    video.pause()
  }
}

function onVisibilityChange() {
  pageHidden = document.hidden
  syncPlayback()
}

function togglePlayback() { userPaused.value = !userPaused.value; syncPlayback() }

async function syncPreference() {
  observer?.disconnect()
  shouldLoad.value = !motionQuery.matches && navigator.connection?.saveData !== true
  if (!shouldLoad.value) { videoEl.value?.pause(); return }
  await nextTick()
  if (!mounted || !videoEl.value) return
  observer = new IntersectionObserver(([entry]) => { videoVisible = entry.isIntersecting; syncPlayback() })
  observer.observe(videoEl.value)
  syncPlayback()
}

onMounted(() => {
  pageHidden = document.hidden
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  motionQuery.addEventListener('change', syncPreference)
  syncPreference()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  mounted = false
  motionQuery?.removeEventListener('change', syncPreference)
  if (observer) observer.disconnect()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>
<style scoped>
.video-toggle { position: absolute; z-index: 2; right: 16px; bottom: 16px; display: grid; place-items: center; width: 44px; height: 44px; border: 1px solid #ffffff50; border-radius: 50%; background: #121316c9; color: white; font-size: 18px; }
</style>
