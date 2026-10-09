<template>
  <router-link :to="`/works/${slug}`" :aria-label="`查看作品：${title}`" class="work-card" :class="`work-card--${variant}`">
    <div class="work-card__media">
      <img v-if="coverUrl && !imgFailed" :src="coverUrl" :alt="title" width="960" height="600" loading="lazy" decoding="async" @error="imgFailed = true" />
      <div v-else class="work-card__placeholder" aria-hidden="true">
        <svg class="work-card__arc" viewBox="0 0 400 300" fill="none"><path d="M315 38C392 125 340 270 215 270C110 270 56 188 78 104C84 202 187 228 250 185C298 153 309 94 282 61" stroke="currentColor" stroke-width="2"/><path d="M99 65L215 143L176 157Z" fill="currentColor"/></svg>
        <span class="work-card__placeholder-label">UNICORN STUDIO<br />WORK ARCHIVE</span><span class="work-card__placeholder-dot"></span>
      </div>
      <span class="work-card__open" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M6 18L18 6M6 6h12v12" stroke="currentColor" stroke-width="1.5"/></svg></span>
    </div>
    <div class="work-card__body">
      <div class="work-card__meta"><span>{{ tag?.label || '作品档案' }}</span><span v-if="date">{{ date }}</span></div>
      <h3>{{ title }}</h3><p v-if="description" class="work-card__description">{{ description }}</p><p v-if="authors" class="work-card__authors">{{ authors }}</p>
    </div>
  </router-link>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { resolveWorkTag } from '@/constants/workTags'
const props = defineProps({
  slug: { type: String, required: true }, title: { type: String, required: true },
  description: { type: String, default: '' }, coverUrl: { type: String, default: '' },
  icon: { type: String, default: '' }, tagType: { type: String, default: '' },
  authors: { type: String, default: '' }, date: { type: String, default: '' },
  coverClass: { type: String, default: '' }, variant: { type: String, default: 'default' },
})
const imgFailed = ref(false)
const tag = computed(() => resolveWorkTag(props.tagType))
watch(() => props.coverUrl, () => { imgFailed.value = false })
</script>
<style scoped>
.work-card {
  display: block;
  min-width: 0;
  color: var(--color-ink,#121316);
  text-decoration: none;
}
.work-card__media {
  position: relative;
  aspect-ratio: 16/10;
  overflow: hidden;
  background: #e5e3dd;
  border-radius: 4px;
}
.work-card__media>img {
  height: 100%;
  width: 100%;
  object-fit: cover;
  transition: transform 550ms cubic-bezier(.2,.7,.2,1);
}
.work-card__placeholder {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #e7e5df;
  color: #b9b6ae;
}
.work-card__arc {
  position: absolute;
  height: 115%;
  width: 100%;
  right: -10%;
  top: -10%;
  transform: rotate(-12deg);
}
.work-card__placeholder-label {
  position: absolute;
  left: 7%;
  bottom: 10%;
  font: 500 11px/1.7 ui-monospace,monospace;
  letter-spacing: .14em;
  color: #62636a;
}
.work-card__placeholder-dot {
  position: absolute;
  right: 9%;
  top: 15%;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--color-accent,#c92d3b);
}
.work-card__open {
  position: absolute;
  bottom: 20px;
  right: 20px;
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--color-paper,#f4f2ec);
  color: var(--color-ink,#121316);
  transition: background 180ms,color 180ms,transform 240ms;
}
.work-card__open svg {
  width: 21px;
  height: 21px;
}
.work-card__body {
  padding: 23px 0 4px;
}
.work-card__meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  color: var(--color-muted-light,#62636a);
  font-size: 12px;
  line-height: 1.5;
  letter-spacing: .025em;
}
.work-card h3 {
  margin: 13px 0 10px;
  font-size: clamp(21px,2.1vw,29px);
  line-height: 1.45;
  font-weight: 650;
  letter-spacing: -.035em;
  overflow-wrap: anywhere;
  transition: color 180ms;
}
.work-card--featured h3 {
  font-size: clamp(24px, 2.6vw, 36px);
}
.work-card__description {
  margin: 0;
  max-width: 58ch;
  font-size: 14px;
  line-height: 1.8;
  color: var(--color-muted-light,#62636a);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.work-card__authors {
  margin: 17px 0 0;
  color: var(--color-muted-light,#62636a);
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.work-card:focus-visible {
  outline: 2px solid var(--color-accent,#c92d3b);
  outline-offset: 7px;
  border-radius: 4px;
}
.work-card:focus-visible .work-card__open {
  background: var(--color-accent,#c92d3b);
  color: #fff;
}
@media(hover:hover) {
  .work-card:hover .work-card__media>img {
    transform: scale(1.025);
  }
  .work-card:hover .work-card__open {
    background: var(--color-accent,#c92d3b);
    color: #fff;
    transform: rotate(6deg);
  }
  .work-card:hover h3 {
    color: var(--color-accent,#c92d3b);
  }
}
@media(max-width:600px) {
  .work-card__body {
    padding-top: 18px;
  }
  .work-card__open {
    right: 14px;
    bottom: 14px;
    width: 44px;
    height: 44px;
  }
  .work-card h3 {
    margin-top: 11px;
  }
  .work-card__meta {
    font-size: 12px;
  }
  .work-card__authors {
    margin-top: 12px;
  }
}
@media(prefers-reduced-motion:reduce) {
  .work-card__media>img,.work-card__open,.work-card h3 {
    transition: none;
  }
}
</style>
