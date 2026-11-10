<template>
  <div class="about-page">
    <BackgroundVideo :src="aboutVideo" :poster="aboutPoster" desktop-only sticky />

    <section class="page-shell about-intro" aria-labelledby="about-title">
      <div class="about-overline">
        <p v-if="page.eyebrow" class="eyebrow"><span class="signal-dot" aria-hidden="true"></span>{{ page.eyebrow }}</p>
        <span class="about-edition">UNICORN STUDIO / ABOUT</span>
      </div>
      <div class="about-heading-grid">
        <h1 id="about-title" class="about-title">{{ page.title }}</h1>
        <p v-if="page.subtitle" class="about-subtitle">{{ page.subtitle }}</p>
      </div>
      <div class="about-rule" aria-hidden="true"><span>好奇，是一切的起点。</span><span>KEEP EXPLORING ↗</span></div>
    </section>

    <section class="page-shell about-story" aria-label="认识独角兽工作室">
      <figure class="about-visual" :class="{ 'about-visual--photo': !!page.imageUrl && !imageFailed }">
        <span class="visual-corner" aria-hidden="true"></span>
        <img :src="imageSrc" :alt="page.imageUrl && !imageFailed ? '独角兽工作室' : '独角兽工作室品牌标志'" width="1139" height="1164" class="about-visual-image" decoding="async" @error="imageFailed = true" />
        <figcaption class="about-visual-caption"><span>独角兽工作室</span><span>IDEAS INTO REALITY</span></figcaption>
      </figure>

      <div class="about-story-copy">
        <p class="eyebrow about-story-label">01 / 我们是谁</p>
        <div class="about-paragraphs">
          <p v-for="(paragraph, index) in paragraphs" :key="index">{{ paragraph }}</p>
        </div>
        <dl v-if="page.stats.length" class="about-stats">
          <div v-for="(stat, index) in page.stats" :key="index"><dd>{{ stat.value }}</dd><dt>{{ stat.label }}</dt></div>
        </dl>
        <router-link to="/works" class="about-text-link">在作品里认识我们 <span aria-hidden="true">↗</span></router-link>
        <p v-if="loadFailed" class="about-source-status" role="status">介绍暂未更新。<button type="button" :disabled="loading" @click="loadAbout">{{ loading ? '正在获取…' : '重新获取' }}</button></p>
      </div>
    </section>

    <section v-if="principles.length" class="about-practice" aria-labelledby="practice-title">
      <div class="page-shell">
        <div class="about-practice-heading">
          <p v-if="extra.principlesEyebrow" class="eyebrow">{{ extra.principlesEyebrow }}</p>
          <h2 id="practice-title" class="section-title configured-lines">{{ extra.principlesTitle }}</h2>
        </div>
        <div class="practice-list">
          <article v-for="item in principles" :key="item.number" class="practice-item">
            <span class="practice-number">{{ item.number }}</span><h3>{{ item.title }}</h3><p>{{ item.description }}</p>
          </article>
        </div>
      </div>
    </section>

    <section class="page-shell about-invitation" aria-labelledby="about-invite-title">
      <div><h2 id="about-invite-title" class="configured-lines">{{ extra.invitationTitle }}</h2><p v-if="extra.invitationDescription">{{ extra.invitationDescription }}</p></div>
      <SiteLink v-if="extra.invitationLabel && extra.invitationUrl" :to="extra.invitationUrl" class="button button--primary">{{ extra.invitationLabel }} <span aria-hidden="true">↗</span></SiteLink>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'
import BackgroundVideo from '@/components/BackgroundVideo.vue'
import logoUrl from '@/image/LOGO.jpg'
import aboutVideo from '@/image/home.mp4'
import aboutPoster from '@/image/home-poster.jpg'
import SiteLink from '@/components/SiteLink.vue'
import { usePresentation } from '@/composables/usePresentation'
const { presentation } = usePresentation()
const extra = computed(() => presentation.about)

const { get } = useApi()
const imageFailed = ref(false)
const loading = ref(false)
const loadFailed = ref(false)
const DEFAULTS = {
  eyebrow: '关于独角兽 / ABOUT US',
  title: '因为好奇，所以相聚。',
  subtitle: '我们是一群热爱技术与创造的伙伴，在学习、研究与真实项目中，探索想法的更多可能。',
  content: '独角兽工作室关注 AI 智能应用、Agent 开发与大模型学术研究，也关注每一个想法如何成为可以体验的作品。\n\n我们希望把学习与实践连接起来：带着问题探索技术，在交流中分享经验，与志同道合的伙伴一起成长。',
  imageUrl: '',
}
const page = reactive({ ...DEFAULTS, stats: [] })
const paragraphs = computed(() => (page.content || '').split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean))
const imageSrc = computed(() => page.imageUrl && !imageFailed.value ? page.imageUrl : logoUrl)
const principles = computed(() => extra.value.principles.map((item, index) => ({ ...item, number: String(index + 1).padStart(2, '0') })))
async function loadAbout() {
  if (loading.value) return
  loading.value = true
  try {
    const result = await get('/api/about')
    if (result?.error || !result?.data) throw new Error('About unavailable')
    const data = result.data
    page.eyebrow = data.eyebrow ?? DEFAULTS.eyebrow
    page.title = data.title || DEFAULTS.title
    page.subtitle = data.subtitle ?? DEFAULTS.subtitle
    page.content = data.content ?? DEFAULTS.content
    page.imageUrl = data.imageUrl || ''
    imageFailed.value = false
    page.stats = Array.isArray(data.stats)
      ? data.stats.map((s) => ({ value: s?.value || '', label: s?.label || '' })).filter((s) => s.value || s.label)
      : []
    loadFailed.value = false
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}
onMounted(loadAbout)
usePageMeta(() => ({ title: page.title === DEFAULTS.title ? '关于独角兽工作室' : page.title, description: page.subtitle }))
</script>

<style scoped>
.about-page {
  position: relative;
  isolation: isolate;
  background: #101522;
  color: #f6f5fb;
  --about-muted: #e1e1ec;
  --about-line: #ffffff4d;
  --about-accent: #ffb1ba;
  --video-shade: linear-gradient(95deg, #0b1020e0, #0b1020b5 60%, #0b1020ab);
  --video-position: 55% center;
}
.about-page > section { position: relative; z-index: 1; }
.about-page :deep(.video-toggle) { justify-self: start; }
.about-intro { padding-block: clamp(56px, 7vw, 108px) 22px; }
.about-overline { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 48px; }
.about-overline .eyebrow { display: flex; align-items: center; gap: 10px; margin: 0; }
.signal-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--color-accent, #c92d3b); flex-shrink: 0; }
.about-edition { font: 10px/1.5 ui-monospace, monospace; letter-spacing: .08em; color: var(--about-muted); }
.about-heading-grid { display: grid; grid-template-columns: 1.45fr 1fr; gap: 64px; align-items: end; }
.about-title { font-size: clamp(40px, 5.5vw, 76px); line-height: 1.2; letter-spacing: -.055em; font-weight: 650; margin: 0; max-width: 10em; overflow-wrap: anywhere; text-wrap: balance; }
.about-subtitle { font-size: clamp(16px, 1.3vw, 18px); line-height: 1.9; color: var(--about-muted); margin: 0 0 4px; max-width: 29em; }
.about-rule { display: flex; justify-content: space-between; gap: 20px; margin-top: 52px; padding-block: 18px; border-top: 1px solid var(--about-line); color: var(--about-muted); font-size: 12px; }
.about-rule span:last-child { font: 10px/1.5 ui-monospace, monospace; letter-spacing: .07em; }
.about-story { display: grid; grid-template-columns: 1fr 1fr; gap: clamp(40px, 7vw, 112px); align-items: center; padding-block: 48px 112px; }
.about-visual { min-width: 0; margin: 0; aspect-ratio: 1 / 1.08; color: #f4f2ec; position: relative; overflow: hidden; display: flex; align-items: center; justify-content: center; }
.about-visual-image { position: relative; width: 75%; height: auto; max-height: 78%; object-fit: contain; }
.about-visual--photo .about-visual-image { width: 100%; height: 100%; max-height: none; object-fit: cover; }
.visual-corner { position: absolute; width: 32px; height: 32px; border-top: 1px solid #ffffff60; border-left: 1px solid #ffffff60; top: 24px; left: 24px; z-index: 1; }
.about-visual-caption { position: absolute; bottom: 0; left: 0; right: 0; padding: 28px 24px 20px; display: flex; justify-content: space-between; align-items: baseline; gap: 16px; font-size: 11px; }
.about-visual--photo .about-visual-caption { background: linear-gradient(transparent, #080808d9); }
.about-visual-caption span:last-child { font: 9px/1.5 ui-monospace, monospace; letter-spacing: .07em; }
.about-story-copy { min-width: 0; padding-block: 16px; }
.about-story-label { margin: 0 0 32px; color: var(--about-muted); }
.about-paragraphs p { font-size: clamp(16px, 1.35vw, 19px); line-height: 1.95; margin: 0 0 24px; overflow-wrap: anywhere; }
.about-paragraphs p + p { color: var(--about-muted); }
.about-stats { display: flex; flex-wrap: wrap; gap: 24px 40px; margin: 36px 0; padding-top: 28px; border-top: 1px solid var(--about-line); }
.about-stats > div { min-width: 72px; }
.about-stats dd { font-size: clamp(28px, 3vw, 44px); font-weight: 600; letter-spacing: -.04em; margin: 0; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.about-stats dt { color: var(--about-muted); font-size: 12px; margin-top: 8px; }
.about-text-link { display: inline-flex; min-height: 44px; align-items: center; gap: 32px; margin-top: 16px; font-size: 14px; font-weight: 600; border-bottom: 1px solid currentColor; transition: color 160ms, gap 180ms; }
.about-text-link:hover { color: var(--about-accent); gap: 40px; }
.about-text-link span { font-size: 23px; }
.about-source-status { font-size: 12px; color: var(--about-muted); margin-top: 20px; }
.about-source-status button { text-decoration: underline; text-underline-offset: 4px; min-height: 44px; padding-inline: 8px; }
.about-practice { padding-block: clamp(64px, 7vw, 112px); }
.about-practice-heading { display: grid; grid-template-columns: .8fr 1.2fr; gap: 32px; align-items: start; margin-bottom: 56px; }
.about-practice-heading .eyebrow { margin: 10px 0 0; }
.about-practice-heading .section-title { margin: 0; }
.practice-item { display: grid; grid-template-columns: .18fr .82fr 1fr; gap: 32px; align-items: baseline; border-top: 1px solid var(--about-line); padding-block: 28px; }
.practice-number { font: 12px/1.6 ui-monospace, monospace; color: var(--about-accent); }
.practice-item h3 { margin: 0; font-size: clamp(18px, 1.7vw, 24px); font-weight: 600; letter-spacing: -.025em; }
.practice-item p { margin: 0; font-size: 15px; line-height: 1.9; color: var(--about-muted); max-width: 31em; }
.about-invitation { display: flex; align-items: flex-end; justify-content: space-between; gap: 40px; padding-block: clamp(72px, 8vw, 128px); }
.about-invitation .eyebrow { margin: 0 0 24px; }
.about-invitation h2 { margin: 0; font-size: clamp(32px, 4.5vw, 60px); line-height: 1.25; font-weight: 600; letter-spacing: -.045em; }
.about-invitation .button { flex-shrink: 0; }
@media (max-width: 900px) {
  .about-heading-grid { grid-template-columns: 1.2fr 1fr; gap: 32px; }
  .about-story { gap: 40px; padding-bottom: 80px; }
  .practice-item { grid-template-columns: 44px 1fr 1.15fr; gap: 20px; }
}
@media (max-width: 640px) {
  .about-overline { margin-bottom: 32px; }
  .about-edition { display: none; }
  .about-heading-grid { grid-template-columns: 1fr; gap: 24px; }
  .about-title { font-size: clamp(36px, 8.8vw, 52px); max-width: 9em; }
  .about-subtitle { font-size: 16px; max-width: 28em; }
  .about-rule { margin-top: 36px; font-size: 11px; }
  .about-rule span:last-child { display: none; }
  .about-story { grid-template-columns: 1fr; gap: 40px; padding-block: 24px 64px; }
  .about-visual { aspect-ratio: 1.12; }
  .about-story-copy { padding: 0; }
  .about-story-label { margin-bottom: 24px; }
  .about-paragraphs p { font-size: 16px; }
  .about-practice-heading { grid-template-columns: 1fr; gap: 24px; margin-bottom: 36px; }
  .about-practice-heading .eyebrow { margin-top: 0; }
  .practice-item { grid-template-columns: 32px 1fr; gap: 12px 16px; padding-block: 24px; }
  .practice-item p { grid-column: 2; font-size: 14px; }
  .about-invitation { align-items: flex-start; flex-direction: column; gap: 32px; }
}
@media (prefers-reduced-motion: reduce) { .about-text-link { transition: none; } }
</style>
