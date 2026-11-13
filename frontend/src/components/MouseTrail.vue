<template>
  <canvas v-if="enabled" ref="canvasRef" aria-hidden="true" class="mouse-trail" :style="{ zIndex: Z_INDEX }"></canvas>
</template>

<script setup>
// 鼠标拖尾（星尘形态）：设计见 docs/主站鼠标拖尾效果设计方案.md §3.3/§3.7。
// 固定槽数组 + alive 标志（稳态零分配）、dt 归一化（跨刷新率一致）、
// pointermove + pointerType 过滤（混合设备只跟真实鼠标）。
import { onBeforeUnmount, onMounted, ref, nextTick } from 'vue'

// ===== 参数（全部"标准帧"口径，施乘 k = dt / 16.67）=====
const Z_INDEX = 45
const SPAWN_DISTANCE = 18 // px 密度常数：期望粒数 = 帧内路程/该值 × 每批粒数
const PARTICLES_PER_SPAWN = 3 // 每批粒子数（光斑:星芒 ≈ 7:3 随机）
const MAX_PARTICLES = 220 // 固定槽数；环形游标，占满后覆盖最老存活槽
const LIFETIME_MIN = 600 // ms（900 ± 300）
const LIFETIME_MAX = 1200
const STAR_LIFETIME_MIN = 1000 // 星芒取长半区
const STAR_LIFETIME_MAX = 1400
const SIZE_DOT_MIN = 2.5
const SIZE_DOT_MAX = 5
const SIZE_STAR_MIN = 6
const SIZE_STAR_MAX = 12
const VELOCITY_INHERIT = 0.3 // 继承鼠标速度
const VELOCITY_CAP = 18 // px/标准帧，防极端甩动把粒子甩出屏
const JITTER_MIN = 0.2
const JITTER_MAX = 0.8
const DAMPING = 0.92 // /标准帧，按 0.92^k 指数施加
const FLOAT_UP = -0.01 // px/标准帧²，轻微上浮（星尘感）
const MAX_OPACITY = 0.95
const TWINKLE_MIN = 6 // Hz
const TWINKLE_MAX = 12
const DPR_CAP = 2
const DT_CLAMP = 32 // ms，防标签页恢复后瞬移
const STD_FRAME = 16.67
const RESIZE_DEBOUNCE = 150
const TRAIL_OFF_KEY = 'mouse-trail' // localStorage 值为 'off' 时手动关闭

// 保留已调优的尺寸、亮度与粒子密度，颜色与品牌红和暖白呼应。
const COLORS = ['#ff8c95', '#ff8c95', '#e6b4b0', '#e6b4b0', '#f4f2ec', '#cf777d', '#d5c6bb']
const TWO_PI = Math.PI * 2

const canvasRef = ref(null)

// ===== 环境判定：同步运行中变化的动效与指针偏好 =====
function readLocalStorageOff() {
  try {
    return localStorage.getItem(TRAIL_OFF_KEY) === 'off'
  } catch {
    return false
  }
}

const motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)')
const pointerPreference = window.matchMedia('(pointer: fine)')
const enabled = ref(false)
let attached = false
let disposed = false

let ctx = null
let cssW = 0
let cssH = 0
let running = false
let rafId = 0
let lastTime = 0
let cursor = 0 // 环形分配游标
let mouseX = 0
let mouseY = 0
let mouseActive = false
let lastSpawnX = 0
let lastSpawnY = 0
let carry = 0 // 撒点余量：期望粒数的小数部分结转，保证按路程均匀、不随帧率/事件率饥饿
let needAlign = true // 首个事件 / 重进窗口 / 瞬移后需对齐撒点游标（不画跨屏长线）
let resizeTimer = 0
const MAX_SEGMENT = 200 // 单帧最大插值路程（px），超出视为瞬移只对齐不撒点

// 固定槽位：启动时一次性分配，运行期零对象分配
const slots = Array.from({ length: MAX_PARTICLES }, () => ({
  alive: false,
  x: 0,
  y: 0,
  vx: 0,
  vy: 0,
  life: 0,
  maxLife: 1,
  size: 0,
  isStar: false,
  spriteIndex: 0,
  rot: 0,
  rotSpeed: 0,
  twinkleFreq: 0,
  twinklePhase: 0,
}))

let dotSprites = []
let starSprite = null

// ===== 贴图预渲染（避免每帧渐变计算与 shadowBlur）=====
function makeDotSprite(color) {
  const s = 32
  const c = document.createElement('canvas')
  c.width = c.height = s
  const g = c.getContext('2d')
  const grad = g.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2)
  // 白热核心 → 品牌色 → 透明：白心保证在明亮背景上同样醒目
  grad.addColorStop(0, '#ffffff')
  grad.addColorStop(0.25, color)
  grad.addColorStop(0.55, color + 'aa')
  grad.addColorStop(1, color + '00')
  g.fillStyle = grad
  g.fillRect(0, 0, s, s)
  return c
}

function makeStarSprite() {
  const s = 32
  const c = document.createElement('canvas')
  c.width = c.height = s
  const g = c.getContext('2d')
  const glow = g.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2)
  glow.addColorStop(0, 'rgba(255,255,255,0.7)')
  glow.addColorStop(0.5, 'rgba(199,210,254,0.35)')
  glow.addColorStop(1, 'rgba(199,210,254,0)')
  g.fillStyle = glow
  g.fillRect(0, 0, s, s)
  g.fillStyle = '#ffffff'
  g.beginPath()
  const cx = s / 2
  const cy = s / 2
  const R = s / 2 - 2
  const r = 3.6
  for (let i = 0; i < 8; i++) {
    const ang = (Math.PI / 4) * i - Math.PI / 2
    const rad = i % 2 === 0 ? R : r
    const x = cx + Math.cos(ang) * rad
    const y = cy + Math.sin(ang) * rad
    if (i === 0) g.moveTo(x, y)
    else g.lineTo(x, y)
  }
  g.closePath()
  g.fill()
  return c
}

// ===== 画布 =====
function resizeCanvas() {
  const dpr = Math.min(window.devicePixelRatio || 1, DPR_CAP)
  cssW = window.innerWidth
  cssH = window.innerHeight
  canvasRef.value.width = Math.round(cssW * dpr)
  canvasRef.value.height = Math.round(cssH * dpr)
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
}

function clearAllSlots() {
  for (let i = 0; i < MAX_PARTICLES; i++) slots[i].alive = false
}

// ===== 生成与更新 =====
function acquire() {
  // 环形游标：槽位空闲则复用；占满则覆盖最老存活槽
  const p = slots[cursor]
  cursor = (cursor + 1) % MAX_PARTICLES
  return p
}

function spawn(x, y, inheritVx, inheritVy) {
  const isStar = Math.random() < 0.3
  const p = acquire()
  p.alive = true
  p.isStar = isStar
  p.x = x + (Math.random() - 0.5) * 6
  p.y = y + (Math.random() - 0.5) * 6
  const jitter = JITTER_MIN + Math.random() * (JITTER_MAX - JITTER_MIN)
  const a = Math.random() * TWO_PI
  const ix = inheritVx * VELOCITY_INHERIT
  const iy = inheritVy * VELOCITY_INHERIT
  const inheritLen = Math.hypot(ix, iy)
  const cap = inheritLen > VELOCITY_CAP ? VELOCITY_CAP / inheritLen : 1
  p.vx = ix * cap + Math.cos(a) * jitter
  p.vy = iy * cap + Math.sin(a) * jitter
  if (isStar) {
    p.maxLife = STAR_LIFETIME_MIN + Math.random() * (STAR_LIFETIME_MAX - STAR_LIFETIME_MIN)
    p.size = SIZE_STAR_MIN + Math.random() * (SIZE_STAR_MAX - SIZE_STAR_MIN)
    p.rot = Math.random() * TWO_PI
    p.rotSpeed = (Math.random() - 0.5) * 0.12
    p.twinkleFreq = TWINKLE_MIN + Math.random() * (TWINKLE_MAX - TWINKLE_MIN)
    p.twinklePhase = Math.random() * TWO_PI
  } else {
    p.maxLife = LIFETIME_MIN + Math.random() * (LIFETIME_MAX - LIFETIME_MIN)
    p.size = SIZE_DOT_MIN + Math.random() * (SIZE_DOT_MAX - SIZE_DOT_MIN)
  }
  p.life = p.maxLife
  p.spriteIndex = Math.floor(Math.random() * dotSprites.length)
}

function startLoop() {
  if (running) return
  running = true
  lastTime = performance.now()
  rafId = requestAnimationFrame(frame)
}

function stopLoop() {
  // 停帧前画布必须干净，防止残留冻结粒子
  running = false
  if (rafId) cancelAnimationFrame(rafId)
  rafId = 0
  if (ctx) ctx.clearRect(0, 0, cssW, cssH)
}

function frame(now) {
  if (!running) return
  rafId = requestAnimationFrame(frame)

  const dt = Math.min(now - lastTime, DT_CLAMP)
  lastTime = now
  const k = dt / STD_FRAME
  const damp = Math.pow(DAMPING, k)

  // 按路程均匀撒点：期望粒数 = 本帧路程 / SPAWN_DISTANCE × 每批粒数，小数余量结转（carry）。
  // 不用「攒够 24px 才撒」的阈值写法：循环自停再重启时若把游标对齐到当前位置，路程会被
  // 反复清零导致生成饥饿（0 路程→停帧→再对齐）；期望值写法在任意事件率/刷新率下密度均匀。
  if (mouseActive) {
    let segX = mouseX - lastSpawnX
    let segY = mouseY - lastSpawnY
    const segLen = Math.hypot(segX, segY)
    if (segLen > MAX_SEGMENT) {
      // 瞬移（切屏/程序性跳变）：只对齐不撒点，避免跨屏长线
      lastSpawnX = mouseX
      lastSpawnY = mouseY
    } else if (segLen > 0) {
      // 鼠标速度换算为 px/标准帧（dt 归一化），继承其 30%，防极端甩动加帽
      let ivx = (segX * (STD_FRAME / dt)) * VELOCITY_INHERIT
      let ivy = (segY * (STD_FRAME / dt)) * VELOCITY_INHERIT
      const ilen = Math.hypot(ivx, ivy)
      if (ilen > VELOCITY_CAP) {
        ivx *= VELOCITY_CAP / ilen
        ivy *= VELOCITY_CAP / ilen
      }
      carry += (segLen / SPAWN_DISTANCE) * PARTICLES_PER_SPAWN
      const n = Math.floor(carry)
      if (n > 0) {
        carry -= n
        carry = Math.min(carry, PARTICLES_PER_SPAWN) // 余量封顶，防异常累积
        for (let i = 1; i <= n; i++) {
          const t = i / (n + 1)
          spawn(lastSpawnX + segX * t, lastSpawnY + segY * t, ivx, ivy)
        }
      }
      lastSpawnX = mouseX
      lastSpawnY = mouseY
    }
  }

  let aliveCount = 0
  for (let i = 0; i < MAX_PARTICLES; i++) {
    const p = slots[i]
    if (!p.alive) continue
    p.life -= dt
    if (p.life <= 0) {
      p.alive = false
      continue
    }
    p.x += p.vx * k
    p.y += p.vy * k
    p.vx *= damp
    p.vy = p.vy * damp + FLOAT_UP * k
    if (p.isStar) {
      p.rot += p.rotSpeed * k
      p.twinklePhase += (p.twinkleFreq * TWO_PI * dt) / 1000
    }
    aliveCount++
  }

  ctx.clearRect(0, 0, cssW, cssH)
  if (aliveCount > 0) {
    // 标准混合：加法混合(lighter)在明亮 Hero 视频/海报上会叠加裁剪至不可见，
    // 饱和色粒子用 source-over 才能在任意背景上保持可见
    for (let i = 0; i < MAX_PARTICLES; i++) {
      const p = slots[i]
      if (!p.alive) continue
      const t = p.life / p.maxLife
      const twinkle = p.isStar ? 0.7 + 0.3 * Math.sin(p.twinklePhase) : 1
      ctx.globalAlpha = MAX_OPACITY * t * twinkle
      const scale = 0.4 + 0.6 * t
      if (p.isStar) {
        const s = p.size * scale
        ctx.save()
        ctx.translate(p.x, p.y)
        ctx.rotate(p.rot)
        ctx.drawImage(starSprite, -s, -s, s * 2, s * 2)
        ctx.restore()
      } else {
        const s = p.size * scale * 2
        ctx.drawImage(dotSprites[p.spriteIndex], p.x - s, p.y - s, s * 2, s * 2)
      }
    }
    ctx.globalAlpha = 1
  }

  // 空闲自停：无存活粒子即清屏停帧，下一次 mouse 移动会重启
  if (aliveCount === 0) stopLoop()
}

// ===== 事件 =====
function onPointerMove(e) {
  // 混合设备甄别：只跟随真实鼠标（触屏 tap 的兼容性 mouse 事件 pointerType 为 'touch'，触控笔为 'pen'）
  if (e.pointerType !== 'mouse') return
  if (e.buttons || e.target?.closest?.('input, textarea, select, [contenteditable="true"], [data-ai-panel]') || window.getSelection()?.toString()) {
    mouseActive = false
    needAlign = true
    return
  }
  if (needAlign) {
    // 首个事件/重进窗口：仅对齐游标（此刻没有路程可画）
    lastSpawnX = e.clientX
    lastSpawnY = e.clientY
    needAlign = false
  }
  mouseX = e.clientX
  mouseY = e.clientY
  mouseActive = true
  startLoop()
}

function onPointerOut(e) {
  // relatedTarget 为 null 即指针离开窗口：重进视为新起点，不画跨屏长线
  if (!e.relatedTarget) {
    mouseActive = false
    needAlign = true
  }
}

function onResize() {
  clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => {
    resizeCanvas()
    clearAllSlots() // 画布尺寸变了，粒子坐标错位，直接清空
    needAlign = true
  }, RESIZE_DEBOUNCE)
}

function onVisibilityChange() {
  if (document.hidden) {
    if (running) stopLoop()
  } else {
    // 恢复可见：若有存活粒子则续播（dt clamp 保证不瞬移）
    for (let i = 0; i < MAX_PARTICLES; i++) {
      if (slots[i].alive) {
        startLoop()
        break
      }
    }
  }
}

function detachTrail() {
  attached = false
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerout', onPointerOut)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  clearTimeout(resizeTimer)
  if (running) stopLoop()
  if (ctx) clearAllSlots()
  mouseActive = false
  needAlign = true
}

async function syncEnvironment() {
  enabled.value = !motionPreference.matches && pointerPreference.matches && !readLocalStorageOff()
  if (!enabled.value) { detachTrail(); return }
  await nextTick()
  if (disposed || !enabled.value || attached || !canvasRef.value) return
  ctx = canvasRef.value.getContext('2d')
  if (!ctx) return
  dotSprites = COLORS.map(makeDotSprite)
  starSprite = makeStarSprite()
  resizeCanvas()
  attached = true
  window.addEventListener('pointermove', onPointerMove, { passive: true })
  window.addEventListener('pointerout', onPointerOut, { passive: true })
  window.addEventListener('resize', onResize, { passive: true })
  document.addEventListener('visibilitychange', onVisibilityChange)
}

onMounted(() => {
  motionPreference.addEventListener('change', syncEnvironment)
  pointerPreference.addEventListener('change', syncEnvironment)
  syncEnvironment()
})

onBeforeUnmount(() => {
  disposed = true
  motionPreference.removeEventListener('change', syncEnvironment)
  pointerPreference.removeEventListener('change', syncEnvironment)
  detachTrail()
})
</script>

<style scoped>
.mouse-trail {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
</style>
