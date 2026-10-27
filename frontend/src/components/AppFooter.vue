<template>
  <footer class="site-footer">
    <div class="page-shell">
      <div class="footer-top">
        <div class="footer-brand">
          <BrandMark />
          <div><strong>{{ displayName }}</strong><span v-if="extra.brandTagline">{{ extra.brandTagline }}</span></div>
          <span v-if="page.showParticles" class="footer-signal" aria-hidden="true"></span>
        </div>
        <p v-if="page.brandIntro" class="footer-intro">{{ page.brandIntro }}</p>
      </div>
      <div v-if="extra.noteTitle || extra.noteEyebrow || (extra.noteLinkLabel && extra.noteLinkUrl) || page.links.length || page.socials.length || displayEmail" class="footer-grid">
        <div v-if="extra.noteTitle || extra.noteEyebrow || (extra.noteLinkLabel && extra.noteLinkUrl)" class="footer-note"><span v-if="extra.noteEyebrow" class="eyebrow">{{ extra.noteEyebrow }}</span><p v-if="extra.noteTitle" class="configured-lines">{{ extra.noteTitle }}</p><SiteLink v-if="extra.noteLinkLabel && extra.noteLinkUrl" :to="extra.noteLinkUrl" class="footer-invite">{{ extra.noteLinkLabel }} <span aria-hidden="true">↗</span></SiteLink></div>
        <div v-if="page.links.length" class="footer-links"><h2 v-if="page.linksTitle">{{ page.linksTitle }}</h2><ul><li v-for="(link, i) in page.links" :key="i"><a v-if="link.newTab || isExternal(link.url)" :href="link.url" :target="link.newTab ? '_blank' : undefined" rel="noopener noreferrer">{{ link.label }}<span v-if="link.newTab" class="sr-only">（新窗口）</span></a><router-link v-else :to="link.url">{{ link.label }}</router-link></li></ul></div>
        <div v-if="page.socials.length || displayEmail" class="footer-contact"><h2 v-if="page.socialTitle">{{ page.socialTitle }}</h2><div v-if="page.socials.length" class="social-list"><a v-for="(social, i) in page.socials" :key="i" :href="social.url" :aria-label="social.title || '社交链接'" :target="isExternal(social.url) ? '_blank' : undefined" rel="noopener noreferrer"><img v-if="social.iconUrl" :src="social.iconUrl" alt="" width="18" height="18" /><span v-else class="social-icon" aria-hidden="true" v-html="resolveIconSvg(social.icon)"></span><span>{{ social.title }}</span><span aria-hidden="true">↗</span></a></div>
          <div v-if="displayEmail" class="contact-email"><span v-if="page.emailLabel">{{ page.emailLabel }}</span><div><a :href="`mailto:${displayEmail}`">{{ displayEmail }}</a><button type="button" @click="copyEmail" aria-label="复制联系邮箱"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="1" stroke="currentColor" stroke-width="1.5" /><path d="M16 8V4H4v12h4" stroke="currentColor" stroke-width="1.5" /></svg></button></div><span class="copy-result" role="status">{{ copyStatus }}</span></div>
        </div>
      </div>
      <div v-if="extra.wordmark" class="footer-wordmark" aria-hidden="true">{{ extra.wordmark }}<span>●</span></div>
      <div class="footer-bottom"><p>{{ copyrightLeft }}</p><p>{{ page.copyrightRight }}</p><a href="#main-content" @click.prevent="backToStart">回到顶部 <span aria-hidden="true">↑</span></a></div>
    </div>
  </footer>
</template>
<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { useApi } from '@/composables/useApi'
import { resolveIconSvg } from '@/constants/socialIcons'
import BrandMark from './BrandMark.vue'
import SiteLink from './SiteLink.vue'
import { usePresentation } from '@/composables/usePresentation'
import { useSiteSettings } from '@/composables/useSiteSettings'
import { safeFooterLink } from '@/utils/links'
const { get } = useApi()
const { presentation } = usePresentation()
const site = useSiteSettings()
const extra = computed(() => presentation.footer)
const currentYear = new Date().getFullYear()
const DEFAULTS = {
  brandName: '独角兽工作室', brandIntro: '用技术与创意，让好奇心长出作品。\n一起探索 AI 智能应用、Agent 开发与大模型研究。',
  showParticles: false, linksTitle: '探索', links: [{ label: '首页', url: '/' }, { label: '作品档案', url: '/works' }, { label: '关于我们', url: '/about' }, { label: '学习资料', url: '/learning' }],
  socialTitle: '保持联系', socials: [], email: '1351094759@qq.com', emailLabel: '联系邮箱',
  copyrightLeft: '© {year} 独角兽工作室', copyrightRight: 'STAY CURIOUS. MAKE THINGS.',
}
const page = reactive({ ...DEFAULTS, links: DEFAULTS.links.map(l => ({ ...l })), socials: [] })
const displayName = computed(() => extra.value.inheritBrand ? site.siteName.value : page.brandName)
const displayEmail = computed(() => extra.value.inheritContact ? site.email.value : page.email)
const copyrightLeft = computed(() => String(page.copyrightLeft || '').replace(/\{year\}/g, String(currentYear)))
const copyStatus = ref('')
let copyTimer
const isExternal = url => /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(url || '')
async function copyEmail() {
  try { await navigator.clipboard.writeText(displayEmail.value); copyStatus.value = '邮箱已复制' }
  catch { copyStatus.value = '请选中邮箱文字复制' }
  clearTimeout(copyTimer)
  copyTimer = setTimeout(() => { copyStatus.value = '' }, 3000)
}
function backToStart() {
  window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
  document.querySelector('#main-content')?.focus({ preventScroll: true })
}
async function loadFooter() {
  try {
    const result = await get('/api/footer')
    const data = !result?.error && result?.data
    if (!data) return
    for (const key of ['brandName', 'brandIntro', 'linksTitle', 'socialTitle', 'email', 'copyrightLeft', 'copyrightRight']) page[key] = data[key] ?? DEFAULTS[key]
    page.emailLabel = data.emailLabel ?? DEFAULTS.emailLabel
    page.showParticles = !!data.showParticles
    if (Array.isArray(data.links)) page.links = data.links.filter(l => l?.url && l?.label).map(l => ({ label: l.label, url: safeFooterLink(l.url), newTab: !!l.newTab })).filter(l => l.url)
    if (Array.isArray(data.socials)) page.socials = data.socials.filter(s => s?.url).map(s => ({ icon: s.icon || '', iconUrl: s.iconUrl || '', url: safeFooterLink(s.url), title: s.title || '' })).filter(s => s.url)
  } catch { /* Keep useful contact details when the API is unavailable. */ }
}
onMounted(loadFooter)
onBeforeUnmount(() => clearTimeout(copyTimer))
</script>
<style scoped>
.site-footer { position: relative; background: var(--color-ink); color: var(--color-paper); border-top: 1px solid #ffffff18; padding-block: 58px 26px; }
.footer-top { display: flex; justify-content: space-between; align-items: start; gap: 36px; padding-bottom: 48px; border-bottom: 1px solid #ffffff20; }
.footer-brand { position: relative; display: flex; align-items: center; min-width: 0; max-width: 100%; gap: 14px; }
.footer-brand > div { min-width: 0; overflow-wrap: anywhere; }
.footer-brand strong { display: block; font-size: 18px; font-weight: 550; letter-spacing: .02em; }
.footer-brand div > span { display: block; margin-top: 9px; font-family: var(--font-mono); font-size: 9px; color: var(--color-muted-dark); letter-spacing: .08em; }
.footer-intro { white-space: pre-line; max-width: 390px; color: var(--color-muted-dark); font-size: 13px; line-height: 1.9; }
.footer-grid { display: grid; grid-template-columns: 1.4fr .6fr 1fr; gap: 50px; padding-block: 58px 42px; }
.footer-note .eyebrow { color: var(--color-muted-dark); font-size: 10px; }
.footer-note p { margin-top: 18px; font-size: clamp(24px, 2.8vw, 40px); line-height: 1.5; letter-spacing: -.035em; }
.footer-invite { display: inline-flex; gap: 36px; align-items: center; min-height: 44px; margin-top: 16px; color: var(--color-accent-on-dark); font-size: 13px; }
.footer-invite span { transition: transform var(--duration-ui); }
.footer-invite:hover span { transform: translate(3px, -3px); }
.footer-grid h2 { color: var(--color-muted-dark); font-size: 11px; font-weight: 400; margin-bottom: 17px; }
.footer-links a { display: inline-flex; align-items: center; min-height: 44px; font-size: 13px; transition: color var(--duration-fast); }
.footer-links a:hover, .social-list a:hover { color: var(--color-accent-on-dark); }
.social-list a { display: flex; align-items: center; gap: 10px; min-height: 44px; font-size: 13px; }
.social-list a > span:last-child { margin-left: auto; }
.social-icon { width: 18px; height: 18px; display: inline-flex; }
.social-icon :deep(svg) { width: 100%; height: 100%; }
.contact-email { margin-top: 18px; }
.contact-email > span { color: var(--color-muted-dark); font-size: 11px; }
.contact-email > div { display: flex; align-items: center; gap: 5px; margin-top: 3px; }
.contact-email a { overflow-wrap: anywhere; font-size: 14px; }
.contact-email button { width: 44px; height: 44px; display: inline-grid; place-items: center; flex-shrink: 0; color: var(--color-muted-dark); }
.contact-email .copy-result { display: block; min-height: 18px; color: var(--color-accent-on-dark); }
.footer-wordmark { padding-block: 14px; font-size: clamp(45px, 15.8vw, 230px); font-weight: 600; line-height: 1.05; letter-spacing: -.075em; user-select: none; color: #e4e0d6; overflow-wrap: anywhere; }
.footer-wordmark span { display: inline-block; vertical-align: top; font-family: var(--font-mono); font-size: .1em; margin: .8em 0 0 .4em; letter-spacing: 0; color: var(--color-accent); }
.footer-bottom { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px 24px; margin-top: 20px; padding-top: 20px; border-top: 1px solid #ffffff20; color: var(--color-muted-dark); font-size: 10px; line-height: 1.8; }
.footer-bottom a { display: inline-flex; gap: 15px; align-items: center; min-height: 44px; }
.footer-signal { position: absolute; right: -18px; top: 5px; width: 5px; height: 5px; border-radius: 50%; background: var(--color-accent-on-dark); animation: signal 3s ease-in-out infinite; }
@keyframes signal { 50% { opacity: .35; } }
@media(max-width: 900px) { .footer-grid { grid-template-columns: 1fr 1fr; gap: 36px; } .footer-note { grid-column: 1 / -1; } }
@media(max-width: 600px) { .site-footer { padding-top: 38px; } .footer-top { flex-direction: column; gap: 22px; padding-bottom: 30px; } .footer-grid { padding-block: 36px 24px; gap: 30px 20px; } .footer-contact { min-width: 0; } .contact-email { position: relative; } .contact-email a { display: block; font-size: 12px; } .contact-email > div { display: block; padding-top: 10px; } .contact-email button { position: absolute; right: 0; top: -14px; } .footer-bottom p:nth-child(2) { display: none; } }
@media(max-width: 374px) { .footer-grid { grid-template-columns: 1fr; } .footer-note { grid-column: auto; } }
</style>
