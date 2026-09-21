<template>
  <div class="min-h-screen relative">
    <!-- Fixed full-page video background -->
    <div class="fixed inset-0 z-0 overflow-hidden">
      <BackgroundVideo :src="homeVideoUrl" :poster="homeVideoPosterUrl" />
      <div class="absolute inset-0 bg-gray-950/50"></div>
    </div>

    <!-- Main content -->
    <div class="relative z-10 flex items-center justify-center min-h-screen -mt-20 pt-32 pb-20 px-4">
      <div class="max-w-5xl w-full grid grid-cols-1 md:grid-cols-5 gap-12 items-center">
        <!-- Left: Text area (60%) -->
        <div class="md:col-span-3">
          <p v-if="page.eyebrow" class="text-sm font-semibold uppercase tracking-widest text-indigo-400 mb-3">
            {{ page.eyebrow }}
          </p>
          <h1 class="text-4xl sm:text-5xl font-extrabold text-white mb-6">{{ page.title }}</h1>
          <p v-if="page.subtitle" class="text-xl font-semibold text-indigo-300 mb-4">{{ page.subtitle }}</p>
          <p
            v-for="(paragraph, idx) in page.paragraphs"
            :key="idx"
            class="leading-relaxed"
            :class="idx === 0 ? 'text-gray-300 text-lg mb-4' : 'text-gray-400 mb-4'"
          >
            {{ paragraph }}
          </p>
        </div>

        <!-- Right: QR code area (40%) -->
        <div class="md:col-span-2 flex flex-col items-center">
          <div class="bg-white/10 border border-white/10 rounded-2xl p-6 backdrop-blur-sm shadow-lg">
            <!-- W-20：原本写 w-82 h-82，但 82 不在 Tailwind 尺寸 scale 内（实测构建 CSS 中 .w-82 为 0 次），
                 尺寸从未生效、图片被 preflight 的 img{max-width:100%} 撑满容器宽度。
                 按设计稿意图恢复成 328px 见方（82 × 4px），并加 max-w-full 防窄屏溢出。 -->
            <img
              :src="qrSrc"
              alt="社群二维码"
              width="990"
              height="1258"
              class="w-[328px] h-[328px] max-w-full object-contain rounded-lg"
            />
          </div>
          <p v-if="page.qrCaption" class="text-gray-300 text-sm mt-4">{{ page.qrCaption }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive } from 'vue'
import BackgroundVideo from '@/components/BackgroundVideo.vue'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'
import homeVideoUrl from '@/image/home.mp4'
import homeVideoPosterUrl from '@/image/home-poster.jpg'
import qrUrl from '@/image/community-qr.jpg'

const { get } = useApi()

// 默认文案 = 改造前的原始硬编码内容；接口失败或字段缺失时回落，保证页面永不空白
const DEFAULTS = {
  eyebrow: '',
  title: '加入我们',
  subtitle: '欢迎加入独角兽工作室交流社群',
  paragraphs: [
    '在这里我们分享前沿技术、实战项目与设计创意，共同成长、一起探索数字世界的更多可能。',
    '扫描下方二维码，即可加入社群与我们直接交流。',
  ],
  qrImage: '',
  qrCaption: 'QQ扫码加入社群',
}

const page = reactive({
  eyebrow: DEFAULTS.eyebrow,
  title: DEFAULTS.title,
  subtitle: DEFAULTS.subtitle,
  paragraphs: [...DEFAULTS.paragraphs],
  qrImage: DEFAULTS.qrImage,
  qrCaption: DEFAULTS.qrCaption,
})

// 二维码留空时使用站点内置默认图
const qrSrc = computed(() => page.qrImage || qrUrl)

async function loadJoin() {
  try {
    const result = await get('/api/join')
    if (result?.error) return
    const data = result?.data
    if (!data) return
    page.eyebrow = data.eyebrow || DEFAULTS.eyebrow
    page.title = data.title || DEFAULTS.title
    page.subtitle = data.subtitle || DEFAULTS.subtitle
    // 后端始终返回数组；显式配置为空数组表示「不展示正文」
    page.paragraphs = Array.isArray(data.paragraphs)
      ? data.paragraphs.map((p) => String(p ?? '').trim()).filter(Boolean)
      : [...DEFAULTS.paragraphs]
    page.qrImage = data.qrImage || ''
    page.qrCaption = data.qrCaption || DEFAULTS.qrCaption
  } catch {
    // 静默降级：保持默认文案
  }
}

onMounted(loadJoin)

usePageMeta(() => ({
  title: page.title,
  description: `${page.subtitle}，分享前沿技术、实战项目与设计创意。`,
}))
</script>
