<template>
  <div class="absolute inset-0 overflow-hidden">
    <img
      :src="poster"
      alt=""
      aria-hidden="true"
      class="absolute inset-0 h-full w-full object-cover"
    />
    <video
      v-if="shouldPlay"
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
import { onMounted, ref } from 'vue'

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

onMounted(() => {
  const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const prefersReducedData = navigator.connection?.saveData === true
  shouldPlay.value = !prefersReducedMotion && !prefersReducedData
})
</script>