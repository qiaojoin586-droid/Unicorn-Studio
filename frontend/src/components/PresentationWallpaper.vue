<template>
  <BackgroundVideo
    :src="ready ? video : ''"
    :poster="poster"
    :fallback-poster="defaultPoster"
    desktop-only
    sticky
  />
</template>

<script setup>
import { computed } from 'vue'
import BackgroundVideo from './BackgroundVideo.vue'
import { mediaUrl } from '@/utils/links'

const props = defineProps({
  config: { type: Object, required: true },
  defaultVideo: { type: String, required: true },
  defaultPoster: { type: String, required: true },
  ready: Boolean,
})

const customImage = computed(() => mediaUrl(props.config.backgroundImageUrl))
const customVideo = computed(() => mediaUrl(props.config.backgroundVideoUrl))
const mode = computed(() => {
  if (!customImage.value) return ''
  if (props.config.backgroundMode === 'image') return 'image'
  return props.config.backgroundMode === 'video' && customVideo.value ? 'video' : ''
})
const poster = computed(() => mode.value ? customImage.value : props.defaultPoster)
const video = computed(() => {
  if (mode.value === 'image') return ''
  return mode.value === 'video' ? customVideo.value : props.defaultVideo
})
</script>
