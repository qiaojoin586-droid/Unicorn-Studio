<template>
  <div class="learning-page">
    <section class="page-shell learning-intro">
      <p class="eyebrow">THE SHARED LIBRARY <span>/ 03</span></p>
      <div class="learning-intro__row"><h1>知识，<br />因分享而生<span>。</span></h1><div class="learning-intro__aside"><span class="library-symbol" aria-hidden="true"><i></i><i></i><i></i></span><p>把有用的发现，留给下一位探索者。<br />精选教程、手册与学习笔记，从这里继续。</p></div></div>
    </section>
    <section class="page-shell library-content" aria-label="学习资料" :aria-busy="loading">
      <div class="library-toolbar"><div class="library-filters" role="group" aria-label="筛选学习资料分类"><button type="button" :class="{ 'is-active': !selectedCategory }" :aria-pressed="!selectedCategory" @click="setCategory('')">全部资料</button><button v-for="category in categories" :key="category" type="button" :class="{ 'is-active': selectedCategory === category }" :aria-pressed="selectedCategory === category" @click="setCategory(category)">{{ category }}</button></div><p role="status">{{ loading ? '正在整理书架' : error ? '连接暂时中断' : `${filteredResources.length} 份资料` }}</p></div>
      <div v-if="loading" class="resource-grid" aria-label="正在加载学习资料"><div v-for="i in 4" :key="i" class="resource-skeleton" aria-hidden="true"><span></span><span></span><span></span></div></div>
      <div v-else-if="error" class="state-panel library-state" role="status"><span class="meta-label">CONNECTION PAUSED</span><h2>书架暂时无法打开。</h2><p>{{ error }}</p><button type="button" class="button button--primary" @click="loadResources">重新加载 <span aria-hidden="true">↗</span></button></div>
      <div v-else-if="filteredResources.length" class="resource-grid">
        <router-link v-for="(item, index) in filteredResources" :key="item.id" :to="`/learning/${item.id}`" class="resource-card">
          <div class="resource-card__top"><span class="resource-number">{{ String(index + 1).padStart(2, '0') }}</span><span class="resource-category">{{ item.category || '学习资料' }}</span></div>
          <h2>{{ item.title }}</h2><p v-if="item.description">{{ item.description }}</p><span class="resource-card__bottom">阅读资料 <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" stroke="currentColor" stroke-width="1.5"/></svg></span>
        </router-link>
      </div>
      <div v-else class="state-panel library-state"><span class="meta-label">LEARNING NEVER STOPS</span><h2>{{ selectedCategory ? '这个书架，等待下一次分享。' : '新的知识，正在整理。' }}</h2><p>{{ selectedCategory ? '可以先看看其他分类的资料。' : '欢迎去作品中看看，我们如何把学习带进真实项目。' }}</p><button v-if="selectedCategory" type="button" class="button button--secondary" @click="setCategory('')">查看全部资料</button><router-link v-else to="/works" class="button button--secondary">探索作品 <span aria-hidden="true">↗</span></router-link></div>
      <div class="library-end"><span>STAY CURIOUS. KEEP SHARING.</span><router-link to="/join">和我们一起学习 <span aria-hidden="true">↗</span></router-link></div>
    </section>
  </div>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'
usePageMeta(() => ({ title: '学习资料', description: '独角兽工作室分享的教程、手册与学习资料，把有用的发现留给下一位探索者。' }))
const { get } = useApi()
const route = useRoute()
const router = useRouter()
const loading = ref(true)
const error = ref('')
const resources = ref([])
const selectedCategory = computed(() => typeof route.query.category === 'string' ? route.query.category : '')
const categories = computed(() => [...new Set(resources.value.map(item => item.category).filter(Boolean))])
const filteredResources = computed(() => selectedCategory.value ? resources.value.filter(item => item.category === selectedCategory.value) : resources.value)
function setCategory(category) {
  const query = { ...route.query }
  if (category) query.category = category
  else delete query.category
  router.replace({ query })
}
async function loadResources() {
  loading.value = true
  error.value = ''
  try {
    const result = await get('/api/learning/resources')
    if (result.error) throw new Error(result.message)
    const payload = result.data || result
    resources.value = Array.isArray(payload.resources) ? payload.resources : []
  } catch (err) { error.value = err.message || '无法连接学习资料服务' }
  finally { loading.value = false }
}
onMounted(loadResources)
</script>
<style scoped>
.learning-page {
  min-height: 80vh;
  background: var(--color-paper,#f4f2ec);
  color: var(--color-ink,#121316);
}
.learning-intro {
  padding-top: 80px;
  padding-bottom: 74px;
}
.learning-intro .eyebrow {
  display: flex;
  justify-content: space-between;
  gap: 20px;
}
.learning-intro__row {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 60px;
  align-items: end;
  margin-top: 46px;
}
.learning-intro h1 {
  margin: 0;
  font-size: clamp(52px,6vw,86px);
  font-weight: 650;
  line-height: 1.22;
  letter-spacing: -.065em;
}
.learning-intro h1>span {
  color: var(--color-accent,#c92d3b);
}
.learning-intro__aside {
  justify-self: end;
  max-width: 350px;
}
.learning-intro__aside p {
  font-size: 15px;
  line-height: 1.95;
  color: var(--color-muted-light,#62636a);
  margin: 25px 0 0;
}
.library-symbol {
  display: flex;
  gap: 6px;
  align-items: flex-end;
  height: 42px;
}
.library-symbol i {
  display: block;
  height: 32px;
  width: 11px;
  border: 1px solid #97978f;
}
.library-symbol i:nth-child(2) {
  height: 42px;
}
.library-symbol i:last-child {
  transform: rotate(-18deg);
  transform-origin: bottom;
  background: var(--color-accent,#c92d3b);
  border-color: var(--color-accent,#c92d3b);
}
.library-content {
  padding-bottom: 70px;
}
.library-toolbar {
  border-top: 1px solid #d8d6d0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  padding-top: 22px;
  margin-bottom: 30px;
}
.library-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 22px;
}
.library-filters button {
  min-width: 44px;
  min-height: 44px;
  padding: 8px 0;
  border: 0;
  border-bottom: 1px solid transparent;
  background: none;
  font-size: 13px;
  color: #6a6b70;
  cursor: pointer;
  transition: color 180ms,border-color 180ms;
}
.library-filters button.is-active {
  color: var(--color-ink,#121316);
  border-bottom-color: var(--color-accent,#c92d3b);
}
.library-toolbar>p {
  margin: 0;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--color-muted-light,#62636a);
}
.resource-grid {
  display: grid;
  grid-template-columns: repeat(2,minmax(0,1fr));
  gap: 24px;
}
.resource-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 300px;
  padding: 32px;
  border: 1px solid #d8d6d0;
  border-radius: 4px;
  text-decoration: none;
  color: var(--color-ink,#121316);
  transition: background 220ms,border-color 220ms;
}
.resource-card__top {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 38px;
}
.resource-number {
  font: 12px ui-monospace,monospace;
  color: #7b7b73;
}
.resource-category {
  font-size: 12px;
  color: var(--color-muted-light,#62636a);
}
.resource-card h2 {
  margin: 0 0 16px;
  font-size: clamp(21px,2vw,28px);
  line-height: 1.5;
  font-weight: 600;
  letter-spacing: -.035em;
  overflow-wrap: anywhere;
}
.resource-card>p {
  margin: 0 0 30px;
  color: var(--color-muted-light,#62636a);
  font-size: 14px;
  line-height: 1.8;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.resource-card__bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  padding-top: 22px;
  margin-top: auto;
  font-size: 12px;
}
.resource-card__bottom svg {
  width: 23px;
  height: 23px;
  transition: transform 220ms;
}
.resource-card:focus-visible {
  outline: 2px solid var(--color-accent,#c92d3b);
  outline-offset: 5px;
}
.resource-card:focus-visible .resource-card__bottom {
  color: var(--color-accent,#c92d3b);
}
.library-state {
  min-height: 330px;
  padding-block: 70px;
}
.library-state h2 {
  margin: 20px 0 16px;
  font-size: clamp(25px,3vw,36px);
  font-weight: 600;
  letter-spacing: -.04em;
}
.library-state p {
  color: var(--color-muted-light,#62636a);
  line-height: 1.8;
  margin-bottom: 28px;
}
.resource-skeleton {
  min-height: 300px;
  border: 1px solid #d8d6d0;
  border-radius: 4px;
  padding: 32px;
  animation: library-pulse 1.8s ease-in-out infinite;
}
.resource-skeleton>span {
  display: block;
  height: 12px;
  width: 23%;
  background: #dfddd7;
  margin-bottom: 45px;
}
.resource-skeleton>span:nth-child(2) {
  width: 75%;
  height: 20px;
  margin-bottom: 20px;
}
.resource-skeleton>span:last-child {
  width: 90%;
  margin-bottom: 0;
}
.library-end {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  border-top: 1px solid #d8d6d0;
  margin-top: 70px;
  padding-top: 26px;
}
.library-end>span {
  font: 10px ui-monospace,monospace;
  letter-spacing: .1em;
  color: #6a6b70;
}
.library-end a {
  font-size: 14px;
  color: var(--color-ink,#121316);
  text-decoration: none;
}
.library-end a>span {
  margin-left: 15px;
}
@keyframes library-pulse {
  50% {
    opacity: .5;
  }
}
@media(hover:hover) {
  .resource-card:hover {
    background: #eceae4;
    border-color: #aaa79e;
  }
  .resource-card:hover .resource-card__bottom {
    color: var(--color-accent,#c92d3b);
  }
  .resource-card:hover .resource-card__bottom svg {
    transform: translateX(4px);
  }
}
@media(max-width:767px) {
  .learning-intro {
    padding-top: 48px;
    padding-bottom: 44px;
  }
  .learning-intro__row {
    grid-template-columns: 1fr;
    gap: 28px;
    margin-top: 35px;
  }
  .learning-intro h1 {
    font-size: clamp(43px,9.5vw,66px);
  }
  .learning-intro__aside {
    justify-self: start;
    max-width: none;
  }
  .library-symbol {
    display: none;
  }
  .learning-intro__aside p {
    font-size: 14px;
    margin-top: 0;
  }
  .library-toolbar {
    align-items: flex-start;
    flex-direction: column;
    gap: 16px;
  }
  .resource-grid {
    grid-template-columns: 1fr;
    gap: 18px;
  }
  .resource-card {
    padding: 25px;
    min-height: 270px;
  }
  .resource-card__top {
    margin-bottom: 28px;
  }
  .library-end {
    margin-top: 48px;
    flex-direction: column;
    gap: 18px;
  }
  .library-content {
    padding-bottom: 48px;
  }
  .resource-skeleton {
    min-height: 270px;
    padding: 25px;
  }
}
@media(prefers-reduced-motion:reduce) {
  .resource-card,.resource-card__bottom svg {
    transition: none;
  }
  .resource-skeleton {
    animation: none;
  }
}
</style>
