<template>
  <div class="learning-detail">
    <div v-if="loading" class="page-shell reading-state" aria-busy="true" role="status"><p class="eyebrow">OPENING THE LIBRARY</p><div class="reading-loading" aria-hidden="true"><span></span><span></span></div><p>正在打开学习资料…</p></div>
    <div v-else-if="error" class="page-shell reading-state" role="status"><p class="eyebrow">RESOURCE UNAVAILABLE</p><h1>暂时无法打开这份资料。</h1><p>{{ error }}</p><div class="reading-actions"><button type="button" class="button button--primary" @click="loadResource">重新加载</button><router-link to="/learning" class="button button--secondary">返回学习资料</router-link></div></div>
    <template v-else-if="resource">
      <section class="page-shell reading-intro">
        <router-link to="/learning" custom v-slot="{ href, navigate }"><a :href="href" class="reading-back" @click="returnToLibrary($event, navigate)"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M19 12H5m6-6-6 6 6 6" stroke="currentColor" stroke-width="1.5"/></svg>返回学习资料</a></router-link>
        <div class="reading-kicker"><p class="eyebrow">NOTES WORTH SHARING</p><span v-if="resource.category" class="reading-category">{{ resource.category }}</span></div>
        <h1>{{ resource.title }}</h1><p v-if="resource.description" class="reading-description">{{ resource.description }}</p>
        <div v-if="updatedLabel || tags.length" class="reading-meta"><span v-if="updatedLabel">{{ updatedLabel }}</span><span v-for="tag in tags" :key="tag" class="reading-tag">{{ tag }}</span></div>
      </section>
      <div class="page-shell reading-body">
        <figure v-if="resource.coverUrl && !coverFailed" class="reading-cover"><img :src="resource.coverUrl" :alt="resource.title" width="1280" height="800" decoding="async" fetchpriority="high" @error="coverFailed = true" /></figure>
        <div class="reading-grid"><aside class="reading-side"><div class="reading-side__inner"><span class="meta-label">ON THIS PAGE</span><nav v-if="contents.length" aria-label="本页目录"><a v-for="item in contents" :key="item.id" :href="`#${encodeURIComponent(item.id)}`" :class="{ 'is-subheading': item.level === 'H3' }">{{ item.text }}</a></nav><p v-else>保持好奇，<br />让每次阅读都有收获。</p></div></aside><article class="reading-article"><div ref="articleContent"><div v-if="resource.content && isHtmlContent" class="rich-content" v-html="resource.content"></div><div v-else-if="resource.content" class="reading-plain">{{ resource.content }}</div><div v-else class="reading-empty"><h2>更多内容，正在整理。</h2><p>可以先访问下方原文或附件，继续你的探索。</p></div></div><div v-if="resource.url || resource.attachmentUrl" class="reading-sources"><p class="meta-label">CONTINUE READING</p><div class="reading-actions"><a v-if="resource.url" :href="resource.url" target="_blank" rel="noopener noreferrer" class="button button--primary">打开原文 <span aria-hidden="true">↗</span><span class="sr-only">（在新窗口打开）</span></a><a v-if="resource.attachmentUrl" :href="resource.attachmentUrl" target="_blank" rel="noopener noreferrer" class="button button--secondary">下载附件 <span aria-hidden="true">↓</span><span class="sr-only">（在新窗口打开）</span></a></div></div></article></div>
        <div class="reading-ending"><span class="meta-label">KEEP YOUR CURIOSITY ALIVE</span><router-link to="/learning" custom v-slot="{ href, navigate }"><a :href="href" @click="returnToLibrary($event, navigate)">发现更多学习资料 <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" stroke="currentColor" stroke-width="1.5"/></svg></a></router-link></div>
      </div>
    </template>
  </div>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'
const route = useRoute()
const router = useRouter()
const { get } = useApi()
const loading = ref(true)
const error = ref('')
const resource = ref(null)
const coverFailed = ref(false)
const articleContent = ref(null)
const contents = ref([])
let requestVersion = 0
usePageMeta(() => ({ title: resource.value?.title || '学习资料详情', description: resource.value?.description || '' }))
const tags = computed(() => typeof resource.value?.tags === 'string' ? resource.value.tags.split(',').map(tag => tag.trim()).filter(Boolean) : [])
const isHtmlContent = computed(() => /<(p|div|h[1-6]|ul|ol|li|strong|em|b|i|a|img|br|pre|code|blockquote|table|span|section)[\s/>]/i.test(resource.value?.content || ''))
const updatedLabel = computed(() => {
  if (!resource.value?.updatedAt) return ''
  const date = new Date(resource.value.updatedAt)
  return Number.isNaN(date.getTime()) ? '' : `更新于 ${date.toLocaleDateString('zh-CN')}`
})
function returnToLibrary(event, navigate) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  const previous = window.history.state?.back
  if (typeof previous === 'string' && previous.split('?')[0] === '/learning') {
    event.preventDefault()
    router.back()
  } else {
    navigate(event)
  }
}
async function loadResource() {
  const version = ++requestVersion
  loading.value = true
  error.value = ''
  resource.value = null
  coverFailed.value = false
  contents.value = []
  try {
    const result = await get(`/api/learning/resources/${encodeURIComponent(route.params.id)}`)
    if (version !== requestVersion) return
    if (result.error) throw new Error(result.status === 404 ? '学习资料不存在或已下线' : result.message)
    resource.value = result.data?.resource || null
    if (!resource.value) throw new Error('学习资料不存在或已下线')
  } catch (err) { if (version === requestVersion) error.value = err.message || '无法加载学习资料详情' }
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
watch([loading, resource], async () => {
  await nextTick()
  hardenContentLinks(articleContent.value)
  contents.value = [...(articleContent.value?.querySelectorAll('.rich-content h2, .rich-content h3') || [])].map((heading, index) => {
    if (!heading.id) heading.id = `learning-${route.params.id}-section-${index + 1}`
    return { id: heading.id, text: heading.textContent?.trim() || '', level: heading.tagName }
  }).filter(item => item.text)
}, { flush: 'post' })
onMounted(loadResource)
watch(() => route.params.id, loadResource)
onBeforeUnmount(() => { requestVersion++ })
</script>
<style scoped>
.learning-detail {
  background: var(--color-paper,#f4f2ec);
  color: var(--color-ink,#121316);
  min-height: 80vh;
}
.reading-intro {
  padding-top: 38px;
  padding-bottom: 50px;
  border-bottom: 1px solid #d8d6d0;
}
.reading-back {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  gap: 12px;
  font-size: 12px;
  color: var(--color-muted-light,#62636a);
  text-decoration: none;
}
.reading-back svg {
  width: 20px;
  height: 20px;
  transition: transform 180ms;
}
.reading-kicker {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 24px;
  margin-top: 54px;
}
.reading-kicker .eyebrow {
  margin: 0;
}
.reading-category {
  font-size: 12px;
  color: var(--color-muted-light,#62636a);
}
.reading-intro h1 {
  font-size: clamp(34px,4.5vw,64px);
  line-height: 1.4;
  letter-spacing: -.05em;
  font-weight: 650;
  margin: 27px 0 24px;
  max-width: 1050px;
  overflow-wrap: anywhere;
}
.reading-description {
  max-width: 760px;
  font-size: 18px;
  line-height: 1.9;
  color: var(--color-muted-light,#62636a);
  margin: 0;
}
.reading-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 18px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--color-muted-light,#62636a);
  margin-top: 30px;
}
.reading-tag {
  padding: 4px 10px;
  border: 1px solid #d8d6d0;
  border-radius: 3px;
}
.reading-body {
  padding-top: 48px;
  padding-bottom: 72px;
}
.reading-cover {
  margin: 0 0 64px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--surface-soft, #e7e5df);
}
.reading-cover img {
  width: 100%;
  height: auto;
  max-height: 700px;
  object-fit: contain;
}
.reading-grid {
  display: grid;
  grid-template-columns: minmax(160px,.75fr) minmax(0,2.4fr);
  gap: 70px;
}
.reading-side__inner {
  position: sticky;
  top: calc(var(--header-height,80px) + 36px);
  padding-top: 8px;
}
.reading-side nav {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin-top: 24px;
  border-left: 1px solid #d8d6d0;
  padding-left: 18px;
  max-height: 65vh;
  overflow: auto;
  overscroll-behavior: contain;
}
.reading-side nav>a {
  display: block;
  padding: 9px 0;
  color: var(--color-muted-light,#62636a);
  font-size: 12px;
  line-height: 1.6;
  text-decoration: none;
  overflow-wrap: anywhere;
}
.reading-side nav>a.is-subheading {
  padding-left: 12px;
  font-size: 11px;
}
.reading-side p {
  font-size: 12px;
  line-height: 1.9;
  color: var(--color-muted-light,#62636a);
  margin-top: 24px;
}
.reading-article {
  min-width: 0;
  max-width: 820px;
}
.reading-plain {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 17px;
  line-height: 1.9;
}
.reading-empty {
  padding: 30px 0 40px;
}
.reading-empty h2 {
  font-size: 28px;
  letter-spacing: -.04em;
  margin: 0 0 16px;
}
.reading-empty p {
  font-size: 16px;
  line-height: 1.9;
  color: var(--color-muted-light,#62636a);
}
.reading-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 24px;
}
.reading-sources {
  margin-top: 50px;
  border-top: 1px solid #d8d6d0;
  padding-top: 28px;
}
.reading-sources .meta-label {
  margin: 0;
}
.reading-ending {
  margin-top: 90px;
  padding-top: 30px;
  border-top: 1px solid #d8d6d0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 30px;
}
.reading-ending>a {
  display: flex;
  align-items: center;
  gap: 30px;
  text-decoration: none;
  color: var(--color-ink,#121316);
  font-size: clamp(23px,3vw,36px);
  font-weight: 600;
  letter-spacing: -.035em;
}
.reading-ending svg {
  width: 30px;
  height: 30px;
}
.reading-state {
  padding-top: 90px;
  padding-bottom: 110px;
  min-height: 65vh;
}
.reading-state h1 {
  font-size: clamp(28px,4vw,48px);
  line-height: 1.4;
  margin: 38px 0 22px;
  letter-spacing: -.04em;
}
.reading-state>p:last-of-type {
  color: var(--color-muted-light,#62636a);
  line-height: 1.8;
}
.reading-loading {
  margin: 45px 0 30px;
  max-width: 700px;
}
.reading-loading span {
  display: block;
  width: 90%;
  height: 34px;
  background: var(--surface-soft, #dfddd7);
  margin: 16px 0;
  animation: reading-pulse 1.8s ease-in-out infinite;
}
.reading-loading span:last-child {
  width: 60%;
}
:deep(.rich-content h2),:deep(.rich-content h3) {
  scroll-margin-top: calc(var(--header-height,80px) + 28px);
}
@keyframes reading-pulse {
  50% {
    opacity: .5;
  }
}
@media(hover:hover) {
  .reading-back:hover svg {
    transform: translateX(-4px);
  }
  .reading-ending>a:hover,.reading-side nav>a:hover {
    color: var(--color-accent,#c92d3b);
  }
}
@media(max-width:767px) {
  .reading-intro {
    padding-top: 24px;
    padding-bottom: 35px;
  }
  .reading-kicker {
    margin-top: 32px;
    flex-direction: column;
    gap: 14px;
  }
  .reading-intro h1 {
    font-size: clamp(29px,7.5vw,45px);
    margin-top: 22px;
  }
  .reading-description {
    font-size: 16px;
  }
  .reading-meta {
    margin-top: 23px;
    gap: 9px 12px;
  }
  .reading-body {
    padding-top: 28px;
    padding-bottom: 48px;
  }
  .reading-cover {
    margin-bottom: 36px;
  }
  .reading-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }
  .reading-side {
    display: none;
  }
  .reading-plain {
    font-size: 16px;
  }
  .reading-ending {
    margin-top: 55px;
    align-items: flex-start;
    flex-direction: column;
    gap: 18px;
  }
  .reading-ending>a {
    width: 100%;
    justify-content: space-between;
    gap: 16px;
  }
  .reading-state {
    padding-top: 55px;
    padding-bottom: 70px;
  }
  .reading-sources {
    margin-top: 35px;
  }
}
@media(prefers-reduced-motion:reduce) {
  .reading-loading span {
    animation: none;
  }
  .reading-back svg {
    transition: none;
  }
}
</style>
