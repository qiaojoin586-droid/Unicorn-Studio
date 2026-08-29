<template>
  <!-- 纯背景图层：只负责背景图交叉淡入 + 暗色遮罩（保证上层原有文字可读）+ 指示器。
       不渲染任何文案内容，Hero 区原有文字/按钮/特效由 HomePage 自身负责，互不干扰。 -->
  <div class="absolute inset-0">
    <!-- 背景图层叠（全部渲染，交叉淡入淡出，切换无闪烁） -->
    <div
      v-for="(item, i) in items"
      :key="item.id"
      class="absolute inset-0 bg-cover bg-center transition-opacity duration-1000"
      :class="i === current ? 'opacity-100' : 'opacity-0'"
      :style="{ backgroundImage: `url(${resolveUrl(item.imageUrl)})` }"
      role="img"
      :aria-label="item.title || ''"
    ></div>

    <!-- 暗色遮罩：延续 Hero 原有深色渐变氛围，保证原有文字可读 -->
    <div class="absolute inset-0 bg-gradient-to-br from-gray-950/80 via-indigo-950/60 to-gray-950/80"></div>

    <!-- 指示器（单张不显示；避开底部的滚动指示器） -->
    <div v-if="items.length > 1" class="absolute bottom-20 left-1/2 -translate-x-1/2 z-10 flex items-center gap-2">
      <button
        v-for="(item, i) in items"
        :key="item.id"
        type="button"
        :aria-label="`切换到第 ${i + 1} 张背景`"
        class="h-2.5 rounded-full transition-all"
        :class="i === current ? 'bg-white w-6' : 'bg-white/40 w-2.5 hover:bg-white/70'"
        @click="go(i)"
      ></button>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  /** 轮播项：[{ id, imageUrl, title, subtitle, linkUrl }]，本组件仅使用 id/imageUrl */
  items: {
    type: Array,
    required: true,
  },
})

const BASE_URL = import.meta.env.VITE_API_BASE || ''

const current = ref(0)
let timer = null

function next() {
  current.value = (current.value + 1) % props.items.length
}

function go(i) {
  current.value = i
  restart()
}

function restart() {
  clearInterval(timer)
  if (autoplayEnabled()) {
    timer = setInterval(next, 5000)
  }
}

function autoplayEnabled() {
  return (
    props.items.length > 1 &&
    !window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

/** /uploads/... 相对路径补 BASE_URL；完整 URL 原样返回 */
function resolveUrl(url) {
  if (!url) return ''
  return /^https?:\/\//.test(url) ? url : BASE_URL + url
}

onMounted(() => {
  if (autoplayEnabled()) {
    timer = setInterval(next, 5000)
  }
})

onBeforeUnmount(() => {
  clearInterval(timer)
})
</script>
