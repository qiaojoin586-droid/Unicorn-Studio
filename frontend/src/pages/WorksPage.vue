<template>
  <div class="works-page">
    <section class="archive-intro">
      <div v-if="background" class="archive-intro__image"><BackgroundVideo v-if="background.mediaType === 'video'" :src="background.videoUrl" :poster="background.imageUrl" /><img v-else :src="background.imageUrl" alt="" decoding="async" /></div>
      <div class="page-shell archive-intro__inner">
        <p class="eyebrow">THE WORK ARCHIVE <span>/ 01</span></p>
        <div class="archive-intro__row"><h1>想法的<br />另一种形状<span>。</span></h1><div class="archive-intro__aside"><span class="archive-index" aria-hidden="true">( U—W )</span><p>从一次探索，到一件作品。<br />在这里，看到技术与好奇心的落点。</p></div></div>
      </div>
    </section>
    <section class="page-shell archive-content" aria-label="作品档案" :aria-busy="loading">
      <div class="archive-toolbar">
        <div class="archive-filters" role="group" aria-label="筛选作品分类">
          <button type="button" :aria-pressed="!selectedType" :class="{ 'is-active': !selectedType }" @click="setType('')">全部作品<span v-if="!loading && !error">{{ works.length }}</span></button>
          <button v-for="type in availableTypes" :key="type.key" type="button" :aria-pressed="selectedType === type.key" :class="{ 'is-active': selectedType === type.key }" @click="setType(type.key)">{{ type.label }}</button>
        </div>
        <p class="archive-result" role="status">{{ loading ? '正在整理档案' : error ? '暂时无法获取档案' : `${filteredWorks.length} 件作品` }}</p>
      </div>
      <div v-if="loading" class="works-grid" aria-label="正在加载作品"><div v-for="i in 4" :key="i" class="archive-skeleton" aria-hidden="true"><div></div><span></span><span></span></div></div>
      <div v-else-if="error" class="state-panel archive-state" role="status"><span class="meta-label">CONNECTION PAUSED</span><h2>作品暂时没有到达。</h2><p>{{ error }}</p><button type="button" class="button button--primary" @click="loadWorks">重新加载 <span aria-hidden="true">↗</span></button></div>
      <div v-else-if="filteredWorks.length" class="works-grid"><WorkCard v-for="work in filteredWorks" :key="work.slug" :slug="work.slug" :title="work.title" :description="work.summary" :cover-url="work.coverUrl" :tag-type="work.tagType" :authors="work.authors" :date="work.dateLabel" /></div>
      <div v-else class="state-panel archive-state"><span class="meta-label">ROOM FOR WHAT'S NEXT</span><h2>{{ selectedType ? '这个分类还在等待新作品。' : '下一件作品，正在发生。' }}</h2><p>{{ selectedType ? '试试其他分类，看看我们已经走过的探索。' : '我们会在这里记录新的项目、研究和成果。' }}</p><button v-if="selectedType" type="button" class="button button--secondary" @click="setType('')">查看全部作品</button><router-link v-else to="/about" class="button button--secondary">先认识我们 <span aria-hidden="true">↗</span></router-link></div>
      <div class="archive-end"><span>EXPERIMENT. MAKE. REPEAT.</span><router-link to="/join">一起做出下一件作品 <span aria-hidden="true">↗</span></router-link></div>
    </section>
  </div>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkCard from '@/components/WorkCard.vue'
import BackgroundVideo from '@/components/BackgroundVideo.vue'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'
import { usePageBackground } from '@/composables/usePageBackground'
import { resolveWorkTag } from '@/constants/workTags'
usePageMeta(() => ({ title: '作品档案', description: '项目、竞赛与研究，记录独角兽工作室将想法变成作品的过程。' }))
const { get } = useApi()
const { background } = usePageBackground('works')
const route = useRoute()
const router = useRouter()
const loading = ref(true)
const error = ref('')
const works = ref([])
const selectedType = computed(() => typeof route.query.type === 'string' ? route.query.type : '')
const availableTypes = computed(() => [...new Set(works.value.map(work => work.tagType).filter(Boolean))].map(key => ({ key, label: resolveWorkTag(key)?.label || key })))
const filteredWorks = computed(() => selectedType.value ? works.value.filter(work => work.tagType === selectedType.value) : works.value)
function setType(type) {
  const query = { ...route.query }
  if (type) query.type = type
  else delete query.type
  router.replace({ query })
}
async function loadWorks() {
  loading.value = true
  error.value = ''
  try {
    // The public endpoint returns the complete published list; filter the whole list.
    const result = await get('/api/works')
    if (result.error) throw new Error(result.message)
    works.value = Array.isArray(result.data?.list) ? result.data.list : []
  } catch (err) { error.value = err.message || '无法加载作品列表' }
  finally { loading.value = false }
}
onMounted(loadWorks)
</script>
<style scoped>
.works-page {
  background: var(--color-paper,#f4f2ec);
  color: var(--color-ink,#121316);
  min-height: 80vh;
}
.archive-intro {
  position: relative;
  overflow: hidden;
  border-bottom: 1px solid #d8d6d0;
}
.archive-intro__inner {
  position: relative;
  padding-top: 80px;
  padding-bottom: 78px;
}
.archive-intro__image {
  position: absolute;
  right: 0;
  top: 0;
  width: 45%;
  height: 100%;
  pointer-events: none;
}

.archive-intro__image :deep(img),
.archive-intro__image :deep(video) {
  opacity: .12;
  mask-image: linear-gradient(to right, transparent, #000);
}
.archive-intro__image :deep(.video-toggle) {
  pointer-events: auto;
  z-index: 3;
}
.archive-intro__image>img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.archive-intro .eyebrow {
  display: flex;
  justify-content: space-between;
  gap: 20px;
}
.archive-intro__row {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 60px;
  align-items: end;
  margin-top: 46px;
}
.archive-intro h1 {
  margin: 0;
  font-size: clamp(52px,6.3vw,90px);
  font-weight: 650;
  line-height: 1.2;
  letter-spacing: -.065em;
}
.archive-intro h1>span {
  color: var(--color-accent,#c92d3b);
}
.archive-intro__aside {
  justify-self: end;
  max-width: 310px;
  padding-bottom: 6px;
}
.archive-index {
  display: block;
  margin-bottom: 40px;
  color: var(--text-on-paper, #77766f);
  font: 12px ui-monospace,monospace;
  letter-spacing: .1em;
}
.archive-intro__aside p {
  font-size: 16px;
  line-height: 1.95;
  color: var(--color-muted-light,#62636a);
  margin: 0;
}
.archive-content {
  padding-top: 38px;
  padding-bottom: 70px;
}
.archive-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin-bottom: 42px;
}
.archive-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 5px 20px;
}
.archive-filters button {
  min-width: 44px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 44px;
  background: transparent;
  border: 0;
  padding: 8px 0;
  color: var(--text-on-paper, #6a6b70);
  font-size: 14px;
  cursor: pointer;
  border-bottom: 1px solid transparent;
  transition: color 180ms,border-color 180ms;
}
.archive-filters button.is-active {
  color: var(--color-ink,#121316);
  border-bottom-color: var(--color-accent,#c92d3b);
}
.archive-filters button span {
  font: 10px ui-monospace,monospace;
  color: var(--text-on-paper, #6a6b70);
  align-self: flex-start;
  padding-top: 2px;
}
.archive-result {
  margin: 0;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--color-muted-light,#62636a);
}
.works-grid {
  display: grid;
  grid-template-columns: repeat(2,minmax(0,1fr));
  gap: 62px 36px;
}
.archive-state {
  padding-block: 80px;
  min-height: 360px;
}
.archive-state h2 {
  margin: 20px 0 16px;
  font-size: clamp(25px,3vw,36px);
  font-weight: 600;
  letter-spacing: -.04em;
}
.archive-state p {
  margin-bottom: 28px;
  color: var(--color-muted-light,#62636a);
  line-height: 1.8;
}
.archive-skeleton>div {
  aspect-ratio: 16/10;
  background: var(--surface-soft, #e2e0da);
  border-radius: 4px;
  animation: archive-pulse 1.8s ease-in-out infinite;
}
.archive-skeleton>span {
  display: block;
  height: 13px;
  width: 60%;
  background: var(--surface-soft, #e2e0da);
  margin-top: 22px;
}
.archive-skeleton>span:last-child {
  width: 40%;
  height: 9px;
  margin-top: 16px;
}
.archive-end {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  border-top: 1px solid #d8d6d0;
  margin-top: 86px;
  padding-top: 26px;
}
.archive-end>span {
  font: 10px ui-monospace,monospace;
  letter-spacing: .1em;
  color: var(--text-on-paper, #6a6b70);
}
.archive-end a {
  font-size: 14px;
  color: var(--color-ink,#121316);
  text-decoration: none;
}
.archive-end a>span {
  display: inline-block;
  margin-left: 15px;
}
@keyframes archive-pulse {
  50% {
    opacity: .5;
  }
}
@media(max-width:767px) {
  .archive-intro__inner {
    padding-top: 48px;
    padding-bottom: 48px;
  }
  .archive-intro__row {
    grid-template-columns: 1fr;
    gap: 28px;
    margin-top: 35px;
  }
  .archive-intro h1 {
    font-size: clamp(45px,10vw,66px);
  }
  .archive-intro__aside {
    justify-self: start;
    max-width: none;
  }
  .archive-index {
    display: none;
  }
  .archive-intro__aside p {
    font-size: 14px;
  }
  .archive-content {
    padding-top: 22px;
    padding-bottom: 48px;
  }
  .archive-toolbar {
    align-items: flex-start;
    gap: 16px;
    flex-direction: column;
    margin-bottom: 28px;
  }
  .archive-filters {
    gap: 4px 18px;
  }
  .archive-filters button {
    font-size: 13px;
  }
  .works-grid {
    grid-template-columns: 1fr;
    gap: 38px;
  }
  .archive-end {
    margin-top: 52px;
    flex-direction: column;
    gap: 18px;
  }
  .archive-intro__image {
    width: 75%;
  }
}
@media(prefers-reduced-motion:reduce) {
  .archive-skeleton>div {
    animation: none;
  }
}
</style>
