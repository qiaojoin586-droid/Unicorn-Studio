<template>
  <div>
    <!-- Hero Section: full-screen, dark gradient -->
    <section class="relative -mt-20 min-h-screen flex items-center justify-center overflow-hidden bg-gradient-to-br from-gray-950 via-indigo-950 to-gray-950">
      <!-- Carousel background layer: 仅作为背景图层叠加（无数据/接口失败时不渲染，Hero 保持原样） -->
      <HeroCarousel v-if="carouselItems.length" :items="carouselItems" />

      <!-- Decorative glow blobs -->
      <div class="absolute inset-0 overflow-hidden pointer-events-none">
        <div class="absolute -top-1/4 -left-1/4 w-[600px] h-[600px] bg-indigo-600/20 rounded-full blur-3xl transform-gpu"></div>
        <div class="absolute -bottom-1/4 -right-1/4 w-[500px] h-[500px] bg-purple-600/20 rounded-full blur-3xl transform-gpu"></div>
        <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[400px] h-[400px] bg-indigo-500/10 rounded-full blur-3xl transform-gpu"></div>
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

    <!-- Shared video background wrapper for Capabilities + Services + Works -->
    <div id="bg-seam" class="relative overflow-hidden">
      <!-- Single video background spanning all three sections -->
      <BackgroundVideo :src="videoUrl" :poster="videoPosterUrl" />
      <!-- Single overlay spanning all three sections -->
      <div class="absolute inset-0 bg-black/30"></div>

      <!-- Capabilities Section -->
      <section id="capabilities" class="relative" :aria-busy="capabilitiesLoading">
        <div class="relative z-10 max-w-6xl mx-auto px-4 py-20">
          <h2 class="text-3xl font-bold text-center text-white mb-4">我们培养的能力</h2>
          <p class="text-gray-200 text-center mb-12 max-w-xl mx-auto">在真实项目中锤炼的核心能力，陪你走得更远</p>
          <div aria-live="polite" aria-atomic="true">
            <p v-if="capabilitiesLoading" class="text-gray-200 text-center mb-8">正在加载培养能力...</p>
            <p v-else-if="capabilitiesError" class="text-gray-200 text-center mb-4">
              {{ capabilitiesError }}
              <button
                type="button"
                @click="loadCapabilities"
                class="text-indigo-300 hover:text-indigo-200 font-semibold transition"
              >
                点击重试
              </button>
            </p>
            <p v-else-if="!capabilities.length" class="text-gray-300 text-center mb-8">
              能力培养体系整理中，敬请关注
            </p>
          </div>
          <div
            v-if="!capabilitiesLoading && !capabilitiesError && capabilities.length"
            class="grid grid-cols-1 md:grid-cols-3 gap-8"
          >
            <ServiceCard
              v-for="capability in capabilities"
              :key="capability.id"
              :title="capability.title"
              :description="capability.description"
              :icon="capability.icon || undefined"
            />
          </div>
        </div>
      </section>

      <!-- Recruitment Directions Section -->
      <section id="services" class="relative" :aria-busy="servicesLoading">
        <div class="relative z-10 max-w-6xl mx-auto px-4 py-20">
          <h2 class="text-3xl font-bold text-center text-white mb-4">主要招收方向</h2>
          <p class="text-gray-200 text-center mb-12 max-w-xl mx-auto">选择适合你的技术方向，与团队共同探索和成长</p>
          <div aria-live="polite" aria-atomic="true">
            <p v-if="servicesLoading" class="text-gray-200 text-center mb-8">正在加载招收方向...</p>
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
            <p v-else-if="!services.length" class="text-gray-300 text-center mb-8">
              招收方向筹备中，敬请关注
            </p>
          </div>
          <div
            v-if="!servicesLoading && !servicesError && services.length"
            class="grid grid-cols-1 md:grid-cols-3 gap-8"
          >
            <ServiceCard
              v-for="service in services"
              :key="service.id"
              :title="service.title"
              :description="service.description"
              :icon="service.icon || undefined"
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
              :cover-url="work.coverUrl"
              :icon="work.icon"
              :tag-type="work.tagType"
              :authors="work.authors"
              :date="work.dateLabel"
              :cover-class="work.coverClass"
            />
          </div>
          <p v-else class="text-gray-400 mb-8">作品整理中，敬请期待～</p>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import BackgroundVideo from '@/components/BackgroundVideo.vue'
import HeroCarousel from '@/components/HeroCarousel.vue'
import ServiceCard from '@/components/ServiceCard.vue'
import WorkCard from '@/components/WorkCard.vue'
import videoUrl from '@/image/index.mp4'
import videoPosterUrl from '@/image/index-poster.jpg'
import { useApi } from '@/composables/useApi'
import { usePageMeta, DEFAULT_DESCRIPTION } from '@/composables/usePageMeta'

usePageMeta(() => ({
  title: '用创意点亮未来',
  description: DEFAULT_DESCRIPTION,
}))

const { get } = useApi()

function scrollToNext() {
  const target = document.getElementById('bg-seam')
  if (target) {
    target.scrollIntoView({ behavior: 'smooth' })
  }
}

// 首页轮播：来自数据库 /api/carousel（仅启用项），仅作为 Hero 背景图层叠加；
// 无数据或接口失败时组件不渲染，Hero 区保持原有默认效果
const carouselItems = ref([])

async function loadCarousel() {
  try {
    const result = await get('/api/carousel')
    if (!result.error) {
      carouselItems.value = result.data?.list || []
    }
  } catch {
    // 静默保持默认 Hero
  }
}

// 主要招收方向：来自数据库 /api/services
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
    servicesError.value = err.message || '无法加载招收方向'
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

// 我们培养的能力：来自数据库 /api/capabilities
const capabilities = ref([])
const capabilitiesLoading = ref(true)
const capabilitiesError = ref('')

async function loadCapabilities() {
  capabilitiesLoading.value = true
  capabilitiesError.value = ''
  try {
    const result = await get('/api/capabilities')
    if (result.error) {
      throw new Error(result.message)
    }
    capabilities.value = result.data?.list || []
  } catch (err) {
    capabilitiesError.value = err.message || '无法加载培养能力'
  } finally {
    capabilitiesLoading.value = false
  }
}

onMounted(() => {
  loadCarousel()
  loadServices()
  loadFeaturedWorks()
  loadCapabilities()
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
