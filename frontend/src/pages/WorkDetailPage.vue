<template>
  <div class="work-detail">
    <div v-if="loading" class="page-shell detail-state" aria-busy="true" role="status"><p class="eyebrow">OPENING THE ARCHIVE</p><div class="detail-loading" aria-hidden="true"><span></span><span></span></div><p>正在打开作品档案…</p></div>
    <div v-else-if="error" class="page-shell detail-state" role="status"><p class="eyebrow">ARCHIVE UNAVAILABLE</p><h1>暂时无法打开这件作品。</h1><p>{{ error }}</p><div class="detail-actions"><button type="button" class="button button--primary" @click="loadWork">重新加载</button><router-link to="/works" class="button button--secondary">返回作品档案</router-link></div></div>
    <template v-else-if="work">
      <section class="detail-intro">
        <div v-if="background" class="detail-intro__background"><BackgroundVideo v-if="background.mediaType === 'video'" :src="background.videoUrl" :poster="background.imageUrl" /><img v-else :src="background.imageUrl" alt="" /></div>
        <div class="page-shell detail-intro__inner">
          <router-link to="/works" custom v-slot="{ href, navigate }"><a :href="href" class="detail-back" @click="returnToArchive($event, navigate)"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M19 12H5m6-6-6 6 6 6" stroke="currentColor" stroke-width="1.5"/></svg>返回作品档案</a></router-link>
          <div class="detail-kicker"><p class="eyebrow">WORK IN FOCUS</p><div class="detail-tags"><span v-if="tag">{{ tag.label }}</span><span v-if="work.category && work.category !== tag?.label">{{ work.category }}</span></div></div>
          <h1>{{ work.title }}</h1><p v-if="work.summary" class="detail-summary">{{ work.summary }}</p>
          <dl class="detail-meta"><div v-if="work.authors"><dt>创作者</dt><dd>{{ work.authors }}</dd></div><div v-if="work.dateLabel"><dt>时间</dt><dd>{{ work.dateLabel }}</dd></div><div v-if="work.viewCount != null"><dt>阅读</dt><dd>{{ work.viewCount }} 次</dd></div></dl>
        </div>
      </section>
      <div class="page-shell detail-body">
        <figure v-if="work.coverUrl && !coverFailed" class="detail-cover"><img :src="work.coverUrl" :alt="work.title" width="1280" height="800" decoding="async" fetchpriority="high" @error="coverFailed = true" /></figure>
        <div class="detail-reading-grid"><aside class="detail-side"><span class="meta-label">THE MAKING OF</span><span class="detail-side__line"></span><p>每一次实践，<br />都让想法更进一步。</p></aside><article ref="articleContent" class="detail-article"><div v-if="work.content && isHtmlContent" class="rich-content" v-html="work.content"></div><div v-else-if="work.content" class="detail-plain">{{ work.content }}</div><div v-else class="detail-empty"><h2>故事还在继续。</h2><p>这件作品的过程记录正在整理，欢迎稍后再来看看。</p></div><a v-if="work.externalLink" :href="work.externalLink" target="_blank" rel="noopener noreferrer" class="button button--primary detail-source">查看原文 <span aria-hidden="true">↗</span><span class="sr-only">（在新窗口打开）</span></a></article></div>
        <div class="detail-ending"><span class="meta-label">THERE'S MORE TO EXPLORE</span><router-link to="/works" custom v-slot="{ href, navigate }"><a :href="href" @click="returnToArchive($event, navigate)">继续探索作品 <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" stroke="currentColor" stroke-width="1.5"/></svg></a></router-link></div>
      </div>
    </template>
  </div>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'
import { usePageBackground } from '@/composables/usePageBackground'
import { resolveWorkTag } from '@/constants/workTags'
import BackgroundVideo from '@/components/BackgroundVideo.vue'
const route = useRoute()
const router = useRouter()
const { get } = useApi()
const { background } = usePageBackground('work-detail')
const loading = ref(true)
const error = ref('')
const work = ref(null)
const coverFailed = ref(false)
const articleContent = ref(null)
let requestVersion = 0
const isHtmlContent = computed(() => /<(p|div|h[1-6]|ul|ol|li|strong|em|b|i|a|img|br|pre|code|blockquote|table|span|section)[\s/>]/i.test(work.value?.content || ''))
const tag = computed(() => resolveWorkTag(work.value?.tagType))
usePageMeta(() => ({ title: work.value?.title || '作品详情', description: work.value?.summary || '' }))
function returnToArchive(event, navigate) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  const previous = window.history.state?.back
  if (typeof previous === 'string' && previous.split('?')[0] === '/works') {
    event.preventDefault()
    router.back()
  } else {
    navigate(event)
  }
}
async function loadWork() {
  const version = ++requestVersion
  loading.value = true
  error.value = ''
  work.value = null
  coverFailed.value = false
  try {
    const result = await get(`/api/works/${encodeURIComponent(route.params.slug)}`)
    if (version !== requestVersion) return
    if (result.error) throw new Error(result.status === 404 ? '作品不存在或已下线' : result.message)
    work.value = result.data?.work || null
    if (!work.value) throw new Error('作品不存在或已下线')
  } catch (err) { if (version === requestVersion) error.value = err.message || '无法加载作品详情' }
  finally { if (version === requestVersion) loading.value = false }
}
// Keep article link hardening independent of the AI Markdown bundle.
function hardenContentLinks(element) {
  element?.querySelectorAll('a[href]').forEach(link => {
    if (link.getAttribute('href')?.startsWith('#')) return
    link.setAttribute('target', '_blank')
    link.setAttribute('rel', 'noopener noreferrer')
  })
}
watch([loading, work], async () => { await nextTick(); hardenContentLinks(articleContent.value) }, { flush: 'post' })
onMounted(loadWork)
watch(() => route.params.slug, loadWork)
onBeforeUnmount(() => { requestVersion++ })
</script>
<style scoped>
.work-detail {
  background: var(--color-paper,#f4f2ec);
  color: var(--color-ink,#121316);
  min-height: 80vh;
}
.detail-intro {
  position: relative;
  overflow: hidden;
  border-bottom: 1px solid #d8d6d0;
}
.detail-intro__inner {
  position: relative;
  padding-top: 38px;
  padding-bottom: 56px;
}
.detail-intro__background {
  position: absolute;
  inset: 0 0 0 auto;
  width: 45%;
  pointer-events: none;
}

.detail-intro__background :deep(img),
.detail-intro__background :deep(video) {
  opacity: .1;
  mask-image: linear-gradient(to right, transparent, #000);
}
.detail-intro__background :deep(.video-toggle) {
  pointer-events: auto;
  z-index: 3;
}
.detail-intro__background>img {
  height: 100%;
  width: 100%;
  object-fit: cover;
}
.detail-back {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  gap: 12px;
  font-size: 12px;
  color: var(--color-muted-light,#62636a);
  text-decoration: none;
}
.detail-back svg {
  width: 20px;
  height: 20px;
  transition: transform 180ms;
}
.detail-kicker {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 24px;
  margin-top: 54px;
}
.detail-kicker .eyebrow {
  margin: 0;
}
.detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  font-size: 12px;
  color: var(--color-muted-light,#62636a);
}
.detail-tags>span+span:before {
  content: '/';
  margin-right: 18px;
  color: #a7a69e;
}
.detail-intro h1 {
  max-width: 1050px;
  font-size: clamp(34px,4.7vw,66px);
  line-height: 1.35;
  letter-spacing: -.05em;
  font-weight: 650;
  margin: 27px 0 24px;
  overflow-wrap: anywhere;
}
.detail-summary {
  max-width: 740px;
  font-size: 18px;
  line-height: 1.9;
  color: var(--color-muted-light,#62636a);
  margin: 0;
}
.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 30px 60px;
  margin: 38px 0 0;
}
.detail-meta>div {
  min-width: 70px;
  max-width: 600px;
}
.detail-meta dt {
  font-size: 11px;
  color: var(--color-muted-light,#62636a);
  margin-bottom: 8px;
}
.detail-meta dd {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.detail-body {
  padding-top: 48px;
  padding-bottom: 72px;
}
.detail-cover {
  margin: 0 0 64px;
  border-radius: 4px;
  overflow: hidden;
  background: #e7e5df;
}
.detail-cover img {
  width: 100%;
  height: auto;
  max-height: 760px;
  object-fit: contain;
}
.detail-reading-grid {
  display: grid;
  grid-template-columns: minmax(150px,.75fr) minmax(0,2.4fr);
  gap: 70px;
}
.detail-side {
  padding-top: 8px;
}
.detail-side__line {
  display: block;
  width: 34px;
  height: 1px;
  background: var(--color-accent,#c92d3b);
  margin: 24px 0;
}
.detail-side p {
  font-size: 12px;
  line-height: 1.9;
  color: var(--color-muted-light,#62636a);
}
.detail-article {
  min-width: 0;
  max-width: 820px;
}
.detail-plain {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 17px;
  line-height: 1.9;
}
.detail-empty {
  padding: 30px 0 50px;
}
.detail-empty h2 {
  font-size: 28px;
  letter-spacing: -.04em;
  margin: 0 0 16px;
}
.detail-empty p {
  font-size: 16px;
  line-height: 1.9;
  color: var(--color-muted-light,#62636a);
}
.detail-source {
  margin-top: 36px;
}
.detail-ending {
  margin-top: 90px;
  padding-top: 30px;
  border-top: 1px solid #d8d6d0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 30px;
}
.detail-ending>a {
  display: flex;
  align-items: center;
  gap: 30px;
  text-decoration: none;
  color: var(--color-ink,#121316);
  font-size: clamp(23px,3vw,36px);
  font-weight: 600;
  letter-spacing: -.035em;
}
.detail-ending svg {
  width: 30px;
  height: 30px;
}
.detail-state {
  padding-top: 90px;
  padding-bottom: 110px;
  min-height: 65vh;
}
.detail-state h1 {
  font-size: clamp(28px,4vw,48px);
  line-height: 1.4;
  margin: 38px 0 22px;
  letter-spacing: -.04em;
}
.detail-state>p:last-of-type {
  color: var(--color-muted-light,#62636a);
  line-height: 1.8;
}
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 30px;
}
.detail-loading {
  margin: 45px 0 30px;
  max-width: 700px;
}
.detail-loading span {
  display: block;
  width: 90%;
  height: 34px;
  background: #dfddd7;
  margin: 16px 0;
  animation: detail-pulse 1.8s ease-in-out infinite;
}
.detail-loading span:last-child {
  width: 60%;
}
@keyframes detail-pulse {
  50% {
    opacity: .5;
  }
}
@media(hover:hover) {
  .detail-back:hover svg {
    transform: translateX(-4px);
  }
  .detail-ending>a:hover {
    color: var(--color-accent,#c92d3b);
  }
}
@media(max-width:767px) {
  .detail-intro__inner {
    padding-top: 24px;
    padding-bottom: 35px;
  }
  .detail-kicker {
    margin-top: 32px;
    flex-direction: column;
    gap: 16px;
  }
  .detail-intro h1 {
    margin-top: 22px;
    font-size: clamp(29px,7.5vw,45px);
  }
  .detail-summary {
    font-size: 16px;
  }
  .detail-meta {
    gap: 25px 35px;
    margin-top: 28px;
  }
  .detail-body {
    padding-top: 28px;
    padding-bottom: 48px;
  }
  .detail-cover {
    margin-bottom: 36px;
  }
  .detail-reading-grid {
    grid-template-columns: 1fr;
    gap: 20px;
  }
  .detail-side {
    display: none;
  }
  .detail-plain {
    font-size: 16px;
  }
  .detail-ending {
    margin-top: 55px;
    align-items: flex-start;
    flex-direction: column;
    gap: 18px;
  }
  .detail-ending>a {
    width: 100%;
    justify-content: space-between;
    gap: 16px;
  }
  .detail-state {
    padding-top: 55px;
    padding-bottom: 70px;
  }
  .detail-intro__background {
    width: 80%;
  }
}
@media(prefers-reduced-motion:reduce) {
  .detail-loading span {
    animation: none;
  }
  .detail-back svg {
    transition: none;
  }
}
</style>
