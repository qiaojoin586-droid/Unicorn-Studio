<template>
  <router-link
    :to="`/works/${slug}`"
    :aria-label="`查看作品详情：${title}`"
    class="block group bg-white rounded-2xl shadow-sm border border-gray-100 overflow-hidden hover:shadow-xl hover:-translate-y-1 transition-all duration-300"
  >
    <!-- Cover image area -->
    <div class="relative h-48 overflow-hidden" :class="coverClass">
      <div class="absolute inset-0 flex items-center justify-center text-6xl">
        {{ icon }}
      </div>
      <!-- Tag -->
      <span
        class="absolute top-4 left-4 text-xs font-semibold px-3 py-1 rounded-full"
        :class="tagType === 'paper'
          ? 'bg-emerald-100 text-emerald-700'
          : 'bg-amber-100 text-amber-700'"
      >
        {{ tagType === 'paper' ? 'SCI 论文' : '竞赛获奖' }}
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

      <!-- Meta -->
      <div class="flex items-center justify-between text-xs text-gray-400">
        <span class="flex items-center gap-1">
          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
          {{ authors }}
        </span>
        <span class="flex items-center gap-1">
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
const props = defineProps({
  slug: { type: String, required: true },
  title: { type: String, required: true },
  description: { type: String, required: true },
  icon: { type: String, default: '🏆' },
  tagType: { type: String, default: 'award' }, // 'award' | 'paper'
  authors: { type: String, default: '' },
  date: { type: String, default: '' },
  coverClass: { type: String, default: 'bg-gradient-to-br from-indigo-100 to-purple-100' },
})
</script>

<style scoped>
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.line-clamp-3 {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
