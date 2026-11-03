<template>
  <div v-if="!allowed" class="preview-message"><h1>无法连接编辑预览</h1><p>请从管理端打开预览，并检查两端地址配置。</p></div>
  <div v-else-if="!received" class="preview-message" role="status"><h1>等待编辑内容</h1><p>预览只展示当前表单，不会提交或发布。</p></div>
  <div v-else class="preview-stage" @click.capture="guardNavigation">
    <AppHeader v-if="chrome" />
    <div :style="{ paddingTop: chrome ? 'var(--header-height)' : '0' }">
      <div v-if="kind === 'work' && view === 'card'" class="preview-card"><WorkCard :key="revision" :slug="model.work?.slug || 'preview-work'" :title="model.work?.title || '未命名作品'" :description="model.work?.summary || ''" :date="model.work?.dateLabel || ''" :cover-url="model.work?.coverUrl || ''" :tag-type="model.work?.tagType || ''" :authors="model.work?.authors || ''" /></div>
      <component v-else :is="renderer" :key="revision" />
    </div>
    <AppFooter v-if="chrome" :key="'footer-' + revision" />
    <p v-if="linkHint" class="preview-link-hint" role="status">链接目标：{{ linkHint }}</p>
  </div>
</template>
<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, provide, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import DOMPurify from 'dompurify'
import { createPresentation, presentationFields } from '../../../shared/presentation-contract.js'
import AppHeader from '@/components/AppHeader.vue'
import AppFooter from '@/components/AppFooter.vue'
import WorkCard from '@/components/WorkCard.vue'
import { safeLink, safeFooterLink } from '@/utils/links'

const route = useRoute()
const kind = String(route.params.kind || '')
const view = String(route.query.view || 'page')
const controller = window.opener || (window.parent !== window ? window.parent : null)
const parentOrigin = String(route.query.parentOrigin || '')
const nonce = String(route.query.nonce || '')
const configuredAdmin = import.meta.env.VITE_ADMIN_SITE_URL
let trustedOrigin = window.location.origin
try { if (configuredAdmin) trustedOrigin = new URL(configuredAdmin, window.location.origin).origin } catch { /* Reject malformed configuration. */ }
const localDevOrigin = import.meta.env.DEV && (() => { try { const url = new URL(parentOrigin); return ['localhost', '127.0.0.1'].includes(url.hostname) && url.hostname === window.location.hostname && ['http:', 'https:'].includes(url.protocol) } catch { return false } })()
const allowed = Boolean(['home', 'work', 'about', 'join', 'footer', 'learning'].includes(kind) && controller && nonce.length >= 16 && (parentOrigin === trustedOrigin || parentOrigin === window.location.origin || localDevOrigin))
const received = ref(false)
const revision = ref(0)
const model = reactive({})
provide('studio-preview', model)
const linkHint = ref('')
let lastPayload = null
let basePresentation = createPresentation()
let robotMeta
let disposed = false
const renderers = {
  home: defineAsyncComponent(() => import('./HomePage.vue')),
  work: defineAsyncComponent(() => import('./WorkDetailPage.vue')),
  about: defineAsyncComponent(() => import('./AboutPage.vue')),
  join: defineAsyncComponent(() => import('./JoinPage.vue')),
  footer: AppFooter,
  learning: defineAsyncComponent(() => import('./LearningDetailPage.vue')),
}
const renderer = renderers[kind]
const chrome = computed(() => kind !== 'footer' && !(kind === 'work' && view === 'card'))
const text = (value, max = 2000) => typeof value === 'string' ? value.slice(0, max) : ''
function image(value) {
  const url = text(value, 4000)
  return /^data:image\/(png|jpe?g|webp|gif);base64,/i.test(url) ? url : safeLink(url)
}
function known(template, incoming) {
  const result = JSON.parse(JSON.stringify(template))
  if (!incoming || typeof incoming !== 'object') return result
  for (const key of Object.keys(template)) {
    const value = incoming[key]
    if (value === undefined || value === null) continue
    if (typeof template[key] === 'string') result[key] = key.endsWith('Url') ? safeLink(text(value)) : text(value)
    else if (typeof template[key] === 'boolean') result[key] = value === true
    else if (key === 'featuredLimit') result[key] = Number.isInteger(value) && value >= 1 && value <= 6 ? value : null
    else if (Array.isArray(template[key]) && Array.isArray(value)) result[key] = value.slice(0, 8).map(item => ({ title: text(item?.title, 160), description: text(item?.description, 1200) }))
  }
  return result
}
function normalize(payload) {
  const result = { presentation: {} }
  for (const section of Object.keys(presentationFields)) result.presentation[section] = known(basePresentation[section], payload.presentation?.[section])
  const pick = (value, keys) => Object.fromEntries(keys.map(key => [key, text(value?.[key], key === 'content' ? 200000 : 4000)]))
  if (payload.work) {
    result.work = pick(payload.work, ['title', 'slug', 'summary', 'category', 'coverUrl', 'content', 'tagType', 'authors', 'dateLabel', 'externalLink'])
    result.work.content = DOMPurify.sanitize(result.work.content, { FORBID_ATTR: ['style'] })
    result.work.coverUrl = image(result.work.coverUrl)
    result.work.externalLink = safeLink(result.work.externalLink)
  }
  if (payload.resource) {
    result.resource = pick(payload.resource, ['title', 'description', 'category', 'content', 'url', 'attachmentUrl', 'coverUrl', 'tags', 'updatedAt'])
    result.resource.content = DOMPurify.sanitize(result.resource.content, { FORBID_ATTR: ['style'] })
    for (const key of ['url', 'attachmentUrl']) result.resource[key] = safeLink(result.resource[key])
    result.resource.coverUrl = image(result.resource.coverUrl)
  }
  if (payload.about) result.about = { ...pick(payload.about, ['eyebrow', 'title', 'subtitle', 'content', 'imageUrl']), imageUrl: image(payload.about.imageUrl), stats: Array.isArray(payload.about.stats) ? payload.about.stats.slice(0, 8).map(item => ({ value: text(item?.value, 30), label: text(item?.label, 50) })) : [] }
  if (payload.join) result.join = { ...pick(payload.join, ['eyebrow', 'title', 'subtitle', 'qrImage', 'qrCaption']), qrImage: image(payload.join.qrImage), paragraphs: Array.isArray(payload.join.paragraphs) ? payload.join.paragraphs.slice(0, 12).map(item => text(item, 500)) : [] }
  if (payload.footer) {
    result.footer = { ...pick(payload.footer, ['brandName','brandIntro','linksTitle','socialTitle','email','emailLabel','copyrightLeft','copyrightRight']), showParticles: payload.footer.showParticles === true }
    result.footer.links = (Array.isArray(payload.footer.links) ? payload.footer.links : []).slice(0, 20).map(item => ({ label: text(item?.label, 50), url: safeFooterLink(item?.url), newTab: item?.newTab === true }))
    result.footer.socials = (Array.isArray(payload.footer.socials) ? payload.footer.socials : []).slice(0, 12).map(item => ({ title: text(item?.title, 50), icon: text(item?.icon, 50), iconUrl: image(item?.iconUrl), url: safeFooterLink(item?.url) }))
  }
  return result
}
function applyPayload(payload) {
  const clean = normalize(payload)
  if (received.value && JSON.stringify(clean) === JSON.stringify(model)) return
  for (const key of Object.keys(model)) delete model[key]
  Object.assign(model, clean)
  received.value = true
  revision.value++
}
function onMessage(event) {
  if (!allowed || !renderer || event.source !== controller || event.origin !== parentOrigin || event.data?.nonce !== nonce || event.data?.kind !== kind || event.data?.type !== 'unicorn:preview:update') return
  try {
    if (JSON.stringify(event.data.payload).length > 1500000) return
    lastPayload = event.data.payload || {}
    applyPayload(lastPayload)
  } catch { /* Ignore malformed or oversized editor messages. */ }
}
function guardNavigation(event) {
  const anchor = event.target.closest?.('a[href]')
  if (!anchor || anchor.getAttribute('href')?.startsWith('#')) return
  event.preventDefault()
  event.stopPropagation()
  linkHint.value = anchor.getAttribute('href') || ''
}
onMounted(async () => {
  robotMeta = document.createElement('meta')
  robotMeta.name = 'robots'
  robotMeta.content = 'noindex, nofollow'
  document.head.appendChild(robotMeta)
  document.title = '未保存预览 · 独角兽工作室'
  if (!allowed || !renderer) return
  window.addEventListener('message', onMessage)
  controller.postMessage({ type: 'unicorn:preview:ready', nonce }, parentOrigin)
  try {
    const response = await fetch((import.meta.env.VITE_API_BASE || '') + '/api/site/presentation', { credentials: 'include', signal: AbortSignal.timeout(3000) })
    const result = response.ok ? await response.json() : null
    if (result?.data && !disposed) {
      const empty = createPresentation()
      for (const section of Object.keys(presentationFields)) basePresentation[section] = known(empty[section], result.data[section])
      if (lastPayload) { applyPayload(lastPayload); await nextTick() }
    }
  } catch { /* The current form can still render; other sections remain empty. */ }
})
onBeforeUnmount(() => { disposed = true; window.removeEventListener('message', onMessage); robotMeta?.remove() })
</script>
<style scoped>
.preview-message { padding: 48px 24px; color: var(--color-ink); min-height: 240px; background: var(--color-paper); }
.preview-message h1 { font-size: 24px; margin-bottom: 16px; }
.preview-message p { color: var(--color-muted-light); line-height: 1.8; }
.preview-card { max-width: 600px; padding: 26px; margin-inline: auto; background: var(--color-paper); }
.preview-link-hint { position: fixed; z-index: 100; left: 12px; right: 12px; bottom: 12px; padding: 10px 16px; background: #121316; color: white; font-size: 12px; overflow-wrap: anywhere; border-radius: 4px; }
</style>
