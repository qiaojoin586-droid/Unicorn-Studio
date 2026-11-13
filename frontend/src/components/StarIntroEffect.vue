<template>
  <Teleport to="body">
    <div
      v-if="visible"
      ref="host"
      :id="id"
      class="star-intro"
      :class="{ 'is-fading': fading }"
      aria-hidden="true"
      @transitionend.self="onTransitionEnd"
    />
  </Teleport>
</template>

<script>
// 同时挂载时只允许一个实例申请播放；存储不可用时仍保证本次 SPA 会话只展示一次。
let owner = null
let playedInMemory = false
let sequence = 0
const SEEN_KEY = 'has_seen_star_effect'
</script>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

const emit = defineEmits(['finished'])
const host = ref(null)
const visible = ref(false)
const fading = ref(false)
const id = `star-intro-${++sequence}`
const token = Symbol(id)
let container
let engine
let motion
let playTimer
let removalTimer
let starting = false
let stopped = false

function seen() {
  try { return playedInMemory || sessionStorage.getItem(SEEN_KEY) !== null }
  catch { return playedInMemory }
}

function destroyContainer(value) {
  if (value && !value.destroyed) value.destroy()
}

function finish() {
  if (stopped) return
  stopped = true
  clearTimeout(playTimer)
  clearTimeout(removalTimer)
  motion?.removeEventListener('change', onMotionChange)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  destroyContainer(container)
  // load() 尚未 resolve 时，实例已可能进入引擎注册表，同样要及时清理。
  for (const item of engine?.items || []) {
    if (item.id.description === id) destroyContainer(item)
  }
  container = undefined
  if (owner === token) owner = null
  visible.value = false
  emit('finished')
}

function onMotionChange() { if (motion.matches) finish() }
function onVisibilityChange() {
  if (document.hidden) { if (starting) finish() }
  else if (!starting) void start()
}
function onTransitionEnd(event) {
  if (fading.value && event.propertyName === 'opacity') finish()
}

async function start() {
  if (starting || stopped) return
  if (seen() || owner || motion.matches) { finish(); return }
  owner = token
  starting = true
  visible.value = true
  try {
    await nextTick()
    const particles = await import('@/effects/starIntroParticles')
    if (stopped) return
    engine = await particles.prepareStarIntroEngine()
    if (stopped) return
    const loaded = await engine.load({ id, element: host.value, options: particles.createStarIntroOptions(window.innerWidth) })
    if (stopped) { destroyContainer(loaded); return }
    if (!loaded) { finish(); return }
    container = loaded
    playedInMemory = true
    try { sessionStorage.setItem(SEEN_KEY, '1') } catch { /* 隐私模式使用内存标记 */ }
    playTimer = setTimeout(() => {
      fading.value = true
      // 后台标签页、样式覆盖等情况下 transitionend 可能不触发，定时清理兜底。
      removalTimer = setTimeout(finish, 680)
    }, 4000)
  } catch (error) {
    if (!stopped) console.warn('[StarIntroEffect] 特效加载失败，跳过本次展示', error)
    finish()
  }
}

onMounted(() => {
  motion = window.matchMedia('(prefers-reduced-motion: reduce)')
  if (seen() || motion.matches) { finish(); return }
  motion.addEventListener('change', onMotionChange)
  document.addEventListener('visibilitychange', onVisibilityChange)
  if (!document.hidden) void start()
})
onBeforeUnmount(finish)
</script>

<style scoped>
.star-intro {
  position: fixed;
  inset: 0;
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  /* 脱离主页局部层叠与裁剪，铺满视口；保持在导航和 AI 弹窗之下。 */
  z-index: 10;
  pointer-events: none;
  opacity: 0.72;
  transition: opacity 600ms ease-out;
}
.star-intro.is-fading { opacity: 0; }
.star-intro :deep(canvas) {
  display: block;
  width: 100%;
  height: 100%;
  pointer-events: none;
  filter: drop-shadow(0 0 3px #ffe2a580);
}
@media (prefers-reduced-motion: reduce) { .star-intro { display: none; transition: none; } }
</style>
