<template>
  <div class="home-page">
    <section class="hero" aria-labelledby="home-title">
      <div class="page-shell hero__shell">
        <div class="hero__topline">
          <p v-if="home.eyebrow" class="eyebrow"><span class="signal-dot" aria-hidden="true"></span>{{ home.eyebrow }}</p>
          <span class="hero__edition" aria-hidden="true">CURIOUS MINDS. REAL THINGS.</span>
        </div>
        <div class="hero__grid">
          <div class="hero__copy">
            <h1 id="home-title" class="configured-lines">{{ home.title }}<span class="hero__period" aria-hidden="true"></span></h1>
            <p v-if="home.description" class="hero__description configured-lines">{{ home.description }}</p>
            <div class="hero__actions">
              <SiteLink v-if="home.primaryLabel && home.primaryUrl" :to="home.primaryUrl" class="button button--primary hero__primary">{{ home.primaryLabel }}<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" /></svg></SiteLink>
              <SiteLink v-if="home.secondaryLabel && home.secondaryUrl" :to="home.secondaryUrl" class="hero__secondary">{{ home.secondaryLabel }} <span aria-hidden="true">↗</span></SiteLink>
            </div>
            <div class="hero__disciplines" aria-label="探索领域"><span>AI 智能应用</span><span>软件开发</span><span>学术研究</span></div>
          </div>
          <div class="hero-visual-wrap">
            <BrandVisual :show-annotation="!recommendation" />
            <HomeRecommendation v-if="recommendation" :item="recommendation" />
          </div>
        </div>
        <div class="hero__bottomline">
          <p>从一个问题开始，<span>向下一种可能出发。</span></p>
          <a href="#selected-works" class="hero__scroll" @click.prevent="scrollToWorks"><span>向下探索</span><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 4v16m-6-6 6 6 6-6" /></svg></a>
        </div>
      </div>
    </section>

    <div class="home-middle">
      <PresentationWallpaper :config="home" :default-video="indexVideo" :default-poster="indexPoster" :ready="ready" />

      <section id="selected-works" class="selected-works" aria-labelledby="works-heading" :aria-busy="worksLoading">
        <div class="page-shell">
          <div class="section-heading">
            <div><p v-if="home.worksEyebrow" class="eyebrow section-eyebrow"><span>01</span>{{ home.worksEyebrow }}</p><h2 id="works-heading" class="section-title configured-lines">{{ home.worksTitle }}</h2></div>
            <div class="section-heading__aside"><p v-if="home.worksDescription" class="configured-lines">{{ home.worksDescription }}</p><router-link to="/works" class="text-link">查看全部作品 <span aria-hidden="true">↗</span></router-link></div>
          </div>
          <div v-if="worksLoading" class="works-skeleton" role="status" aria-label="正在加载精选作品"><div v-for="item in 2" :key="item" class="works-skeleton__item"><div></div><span></span><span></span></div></div>
          <div v-else-if="worksError" class="home-state" role="status"><span class="meta-label">作品暂未加载</span><p>{{ worksError }}</p><button type="button" class="text-link" @click="loadFeaturedWorks">重新加载 <span aria-hidden="true">↗</span></button></div>
          <div v-else-if="featuredWorks.length" class="work-gallery" :class="{ 'work-gallery--single': featuredWorks.length === 1 }">
            <div v-for="(work, index) in featuredWorks" :key="work.slug" class="work-gallery__item">
              <WorkCard :slug="work.slug" :title="work.title" :description="work.summary || ''" :cover-url="work.coverUrl || ''" :tag-type="work.tagType || ''" :authors="work.authors || ''" :date="work.dateLabel || ''" :variant="index === 0 ? 'featured' : 'default'" />
            </div>
          </div>
          <div v-else class="home-state"><span class="meta-label">WORK IN PROGRESS</span><p>新的作品正在整理，探索还在继续。</p><router-link to="/learning" class="text-link">先从学习资料开始 <span aria-hidden="true">↗</span></router-link></div>
        </div>
      </section>

      <section class="growth" aria-labelledby="growth-heading" :aria-busy="capabilitiesLoading">
        <div class="page-shell growth__grid">
          <div class="growth__intro">
            <p v-if="home.capabilitiesEyebrow" class="eyebrow section-eyebrow"><span>02</span>{{ home.capabilitiesEyebrow }}</p>
            <h2 id="growth-heading" class="section-title configured-lines">{{ home.capabilitiesTitle }}</h2>
            <p v-if="home.capabilitiesDescription" class="growth__description configured-lines">{{ home.capabilitiesDescription }}</p>
            <router-link to="/about" class="text-link">认识独角兽 <span aria-hidden="true">↗</span></router-link>
            <svg class="growth__drawing" viewBox="0 0 220 120" fill="none" aria-hidden="true"><path d="M8 100C60 100 36 13 104 13c72 0 39 88 108 88M78 110l28-96 29 72M10 100h202" /></svg>
          </div>
          <div class="growth__content">
            <div v-if="capabilitiesLoading" class="home-state home-state--dark" role="status"><p>正在加载我们的成长方式…</p></div>
            <div v-else-if="capabilitiesError" class="home-state home-state--dark" role="status"><p>{{ capabilitiesError }}</p><button type="button" class="text-link" @click="loadCapabilities">重新加载 <span aria-hidden="true">↗</span></button></div>
            <div v-else-if="!capabilities.length" class="home-state home-state--dark"><p>培养体系正在整理，欢迎先来认识我们。</p><router-link to="/about" class="text-link">关于工作室 <span aria-hidden="true">↗</span></router-link></div>
            <div v-else class="capability-list">
              <details v-for="(capability, index) in capabilities" :key="capability.id" class="capability" :open="index === 0">
                <summary><span class="capability__number"><StudioIcon v-if="capability.icon" :icon="capability.icon" /><template v-else>{{ String(index + 1).padStart(2, '0') }}</template></span><h3>{{ capability.title }}</h3><span class="capability__toggle" aria-hidden="true"></span></summary>
                <div class="capability__body"><p>{{ capability.description }}</p></div>
              </details>
            </div>
            <p class="growth__footnote"><span class="signal-dot" aria-hidden="true"></span> 学习不止于课堂，成长发生在实践。</p>
          </div>
        </div>
      </section>

      <section class="directions" aria-labelledby="directions-heading" :aria-busy="servicesLoading">
        <div class="page-shell">
          <div class="section-heading section-heading--directions"><div><p v-if="home.directionsEyebrow" class="eyebrow section-eyebrow"><span>03</span>{{ home.directionsEyebrow }}</p><h2 id="directions-heading" class="section-title configured-lines">{{ home.directionsTitle }}</h2></div><p v-if="home.directionsDescription" class="directions__description configured-lines">{{ home.directionsDescription }}</p></div>
          <div v-if="servicesLoading" class="home-state" role="status"><p>正在加载探索方向…</p></div>
          <div v-else-if="servicesError" class="home-state" role="status"><p>{{ servicesError }}</p><button type="button" class="text-link" @click="loadServices">重新加载 <span aria-hidden="true">↗</span></button></div>
          <div v-else-if="services.length" class="direction-list">
            <router-link v-for="(service, index) in services" :key="service.id" to="/join" class="direction-row" :aria-label="service.title + '，了解如何加入'"><span class="direction-row__number"><StudioIcon v-if="service.icon" :icon="service.icon" /><template v-else>{{ String(index + 1).padStart(2, '0') }}</template></span><h3>{{ service.title }}</h3><p>{{ service.description }}</p><span class="direction-row__arrow" aria-hidden="true">↗</span></router-link>
          </div>
          <div v-else class="home-state"><p>探索方向正在更新，欢迎带着你的想法来交流。</p><router-link to="/join" class="text-link">加入交流 <span aria-hidden="true">↗</span></router-link></div>
        </div>
      </section>
    </div>

    <section class="invitation" aria-labelledby="invitation-heading">
      <div class="page-shell invitation__shell">
        <p v-if="home.invitationEyebrow" class="eyebrow section-eyebrow"><span>04</span>{{ home.invitationEyebrow }}</p>
        <div class="invitation__grid"><div><h2 id="invitation-heading" class="configured-lines">{{ home.invitationTitle }}</h2><p v-if="home.invitationDescription" class="configured-lines">{{ home.invitationDescription }}</p></div><SiteLink v-if="home.invitationLabel && home.invitationUrl" :to="home.invitationUrl" class="invitation__link"><svg viewBox="0 0 80 80" fill="none" aria-hidden="true"><path d="M16 64 64 16M17 16h47v47" /></svg><span>{{ home.invitationLabel }}</span></SiteLink></div>
        <div class="invitation__bottom"><span>LEARN. EXPERIMENT. CREATE.</span><span>UNICORN STUDIO</span></div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import BrandVisual from '@/components/BrandVisual.vue'
import WorkCard from '@/components/WorkCard.vue'
import SiteLink from '@/components/SiteLink.vue'
import StudioIcon from '@/components/StudioIcon.vue'
import HomeRecommendation from '@/components/HomeRecommendation.vue'
import PresentationWallpaper from '@/components/PresentationWallpaper.vue'
import indexVideo from '@/image/index.mp4'
import indexPoster from '@/image/index-poster.jpg'
import { usePresentation } from '@/composables/usePresentation'
import { useApi } from '@/composables/useApi'
import { usePageMeta, DEFAULT_DESCRIPTION } from '@/composables/usePageMeta'

const { get } = useApi()
const { presentation, ready } = usePresentation()
const home = computed(() => presentation.home)
usePageMeta(() => ({ home: true, title: home.value.title.replace(/\n/g, ''), description: DEFAULT_DESCRIPTION }))
const recommendation = ref(null)
const works = ref([])
const worksLoading = ref(true)
const worksError = ref('')
const capabilities = ref([])
const capabilitiesLoading = ref(true)
const capabilitiesError = ref('')
const services = ref([])
const servicesLoading = ref(true)
const servicesError = ref('')
const featuredWorks = computed(() => {
  const limit = home.value.featuredLimit
  return Number.isInteger(limit) && limit >= 1 && limit <= 6 ? works.value.slice(0, limit) : []
})
watch([() => home.value.recommendationType, () => home.value.recommendationId], async ([type, id], _, onCleanup) => {
  recommendation.value = null
  if (!['work', 'learning', 'join'].includes(type) || (type !== 'join' && !id)) return
  let current = true
  onCleanup(() => { current = false })
  const endpoint = { work: '/api/works', learning: '/api/learning/resources', join: '/api/join' }[type]
  const result = await get(endpoint)
  if (!current || result.error) return
  const data = result.data
  if (type === 'join') {
    if (data?.title) recommendation.value = { label: '招募', title: data.title, description: data.subtitle, link: '/join' }
    return
  }
  const items = type === 'work' ? data?.list : data?.resources
  const item = Array.isArray(items) && items.find(item => String(item.id) === id)
  if (!item?.title || (type === 'work' && !item.slug)) return
  recommendation.value = {
    label: type === 'work' ? '作品' : '学习资料',
    title: item.title,
    description: type === 'work' ? item.summary : item.description,
    coverUrl: item.coverUrl,
    link: type === 'work' ? '/works/' + encodeURIComponent(item.slug) : '/learning/' + id,
  }
}, { immediate: true })
function scrollToWorks() {
  const target = document.getElementById('selected-works')
  if (!target) return
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  target.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth' })
}
async function loadFeaturedWorks() {
  worksLoading.value = true
  worksError.value = ''
  try { const result = await get('/api/works?featured=true'); if (result.error) throw new Error(result.message); works.value = Array.isArray(result.data?.list) ? result.data.list : [] } catch (error) { worksError.value = error.message || '暂时无法加载作品，请稍后重试。' } finally { worksLoading.value = false }
}
async function loadCapabilities() {
  capabilitiesLoading.value = true
  capabilitiesError.value = ''
  try { const result = await get('/api/capabilities'); if (result.error) throw new Error(result.message); capabilities.value = Array.isArray(result.data?.list) ? result.data.list : [] } catch (error) { capabilitiesError.value = error.message || '暂时无法加载培养能力，请稍后重试。' } finally { capabilitiesLoading.value = false }
}
async function loadServices() {
  servicesLoading.value = true
  servicesError.value = ''
  try { const result = await get('/api/services'); if (result.error) throw new Error(result.message); services.value = Array.isArray(result.data?.list) ? result.data.list : [] } catch (error) { servicesError.value = error.message || '暂时无法加载招收方向，请稍后重试。' } finally { servicesLoading.value = false }
}
onMounted(() => { loadFeaturedWorks(); loadCapabilities(); loadServices() })
</script>

<style scoped>
.hero-visual-wrap { position: relative; min-width: 0; }
.home-page { --home-ink: var(--color-ink, #121316); --home-paper: var(--color-paper, #f4f2ec); --home-accent: var(--color-accent, #c92d3b); color: var(--home-ink); background: var(--home-paper); }
.hero { margin-top: calc(-1 * var(--header-height, 80px)); padding-top: var(--header-height, 80px); color: var(--feature-text); background: var(--feature-bg); overflow: hidden; }
.hero__shell { display: flex; flex-direction: column; min-height: min(850px, calc(100svh - var(--header-height, 80px))); }
.hero__topline { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding-top: 37px; }
.hero__topline .eyebrow { display: flex; align-items: center; gap: 11px; color: #c1c0bf; font-size: 11px; letter-spacing: .12em; }
.signal-dot { display: inline-block; width: 7px; height: 7px; flex: 0 0 7px; background: #ef4c58; border-radius: 50%; }
.hero__edition { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; color: #8e8f95; font-size: 10px; letter-spacing: .1em; }
.hero__grid { display: grid; grid-template-columns: 1.15fr 1fr; align-items: center; gap: 8px; flex: 1; min-height: 560px; padding: 34px 0 42px; }
.hero__copy { position: relative; z-index: 2; padding: 15px 0 24px; }
.hero h1 { position: relative; display: inline-block; max-width: 100%; margin: 0; font-size: clamp(58px, 6.65vw, 101px); font-weight: 650; line-height: 1.22; letter-spacing: -.065em; text-wrap: balance; overflow-wrap: anywhere; }
.hero h1 > span:first-child { color: #aaa9a6; }
.hero__period { position: absolute; top: 2px; right: -25px; width: 10px; height: 10px; border-radius: 50%; background: var(--home-accent); }
.hero__description { margin: 32px 0 0; color: #a7a8af; font-size: clamp(15px, 1.25vw, 17px); line-height: 1.95; letter-spacing: .015em; }
.hero__actions { display: flex; flex-wrap: wrap; align-items: center; gap: 29px; margin-top: 33px; }
.hero__primary { min-height: 52px; padding-inline: 24px; }
.hero__primary svg { width: 19px; height: 19px; stroke: currentColor; stroke-width: 1.7; transition: transform 180ms ease; }
.hero__primary:hover svg { transform: translateX(3px); }
.hero__secondary { display: inline-flex; align-items: center; justify-content: center; min-height: 48px; gap: 18px; color: #f4f2ec; font-size: 14px; text-decoration: none; }
.hero__secondary span { color: #ff8c95; font-size: 22px; transition: transform 180ms ease; }
.hero__secondary:hover span { transform: translate(2px, -2px); }
.hero__disciplines { display: flex; flex-wrap: wrap; gap: 10px 20px; margin-top: 44px; color: #94959d; font-size: 11px; letter-spacing: .08em; }
.hero__disciplines span + span::before { content: '/'; margin-right: 20px; color: #55565d; }
.hero__bottomline { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 24px 0 26px; border-top: 1px solid #34353a; }
.hero__bottomline p { margin: 0; font-size: 12px; color: #a7a8af; letter-spacing: .025em; }
.hero__bottomline p span { margin-left: 13px; color: #73747c; }
.hero__scroll { display: inline-flex; align-items: center; min-height: 44px; gap: 22px; color: #c9c8c5; text-decoration: none; font-size: 11px; }
.hero__scroll svg { width: 18px; height: 18px; stroke: currentColor; stroke-width: 1.3; transition: transform 180ms ease; }
.hero__scroll:hover svg { transform: translateY(3px); }
.home-middle {
  position: relative;
  isolation: isolate;
  --color-ink: #f6f5fb;
  --color-paper: #161925;
  --color-muted-light: #e1e1ec;
  --text-on-paper: #e1e1ec;
  --feature-text: #f6f5fb;
  --feature-muted: #e1e1ec;
  --feature-line: #ffffff4d;
  --feature-accent: #ffb1ba;
  --accent-on-paper: var(--feature-accent);
  --surface-soft: #24293a;
  --video-shade: linear-gradient(90deg, #0a0e1be0, #101522ab 56%, #0b0e18ad);
  --video-position: 65% center;
  background: var(--color-paper);
  color: var(--feature-text);
}
.home-middle > section { position: relative; z-index: 1; }
.home-middle :deep(.video-toggle) { justify-self: start; }
.selected-works { padding: 120px 0 128px; scroll-margin-top: calc(var(--header-height, 80px) + 20px); }
.section-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 48px; margin-bottom: 58px; }
.section-eyebrow { display: flex; align-items: center; gap: 15px; margin: 0 0 30px; color: var(--text-on-paper, #68676b); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; letter-spacing: .12em; }
.section-eyebrow > span { display: inline-flex; align-items: center; justify-content: center; width: 27px; height: 27px; border: 1px solid currentColor; border-radius: 50%; font-size: 9px; letter-spacing: 0; }
.home-page .section-title { margin: 0; font-weight: 600; letter-spacing: -.045em; font-size: clamp(32px, 3.75vw, 54px); line-height: 1.36; }
.section-heading__aside { max-width: 300px; padding-bottom: 5px; }
.section-heading__aside p, .directions__description { margin: 0 0 24px; color: var(--text-on-paper, #62636a); font-size: 15px; line-height: 1.9; }
.text-link { display: inline-flex; min-height: 44px; align-items: center; gap: 28px; background: none; border: 0; padding: 0; color: inherit; font-family: inherit; font-size: 13px; font-weight: 550; text-decoration: none; cursor: pointer; }
.text-link > span { font-size: 24px; transition: transform 180ms ease; }
.text-link:hover > span, .text-link:focus-visible > span { transform: translate(3px, -3px); }
.work-gallery { display: grid; grid-template-columns: 1.2fr 1fr; gap: 64px 44px; align-items: start; }
.work-gallery__item { min-width: 0; }
.work-gallery__item:nth-child(2) { padding-top: 114px; }
.work-gallery__item:nth-child(3) { grid-column: 1; max-width: 86%; }
.work-gallery--single { grid-template-columns: minmax(0, 1fr); max-width: 930px; }
.work-gallery--single .work-gallery__item { padding-top: 0; }
.home-state { padding: 44px 0; border-block: 1px solid var(--feature-line); }
.home-state p { margin: 12px 0; color: var(--text-on-paper, #62636a); font-size: 15px; line-height: 1.8; }
.home-state .meta-label { color: var(--text-on-paper, #62636a); font-size: 10px; letter-spacing: .12em; }
.works-skeleton { display: grid; grid-template-columns: 1.2fr 1fr; gap: 44px; }
.works-skeleton__item > div { aspect-ratio: 16 / 10; background: var(--surface-soft, #dedbd5); border-radius: 8px; }
.works-skeleton__item > span { display: block; width: 63%; height: 18px; margin-top: 24px; border-radius: 3px; background: var(--surface-soft, #dedbd5); }
.works-skeleton__item > span:last-child { width: 87%; height: 10px; margin-top: 13px; }
.works-skeleton__item:nth-child(2) { padding-top: 114px; }
.growth { padding: 116px 0 104px; }
.growth__grid { display: grid; grid-template-columns: .9fr 1.1fr; gap: 80px; }
.growth__description { margin: 30px 0 22px; color: var(--feature-muted); font-size: 15px; line-height: 1.95; }
.growth__drawing { display: block; width: 180px; height: 98px; margin-top: 48px; stroke: #66666c; stroke-width: 1; }
.growth__content { padding-top: 51px; }
.capability { border-top: 1px solid var(--feature-line); }
.capability:last-child { border-bottom: 1px solid var(--feature-line); }
.capability summary { display: flex; align-items: center; gap: 25px; min-height: 92px; padding: 20px 0; cursor: pointer; list-style: none; }
.capability summary::-webkit-details-marker { display: none; }
.capability__number { color: var(--feature-muted); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px; }
.capability h3 { flex: 1; margin: 0; font-size: clamp(20px, 2vw, 28px); font-weight: 500; line-height: 1.45; }
.capability__toggle { position: relative; width: 24px; height: 24px; flex-shrink: 0; }
.capability__toggle::before, .capability__toggle::after { position: absolute; content: ''; width: 13px; height: 1px; top: 11px; left: 5px; background: #c9c8c5; }
.capability__toggle::after { transform: rotate(90deg); transition: transform 180ms ease; }
.capability[open] .capability__toggle::after { transform: rotate(0); }
.capability[open] h3 { color: var(--feature-accent); }
.capability__body { padding: 0 34px 30px 42px; }
.capability__body p { max-width: 450px; margin: 0; color: var(--feature-muted); font-size: 15px; line-height: 1.95; }
.growth__footnote { display: flex; align-items: center; gap: 12px; margin: 37px 0 0; color: var(--feature-muted); font-size: 11px; line-height: 1.8; }
.growth__footnote .signal-dot { width: 5px; height: 5px; flex-basis: 5px; }
.directions { padding: 116px 0 128px; }
.section-heading--directions { margin-bottom: 55px; }
.directions__description { width: 300px; margin-bottom: 5px; }
.direction-row { display: grid; grid-template-columns: 48px 1fr 1.2fr 56px; gap: 24px; align-items: center; min-height: 132px; padding: 26px 0; color: inherit; text-decoration: none; border-top: 1px solid var(--feature-line); transition: background-color 180ms ease; }
.direction-row:last-child { border-bottom: 1px solid var(--feature-line); }
.direction-row__number { align-self: start; padding-top: 9px; color: var(--text-on-paper, #77767c); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px; }
.direction-row h3 { margin: 0; font-size: clamp(23px, 2.4vw, 34px); font-weight: 550; line-height: 1.35; letter-spacing: -.03em; }
.direction-row p { max-width: 390px; margin: 0; color: var(--text-on-paper, #62636a); font-size: 14px; line-height: 1.85; }
.direction-row__arrow { display: flex; align-items: center; justify-content: center; width: 48px; height: 48px; border: 1px solid #cfcdc6; border-radius: 50%; font-size: 27px; transition: color 180ms ease, background-color 180ms ease, transform 180ms ease; }
.direction-row:hover h3 { color: var(--feature-accent); }
.direction-row:hover .direction-row__arrow, .direction-row:focus-visible .direction-row__arrow { color: #fff; border-color: var(--home-accent); background: var(--home-accent); transform: rotate(4deg); }
.invitation { overflow: hidden; color: var(--feature-text); background: var(--feature-raised-bg); }
.invitation__shell { padding-top: 90px; padding-bottom: 29px; }
.invitation .section-eyebrow { color: #a7a8af; margin-bottom: 43px; }
.invitation__grid { display: flex; align-items: center; justify-content: space-between; gap: 44px; }
.invitation h2 { margin: 0; font-size: clamp(42px, 6vw, 84px); font-weight: 550; line-height: 1.26; letter-spacing: -.055em; }
.invitation h2 span { color: #a8a7a4; }
.invitation__grid p { margin: 26px 0 0; color: #aaaab1; font-size: 14px; line-height: 1.9; }
.invitation__link { display: flex; flex-shrink: 0; flex-direction: column; align-items: center; justify-content: center; width: clamp(140px, 16vw, 220px); height: clamp(140px, 16vw, 220px); margin-right: 5%; border: 1px solid #77777c; border-radius: 50%; color: #f4f2ec; text-decoration: none; transition: background-color 200ms ease, border-color 200ms ease; }
.invitation__link svg { width: 65px; height: 65px; stroke: currentColor; stroke-width: 1.2; transition: transform 200ms ease; }
.invitation__link span { margin-top: 15px; font-size: 12px; }
.invitation__link:hover, .invitation__link:focus-visible { background: var(--home-accent); border-color: var(--home-accent); }
.invitation__link:hover svg { transform: translate(3px, -3px); }
.invitation__bottom { display: flex; justify-content: space-between; gap: 20px; margin-top: 80px; padding-top: 27px; border-top: 1px solid #414147; color: #9b9ba2; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 9px; letter-spacing: .12em; }
@media (min-width: 1600px) { .hero h1 { font-size: 112px; } }
@media (max-width: 1100px) { .hero__grid { min-height: 530px; grid-template-columns: 1.15fr 1fr; } .hero h1 { font-size: clamp(54px, 6.5vw, 76px); } .hero__description { font-size: 14px; } .growth__grid { grid-template-columns: .85fr 1.15fr; gap: 40px; } .direction-row { grid-template-columns: 30px 1fr 1.1fr 45px; gap: 18px; } .direction-row__arrow { width: 43px; height: 43px; } }
@media (max-width: 767px) {
  .hero__shell { min-height: auto; } .hero__topline { padding-top: 26px; } .hero__topline .eyebrow { font-size: 10px; gap: 8px; letter-spacing: .04em; } .hero__edition { display: none; }
  .hero__grid { display: flex; flex-direction: column; align-items: stretch; gap: 0; min-height: 0; padding-top: 49px; padding-bottom: 21px; } .hero__copy { padding: 0; }
  .hero h1 { font-size: clamp(47px, 10.65vw, 76px); letter-spacing: -.065em; line-height: 1.24; } .hero__period { width: 7px; height: 7px; right: -17px; }
  .hero__description { margin-top: 24px; font-size: 14px; line-height: 1.95; } .hero__desktop-break { display: none; } .hero__actions { gap: 26px; margin-top: 25px; } .hero__primary { min-height: 49px; padding-inline: 22px; font-size: 13px; } .hero__secondary { gap: 15px; font-size: 13px; }
  .hero__disciplines { margin-top: 28px; font-size: 11px; gap: 8px 13px; } .hero__disciplines span + span::before { margin-right: 13px; }
  .hero__grid :deep(.brand-visual) { width: min(100%, 440px); align-self: center; margin-top: -8px; margin-bottom: -7px; }
  .hero__bottomline { padding: 13px 0 17px; } .hero__bottomline p { font-size: 10px; max-width: 180px; line-height: 1.8; } .hero__bottomline p span { display: block; margin: 0; } .hero__scroll { gap: 13px; font-size: 10px; }
  .selected-works, .directions { padding: 71px 0 76px; } .section-heading { align-items: flex-start; flex-direction: column; gap: 26px; margin-bottom: 33px; } .section-eyebrow { font-size: 10px; gap: 11px; margin-bottom: 22px; } .section-eyebrow > span { width: 24px; height: 24px; font-size: 9px; } .home-page .section-title { font-size: clamp(30px, 7.4vw, 46px); line-height: 1.4; }
  .section-heading__aside { display: flex; max-width: none; width: 100%; justify-content: space-between; align-items: flex-end; gap: 14px; padding: 0; } .section-heading__aside p { margin: 0; font-size: 12px; } .section-heading__aside .text-link { font-size: 12px; gap: 8px; flex-shrink: 0; } .section-heading__aside .text-link span { font-size: 20px; }
  .work-gallery { grid-template-columns: minmax(0, 1fr); gap: 38px; } .work-gallery__item:nth-child(2) { padding-top: 0; } .work-gallery__item:nth-child(3) { max-width: none; }
  .works-skeleton { grid-template-columns: minmax(0, 1fr); gap: 40px; } .works-skeleton__item:nth-child(2) { padding-top: 0; }
  .growth { padding: 71px 0 74px; } .growth__grid { grid-template-columns: minmax(0, 1fr); gap: 33px; } .growth__break { display: none; } .growth__description { margin: 23px 0 10px; font-size: 14px; } .growth__drawing { display: none; } .growth__content { padding-top: 0; }
  .capability summary { min-height: 78px; gap: 17px; padding: 21px 0; } .capability h3 { font-size: 22px; } .capability__body { padding-left: 32px; padding-right: 15px; padding-bottom: 25px; } .capability__body p { font-size: 14px; } .growth__footnote { margin-top: 27px; font-size: 11px; }
  .directions__description { width: auto; font-size: 14px; } .section-heading--directions { gap: 22px; }
  .direction-row { grid-template-columns: 21px minmax(0, 1fr) 40px; gap: 13px; padding: 24px 0; } .direction-row__number { font-size: 9px; padding-top: 8px; grid-row: 1 / 3; } .direction-row h3 { font-size: clamp(21px, 5.7vw, 28px); } .direction-row p { font-size: 14px; grid-column: 2; } .direction-row__arrow { width: 39px; height: 39px; font-size: 23px; grid-column: 3; grid-row: 1 / 3; }
  .invitation__shell { padding-top: 64px; padding-bottom: 23px; } .invitation .section-eyebrow { margin-bottom: 32px; } .invitation__grid { flex-direction: column; align-items: flex-start; gap: 32px; } .invitation h2 { font-size: clamp(39px, 10vw, 62px); } .invitation__grid p { margin-top: 24px; font-size: 12px; }
  .invitation__link { align-self: flex-end; width: 115px; height: 115px; margin-top: -75px; margin-right: 0; } .invitation__link svg { width: 39px; height: 39px; } .invitation__link span { font-size: 10px; margin-top: 8px; }
  .invitation__bottom { margin-top: 47px; padding-top: 23px; font-size: 7px; letter-spacing: .06em; }
}
@media (max-width: 374px) { .hero h1 { font-size: 44px; } .hero__topline .eyebrow { font-size: 9px; } .section-heading__aside { flex-direction: column; align-items: flex-start; gap: 7px; } .invitation__link { margin-top: 0; } }
@media (prefers-reduced-motion: no-preference) {
  .hero__copy h1, .hero__description, .hero__actions, .hero__disciplines { animation: home-arrive 650ms cubic-bezier(.16, 1, .3, 1) both; }
  .hero__description { animation-delay: 70ms; }
  .hero__actions { animation-delay: 130ms; }
  .hero__disciplines { animation-delay: 180ms; }
  @keyframes home-arrive { from { opacity: .65; transform: translateY(14px); } to { opacity: 1; transform: translateY(0); } }
}
@media (prefers-reduced-motion: reduce) { .hero__primary svg, .hero__secondary span, .hero__scroll svg, .text-link > span, .capability__toggle::after, .direction-row, .direction-row__arrow, .invitation__link, .invitation__link svg { transition: none; } }
/* 翻转后，原深色区块的文字随浅色表面变深。 */
:global([data-theme='light'] .hero__topline .eyebrow) ,
:global([data-theme='light'] .hero__edition) ,
:global([data-theme='light'] .hero h1 > span:first-child) ,
:global([data-theme='light'] .hero__description) ,
:global([data-theme='light'] .hero__disciplines) ,
:global([data-theme='light'] .hero__bottomline p) ,
:global([data-theme='light'] .hero__bottomline p span) ,
:global([data-theme='light'] .invitation .section-eyebrow) ,
:global([data-theme='light'] .invitation h2 span) ,
:global([data-theme='light'] .invitation__grid p) ,
:global([data-theme='light'] .invitation__bottom) { color: var(--feature-muted); }
:global([data-theme='light'] .hero__secondary) ,
:global([data-theme='light'] .hero__scroll) ,
:global([data-theme='light'] .invitation__link) { color: var(--feature-text); }
:global([data-theme='light'] .hero__secondary span) { color: var(--feature-accent); }
:global([data-theme='light'] .hero__bottomline) ,
:global([data-theme='light'] .invitation__link) ,
:global([data-theme='light'] .invitation__bottom) { border-color: var(--feature-line); }
:global([data-theme='light'] .invitation__link:hover) ,
:global([data-theme='light'] .invitation__link:focus-visible) { color: #fff; border-color: var(--home-accent); }
</style>
