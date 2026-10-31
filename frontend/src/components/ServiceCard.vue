<template>
  <article class="service-card" :class="'service-card--' + tone">
    <div class="service-card-heading">
      <span class="service-card-mark" aria-hidden="true">
        <svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"><path :d="iconPath" /></svg>
      </span>
      <span v-if="displayNumber" class="service-card-number" aria-hidden="true">{{ displayNumber }}</span>
      <span v-else class="service-card-signal" aria-hidden="true"></span>
    </div>
    <h3>{{ title }}</h3>
    <p>{{ description }}</p>
  </article>
</template>

<script setup>
import { computed } from 'vue'
const props = defineProps({
  title: { type: String, required: true },
  description: { type: String, required: true },
  icon: { type: String, default: '' },
  tone: { type: String, default: 'paper', validator: (value) => ['paper', 'dark'].includes(value) },
  number: { type: [String, Number], default: '' },
  index: { type: Number, default: null },
})
const displayNumber = computed(() => props.number !== '' ? String(props.number).padStart(2, '0') : props.index !== null ? String(props.index + 1).padStart(2, '0') : '')
const iconPath = computed(() => {
  const hint = (props.icon + ' ' + props.title).toLowerCase()
  if (/code|web|前端|后端|软件|开发|编程/.test(hint)) return 'M11 9 4 16l7 7m10-14 7 7-7 7M18 5l-4 22'
  if (/ai|brain|robot|智能|模型|算法/.test(hint)) return 'M16 4v24M8 8l8-4 8 4v16l-8 4-8-4V8Zm0 0 16 16M24 8 8 24M3 16h26'
  if (/design|创意|设计/.test(hint)) return 'm16 3 4 9 9 4-9 4-4 9-4-9-9-4 9-4 4-9Z'
  if (/research|学术|研究|论文/.test(hint)) return 'M8 4h16v24H8V4Zm4 7h8m-8 5h8m-8 5h5M4 8v17h4'
  return 'M25 7A12 12 0 1 0 25 25M10 22 25 7m-9 0h9v9'
})
</script>

<style scoped>
.service-card { --card-ink: var(--color-ink, #121316); --card-muted: var(--color-muted-light, #62636a); --card-line: #12131625; color: var(--card-ink); background: var(--color-paper, #f4f2ec); padding: 28px 0 12px; border-top: 1px solid var(--card-line); min-width: 0; }
.service-card--dark { --card-ink: var(--feature-text); --card-muted: var(--feature-muted); --card-line: var(--feature-line); background: var(--feature-bg); }
.service-card-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 32px; }
.service-card-mark { display: block; width: 34px; height: 34px; }
.service-card-mark svg { width: 100%; height: 100%; }
.service-card-number { font: 11px/1.6 ui-monospace, monospace; color: var(--card-muted); }
.service-card-signal { width: 5px; height: 5px; border-radius: 50%; background: var(--color-accent, #c92d3b); }
.service-card h3 { font-size: clamp(20px, 1.9vw, 27px); line-height: 1.4; letter-spacing: -.035em; font-weight: 600; margin: 0 0 16px; overflow-wrap: anywhere; }
.service-card p { font-size: 15px; color: var(--card-muted); line-height: 1.9; margin: 0; max-width: 32em; overflow-wrap: anywhere; }
@media (max-width: 640px) { .service-card { padding-top: 24px; } .service-card-heading { margin-bottom: 24px; } .service-card p { font-size: 14px; } }
</style>
