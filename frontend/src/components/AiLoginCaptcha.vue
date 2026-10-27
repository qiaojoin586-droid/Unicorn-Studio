<template>
  <!-- canvas 宽度由内联样式给（与画布内部的 W 保持一致），高度用 h-12 与输入框同高 -->
  <canvas
    ref="canvasEl"
    class="h-12 shrink-0 cursor-pointer rounded-full border border-violet-200 transition-opacity hover:opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/40"
    style="width: 110px; height: 48px"
    role="button"
    tabindex="0"
    aria-label="图形验证码"
    title="点击换一张"
    @click="refresh"
    @keydown="onKeydown"
  />
</template>

<script setup>
import { onMounted, ref } from 'vue'

/** 画布 CSS 尺寸 */
const W = 110
const H = 48

/** 去掉了易混淆的 0/O、1/I/l */
const CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789'

/**
 * 浅底深字的配色（写死，不依赖 CSS 变量 —— 本项目没有参考包那套 --color-* 令牌）。
 * 参考包就是这么做的（浅底 + 深色字符），验证码的首要目标是「一眼能认出来」，
 * 之前改成暗底浅紫字，字符和干扰线同色系糊在一起，根本认不出。
 */
const TONE = {
  bgFrom: '#ffffff',
  bgTo: '#f5f3ff', // violet-50
  noise: '#a78bfa', // violet-400，只做干扰，靠 globalAlpha 压到很淡
  char: '#312e81', // indigo-900，单色比双色交替更好认
}

const emit = defineEmits(['generated'])

const canvasEl = ref(null)
/** 当前答案。生成与绘制分开：重绘只换样式不换答案 */
let currentCode = ''

function createCode(len = 4) {
  let code = ''
  for (let i = 0; i < len; i++) {
    code += CHARS[Math.floor(Math.random() * CHARS.length)]
  }
  return code
}

/** 把 currentCode 画到画布上（不生成新码） */
function draw() {
  const canvas = canvasEl.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  // jsdom 等环境下 getContext 返回 null，必须安全退出
  if (!ctx) return

  // HiDPI：按 dpr 放大位图，再缩放绘图坐标，之后一律按 CSS 像素作图
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  if (canvas.width !== W * dpr || canvas.height !== H * dpr) {
    canvas.width = W * dpr
    canvas.height = H * dpr
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

  // 底色
  const grad = ctx.createLinearGradient(0, 0, W, H)
  grad.addColorStop(0, TONE.bgFrom)
  grad.addColorStop(1, TONE.bgTo)
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, W, H)

  // 干扰曲线（透明度走 globalAlpha，避免手写 rgba 拼接）。条数与浓度都刻意压低，
  // 浅底上稍微重一点就会把字符盖住
  ctx.strokeStyle = TONE.noise
  ctx.lineWidth = 1
  for (let i = 0; i < 3; i++) {
    ctx.globalAlpha = 0.1 + Math.random() * 0.1
    ctx.beginPath()
    ctx.moveTo(Math.random() * W, Math.random() * H)
    ctx.bezierCurveTo(
      Math.random() * W, Math.random() * H,
      Math.random() * W, Math.random() * H,
      Math.random() * W, Math.random() * H,
    )
    ctx.stroke()
  }

  // 干扰点
  ctx.fillStyle = TONE.noise
  for (let i = 0; i < 16; i++) {
    ctx.globalAlpha = 0.1 + Math.random() * 0.12
    ctx.beginPath()
    ctx.arc(Math.random() * W, Math.random() * H, 1, 0, Math.PI * 2)
    ctx.fill()
  }
  ctx.globalAlpha = 1

  // 字符：统一深色，不做色相抖动
  ctx.font = 'bold 22px "JetBrains Mono", "Courier New", monospace'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = TONE.char
  for (let i = 0; i < currentCode.length; i++) {
    ctx.save()
    ctx.translate(16 + i * 24, H / 2)
    ctx.rotate((Math.random() - 0.5) * 0.4)
    ctx.fillText(currentCode[i], 0, 0)
    ctx.restore()
  }
}

/** 换一张新验证码，新码经 generated 事件交给父组件用于本地校验 */
function refresh() {
  currentCode = createCode(4)
  emit('generated', currentCode)
  draw()
}

function onKeydown(e) {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    refresh()
  }
}

onMounted(refresh)

defineExpose({ refresh })
</script>
