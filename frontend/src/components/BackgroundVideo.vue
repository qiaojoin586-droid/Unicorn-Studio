<template>
  <div class="background-video" :class="{ 'background-video--sticky': sticky }">
    <div ref="frame" class="background-video__media" :class="{ 'has-played': shouldLoad && hasPlayed }" aria-hidden="true">
      <img v-if="displayPoster" :src="displayPoster" alt="" decoding="async" @error="posterFailed = true" />
      <video
        v-if="shouldLoad"
        ref="videoEl"
        :src="src"
        muted
        loop
        playsinline
        preload="none"
        @playing="hasPlayed = true"
        @error="failed = true"
      />
    </div>
    <button
      v-if="shouldLoad && hasPlayed"
      type="button"
      class="video-toggle"
      :aria-label="userPaused ? '播放背景动态' : '暂停背景动态'"
      @click="userPaused = !userPaused"
    >
      <span aria-hidden="true">{{ userPaused ? '▷' : 'Ⅱ' }}</span>
    </button>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  src: { type: String, default: '' },
  poster: { type: String, required: true },
  fallbackPoster: { type: String, default: '' },
  desktopOnly: Boolean,
  sticky: Boolean,
})

const frame = ref(null)
const videoEl = ref(null)
const permitted = ref(false)
const visible = ref(false)
const hasEntered = ref(false)
const hasPlayed = ref(false)
const pageHidden = ref(false)
const userPaused = ref(false)
const failed = ref(false)
const posterFailed = ref(false)
const displayPoster = computed(() => posterFailed.value ? props.fallbackPoster : props.poster)
const shouldLoad = computed(() => Boolean(props.src) && permitted.value && hasEntered.value && !failed.value)
let observer
let motionQuery
let mobileQuery
let connection

function syncPreference() {
  permitted.value = !motionQuery.matches && !(props.desktopOnly && mobileQuery.matches) && !connection?.saveData
  if (!permitted.value) hasPlayed.value = false
}

function syncPageVisibility() {
  pageHidden.value = document.hidden
}

function syncPlayback() {
  const video = videoEl.value
  if (!video) return
  if (!visible.value || pageHidden.value || userPaused.value) {
    video.pause()
    return
  }

  video.muted = true
  video.play().catch((error) => {
    if (error.name !== 'AbortError' && video === videoEl.value) failed.value = true
  })
}

watch([videoEl, visible, pageHidden, userPaused], syncPlayback, { flush: 'post' })
watch(() => props.poster, () => { posterFailed.value = false })
watch(() => props.src, () => {
  failed.value = false
  hasPlayed.value = false
  userPaused.value = false
  syncPlayback()
}, { flush: 'post' })

onMounted(() => {
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  mobileQuery = window.matchMedia('(max-width: 767px), (pointer: coarse)')
  connection = navigator.connection
  motionQuery.addEventListener('change', syncPreference)
  mobileQuery.addEventListener('change', syncPreference)
  connection?.addEventListener('change', syncPreference)
  document.addEventListener('visibilitychange', syncPageVisibility)
  syncPreference()
  syncPageVisibility()
  if ('IntersectionObserver' in window) {
    observer = new IntersectionObserver(([entry]) => {
      visible.value = entry.isIntersecting
      if (entry.isIntersecting) hasEntered.value = true
    })
    observer.observe(frame.value)
  }
})

onBeforeUnmount(() => {
  observer?.disconnect()
  motionQuery?.removeEventListener('change', syncPreference)
  mobileQuery?.removeEventListener('change', syncPreference)
  connection?.removeEventListener('change', syncPreference)
  document.removeEventListener('visibilitychange', syncPageVisibility)
})
</script>

<style scoped>
.background-video, .background-video__media { position: absolute; inset: 0; pointer-events: none; }
.background-video__media { overflow: hidden; }
.background-video__media img, .background-video__media video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: var(--video-position, center);
}
.background-video__media video { opacity: 0; }
.background-video__media.has-played video { opacity: 1; }
.background-video__media.has-played img { visibility: hidden; }
.background-video__media::after {
  position: absolute;
  inset: 0;
  content: '';
  background: var(--video-shade, transparent);
}
.background-video--sticky { display: grid; grid-template-areas: "wallpaper"; }
.background-video--sticky .background-video__media {
  grid-area: wallpaper;
  position: sticky;
  top: 0;
  height: 100vh;
  height: 100svh;
  align-self: start;
}
.video-toggle {
  position: absolute;
  z-index: 2;
  right: 16px;
  bottom: 16px;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border: 1px solid #ffffff50;
  border-radius: 50%;
  background: #121316e6;
  color: white;
  font-size: 18px;
  pointer-events: auto;
}
.background-video--sticky .video-toggle {
  grid-area: wallpaper;
  position: sticky;
  right: auto;
  top: calc(100vh - 76px);
  top: calc(100svh - 76px);
  bottom: auto;
  margin: 16px;
  align-self: start;
  justify-self: end;
}
</style>
