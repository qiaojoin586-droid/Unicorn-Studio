<template>
  <div class="brand-visual" aria-hidden="true" @pointermove.passive="onPointerMove" @pointerleave="resetPosition">
    <div ref="stage" class="brand-visual__stage">
      <svg class="brand-visual__sculpture" viewBox="0 0 640 640" fill="none" focusable="false">
        <defs>
          <linearGradient id="unicorn-arc-material" x1="103" y1="135" x2="482" y2="503" gradientUnits="userSpaceOnUse"><stop stop-color="#FAF8F1" /><stop offset=".52" stop-color="#E4E1D9" /><stop offset="1" stop-color="#A4A29F" /></linearGradient>
          <linearGradient id="unicorn-horn-material" x1="194" y1="142" x2="253" y2="363" gradientUnits="userSpaceOnUse"><stop stop-color="#F5F3ED" /><stop offset="1" stop-color="#A5A39F" /></linearGradient>
        </defs>
        <circle cx="320" cy="320" r="264" stroke="#38393F" stroke-width=".7" />
        <circle cx="320" cy="320" r="214" stroke="#2A2B31" stroke-width=".7" stroke-dasharray="2 7" />
        <path d="M320 42v28M320 570v28M42 320h28M570 320h28" stroke="#7B7B80" stroke-width=".8" />
        <path d="M320 49V591M49 320H591" stroke="#28292F" stroke-width=".6" />
        <ellipse cx="320" cy="320" rx="292" ry="103" transform="rotate(-39 320 320)" stroke="#626168" stroke-width=".75" />
        <path d="M445 113C540 177 560 302 494 411C436 510 300 552 190 493C117 454 91 382 124 310C109 393 192 454 281 434C399 408 461 294 445 113Z" fill="url(#unicorn-arc-material)" />
        <path d="M124 310L255 100L229 328L185 396Z" fill="url(#unicorn-horn-material)" />
        <path d="M255 100L297 261L229 328Z" fill="#F4F2EC" />
        <path d="M255 100L229 328" stroke="#FFFDF9" stroke-opacity=".8" stroke-width=".8" />
        <path d="M297 261L332 290L229 328Z" fill="#C4C1BA" />
        <path d="M190 493C300 552 436 510 494 411" stroke="#F4F2EC" stroke-opacity=".2" />
        <circle cx="520" cy="126" r="15" fill="#C92D3B" />
        <circle cx="520" cy="126" r="24" stroke="#C92D3B" stroke-opacity=".28" stroke-width=".7" />
        <path d="M91 485h36m-18-18v36" stroke="#8A898F" stroke-width=".7" />
      </svg>
      <span class="brand-visual__label">AN IDEA TAKES SHAPE</span>
      <span v-if="!imageSrc || imageFailed" class="brand-visual__coordinate">U / 01</span>
      <div v-if="imageSrc && !imageFailed" class="brand-visual__preview">
        <div class="brand-visual__preview-top"><span></span> FROM THE STUDIO <span>↗</span></div>
        <img :src="imageSrc" alt="" width="320" height="200" decoding="async" @error="imageFailed = true" />
        <p v-if="imageTitle">{{ imageTitle }}</p>
      </div>
      <div v-else-if="showAnnotation" class="brand-visual__annotation"><span class="brand-visual__annotation-line"></span><span>从想法，到作品。<small>THE MAKING OF POSSIBILITIES</small></span></div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({ imageSrc: { type: String, default: '' }, imageTitle: { type: String, default: '' }, showAnnotation: { type: Boolean, default: true } })
const stage = ref(null)
const imageFailed = ref(false)
let motionPreference
let pointerCapability
let frameId = 0
let offsetX = 0
let offsetY = 0

watch(() => props.imageSrc, () => { imageFailed.value = false })

function resetPosition() {
  cancelAnimationFrame(frameId)
  frameId = 0
  stage.value?.style.setProperty('--visual-x', '0px')
  stage.value?.style.setProperty('--visual-y', '0px')
}

function onPointerMove(event) {
  if (event.pointerType !== 'mouse' || motionPreference?.matches || !pointerCapability?.matches) return
  const bounds = event.currentTarget.getBoundingClientRect()
  offsetX = ((event.clientX - bounds.left) / bounds.width - .5) * 12
  offsetY = ((event.clientY - bounds.top) / bounds.height - .5) * 12
  if (frameId) return
  frameId = requestAnimationFrame(() => {
    stage.value?.style.setProperty('--visual-x', offsetX + 'px')
    stage.value?.style.setProperty('--visual-y', offsetY + 'px')
    frameId = 0
  })
}

onMounted(() => {
  motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)')
  pointerCapability = window.matchMedia('(hover: hover) and (pointer: fine)')
  motionPreference.addEventListener('change', resetPosition)
  pointerCapability.addEventListener('change', resetPosition)
})
onBeforeUnmount(() => {
  cancelAnimationFrame(frameId)
  motionPreference?.removeEventListener('change', resetPosition)
  pointerCapability?.removeEventListener('change', resetPosition)
})
</script>

<style scoped>
.brand-visual { position: relative; width: calc(100% + 58px); margin-left: -18px; min-width: 0; aspect-ratio: 1; }
.brand-visual__stage { position: relative; width: 100%; height: 100%; transform: translate(var(--visual-x, 0px), var(--visual-y, 0px)); transition: transform 260ms cubic-bezier(.2, .65, .2, 1); }
.brand-visual__sculpture { display: block; width: 100%; height: 100%; overflow: visible; }
.brand-visual__label { position: absolute; top: 7%; left: 2%; color: #818188; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 8px; letter-spacing: .16em; writing-mode: vertical-rl; }
.brand-visual__coordinate { position: absolute; right: 8%; bottom: 10%; color: #88888e; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; letter-spacing: .12em; }
.brand-visual__preview { position: absolute; right: -1%; bottom: 7%; width: 36%; padding: 10px; border: 1px solid #5b5a5c; border-radius: 3px; background: #212226; transform: rotate(5deg); box-shadow: 0 16px 45px #0004; }
.brand-visual__preview-top { display: flex; align-items: center; gap: 6px; padding-bottom: 9px; color: #aaa9a6; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 6px; letter-spacing: .07em; }
.brand-visual__preview-top > span:first-child { width: 4px; height: 4px; border-radius: 50%; background: #e6525c; }
.brand-visual__preview-top > span:last-child { margin-left: auto; font-size: 11px; }
.brand-visual__preview img { display: block; width: 100%; aspect-ratio: 16 / 10; height: auto; object-fit: cover; background: #2c2d33; }
.brand-visual__preview p { margin: 9px 0 0; color: #c6c5c1; font-size: 8px; line-height: 1.5; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.brand-visual__annotation { display: flex; align-items: flex-start; gap: 13px; position: absolute; right: 1%; bottom: 6%; color: #c1c0bc; font-size: 12px; letter-spacing: .03em; }
.brand-visual__annotation-line { width: 40px; height: 1px; margin-top: 8px; background: #a5a29a; }
.brand-visual__annotation small { display: block; margin-top: 7px; color: #73747b; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 7px; letter-spacing: .08em; }
@media (max-width: 1100px) { .brand-visual { width: calc(100% + 24px); margin-left: -10px; } .brand-visual__preview { right: 1%; } }
@media (max-width: 767px) { .brand-visual { width: 100%; margin-left: 0; } .brand-visual__label { font-size: 6px; } .brand-visual__coordinate { font-size: 8px; } .brand-visual__annotation { font-size: 10px; } .brand-visual__annotation small { font-size: 6px; } .brand-visual__preview { bottom: 9%; right: 4%; width: 34%; padding: 7px; } .brand-visual__preview-top { font-size: 5px; padding-bottom: 6px; } .brand-visual__preview p { font-size: 7px; margin-top: 6px; } }
@media (prefers-reduced-motion: no-preference) { .brand-visual__sculpture { animation: sculpture-arrive 900ms cubic-bezier(.16, 1, .3, 1) both; } @keyframes sculpture-arrive { from { transform: translateY(14px) rotate(-3deg); opacity: .6; } to { transform: translateY(0) rotate(0); opacity: 1; } } }
@media (prefers-reduced-motion: reduce) { .brand-visual__stage { transform: none; transition: none; } }
:global([data-theme='light'] .brand-visual__sculpture) { filter: invert(1) hue-rotate(180deg); }
:global([data-theme='light'] .brand-visual__label),
:global([data-theme='light'] .brand-visual__coordinate),
:global([data-theme='light'] .brand-visual__annotation),
:global([data-theme='light'] .brand-visual__annotation small) { color: var(--feature-muted); }
</style>
