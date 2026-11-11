<template>
  <SiteLink :to="item.link" class="recommendation">
    <span class="recommendation__label">本期推荐 · {{ item.label }}</span>
    <img v-if="image && !imageFailed" :src="image" alt="" width="320" height="200" decoding="async" @error="imageFailed = true" />
    <strong>{{ item.title }}</strong>
    <span v-if="item.description" class="recommendation__description">{{ item.description }}</span>
    <span class="recommendation__action">查看{{ item.label }} <span aria-hidden="true">↗</span></span>
  </SiteLink>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import SiteLink from './SiteLink.vue'
import { mediaUrl } from '@/utils/links'

const props = defineProps({ item: { type: Object, required: true } })
const image = computed(() => mediaUrl(props.item.coverUrl))
const imageFailed = ref(false)
watch(image, () => { imageFailed.value = false })
</script>

<style scoped>
.recommendation {
  position: absolute;
  right: 0;
  bottom: 4%;
  display: grid;
  gap: 10px;
  width: clamp(174px, 49%, 280px);
  padding: 14px;
  border: 1px solid #ffffff38;
  border-radius: 4px;
  background: #202125;
  box-shadow: 0 12px 35px #0005;
  color: #f4f2ec;
  text-decoration: none;
}
.recommendation:hover, .recommendation:focus-visible { border-color: #ff8c95; }
.recommendation img { width: 100%; height: auto; aspect-ratio: 16/10; object-fit: cover; }
.recommendation strong { font-size: 14px; line-height: 1.5; overflow-wrap: anywhere; }
.recommendation__label, .recommendation__action { font-size: 12px; color: #ffb1ba; }
.recommendation__action { display: flex; justify-content: space-between; }
.recommendation__description {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  color: #d2d1d7;
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
@media (max-width: 767px) { .recommendation { bottom: 0; gap: 7px; padding: 10px; } }
</style>
