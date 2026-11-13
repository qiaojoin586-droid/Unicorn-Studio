<template>
  <section class="site-preview">
    <header class="preview-heading"><div><strong>{{ title || '主站效果预览' }}</strong><span>未保存预览 · 不影响线上内容</span></div><a :href="publicSiteUrl(publicPath || '/')" target="_blank" rel="noopener noreferrer">查看线上 ↗</a></header>
    <div class="preview-toolbar">
      <div class="preview-segment" aria-label="预览屏幕"><button type="button" :aria-pressed="device === 'desktop'" @click="device = 'desktop'">桌面</button><button type="button" :aria-pressed="device === 'mobile'" @click="device = 'mobile'">手机</button></div>
      <div v-if="kind === 'work'" class="preview-segment" aria-label="作品预览类型"><button type="button" :aria-pressed="view === 'card'" @click="view = 'card'">卡片</button><button type="button" :aria-pressed="view === 'detail'" @click="view = 'detail'">详情</button></div>
      <button type="button" class="preview-expand" @click="openPreview">放大预览 ↗</button>
    </div>
    <div ref="viewport" class="preview-viewport" :style="{ height: frameHeight * scale + 'px' }"><iframe ref="frame" :src="frameUrl" :title="title || '主站未保存内容预览'" :style="{ left: Math.max(0, (availableWidth - canvasWidth * scale) / 2) + 'px', width: canvasWidth + 'px', height: frameHeight + 'px', transform: `scale(${scale})` }" referrerpolicy="strict-origin-when-cross-origin" @load="sendPayload" /></div>
    <p class="preview-status" role="status">{{ previewStatus }}</p>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { publicSiteUrl } from '@/utils/site'
import { presentationFields } from '../../../shared/presentation-contract.js'
import { http } from '@/utils/request'
const props = defineProps<{ kind: 'home' | 'work' | 'about' | 'join' | 'footer' | 'learning'; payload: Record<string, unknown>; publicPath?: string; title?: string }>()
const frame = ref<HTMLIFrameElement>()
const viewport = ref<HTMLElement>()
const availableWidth = ref(390)
const device = ref<'desktop' | 'mobile'>('mobile')
const view = ref('card')
const ready = ref(false)
const contentState = ref<'none' | 'checking' | 'verified' | 'unverified' | 'oversize'>('none')
const previewStatus = computed(() => {
  if (!ready.value) return '正在连接主站预览；请确认主站地址与服务已配置。'
  if (contentState.value === 'checking') return '已连接主站模板，正在按服务端规则校验富文本。'
  if (contentState.value === 'unverified') return '已连接主站模板；富文本暂用本地安全预览，服务端净化尚未核验，请保存后核对。'
  if (contentState.value === 'oversize') return '正文超过 20 万字符，预览仅展示上限内内容；请精简后再保存。'
  return contentState.value === 'verified' ? '已连接主站模板，富文本已按服务端保存规则净化。' : '已连接主站模板，表单变化会同步更新。'
})
const nonce = crypto.randomUUID?.() || Array.from(crypto.getRandomValues(new Uint32Array(4)), value => value.toString(16)).join('-')
let popup: Window | null = null
let observer: ResizeObserver | null = null
let resizeFrame = 0
let timer: ReturnType<typeof setTimeout>
let generation = 0
let sanitizeRequest: AbortController | null = null
let checkedSource: string | null = null
let checkedHtml = ''
const canvasWidth = computed(() => device.value === 'mobile' ? 390 : props.kind === 'work' && view.value === 'card' ? 540 : 1280)
const frameHeight = computed(() => props.kind === 'work' && view.value === 'card' ? 660 : 900)
const scale = computed(() => Math.min(1, availableWidth.value / canvasWidth.value))
const frameUrl = computed(() => {
  const url = new URL(publicSiteUrl('preview/' + props.kind))
  url.searchParams.set('parentOrigin', window.location.origin)
  url.searchParams.set('nonce', nonce)
  url.searchParams.set('view', props.kind === 'work' ? view.value : 'page')
  return url.href
})
async function sendPayload() {
  const currentGeneration = ++generation
  sanitizeRequest?.abort()
  const raw = JSON.parse(JSON.stringify(props.payload))
  const select = (value: any, keys: string[]) => Object.fromEntries(keys.filter(key => value && Object.prototype.hasOwnProperty.call(value, key)).map(key => [key, value[key]]))
  const payload: Record<string, unknown> = {}
  if (raw.presentation) payload.presentation = Object.fromEntries(Object.entries(presentationFields).filter(([key]) => raw.presentation[key]).map(([key, fields]) => [key, select(raw.presentation[key], fields)]))
  if (raw.work) payload.work = select(raw.work, ['title','slug','summary','coverUrl','content','tagType','category','authors','dateLabel','externalLink'])
  if (raw.about) payload.about = select(raw.about, ['eyebrow','title','subtitle','content','imageUrl','stats'])
  if (raw.join) payload.join = select(raw.join, ['eyebrow','title','subtitle','paragraphs','qrImage','qrCaption'])
  if (raw.footer) payload.footer = select(raw.footer, ['brandName','brandIntro','showParticles','linksTitle','links','socialTitle','socials','email','emailLabel','copyrightLeft','copyrightRight'])
  if (raw.resource) payload.resource = select(raw.resource, ['id','title','description','category','content','url','attachmentUrl','coverUrl','tags','updatedAt'])
  const rich = (payload.work || payload.resource) as Record<string, unknown> | undefined
  const source = typeof rich?.content === 'string' ? rich.content : ''
  contentState.value = 'none'
  if (rich && source) {
    if (source.length > 200000) contentState.value = 'oversize'
    else if (checkedSource === source) { rich.content = checkedHtml; contentState.value = 'verified' }
    else {
      contentState.value = 'checking'
      sanitizeRequest = new AbortController()
      try {
        const result = await http.post<{ html: string }>('/api/admin/presentation/preview-html', { html: source }, { silent: true, signal: sanitizeRequest.signal, timeout: 5000 })
        if (currentGeneration !== generation) return
        if (typeof result?.html !== 'string') throw new Error('Preview validation unavailable')
        checkedSource = source
        checkedHtml = result.html
        rich.content = result.html
        contentState.value = 'verified'
      } catch {
        if (currentGeneration !== generation) return
        contentState.value = 'unverified'
      }
    }
  }
  if (currentGeneration !== generation) return
  const message = { type: 'unicorn:preview:update', nonce, kind: props.kind, payload }
  const origin = new URL(frameUrl.value).origin
  frame.value?.contentWindow?.postMessage(message, origin)
  if (popup && !popup.closed) popup.postMessage(message, origin)
}
function onMessage(event: MessageEvent) {
  if (event.origin !== new URL(frameUrl.value).origin || (event.source !== frame.value?.contentWindow && event.source !== popup)) return
  if (event.data?.type !== 'unicorn:preview:ready' || event.data?.nonce !== nonce) return
  ready.value = true
  sendPayload()
}
function openPreview() { popup = window.open(frameUrl.value, '_blank'); }
watch(() => props.payload, () => { clearTimeout(timer); timer = setTimeout(sendPayload, 180) }, { deep: true })
watch(frameUrl, () => { ready.value = false })
onMounted(() => {
  window.addEventListener('message', onMessage)
  observer = new ResizeObserver(entries => {
    const width = Math.max(1, entries[0].contentRect.width)
    if (Math.abs(width - availableWidth.value) < 0.5) return
    cancelAnimationFrame(resizeFrame)
    resizeFrame = requestAnimationFrame(() => { availableWidth.value = width })
  })
  if (viewport.value) observer.observe(viewport.value)
})
onBeforeUnmount(() => { generation++; sanitizeRequest?.abort(); clearTimeout(timer); cancelAnimationFrame(resizeFrame); observer?.disconnect(); window.removeEventListener('message', onMessage) })
</script>
<style scoped>
.site-preview { border: 1px solid var(--el-border-color); border-radius: 10px; background: white; overflow: hidden; min-width: 0; }
.preview-heading { display: flex; align-items: start; justify-content: space-between; gap: 16px; padding: 18px; border-bottom: 1px solid var(--el-border-color-lighter); }
.preview-heading strong { display: block; color: var(--el-text-color-primary); font-size: 14px; }
.preview-heading span { display: block; font-size: 11px; color: var(--el-text-color-secondary); line-height: 1.6; margin-top: 6px; }
.preview-heading a { font-size: 12px; color: var(--el-text-color-primary); white-space: nowrap; text-decoration: none; min-height: 32px; padding-top: 3px; }
.preview-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 12px 16px; background: var(--el-fill-color-light); }
.preview-segment { display: inline-flex; border: 1px solid var(--el-border-color); border-radius: 5px; overflow: hidden; }
.preview-segment button, .preview-expand { min-height: 34px; border: 0; padding: 6px 12px; font-size: 12px; font-family: inherit; cursor: pointer; color: var(--el-text-color-regular); background: white; }
.preview-segment button[aria-pressed=true] { color: white; background: var(--brand-ink, #121316); }
.preview-expand { margin-left: auto; background: transparent; padding-inline: 0; }
.preview-viewport { overflow: hidden; position: relative; min-height: 200px; background: #e7e5df; }
.preview-viewport iframe { position: absolute; top: 0; left: 0; border: 0; transform-origin: top left; background: #f4f2ec; }
.preview-status { padding: 10px 16px; font-size: 11px; color: var(--el-text-color-secondary); line-height: 1.7; }
</style>
