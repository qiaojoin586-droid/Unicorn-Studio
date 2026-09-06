<template>
  <div class="min-h-screen relative bg-gradient-to-br from-gray-950 via-indigo-950 to-gray-950">
    <!-- 页面背景：来自后台「轮播图管理 → 页面背景」；未配置或加载失败时保持原有渐变 -->
    <div v-if="background" class="fixed inset-0 z-0 overflow-hidden">
      <BackgroundVideo
        v-if="background.mediaType === 'video'"
        :src="background.videoUrl"
        :poster="background.imageUrl"
      />
      <img
        v-else
        :src="background.imageUrl"
        alt=""
        aria-hidden="true"
        fetchpriority="high"
        decoding="async"
        class="absolute inset-0 h-full w-full object-cover"
      />
      <!-- 遮罩：保证正文可读性 -->
      <div class="absolute inset-0 bg-gray-950/60"></div>
    </div>

    <!-- Loading state -->
    <div v-if="loading" class="relative z-10 flex items-center justify-center min-h-screen -mt-20 pt-20 px-4">
      <div class="text-center">
        <div class="text-4xl mb-3 animate-bounce-subtle">🦄</div>
        <p class="text-gray-400">正在加载作品详情...</p>
      </div>
    </div>

    <!-- Error state -->
    <div v-else-if="error" class="relative z-10 flex items-center justify-center min-h-screen -mt-20 pt-20 px-4">
      <div class="bg-gray-900/90 border border-white/10 rounded-2xl p-8 max-w-sm w-full text-center backdrop-blur-sm shadow-2xl">
        <div class="text-4xl mb-3">⚠️</div>
        <h2 class="text-2xl font-bold text-white mb-2">加载失败</h2>
        <p class="text-gray-400 text-sm mb-4">{{ error }}</p>
        <div class="flex gap-3 justify-center">
          <button
            type="button"
            @click="loadWork"
            class="bg-indigo-600 hover:bg-indigo-500 text-white font-semibold px-6 py-2.5 rounded-lg transition"
          >
            重试
          </button>
          <router-link
            to="/works"
            class="bg-white/10 hover:bg-white/20 text-white font-semibold px-6 py-2.5 rounded-lg transition"
          >
            返回作品列表
          </router-link>
        </div>
      </div>
    </div>

    <!-- Content -->
    <template v-else>
      <!-- Hero -->
      <section class="relative z-10 -mt-20 pt-32 pb-12 overflow-hidden">
        <div class="absolute inset-0 pointer-events-none">
          <div class="absolute -top-1/3 -right-1/4 w-[500px] h-[500px] bg-indigo-600/15 rounded-full blur-3xl"></div>
          <div class="absolute -bottom-1/3 -left-1/4 w-[400px] h-[400px] bg-purple-600/15 rounded-full blur-3xl"></div>
        </div>
        <div class="relative max-w-4xl mx-auto px-4">
          <router-link
            to="/works"
            class="inline-flex items-center gap-1.5 text-indigo-300 hover:text-indigo-200 text-sm font-semibold transition mb-6"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
            </svg>
            返回作品列表
          </router-link>

          <div class="flex items-center gap-3 mb-4">
            <span
              class="text-xs font-semibold px-3 py-1 rounded-full"
              :class="work.tagType === 'paper'
                ? 'bg-emerald-500/20 text-emerald-300'
                : 'bg-amber-500/20 text-amber-300'"
            >
              {{ work.tagType === 'paper' ? 'SCI 论文' : '竞赛获奖' }}
            </span>
            <span v-if="work.category" class="text-xs font-semibold px-3 py-1 rounded-full bg-indigo-500/20 text-indigo-300">
              {{ work.category }}
            </span>
          </div>

          <h1 class="text-3xl sm:text-4xl font-extrabold text-white mb-6 leading-tight">{{ work.title }}</h1>

          <p class="text-gray-300 text-lg leading-relaxed mb-6">{{ work.summary }}</p>

          <div class="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm text-gray-400">
            <span v-if="work.authors" class="flex items-center gap-1.5">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
              {{ work.authors }}
            </span>
            <span v-if="work.dateLabel" class="flex items-center gap-1.5">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
              </svg>
              {{ work.dateLabel }}
            </span>
            <span class="flex items-center gap-1.5">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
              </svg>
              {{ work.viewCount || 0 }} 次浏览
            </span>
          </div>
        </div>
      </section>

      <!-- Cover + Content -->
      <section class="relative z-10 max-w-4xl mx-auto px-4 pb-20">
        <div class="bg-white/5 border border-white/10 rounded-2xl p-6 sm:p-10 backdrop-blur-sm">
          <!-- Cover -->
          <div class="relative h-56 sm:h-72 overflow-hidden rounded-xl mb-8" :class="work.coverClass || 'bg-gradient-to-br from-indigo-100 to-purple-100'">
            <img
              v-if="work.coverUrl"
              :src="work.coverUrl"
              :alt="work.title"
              class="w-full h-full object-cover"
            />
            <div v-else class="absolute inset-0 flex items-center justify-center text-7xl">
              {{ work.icon || '🏆' }}
            </div>
          </div>

          <!-- Content -->
          <div class="text-gray-300 leading-relaxed space-y-4 whitespace-pre-wrap">{{ work.content }}</div>

          <a
            v-if="work.externalLink"
            :href="work.externalLink"
            target="_blank"
            rel="noopener noreferrer"
            class="inline-flex items-center gap-2 mt-8 bg-indigo-600 hover:bg-indigo-500 text-white font-semibold px-6 py-3 rounded-xl transition"
          >
            查看原文
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
            </svg>
          </a>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'
import { usePageBackground } from '@/composables/usePageBackground'
import BackgroundVideo from '@/components/BackgroundVideo.vue'

const route = useRoute()
const { get } = useApi()

// 页面背景由后台「轮播图管理 → 页面背景」配置，未配置时保持原有渐变样式
const { background } = usePageBackground('work-detail')

const loading = ref(true)
const error = ref('')
const work = ref(null)

usePageMeta(() => ({
  title: work.value?.title || '作品详情',
  description: work.value?.summary || '',
}))

async function loadWork() {
  loading.value = true
  error.value = ''
  try {
    const result = await get(`/api/works/${route.params.slug}`)

    if (result.error) {
      throw new Error(result.status === 404 ? '作品不存在或已下线' : result.message)
    }

    work.value = result.data?.work || null
    if (!work.value) {
      throw new Error('作品不存在或已下线')
    }
  } catch (err) {
    error.value = err.message || '无法加载作品详情'
  } finally {
    loading.value = false
  }
}

// 同路由记录间参数变化（/works/a → /works/b）会复用组件实例，需 watch 参数重新加载
onMounted(loadWork)
watch(() => route.params.slug, loadWork)
</script>

<style scoped>
@keyframes bounce-subtle {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}
.animate-bounce-subtle {
  animation: bounce-subtle 1.2s ease-in-out infinite;
}
</style>
