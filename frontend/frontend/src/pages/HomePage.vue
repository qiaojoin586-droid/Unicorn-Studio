<template>
  <div>
    <!-- Hero Section: full-screen, dark gradient -->
    <section class="relative -mt-20 min-h-screen flex items-center justify-center overflow-hidden bg-gradient-to-br from-gray-950 via-indigo-950 to-gray-950">
      <!-- Decorative glow blobs -->
      <div class="absolute inset-0 overflow-hidden pointer-events-none">
        <div class="absolute -top-1/4 -left-1/4 w-[600px] h-[600px] bg-indigo-600/20 rounded-full blur-3xl"></div>
        <div class="absolute -bottom-1/4 -right-1/4 w-[500px] h-[500px] bg-purple-600/20 rounded-full blur-3xl"></div>
        <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[400px] h-[400px] bg-indigo-500/10 rounded-full blur-3xl"></div>
      </div>

      <!-- Subtle grid pattern overlay -->
      <div
        class="absolute inset-0 opacity-[0.03]"
        style="background-image: linear-gradient(rgba(255,255,255,.1) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px); background-size: 60px 60px;"
      ></div>

      <!-- Content -->
      <div class="relative z-10 text-center px-6 max-w-4xl mx-auto">
        <p class="text-indigo-400 text-sm font-semibold tracking-widest uppercase mb-6 animate-fade-in-up">
          Unicorn Studio
        </p>
        <h1 class="text-5xl sm:text-6xl md:text-7xl lg:text-8xl font-extrabold leading-tight mb-8 animate-fade-in-up animation-delay-100">
          <span class="gradient-flow">独角兽工作室</span>
        </h1>
        <p class="text-lg sm:text-xl text-gray-400 max-w-2xl mx-auto mb-12 leading-relaxed animate-fade-in-up animation-delay-200">
          以技术为笔，以创意为墨，<br class="hidden sm:block" />
          在数字世界中勾勒非凡体验。
        </p>
        <div class="animate-fade-in-up animation-delay-300">
          <button
            @click="scrollToNext"
            class="group inline-flex items-center gap-2 bg-indigo-600 hover:bg-indigo-500 text-white font-semibold px-8 py-3.5 rounded-full transition-all hover:shadow-lg hover:shadow-indigo-500/25"
          >
            了解更多
            <svg class="w-4 h-4 transition-transform group-hover:translate-y-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
            </svg>
          </button>
        </div>
      </div>

      <!-- Scroll indicator at bottom -->
      <div class="absolute bottom-8 left-1/2 -translate-x-1/2 animate-bounce">
        <div class="w-6 h-10 rounded-full border-2 border-white/20 flex items-start justify-center p-1.5">
          <div class="w-1 h-2 bg-white/40 rounded-full animate-scroll-dot"></div>
        </div>
      </div>
    </section>

    <!-- Shared video background wrapper for Services + Works -->
    <div class="relative overflow-hidden">
      <!-- Single video background spanning both sections -->
      <BackgroundVideo :src="videoUrl" :poster="videoPosterUrl" />
      <!-- Single overlay spanning both sections -->
      <div class="absolute inset-0 bg-black/30"></div>

      <!-- Services Section -->
      <section id="services" class="relative">
        <div class="relative z-10 max-w-6xl mx-auto px-4 py-20">
          <h2 class="text-3xl font-bold text-center text-white mb-4">我们培养的能力</h2>
          <p class="text-gray-200 text-center mb-12 max-w-xl mx-auto">全方位数字产品技能，助力业务腾飞</p>
          <p v-if="servicesLoading" class="text-gray-200 text-center mb-8">正在加载能力介绍...</p>
          <p v-else-if="servicesError" class="text-gray-200 text-center mb-4">
            {{ servicesError }}
            <button
              type="button"
              @click="loadServices"
              class="text-indigo-300 hover:text-indigo-200 font-semibold transition"
            >
              点击重试
            </button>
          </p>
          <div class="grid grid-cols-1 md:grid-cols-3 gap-8">
            <ServiceCard
              v-for="service in services"
              :key="service.id"
              :title="service.title"
              :description="service.description"
              :icon="service.icon"
            />
          </div>
        </div>
      </section>

      <!-- Works Section -->
      <section class="relative">
        <div class="relative z-10 max-w-6xl mx-auto px-4 py-20">
          <div class="flex items-center justify-between mb-4">
            <h2 class="text-3xl font-bold text-white">我们的作品</h2>
            <router-link
              to="/works"
              class="text-indigo-300 hover:text-indigo-200 font-semibold text-sm flex items-center gap-1 transition"
            >
              更多
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
              </svg>
            </router-link>
          </div>
          <p class="text-gray-200 mb-12 max-w-xl">竞赛获奖与学术论文，记录每一次突破与成长</p>
          <p v-if="worksLoading" class="text-gray-200 mb-8">正在加载作品...</p>
          <p v-else-if="worksError" class="text-gray-200 mb-4">
            {{ worksError }}
            <button
              type="button"
              @click="loadFeaturedWorks"
              class="text-indigo-300 hover:text-indigo-200 font-semibold transition"
            >
              点击重试
            </button>
          </p>
          <div v-if="works.length" class="grid grid-cols-1 md:grid-cols-3 gap-8">
            <WorkCard
              v-for="work in works"
              :key="work.slug"
              :slug="work.slug"
              :title="work.title"
              :description="work.summary"
              :icon="work.icon"
              :tag-type="work.tagType"
              :authors="work.authors"
              :date="work.dateLabel"
              :cover-class="work.coverClass"
            />
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import BackgroundVideo from '@/components/BackgroundVideo.vue'
import ServiceCard from '@/components/ServiceCard.vue'
import WorkCard from '@/components/WorkCard.vue'
import videoUrl from '@/image/index.mp4'
import videoPosterUrl from '@/image/index-poster.jpg'
import { useApi } from '@/composables/useApi'

const { get } = useApi()

function scrollToNext() {
  const target = document.getElementById('services')
  if (target) {
    target.scrollIntoView({ behavior: 'smooth' })
  }
}

// 能力培养：来自数据库 /api/services
const services = ref([])
const servicesLoading = ref(true)
const servicesError = ref('')

async function loadServices() {
  servicesLoading.value = true
  servicesError.value = ''
  try {
    const result = await get('/api/services')
    if (result.error) {
      throw new Error(result.message)
    }
    services.value = result.data?.list || []
  } catch (err) {
    servicesError.value = err.message || '无法加载能力介绍'
  } finally {
    servicesLoading.value = false
  }
}

// 推荐作品：来自数据库 /api/works?featured=true
const works = ref([])
const worksLoading = ref(true)
const worksError = ref('')

async function loadFeaturedWorks() {
  worksLoading.value = true
  worksError.value = ''
  try {
    const result = await get('/api/works?featured=true')
    if (result.error) {
      throw new Error(result.message)
    }
    works.value = result.data?.list || []
  } catch (err) {
    worksError.value = err.message || '无法加载作品'
  } finally {
    worksLoading.value = false
  }
}

onMounted(() => {
  loadServices()
  loadFeaturedWorks()
})
</script>

<style scoped>
/* Fade-in-up animation */
@keyframes fade-in-up {
  from {
    opacity: 0;
    transform: translateY(24px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.animate-fade-in-up {
  animation: fade-in-up 0.8s ease-out both;
}

.animation-delay-100 {
  animation-delay: 0.1s;
}

.animation-delay-200 {
  animation-delay: 0.2s;
}

.animation-delay-300 {
  animation-delay: 0.3s;
}

/* Scroll indicator dot */
@keyframes scroll-dot {
  0%, 100% {
    opacity: 0;
    transform: translateY(0);
  }
  50% {
    opacity: 1;
    transform: translateY(6px);
  }
}

.animate-scroll-dot {
  animation: scroll-dot 2s ease-in-out infinite;
}

/* Gradient flow animation */
.gradient-flow {
  background: linear-gradient(90deg, #818cf8, #a78bfa, #c084fc, #818cf8);
  background-size: 200% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: gradient-flow 3s linear infinite;
}

@keyframes gradient-flow {
  0%   { background-position: 0% 50%; }
  100% { background-position: 200% 50%; }
}
</style>
