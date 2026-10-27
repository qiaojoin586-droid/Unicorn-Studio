<template>
  <div class="join-page">
    <section class="page-shell join-section" aria-labelledby="join-title">
      <div class="join-overline">
        <p v-if="page.eyebrow" class="eyebrow"><span aria-hidden="true"></span>{{ page.eyebrow }}</p>
        <span class="join-edition">UNICORN STUDIO / AN OPEN INVITATION</span>
      </div>

      <div class="join-layout">
        <div class="join-copy">
          <h1 id="join-title" class="join-title">{{ page.title }}</h1>
          <p v-if="page.subtitle" class="join-subtitle">{{ page.subtitle }}</p>
          <div class="join-paragraphs">
            <p v-for="(paragraph, index) in page.paragraphs" :key="index">{{ paragraph }}</p>
          </div>
          <div class="join-topics" aria-label="我们的探索方向"><span>AI 智能应用</span><span>软件开发</span><span>学术研究</span></div>

          <div class="join-invitation">
            <svg class="invitation-symbol" viewBox="0 0 64 64" fill="none" aria-hidden="true">
              <path d="M51 13A25 25 0 1 0 51 51" stroke="currentColor" stroke-width="1.2" />
              <path d="M20 44 51 13M31 13h20v20" stroke="currentColor" stroke-width="1.2" />
              <circle cx="51" cy="13" r="3" fill="#FF8C95" />
            </svg>
            <p class="configured-lines">{{ extra.invitationText }}</p>
          </div>

          <div v-if="contactEmail || contactLinks.length" class="join-contact">
            <p class="join-contact-label">也可以在这里联系</p>
            <a v-if="contactEmail" :href="emailHref" class="join-contact-link">{{ contactEmail }} <span aria-hidden="true">↗</span></a>
            <a v-for="contact in contactLinks" :key="contact.url" :href="contact.url" target="_blank" rel="noopener noreferrer" class="join-contact-link">{{ contact.title }} <span class="sr-only">（在新窗口打开）</span><span aria-hidden="true">↗</span></a>
          </div>
          <p v-if="loadFailed" class="join-load-status" role="status">社群信息暂未更新。<button type="button" :disabled="loading" @click="loadJoin">{{ loading ? '正在获取…' : '重新获取' }}</button></p>
        </div>

        <div class="join-entry">
          <div class="join-qr-card">
            <div class="qr-card-heading"><span>JOIN THE CONVERSATION</span><span class="qr-card-dot" aria-hidden="true"></span></div>
            <div class="join-qr-art">
              <img v-if="!qrFailed" :src="qrSrc" alt="独角兽工作室社群二维码" :width="usingDefaultQr ? 1264 : undefined" :height="usingDefaultQr ? 1505 : undefined" decoding="async" @error="qrFailed = true" />
              <p v-else class="qr-unavailable" role="status">二维码暂时无法显示。请使用下方加入入口或页面中的联系方式。</p>
            </div>
            <p v-if="displayQrCaption" class="qr-caption">{{ displayQrCaption }}</p>
            <div v-if="groupNumber" class="join-group">
              <span class="group-label">{{ displayPlatform }} {{ displayPlatform === 'QQ' ? '群号' : '联系标识' }}</span>
              <span class="group-number">{{ groupNumber }}</span>
              <button type="button" class="group-copy" :aria-label="copySuccess ? '联系标识已复制' : '复制联系标识'" @click="copyGroupNumber">
                <svg v-if="!copySuccess" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2" /><path d="M15 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h3" /></svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="m5 12 4 4L19 6" /></svg>
              </button>
            </div>
            <p class="copy-status" role="status" aria-live="polite">{{ copyStatus }}</p>
          </div>
          <div class="join-phone-help">
            <span class="phone-help-index">正在用手机浏览？</span>
            <p v-if="extra.phoneHelp">{{ extra.phoneHelp }}</p>
            <p v-else-if="groupNumber && displayPlatform === 'QQ'">复制群号，在 QQ 中搜索即可找到社群；也可以保存二维码后，在 QQ 中从相册识别。</p>
            <p v-else>保存二维码后，在对应应用中从相册识别<span v-if="contactEmail || contactLinks.length">；也可以通过页面中的联系方式和我们交流</span>。</p>
            <SiteLink v-if="extra.joinUrl" :to="extra.joinUrl" class="qr-save-link">打开加入入口 <span aria-hidden="true">↗</span></SiteLink>
            <a v-if="!qrFailed" :href="qrSrc" target="_blank" rel="noopener noreferrer" download="unicorn-studio-community.jpg" class="qr-save-link">查看 / 保存二维码 <span class="sr-only">（打开原图）</span><span aria-hidden="true">↗</span></a>
          </div>
        </div>
      </div>

      <div class="join-bottom-note"><span>想法不需要完美，开始就有可能。</span><router-link to="/works">先看看我们的作品 <span aria-hidden="true">↗</span></router-link></div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'
import qrUrl from '@/image/community-qr.jpg'
import SiteLink from '@/components/SiteLink.vue'
import { usePresentation } from '@/composables/usePresentation'
import { useSiteSettings } from '@/composables/useSiteSettings'

const { get } = useApi()
const { presentation } = usePresentation()
const extra = computed(() => presentation.join)
const site = useSiteSettings()
// 群号来自随站点提供的 community-qr.jpg，仅在展示这张二维码时使用。
const GROUP_NUMBER = '1107213369'
const DEFAULTS = {
  eyebrow: 'JOIN US / 一份邀请',
  title: '下一件作品，和你一起。',
  subtitle: '欢迎加入独角兽工作室交流社群',
  paragraphs: [
    '在这里，分享前沿技术、实战项目与设计创意，让一次交流成为新的起点。',
    '无论是带着问题而来，还是已经有了想法，都欢迎你和我们一起探索。',
  ],
  qrImage: '',
  qrCaption: 'QQ 扫码加入社群',
}
const page = reactive({ ...DEFAULTS, paragraphs: [...DEFAULTS.paragraphs] })
const loading = ref(false)
const loadFailed = ref(false)
const qrFailed = ref(false)
const footerEmail = ref('')
const contactEmail = computed(() => {
  const value = presentation.footer.inheritContact ? site.email.value : footerEmail.value
  return /^[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+$/.test(value || '') ? value : ''
})
const contactLinks = ref([])
const copyStatus = ref('')
const copySuccess = ref(false)
let copyTimer = null
const usingDefaultQr = computed(() => !page.qrImage)
const groupNumber = computed(() => extra.value.groupNumber || (usingDefaultQr.value ? GROUP_NUMBER : ''))
const displayPlatform = computed(() => usingDefaultQr.value && !extra.value.groupNumber ? 'QQ' : extra.value.platform || '社群')
const qrSrc = computed(() => usingDefaultQr.value ? qrUrl : page.qrImage)
const displayQrCaption = computed(() => qrFailed.value ? '' : page.qrCaption)
const emailHref = computed(() => 'mailto:' + encodeURIComponent(contactEmail.value))

async function loadJoin() {
  if (loading.value) return
  loading.value = true
  try {
    const result = await get('/api/join')
    if (result?.error || !result?.data) throw new Error('Join unavailable')
    const data = result.data
    page.eyebrow = data.eyebrow ?? DEFAULTS.eyebrow
    page.title = data.title || DEFAULTS.title
    page.subtitle = data.subtitle ?? DEFAULTS.subtitle
    page.paragraphs = Array.isArray(data.paragraphs) ? data.paragraphs.map((p) => String(p ?? '').trim()).filter(Boolean) : [...DEFAULTS.paragraphs]
    page.qrImage = data.qrImage || ''
    page.qrCaption = data.qrCaption ?? DEFAULTS.qrCaption
    qrFailed.value = false
    loadFailed.value = false
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

async function loadContacts() {
  try {
    const result = await get('/api/footer')
    if (result?.error || !result?.data) return
    const data = result.data
    const email = String(data.email || '').trim()
    if (/^[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+$/.test(email)) footerEmail.value = email
    contactLinks.value = (Array.isArray(data.socials) ? data.socials : []).flatMap((social) => {
      try {
        const url = new URL(social?.url)
        // 平台首页不是工作室的联系入口，只有实际配置的具体页面或加群地址才展示。
        if (!['https:', 'http:'].includes(url.protocol) || (url.pathname === '/' && !url.search && !url.hash)) return []
        const title = String(social?.title || '').trim()
        return title ? [{ url: url.href, title }] : []
      } catch { return [] }
    }).slice(0, 3)
  } catch {
    // 联系方式是补充入口，不影响现有二维码与入群路径。
  }
}

async function copyGroupNumber() {
  clearTimeout(copyTimer)
  try {
    if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable')
    await navigator.clipboard.writeText(groupNumber.value)
    copySuccess.value = true
    copyStatus.value = displayPlatform.value === 'QQ' ? '群号已复制，打开 QQ 搜索即可。' : '联系标识已复制。'
  } catch {
    copySuccess.value = false
    copyStatus.value = '请长按或选中群号，手动复制。'
  }
  copyTimer = setTimeout(() => { copySuccess.value = false; copyStatus.value = '' }, 5000)
}
onMounted(() => { loadJoin(); loadContacts() })
onBeforeUnmount(() => clearTimeout(copyTimer))
usePageMeta(() => ({ title: page.title === DEFAULTS.title ? '加入我们' : page.title, description: page.subtitle }))
</script>

<style scoped>
.qr-unavailable { min-height: 220px; display: grid; place-items: center; padding: 28px; color: #62636a; font-size: 14px; line-height: 1.8; text-align: center; }
.join-page { color: var(--color-paper, #f4f2ec); background: var(--color-ink, #121316); }
.join-section { padding-block: clamp(48px, 6vw, 88px) 0; }
.join-overline { display: flex; justify-content: space-between; align-items: center; gap: 24px; padding-bottom: 28px; border-bottom: 1px solid #ffffff25; margin-bottom: 64px; }
.join-overline .eyebrow { display: flex; align-items: center; gap: 10px; margin: 0; color: #a7a8af; }
.join-overline .eyebrow > span { width: 7px; height: 7px; flex-shrink: 0; border-radius: 50%; background: #ff8c95; }
.join-edition { color: #a7a8af; font: 10px/1.5 ui-monospace, monospace; letter-spacing: .06em; }
.join-layout { display: grid; grid-template-columns: 1.2fr .8fr; gap: clamp(48px, 8vw, 128px); align-items: start; }
.join-copy { min-width: 0; }
.join-title { margin: 0 0 28px; font-size: clamp(42px, 5vw, 72px); font-weight: 550; line-height: 1.25; letter-spacing: -.055em; max-width: 9em; text-wrap: balance; overflow-wrap: anywhere; }
.join-subtitle { color: #f4f2ec; font-size: clamp(18px, 1.7vw, 23px); line-height: 1.6; margin: 0 0 24px; font-weight: 500; }
.join-paragraphs { max-width: 30em; }
.join-paragraphs p { font-size: 15px; color: #a7a8af; line-height: 1.95; margin: 0 0 12px; overflow-wrap: anywhere; }
.join-topics { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 28px; }
.join-topics span { font-size: 11px; border: 1px solid #ffffff30; padding: 7px 12px; color: #c5c4c2; border-radius: 2px; }
.join-invitation { display: flex; align-items: center; gap: 28px; margin-block: 64px 40px; }
.invitation-symbol { width: 66px; height: 66px; flex-shrink: 0; }
.join-invitation p { font-size: clamp(20px, 2.2vw, 30px); line-height: 1.5; letter-spacing: -.035em; margin: 0; }
.join-contact { padding-top: 28px; border-top: 1px solid #ffffff25; display: flex; flex-wrap: wrap; column-gap: 24px; max-width: 32em; }
.join-contact-label { width: 100%; color: #a7a8af; font-size: 12px; margin: 0 0 4px; }
.join-contact-link { display: inline-flex; align-items: center; min-height: 44px; gap: 20px; color: #f4f2ec; font-size: 14px; overflow-wrap: anywhere; transition: color 160ms; }
.join-contact-link:hover { color: #ff8c95; }
.join-contact-link span:last-child { flex-shrink: 0; }
.join-entry { width: 100%; max-width: 380px; margin-inline: auto 0; min-width: 0; }
.join-qr-card { padding: 24px 24px 16px; color: #121316; background: #f4f2ec; position: relative; }
.join-qr-card::before { content: ''; position: absolute; width: 32px; height: 32px; top: -8px; right: -8px; border-top: 1px solid #a7a8af; border-right: 1px solid #a7a8af; pointer-events: none; }
.qr-card-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; font: 9px/1.5 ui-monospace, monospace; letter-spacing: .065em; padding-bottom: 18px; }
.qr-card-dot { width: 6px; height: 6px; border-radius: 50%; background: #c92d3b; flex-shrink: 0; }
.join-qr-art { background: #fff; overflow: hidden; }
.join-qr-art img { display: block; width: 100%; height: auto; }
.qr-caption { font-size: 12px; line-height: 1.6; margin: 18px 0 0; color: #62636a; }
.join-group { display: flex; gap: 12px; align-items: center; margin-top: 16px; padding-top: 14px; border-top: 1px solid #12131625; }
.group-label { color: #62636a; font-size: 11px; max-width: 35%; overflow-wrap: anywhere; }
.group-number { min-width: 0; flex: 1; overflow-wrap: anywhere; font-size: clamp(15px, 1.5vw, 19px); font-weight: 600; font-variant-numeric: tabular-nums; letter-spacing: .015em; user-select: all; }
.group-copy { width: 44px; height: 44px; flex-shrink: 0; margin-left: auto; display: grid; place-items: center; border: 1px solid #12131630; border-radius: 50%; background: transparent; transition: color 160ms, background 160ms; }
.group-copy:hover { color: #fff; background: #121316; }
.group-copy svg { width: 17px; height: 17px; }
.copy-status { font-size: 11px; color: #62636a; line-height: 1.6; min-height: 18px; margin: 8px 0 0; }
.join-phone-help { margin-top: 24px; max-width: 29em; }
.phone-help-index { font-size: 12px; font-weight: 500; }
.join-phone-help p { color: #a7a8af; font-size: 12px; line-height: 1.9; margin: 8px 0 4px; }
.qr-save-link { display: inline-flex; align-items: center; gap: 16px; min-height: 44px; font-size: 12px; color: #ff8c95; text-decoration: underline; text-underline-offset: 5px; }
.join-load-status { font-size: 12px; line-height: 1.8; color: #a7a8af; margin-top: 24px; }
.join-load-status button { min-height: 44px; padding-inline: 8px; color: #ff8c95; text-decoration: underline; text-underline-offset: 4px; }
.join-bottom-note { margin-top: 80px; padding-block: 28px; border-top: 1px solid #ffffff25; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px 32px; color: #a7a8af; font-size: 12px; }
.join-bottom-note a { display: inline-flex; align-items: center; gap: 24px; min-height: 44px; color: #f4f2ec; }
.join-bottom-note a:hover { color: #ff8c95; }
@media (max-width: 900px) {
  .join-layout { gap: 40px; grid-template-columns: 1.05fr .95fr; }
  .join-title { font-size: 46px; }
  .join-qr-card { padding: 20px 20px 12px; }
  .join-invitation { gap: 20px; margin-top: 48px; }
  .invitation-symbol { width: 52px; height: 52px; }
}
@media (max-width: 640px) {
  .join-overline { margin-bottom: 40px; padding-bottom: 24px; }
  .join-edition { display: none; }
  .join-layout { grid-template-columns: 1fr; gap: 48px; }
  .join-title { font-size: clamp(36px, 9vw, 52px); margin-bottom: 24px; }
  .join-subtitle { font-size: 18px; }
  .join-paragraphs p { font-size: 15px; }
  .join-topics { margin-top: 24px; }
  .join-invitation { margin-block: 40px 28px; }
  .join-invitation p { font-size: 23px; }
  .join-entry { max-width: 360px; margin-inline: auto; }
  .join-bottom-note { margin-top: 48px; padding-block: 20px 32px; }
  .group-number { font-size: 18px; }
}
@media (max-width: 360px) {
  .join-qr-card { padding: 18px 16px 12px; }
  .join-group { gap: 8px; }
  .group-number { font-size: 16px; }
  .group-copy { width: 44px; height: 44px; }
  .qr-card-heading { font-size: 8px; letter-spacing: .025em; }
}
@media (prefers-reduced-motion: reduce) { .join-contact-link, .group-copy { transition: none; } }
</style>
