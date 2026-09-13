<template>
  <div class="min-h-screen relative">
    <!-- Fixed full-page video background -->
    <div class="fixed inset-0 z-0 overflow-hidden">
      <BackgroundVideo :src="homeVideoUrl" :poster="homeVideoPosterUrl" />
      <div class="absolute inset-0 bg-gray-950/50"></div>
    </div>

    <!-- Loading state：内容区骨架屏（优化方案 F-13），不再全屏遮罩；与新网格布局对齐 -->
    <div
      v-if="loading"
      class="relative z-10 max-w-6xl mx-auto px-4 pt-40 pb-20"
      aria-busy="true"
      aria-label="正在加载学习资料"
    >
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
        <div v-for="i in 4" :key="i" class="aspect-square rounded-2xl border border-white/10 bg-white/5 p-6 animate-pulse">
          <div class="h-5 w-20 bg-white/10 rounded-full mb-5"></div>
          <div class="h-6 w-3/4 bg-white/10 rounded mb-3"></div>
          <div class="h-4 w-full bg-white/10 rounded mb-2"></div>
          <div class="h-4 w-2/3 bg-white/10 rounded"></div>
        </div>
      </div>
    </div>

    <!-- Error state -->
    <div
      v-else-if="error"
      class="fixed inset-0 z-50 flex items-center justify-center px-4"
    >
      <div class="absolute inset-0 bg-black/70 backdrop-blur-sm"></div>
      <div class="relative bg-gray-900/90 border border-white/10 rounded-2xl p-8 max-w-sm w-full text-center backdrop-blur-sm shadow-2xl">
        <div class="text-4xl mb-3">⚠️</div>
        <h2 class="text-2xl font-bold text-white mb-2">加载失败</h2>
        <p class="text-gray-400 text-sm mb-4">{{ error }}</p>
        <button
          type="button"
          @click="loadResources"
          class="w-full bg-indigo-600 hover:bg-indigo-500 text-white font-semibold py-3 rounded-lg transition"
        >
          重试
        </button>
      </div>
    </div>

    <!-- Page content -->
    <template v-else>
      <!-- Hero banner -->
      <section class="relative z-10 -mt-20 pt-32 pb-16 overflow-hidden">
        <div class="absolute inset-0 pointer-events-none">
          <div class="absolute -top-1/3 -right-1/4 w-[500px] h-[500px] bg-indigo-600/15 rounded-full blur-3xl"></div>
          <div class="absolute -bottom-1/3 -left-1/4 w-[400px] h-[400px] bg-purple-600/15 rounded-full blur-3xl"></div>
        </div>
        <div class="relative max-w-6xl mx-auto px-4 text-center">
          <p class="text-indigo-400 text-sm font-semibold tracking-widest uppercase mb-4">Learning Resources</p>
          <h1 class="text-4xl sm:text-5xl font-extrabold text-white mb-4">学习资料</h1>
          <p class="text-gray-400 max-w-xl mx-auto">精选教程与手册，助力快速成长</p>
        </div>
      </section>

      <!-- Learning cards：透明正方形卡片网格（每行最多 4 个，居中响应式） -->
      <section class="relative z-10 max-w-6xl mx-auto px-4 pb-20">
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
          <router-link
            v-for="item in resources"
            :key="item.id"
            :to="`/learning/${item.id}`"
            class="group relative flex flex-col aspect-square rounded-2xl border border-white/10 bg-white/10 backdrop-blur-md p-6 shadow-lg shadow-black/20 transition-all duration-300 hover:-translate-y-2 hover:bg-white/15 hover:border-indigo-400/40 hover:shadow-xl hover:shadow-indigo-500/10"
          >
            <!-- 顶部：分类标签 + 外链图标 -->
            <div class="flex items-start justify-between gap-3">
              <span
                v-if="item.category"
                class="inline-flex items-center rounded-full border border-indigo-400/20 bg-indigo-500/10 px-3 py-1 text-xs font-semibold text-indigo-300"
              >
                {{ item.category }}
              </span>
              <svg
                class="w-5 h-5 shrink-0 text-gray-400 group-hover:text-indigo-400 transition-colors"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 17L17 7M7 7h10v10" />
              </svg>
            </div>

            <!-- 标题 -->
            <h3 class="mt-5 text-xl font-bold text-white line-clamp-2 group-hover:text-indigo-300 transition-colors">
              {{ item.title }}
            </h3>

            <!-- 描述 -->
            <p class="mt-3 text-sm text-gray-300 leading-relaxed line-clamp-3">
              {{ item.description }}
            </p>

            <!-- 底部：查看详情 -->
            <span
              class="mt-auto pt-4 inline-flex items-center gap-1.5 text-indigo-400 font-semibold text-sm transition-all group-hover:gap-2.5 group-hover:text-indigo-300"
            >
              查看详情
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
              </svg>
            </span>
          </router-link>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import BackgroundVideo from '@/components/BackgroundVideo.vue'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'
import homeVideoUrl from '@/image/home.mp4'
import homeVideoPosterUrl from '@/image/home-poster.jpg'

usePageMeta(() => ({
  title: '学习资料',
  description: '精选教程与手册，助力快速成长。',
}))

const { get } = useApi()

const loading = ref(true)
const error = ref('')
const resources = ref([])

async function loadResources() {
  loading.value = true
  error.value = ''
  try {
    const result = await get('/api/learning/resources')

    if (result.error) {
      throw new Error(result.message)
    }

    // Support both wrapped ({ code, data: { resources } }) and flat ({ resources }) formats
    const payload = result.data || result
    resources.value = payload.resources || []
  } catch (err) {
    error.value = err.message || '无法连接学习资料服务'
  } finally {
    loading.value = false
  }
}

onMounted(loadResources)
</script>
