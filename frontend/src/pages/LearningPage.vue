<template>
  <div class="min-h-screen relative">
    <!-- Fixed full-page video background -->
    <div class="fixed inset-0 z-0 overflow-hidden">
      <BackgroundVideo :src="homeVideoUrl" :poster="homeVideoPosterUrl" />
      <div class="absolute inset-0 bg-gray-950/50"></div>
    </div>

    <!-- Loading state -->
    <div
      v-if="loading"
      class="fixed inset-0 z-50 flex items-center justify-center px-4"
    >
      <div class="absolute inset-0 bg-black/70 backdrop-blur-sm"></div>
      <div class="relative bg-gray-900/90 border border-white/10 rounded-2xl p-8 max-w-sm w-full text-center backdrop-blur-sm shadow-2xl">
        <div class="text-white font-semibold mb-2">正在加载学习资料</div>
        <p class="text-gray-400 text-sm">请稍候...</p>
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

      <!-- Learning cards -->
      <section class="relative z-10 max-w-4xl mx-auto px-4 pb-20">
        <div class="space-y-6">
          <router-link
            v-for="item in resources"
            :key="item.id"
            :to="`/learning/${item.id}`"
            class="block bg-white/5 border border-white/10 rounded-xl p-6 backdrop-blur-sm hover:bg-white/10 hover:border-indigo-500/30 transition-all duration-300 hover:-translate-y-1"
          >
            <h3 class="text-xl font-bold text-white mb-2">{{ item.title }}</h3>
            <p class="text-gray-400 mb-4 leading-relaxed">{{ item.description }}</p>
            <span
              class="inline-flex items-center gap-1.5 text-indigo-400 hover:text-indigo-300 font-semibold text-sm transition"
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
import homeVideoUrl from '@/image/home.mp4'
import homeVideoPosterUrl from '@/image/home-poster.jpg'

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
