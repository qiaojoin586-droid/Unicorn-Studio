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

    <!-- Hero banner -->
    <section class="relative z-10 -mt-20 pt-32 pb-16 overflow-hidden">
      <div class="absolute inset-0 pointer-events-none">
        <div class="absolute -top-1/3 -right-1/4 w-[500px] h-[500px] bg-indigo-600/15 rounded-full blur-3xl"></div>
        <div class="absolute -bottom-1/3 -left-1/4 w-[400px] h-[400px] bg-purple-600/15 rounded-full blur-3xl"></div>
      </div>
      <div class="relative max-w-6xl mx-auto px-4 text-center">
        <p class="text-indigo-400 text-sm font-semibold tracking-widest uppercase mb-4">{{ page.eyebrow }}</p>
        <h1 class="text-4xl sm:text-5xl font-extrabold text-white mb-4">{{ page.title }}</h1>
        <p class="text-gray-400 max-w-xl mx-auto">{{ page.subtitle }}</p>
      </div>
    </section>

    <!-- Main content -->
    <section class="relative z-10 max-w-6xl mx-auto px-4 pb-20">
      <div class="grid grid-cols-1 md:grid-cols-2 gap-12 items-stretch">
        <!-- Text -->
        <div>
          <p
            v-for="(paragraph, idx) in paragraphs"
            :key="idx"
            class="text-lg leading-relaxed"
            :class="idx === 0 ? 'mb-6 text-gray-300' : 'mb-8 text-gray-400'"
          >
            {{ paragraph }}
          </p>

          <!-- Stats：后台可增删，为空时不渲染 -->
          <div v-if="page.stats.length" class="flex flex-wrap gap-x-6 gap-y-4">
            <div
              v-for="(stat, idx) in page.stats"
              :key="idx"
              class="text-center bg-white/5 rounded-xl px-6 py-4 border border-white/10"
            >
              <div class="text-3xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-indigo-400 to-purple-400">{{ stat.value }}</div>
              <div class="text-sm text-gray-500 mt-1">{{ stat.label }}</div>
            </div>
          </div>
        </div>

        <!-- Visual card -->
        <div class="bg-gradient-to-br from-indigo-900/50 to-purple-900/50 rounded-2xl border border-white/10 backdrop-blur-sm overflow-hidden">
          <img :src="logoSrc" alt="社团LOGO" width="1139" height="1164" class="w-full h-full object-contain rounded-lg" />
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive } from 'vue'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'
import { usePageBackground } from '@/composables/usePageBackground'
import BackgroundVideo from '@/components/BackgroundVideo.vue'
import logoUrl from '@/image/LOGO.jpg'

// 页面背景由后台「轮播图管理 → 页面背景」配置，未配置时保持原有渐变样式
const { background } = usePageBackground('about')

const { get } = useApi()

// 默认文案 = 改造前的原始硬编码内容；接口失败或字段缺失时回落，保证页面永不空白
const DEFAULTS = {
  eyebrow: 'About Us',
  title: '关于独角兽工作室',
  subtitle: '一群热爱技术与创造力的伙伴，致力于在数字世界中创造价值',
  content: '独角兽工作室是一支充满激情的技术团队，专注于为客户提供高质量的数字产品与服务。我们相信，优秀的技术结合出色的设计，能够为用户带来非凡的体验。\n\n自创立以来，我们已为众多企业提供了 Web 开发、移动应用、大模型学术研究等方面的专业服务，赢得了客户的广泛认可与好评。',
  imageUrl: '',
  stats: [
    { value: '50+', label: '完成项目' },
    { value: '30+', label: '合作客户' },
    { value: '5+', label: '年行业经验' },
  ],
}

const page = reactive({
  eyebrow: DEFAULTS.eyebrow,
  title: DEFAULTS.title,
  subtitle: DEFAULTS.subtitle,
  content: DEFAULTS.content,
  imageUrl: DEFAULTS.imageUrl,
  stats: DEFAULTS.stats.map((s) => ({ ...s })),
})

// 正文按空行分段（与后台表单的填写约定一致）
const paragraphs = computed(() =>
  (page.content || '')
    .split(/\n\s*\n/)
    .map((p) => p.trim())
    .filter(Boolean),
)

// 配图留空时使用站点默认 LOGO
const logoSrc = computed(() => page.imageUrl || logoUrl)

async function loadAbout() {
  try {
    const result = await get('/api/about')
    if (result?.error) return
    const data = result?.data
    if (!data) return
    page.eyebrow = data.eyebrow || DEFAULTS.eyebrow
    page.title = data.title || DEFAULTS.title
    page.subtitle = data.subtitle || DEFAULTS.subtitle
    page.content = data.content || DEFAULTS.content
    page.imageUrl = data.imageUrl || ''
    // 后端显式返回空数组表示「不展示统计区域」，此时不回填默认值
    page.stats = Array.isArray(data.stats)
      ? data.stats.map((s) => ({ value: s?.value || '', label: s?.label || '' }))
      : DEFAULTS.stats.map((s) => ({ ...s }))
  } catch {
    // 静默降级：保持默认文案
  }
}

onMounted(loadAbout)

usePageMeta(() => ({
  title: page.title,
  description: page.subtitle,
}))
</script>
