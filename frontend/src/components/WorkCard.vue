<template>
  <router-link
    :to="`/works/${slug}`"
    :aria-label="`查看作品详情：${title}`"
    class="block group bg-white rounded-2xl shadow-sm border border-gray-100 overflow-hidden hover:shadow-xl hover:-translate-y-1 transition-all duration-300"
  >
    <!-- Cover image area -->
    <div class="relative h-48 overflow-hidden" :class="coverClass">
      <!-- 有封面图且未加载失败：渲染图片 -->
      <img
        v-if="coverUrl && !imgFailed"
        :src="coverUrl"
        :alt="title"
        class="w-full h-full object-cover"
        loading="lazy"
        @error="imgFailed = true"
      />
      <!-- 无封面图或加载失败：渐变 + emoji 图标兜底 -->
      <div v-else class="absolute inset-0 flex items-center justify-center text-6xl">
        {{ icon }}
      </div>
      <!-- Tag：文案与配色来自 workTags 字典；tagType 为空表示后台「分类」未设置，此时不渲染，
           不再像改造前那样一律退化成「竞赛获奖」 -->
      <span
        v-if="tag"
        class="absolute top-4 left-4 text-xs font-semibold px-3 py-1 rounded-full"
        :class="tag.className"
      >
        {{ tag.label }}
      </span>
    </div>

    <!-- Content -->
    <div class="p-6">
      <h3 class="text-lg font-bold text-gray-900 mb-2 group-hover:text-indigo-600 transition-colors line-clamp-2">
        {{ title }}
      </h3>
      <p class="text-sm text-gray-500 leading-relaxed mb-4 line-clamp-3">
        {{ description }}
      </p>

      <!-- Meta：作者与日期都可能为空（后台未填），两者皆空时整行不渲染，
           避免出现「一个图标 + 一片空白」的空行 -->
      <div v-if="authors || date" class="flex items-center justify-between text-xs text-gray-500">
        <span v-if="authors" class="flex items-center gap-1">
          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
          {{ authors }}
        </span>
        <span v-if="date" class="flex items-center gap-1">
          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
          </svg>
          {{ date }}
        </span>
      </div>
    </div>
  </router-link>
</template>

<script setup>
import { computed, ref } from 'vue'
import { resolveWorkTag } from '@/constants/workTags'

const props = defineProps({
  slug: { type: String, required: true },
  title: { type: String, required: true },
  description: { type: String, required: true },
  coverUrl: { type: String, default: '' },
  icon: { type: String, default: '🏆' },
  tagType: { type: String, default: '' }, // 'award' | 'paper' | 'project' | 'other'，见 workTags.js
  authors: { type: String, default: '' },
  date: { type: String, default: '' },
  coverClass: { type: String, default: 'bg-gradient-to-br from-indigo-100 to-purple-100' },
})

// 封面图加载失败（404/网络错误）时回退到渐变 + emoji 图标兜底
const imgFailed = ref(false)

/** 徽章配置；未设置分类时为 null ⇒ 模板不渲染徽章 */
const tag = computed(() => resolveWorkTag(props.tagType))
</script>

<!-- line-clamp 使用 Tailwind 3.4 内置工具类，无需手写（优化方案 F-21） -->
