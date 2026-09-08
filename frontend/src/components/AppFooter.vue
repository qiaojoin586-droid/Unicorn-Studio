<template>
  <footer class="relative z-10 bg-gray-900 text-gray-300">
    <div class="max-w-6xl mx-auto px-6 py-12">
      <div class="grid grid-cols-1 md:grid-cols-3 gap-10">
        <!-- Brand -->
        <div class="relative">
          <!-- 左侧装饰粒子动效：后台「页脚设置」开关控制，默认关闭 -->
          <div v-if="page.showParticles" class="brand-particles" aria-hidden="true">
            <span v-for="n in 6" :key="n" class="particle" :class="`particle-${n}`"></span>
          </div>

          <div class="relative flex items-center gap-2 text-white text-xl font-bold mb-4" :aria-label="page.brandName">
            <span class="text-2xl" aria-hidden="true">🦄</span>
            <span>{{ page.brandName }}</span>
          </div>
          <p class="relative text-gray-400 leading-relaxed text-sm whitespace-pre-line">{{ page.brandIntro }}</p>
        </div>

        <!-- Quick Links -->
        <div v-if="page.links.length">
          <h4 class="text-white font-semibold mb-4">{{ page.linksTitle }}</h4>
          <ul class="space-y-2 text-sm">
            <li v-for="(link, idx) in page.links" :key="idx">
              <!-- 新窗口 / 外部链接用原生 a；站内同窗口保留 SPA 跳转 -->
              <a
                v-if="link.newTab || isExternal(link.url)"
                :href="link.url"
                :target="link.newTab ? '_blank' : undefined"
                rel="noopener noreferrer"
                class="hover:text-indigo-400 transition"
              >{{ link.label }}</a>
              <router-link v-else :to="link.url" class="hover:text-indigo-400 transition">
                {{ link.label }}
              </router-link>
            </li>
          </ul>
        </div>

        <!-- Social -->
        <div>
          <h4 class="text-white font-semibold mb-4">{{ page.socialTitle }}</h4>
          <div v-if="page.socials.length" class="flex space-x-4">
            <a
              v-for="(social, idx) in page.socials"
              :key="idx"
              :href="social.url"
              :title="social.title"
              :aria-label="social.title"
              :target="isExternal(social.url) ? '_blank' : undefined"
              rel="noopener noreferrer"
              class="w-10 h-10 flex items-center justify-center rounded-full bg-gray-800 hover:bg-indigo-600 text-gray-400 hover:text-white transition-all"
            >
              <img v-if="social.iconUrl" :src="social.iconUrl" alt="" class="w-5 h-5 object-contain" />
              <span v-else class="social-icon" v-html="resolveIconSvg(social.icon)"></span>
            </a>
          </div>
          <p class="mt-4 text-sm text-gray-500">
            {{ page.emailLabel }}{{ page.email }}
          </p>
        </div>
      </div>

      <!-- Divider & Copyright -->
      <div class="border-t border-gray-800 mt-10 pt-6 flex flex-col md:flex-row items-center justify-between text-sm text-gray-500">
        <p>{{ copyrightLeft }}</p>
        <p class="mt-2 md:mt-0">{{ page.copyrightRight }}</p>
      </div>
    </div>
  </footer>
</template>

<script setup>
import { computed, onMounted, reactive } from 'vue'
import { useApi } from '@/composables/useApi'
import { resolveIconSvg } from '@/constants/socialIcons'

const { get } = useApi()

const currentYear = computed(() => new Date().getFullYear())

// 默认值 = 页脚改造前的原始文案/链接；接口失败或字段缺失时回落，保证页脚永不空白
const DEFAULTS = {
  brandName: '独角兽工作室',
  brandIntro: '用技术与创意，打造卓越的数字产品。\n我们专注于AI智能开发、Agent开发与大模型学术研究。',
  showParticles: false,
  linksTitle: '快速链接',
  links: [
    { label: '首页', url: '/', newTab: false },
    { label: '作品', url: '/works', newTab: false },
    { label: '关于我们', url: '/about', newTab: false },
    { label: '学习资料', url: '/learning', newTab: false },
  ],
  socialTitle: '关注我们',
  socials: [
    { icon: 'github', iconUrl: '', url: 'https://github.com', title: 'GitHub' },
    { icon: 'qq', iconUrl: '', url: 'https://qq.com', title: 'QQ' },
  ],
  email: '1351094759@qq.com',
  emailLabel: '邮箱：',
  copyrightLeft: '© {year} 独角兽工作室 · 用创意点亮未来',
  copyrightRight: '每个人都有一颗炽热的 ❤️ and You',
}

const page = reactive({
  brandName: DEFAULTS.brandName,
  brandIntro: DEFAULTS.brandIntro,
  showParticles: DEFAULTS.showParticles,
  linksTitle: DEFAULTS.linksTitle,
  links: DEFAULTS.links.map((l) => ({ ...l })),
  socialTitle: DEFAULTS.socialTitle,
  socials: DEFAULTS.socials.map((s) => ({ ...s })),
  email: DEFAULTS.email,
  emailLabel: DEFAULTS.emailLabel,
  copyrightLeft: DEFAULTS.copyrightLeft,
  copyrightRight: DEFAULTS.copyrightRight,
})

// {year} 占位符替换为当前年份，避免版权年份被冻结
const copyrightLeft = computed(() =>
  (page.copyrightLeft || '').replace(/\{year\}/g, String(currentYear.value)),
)

const EXTERNAL_RE = /^[a-zA-Z][a-zA-Z0-9+.-]*:/

/** 带 scheme 的绝对地址（http/https/mailto/tel）视为外部链接 */
function isExternal(url) {
  return EXTERNAL_RE.test(url || '')
}

async function loadFooter() {
  try {
    const result = await get('/api/footer')
    if (result?.error) return
    const data = result?.data
    if (!data) return
    page.brandName = data.brandName || DEFAULTS.brandName
    page.brandIntro = data.brandIntro || DEFAULTS.brandIntro
    page.showParticles = !!data.showParticles
    page.linksTitle = data.linksTitle || DEFAULTS.linksTitle
    page.socialTitle = data.socialTitle || DEFAULTS.socialTitle
    page.email = data.email || DEFAULTS.email
    page.emailLabel = data.emailLabel ?? DEFAULTS.emailLabel
    page.copyrightLeft = data.copyrightLeft || DEFAULTS.copyrightLeft
    page.copyrightRight = data.copyrightRight || DEFAULTS.copyrightRight
    // 后端显式返回空数组表示「不展示该区域」，此时不回填默认值
    page.links = Array.isArray(data.links)
      ? data.links.map((l) => ({ label: l?.label || '', url: l?.url || '', newTab: !!l?.newTab }))
      : DEFAULTS.links.map((l) => ({ ...l }))
    page.socials = Array.isArray(data.socials)
      ? data.socials.map((s) => ({
          icon: s?.icon || '',
          iconUrl: s?.iconUrl || '',
          url: s?.url || '',
          title: s?.title || '',
        }))
      : DEFAULTS.socials.map((s) => ({ ...s }))
  } catch {
    // 静默降级：保持默认文案
  }
}

onMounted(loadFooter)
</script>

<style scoped>
.social-icon {
  display: inline-flex;
  width: 20px;
  height: 20px;
}
.social-icon :deep(svg) {
  width: 100%;
  height: 100%;
}

/* 左侧装饰粒子动效（后台开关控制） */
.brand-particles {
  position: absolute;
  inset: -12px -24px 0 -24px;
  overflow: hidden;
  pointer-events: none;
}
.particle {
  position: absolute;
  border-radius: 9999px;
  background: rgba(129, 140, 248, 0.55);
  animation: footer-particle-float 7s ease-in-out infinite;
}
.particle-1 {
  width: 5px;
  height: 5px;
  left: 4%;
  top: 64%;
  animation-delay: 0s;
}
.particle-2 {
  width: 3px;
  height: 3px;
  left: 18%;
  top: 22%;
  animation-delay: 1.1s;
}
.particle-3 {
  width: 4px;
  height: 4px;
  left: 32%;
  top: 78%;
  animation-delay: 2.2s;
}
.particle-4 {
  width: 3px;
  height: 3px;
  left: 48%;
  top: 40%;
  animation-delay: 3.1s;
}
.particle-5 {
  width: 6px;
  height: 6px;
  left: 62%;
  top: 70%;
  animation-delay: 1.7s;
}
.particle-6 {
  width: 3px;
  height: 3px;
  left: 74%;
  top: 26%;
  animation-delay: 2.7s;
}
@keyframes footer-particle-float {
  0%,
  100% {
    transform: translateY(0);
    opacity: 0.3;
  }
  50% {
    transform: translateY(-14px);
    opacity: 0.85;
  }
}
@media (prefers-reduced-motion: reduce) {
  .particle {
    animation: none;
    opacity: 0.4;
  }
}
</style>
