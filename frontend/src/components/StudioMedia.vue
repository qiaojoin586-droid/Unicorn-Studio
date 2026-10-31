<template>
  <div v-if="items.length" class="studio-media" aria-roledescription="轮播图" aria-label="首页精选内容">
    <template v-if="!imageFailed"><SiteLink v-if="href" :to="href" class="media-art" :aria-label="item.title || '查看精选内容'"><img :src="imageUrl" :alt="item.title || ''" width="320" height="200" decoding="async" @error="imageFailed = true" /></SiteLink><img v-else class="media-art" :src="imageUrl" :alt="item.title || ''" width="320" height="200" decoding="async" @error="imageFailed = true" /></template>
    <div v-else class="media-fallback">图片暂不可用</div>
    <div class="media-caption" aria-live="polite"><p v-if="item.title">{{ item.title }}</p><span v-if="item.subtitle">{{ item.subtitle }}</span><SiteLink v-if="href" :to="href" class="media-link">查看内容 <span aria-hidden="true">↗</span></SiteLink></div>
    <div v-if="mode === 'carousel' && items.length > 1" class="media-controls"><button type="button" aria-label="上一张精选内容" @click="move(-1)">←</button><span>{{ index + 1 }} / {{ items.length }}</span><button type="button" aria-label="下一张精选内容" @click="move(1)">→</button></div>
  </div>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import SiteLink from './SiteLink.vue'
import { safeLink } from '@/utils/links'
const props = defineProps({ items: { type: Array, default: () => [] }, mode: { type: String, default: 'carousel' } })
const index = ref(0)
const imageFailed = ref(false)
const item = computed(() => props.items[index.value] || props.items[0] || {})
const imageUrl = computed(() => /^(https?:|data:image\/)/i.test(item.value.imageUrl || '') ? item.value.imageUrl : (import.meta.env.VITE_API_BASE || '') + (item.value.imageUrl || ''))
const href = computed(() => safeLink(item.value.linkUrl))
function move(direction) { index.value = (index.value + direction + props.items.length) % props.items.length }
watch(() => [props.mode, props.items], () => { index.value = 0; imageFailed.value = false })
watch(index, () => { imageFailed.value = false })
</script>
<style scoped>
.studio-media { position: absolute; right: 0; bottom: 4%; width: 40%; max-width: 250px; min-width: 160px; padding: 12px; background: #202125; border: 1px solid #ffffff38; color: #f4f2ec; border-radius: 4px; box-shadow: 0 12px 35px #0003; }
.media-art { display: block; width: 100%; aspect-ratio: 16/10; object-fit: cover; }
.media-art img { width: 100%; height: 100%; object-fit: cover; }
.media-caption { padding-top: 10px; }
.media-caption p { font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.media-caption > span { display: block; font-size: 11px; color: #bbbcc2; line-height: 1.65; margin-top: 5px; }
.media-link { display: inline-flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 44px; color: var(--color-accent-on-dark); font-size: 11px; }
.media-controls { display: flex; align-items: center; justify-content: space-between; gap: 5px; margin-top: 5px; border-top: 1px solid #ffffff20; }
.media-controls button { min-width: 44px; min-height: 44px; font-size: 18px; }
.media-controls button:hover { color: var(--color-accent-on-dark); }
.media-controls > span { font: 10px var(--font-mono); color: #bbbcc2; }
.media-fallback { aspect-ratio: 16/10; display: grid; place-items: center; font-size: 12px; color: #bbbcc2; background: #303137; }
@media(max-width:767px) { .studio-media { right: 0; bottom: -12px; width: 44%; min-width: 155px; padding: 9px; } .media-caption p { font-size: 11px; } }
</style>
