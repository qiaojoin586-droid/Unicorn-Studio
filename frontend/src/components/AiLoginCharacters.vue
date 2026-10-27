<template>
  <!--
    四个卡通角色（参考包 AnimatedCharacters 的 Vue 版）
    画布 550×400，角色全部 position:absolute; bottom:0，靠 z-index 分层。

    ⚠️ 模板里绑定的都是**常量**样式对象，props 一个都没用到 —— 也就是说本组件只渲染一次。
    鼠标跟随、眨眼、偷看全部走 render() 命令式改 style：原版就是靠这个做到「mousemove 不触发
    一次重渲染」，用 setState 的话 60fps 的鼠标移动会把整棵树重渲染一遍。
  -->
  <div ref="holderEl" class="relative h-full w-full min-h-[200px]">
    <div class="absolute bottom-0 left-1/2" :style="stageStyle" aria-hidden="true">

      <!-- 紫：最高，后排 -->
      <div ref="purpleEl" :style="ST.purple">
        <div ref="purpleEyesEl" :style="ST.eyesPurple">
          <div :ref="slot(purpleEyeballs, 0)" :style="ST.eyeballLg">
            <div :ref="slot(purplePupils, 0)" :style="ST.pupilLg"></div>
          </div>
          <div :ref="slot(purpleEyeballs, 1)" :style="ST.eyeballLg">
            <div :ref="slot(purplePupils, 1)" :style="ST.pupilLg"></div>
          </div>
        </div>
      </div>

      <!-- 黑：中排 -->
      <div ref="blackEl" :style="ST.black">
        <div ref="blackEyesEl" :style="ST.eyesBlack">
          <div :ref="slot(blackEyeballs, 0)" :style="ST.eyeballSm">
            <div :ref="slot(blackPupils, 0)" :style="ST.pupilSm"></div>
          </div>
          <div :ref="slot(blackEyeballs, 1)" :style="ST.eyeballSm">
            <div :ref="slot(blackPupils, 1)" :style="ST.pupilSm"></div>
          </div>
        </div>
      </div>

      <!-- 橙：前排左，半圆（圆角 = 宽度一半），没有白眼球 -->
      <div ref="orangeEl" :style="ST.orange">
        <div ref="orangeEyesEl" :style="ST.eyesOrange">
          <div :ref="slot(orangePupils, 0)" :style="ST.pupilBare"></div>
          <div :ref="slot(orangePupils, 1)" :style="ST.pupilBare"></div>
        </div>
      </div>

      <!-- 黄：前排右，半圆 + 嘴 -->
      <div ref="yellowEl" :style="ST.yellow">
        <div ref="yellowEyesEl" :style="ST.eyesYellow">
          <div :ref="slot(yellowPupils, 0)" :style="ST.pupilBare"></div>
          <div :ref="slot(yellowPupils, 1)" :style="ST.pupilBare"></div>
        </div>
        <div ref="mouthEl" :style="ST.mouth"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/** 角色四色。参考包的规格要求硬编码，换配色改这四个字面量即可 */
const CH_PURPLE = '#6c3ff5'
const CH_BLACK = '#2d2d2d'
const CH_ORANGE = '#ff9b6b'
const CH_YELLOW = '#e8d754'

const CANVAS_W = 550
const CANVAS_H = 400
/** 瞳孔朝鼠标偏移的最大距离（像素） */
const PUPIL_MAX_DIST = 5

const BODY_TRANSITION = 'transform 700ms ease-in-out, height 700ms ease-in-out, left 700ms ease-in-out, top 700ms ease-in-out'
const EYES_TRANSITION = 'left 700ms ease-in-out, top 700ms ease-in-out'
/** 前排（橙 / 黄）跟进更快 */
const EYES_TRANSITION_FAST = 'left 200ms ease-out, top 200ms ease-out'

const props = defineProps({
  /** 任一输入框聚焦中 */
  isTyping: { type: Boolean, default: false },
  /** 密码是否明文可见 */
  showPassword: { type: Boolean, default: false },
  /** 密码已输入长度（为 0 时角色对「可见性变化」无反应） */
  passwordLength: { type: Number, default: 0 },
})

/** 常量样式表：模板只绑定一次，之后一律由 render() 命令式改写 */
const ST = {
  stage: {
    width: CANVAS_W + 'px',
    height: CANVAS_H + 'px',
    transformOrigin: 'bottom center',
  },
  // ⚠️ 这里刻意不写 height：它由 render() 按状态改写（400 / 440），
  // 一旦留在绑定里，组件每次重渲染都会被 patchStyle 打回固定值。
  purple: { position: 'absolute', bottom: '0', left: '70px', width: '180px', background: CH_PURPLE, borderRadius: '10px 10px 0 0', zIndex: 1, transformOrigin: 'bottom center', transition: BODY_TRANSITION },
  black: { position: 'absolute', bottom: '0', left: '240px', width: '120px', height: '310px', background: CH_BLACK, borderRadius: '8px 8px 0 0', zIndex: 2, transformOrigin: 'bottom center', transition: BODY_TRANSITION },
  orange: { position: 'absolute', bottom: '0', left: '0', width: '240px', height: '200px', background: CH_ORANGE, borderRadius: '120px 120px 0 0', zIndex: 3, transformOrigin: 'bottom center', transition: BODY_TRANSITION },
  yellow: { position: 'absolute', bottom: '0', left: '310px', width: '140px', height: '230px', background: CH_YELLOW, borderRadius: '70px 70px 0 0', zIndex: 4, transformOrigin: 'bottom center', transition: BODY_TRANSITION },
  mouth: { position: 'absolute', width: '80px', height: '4px', background: CH_BLACK, borderRadius: '999px', transition: 'left 200ms ease-out, top 200ms ease-out' },
  eyesPurple: { position: 'absolute', display: 'flex', gap: '32px', transition: EYES_TRANSITION },
  eyesBlack: { position: 'absolute', display: 'flex', gap: '24px', transition: EYES_TRANSITION },
  eyesOrange: { position: 'absolute', display: 'flex', gap: '32px', transition: EYES_TRANSITION_FAST },
  eyesYellow: { position: 'absolute', display: 'flex', gap: '24px', transition: EYES_TRANSITION_FAST },
  eyeballLg: { width: '18px', height: '18px', borderRadius: '999px', background: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', transition: 'height 150ms ease-out' },
  eyeballSm: { width: '16px', height: '16px', borderRadius: '999px', background: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', transition: 'height 150ms ease-out' },
  pupilLg: { width: '7px', height: '7px', borderRadius: '999px', background: CH_BLACK, transition: 'transform 100ms ease-out' },
  pupilSm: { width: '6px', height: '6px', borderRadius: '999px', background: CH_BLACK, transition: 'transform 100ms ease-out' },
  pupilBare: { width: '12px', height: '12px', borderRadius: '999px', background: CH_BLACK, transition: 'transform 100ms ease-out' },
}

const holderEl = ref(null)
const purpleEl = ref(null)
const blackEl = ref(null)
const orangeEl = ref(null)
const yellowEl = ref(null)
const purpleEyesEl = ref(null)
const blackEyesEl = ref(null)
const orangeEyesEl = ref(null)
const yellowEyesEl = ref(null)
const mouthEl = ref(null)

/**
 * 画布缩放值。必须走响应式，不能在 fit() 里直接写 el.style.transform ——
 * 模板里 stage 的 :style 归 Vue 管，组件每次重渲染都会把它重放一遍
 * （patchStyle 无条件重设 next 里的每个 key），手写的值会被打回 scale(1)，
 * 表现就是「点一下角色它自己变大且不再缩回去」。
 */
const scale = ref(1)
const stageStyle = computed(() => ({
  ...ST.stage,
  transform: `translateX(-50%) scale(${scale.value})`,
}))

// 白眼球（仅紫 / 黑，眨眼时改高度）与瞳孔（四个角色都有）
const purpleEyeballs = []
const blackEyeballs = []
const purplePupils = []
const blackPupils = []
const orangePupils = []
const yellowPupils = []

/** 模板 ref 收集器：按下标写入，重复调用也不会堆重复项 */
function slot(list, index) {
  return (el) => {
    list[index] = el
  }
}

const mouse = { x: 0, y: 0 }
/**
 * 鼠标是否已经进过登录弹窗。
 * 没进之前整个组件停在静态姿态（见 render() 的 frozen 分支）：不跟随鼠标，也不代入
 * 「打字 / 露密码 / 对视」这些状态。原因是弹窗一出现就自动聚焦了邮箱框，isTyping 立刻为 true，
 * 角色会自己切到对视姿态；再加上此时 mouse 是拿视口中心当默认值的，四个角色的脸会被
 * 一路顶到偏移上限，看着就像"卡错了位置"。
 */
let active = false
let peeking = false
let lookingAtEachOther = false
let rafId = null
let lookTimer = 0

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))

/** 鼠标相对角色中心的偏移 → 面部位移与身体倾斜 */
function faceOf(el) {
  // 鼠标还没进弹窗：五官回正、身体不倾斜，所有人一起静止
  if (!active) return { faceX: 0, faceY: 0, bodySkew: 0 }
  const r = el.getBoundingClientRect()
  const dx = mouse.x - (r.left + r.width / 2)
  // 纵向取 1/3 处作为「面部中心」，与视觉重心一致
  const dy = mouse.y - (r.top + r.height / 3)
  return {
    faceX: clamp(dx / 20, -15, 15),
    faceY: clamp(dy / 30, -10, 10),
    bodySkew: clamp(-dx / 120, -6, 6),
  }
}

/** 单只瞳孔朝鼠标方向偏移，最多 PUPIL_MAX_DIST 像素 */
function pupilOffset(el) {
  const r = el.getBoundingClientRect()
  const dx = mouse.x - (r.left + r.width / 2)
  const dy = mouse.y - (r.top + r.height / 2)
  const d = Math.min(Math.hypot(dx, dy), PUPIL_MAX_DIST)
  const a = Math.atan2(dy, dx)
  return { x: Math.cos(a) * d, y: Math.sin(a) * d }
}

/** 设置一组瞳孔的朝向；不给 fx 时自动朝鼠标 */
function setLook(pupils, fx, fy) {
  pupils.forEach((p) => {
    if (!p) return
    if (!active) {
      // 静止姿态：瞳孔回正
      p.style.transform = 'translate(0px,0px)'
    } else if (fx === undefined) {
      const o = pupilOffset(p)
      p.style.transform = `translate(${o.x}px,${o.y}px)`
    } else {
      p.style.transform = `translate(${fx}px,${fy}px)`
    }
  })
}

/** 主渲染：所有状态分支的唯一定义处 */
function render() {
  const purple = purpleEl.value
  const black = blackEl.value
  const orange = orangeEl.value
  const yellow = yellowEl.value
  const eyesPurple = purpleEyesEl.value
  const eyesBlack = blackEyesEl.value
  const eyesOrange = orangeEyesEl.value
  const eyesYellow = yellowEyesEl.value
  const mouth = mouthEl.value
  if (!purple || !black || !orange || !yellow) return

  // 鼠标还没进弹窗：一律走静态姿态，不代入任何交互状态
  const frozen = !active
  // passwordLength > 0 才参与「藏 / 露」判定：空密码框时角色不该有反应
  const isHiding = !frozen && props.passwordLength > 0 && !props.showPassword
  const revealed = !frozen && props.passwordLength > 0 && props.showPassword
  const excited = !frozen && (props.isTyping || isHiding)
  const looking = !frozen && lookingAtEachOther

  const pp = faceOf(purple)
  const bp = faceOf(black)
  const op = faceOf(orange)
  const yp = faceOf(yellow)

  // ── 身体 ──
  if (revealed) {
    purple.style.height = `${CANVAS_H}px`
    purple.style.transform = 'translateX(0) skewX(0deg)'
  } else if (excited) {
    // 打字 / 藏密码：长高 40px 并前倾
    purple.style.height = `${CANVAS_H + 40}px`
    purple.style.transform = `translateX(40px) skewX(${pp.bodySkew - 12}deg)`
  } else {
    purple.style.height = `${CANVAS_H}px`
    purple.style.transform = `translateX(0) skewX(${pp.bodySkew}deg)`
  }

  if (revealed) {
    black.style.transform = 'translateX(0) skewX(0deg)'
  } else if (looking) {
    black.style.transform = `translateX(20px) skewX(${bp.bodySkew * 1.5 + 10}deg)`
  } else {
    black.style.transform = `translateX(0) skewX(${bp.bodySkew * (excited ? 1.5 : 1)}deg)`
  }

  orange.style.transform = revealed ? 'skewX(0deg)' : `skewX(${op.bodySkew}deg)`
  yellow.style.transform = revealed ? 'skewX(0deg)' : `skewX(${yp.bodySkew}deg)`

  // ── 眼睛 ──
  if (eyesPurple) {
    if (revealed) {
      eyesPurple.style.left = '20px'
      eyesPurple.style.top = '35px'
      setLook(purplePupils, peeking ? 4 : -4, peeking ? 5 : -4)
    } else if (looking) {
      eyesPurple.style.left = '55px'
      eyesPurple.style.top = '65px'
      setLook(purplePupils, 3, 4)
    } else {
      eyesPurple.style.left = `${45 + pp.faceX}px`
      eyesPurple.style.top = `${40 + pp.faceY}px`
      setLook(purplePupils)
    }
  }

  if (eyesBlack) {
    if (revealed) {
      eyesBlack.style.left = '10px'
      eyesBlack.style.top = '28px'
      setLook(blackPupils, -4, -4)
    } else if (looking) {
      eyesBlack.style.left = '32px'
      eyesBlack.style.top = '12px'
      setLook(blackPupils, 0, -4)
    } else {
      eyesBlack.style.left = `${26 + bp.faceX}px`
      eyesBlack.style.top = `${32 + bp.faceY}px`
      setLook(blackPupils)
    }
  }

  if (eyesOrange) {
    if (revealed) {
      eyesOrange.style.left = '50px'
      eyesOrange.style.top = '85px'
      setLook(orangePupils, -5, -4)
    } else {
      eyesOrange.style.left = `${82 + op.faceX}px`
      eyesOrange.style.top = `${90 + op.faceY}px`
      setLook(orangePupils)
    }
  }

  if (eyesYellow) {
    if (revealed) {
      eyesYellow.style.left = '20px'
      eyesYellow.style.top = '35px'
      setLook(yellowPupils, -5, -4)
    } else {
      eyesYellow.style.left = `${52 + yp.faceX}px`
      eyesYellow.style.top = `${40 + yp.faceY}px`
      setLook(yellowPupils)
    }
  }

  if (mouth) {
    if (revealed) {
      mouth.style.left = '10px'
      mouth.style.top = '88px'
    } else {
      mouth.style.left = `${40 + yp.faceX}px`
      mouth.style.top = `${88 + yp.faceY}px`
    }
  }
}

/** 合并到下一帧执行：1000Hz 鼠标每帧可派发十余次 mousemove */
function scheduleRender() {
  if (rafId !== null) return
  rafId = requestAnimationFrame(() => {
    rafId = null
    render()
  })
}

/** 把 550×400 画布缩放到容器可用空间（宽高同时夹取，矮屏不溢出） */
function fit() {
  const el = holderEl.value
  if (!el) return
  const r = el.getBoundingClientRect()
  if (r.width <= 0 || r.height <= 0) return
  scale.value = Math.min(1.25, r.width / CANVAS_W, r.height / CANVAS_H)
}

let resizeObserver = null
let cancelled = false
const timers = new Set()

/**
 * 指针是否已经落在登录弹窗范围内。
 * 不用 mouseenter 判断：弹窗有可能是正好"啪"地出现在指针底下冒出来的，那种情况
 * mouseenter 根本不会触发，人物会一直僵着。按坐标判定就没这个问题。
 */
function inDialog(x, y) {
  const panel = holderEl.value?.closest('[data-ai-login-panel]')
  // 拿不到弹窗（例如离线预览页没有这个标记）就当作已进入，免得永远不动
  if (!panel) return true
  const r = panel.getBoundingClientRect()
  return x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
}

function onMouseMove(e) {
  // 鼠标进弹窗之前人物保持静止；进来的这一瞬间才开始正常跟随
  if (!active && inDialog(e.clientX, e.clientY)) {
    active = true
    // 激活这一帧直接落位，不用等下一帧（之后都交给 scheduleRender 合并）
    mouse.x = e.clientX
    mouse.y = e.clientY
    render()
    return
  }
  if (!active) return
  mouse.x = e.clientX
  mouse.y = e.clientY
  scheduleRender()
}

/** 随机眨眼（紫 + 黑），间隔 3000–7000ms，每次闭眼 150ms */
function startBlink(getEls, rest) {
  const tick = () => {
    const t1 = setTimeout(() => {
      timers.delete(t1)
      if (cancelled) return
      const ebs = getEls().filter(Boolean)
      ebs.forEach((eb) => { eb.style.height = '2px' })
      const t2 = setTimeout(() => {
        timers.delete(t2)
        if (cancelled) return
        ebs.forEach((eb) => { eb.style.height = `${rest}px` })
        tick()
      }, 150)
      timers.add(t2)
    }, 3000 + Math.random() * 4000)
    timers.add(t1)
  }
  tick()
}

/** 密码可见且有内容时的「偷看」，间隔 2000–5000ms，每次 800ms */
function startPeek() {
  const t1 = setTimeout(() => {
    timers.delete(t1)
    if (cancelled) return
    if (props.passwordLength > 0 && props.showPassword) {
      peeking = true
      render()
      const t2 = setTimeout(() => {
        timers.delete(t2)
        if (cancelled) return
        peeking = false
        render()
        startPeek()
      }, 800)
      timers.add(t2)
    } else {
      startPeek()
    }
  }, 2000 + Math.random() * 3000)
  timers.add(t1)
}

// 状态变化 → 重绘（模板没引用 props，所以这是唯一的响应路径）
watch(() => [props.showPassword, props.passwordLength], () => render())

// 输入聚焦 → 紫黑对视 800ms（isTyping 上升沿触发）
watch(
  () => props.isTyping,
  (typing) => {
    lookingAtEachOther = typing
    render()
    if (!typing) return
    clearTimeout(lookTimer)
    lookTimer = setTimeout(() => {
      lookingAtEachOther = false
      render()
    }, 800)
  },
)

onMounted(() => {
  window.addEventListener('mousemove', onMouseMove, { passive: true })
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(fit)
    resizeObserver.observe(holderEl.value)
  }
  window.addEventListener('resize', fit)

  const reduceMotion =
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches

  if (!reduceMotion) {
    startBlink(() => purpleEyeballs, 18)
    startBlink(() => blackEyeballs, 16)
    startPeek()
  }

  // 首帧把过渡全关掉再落位。
  // 原因：眼睛 / 嘴这些绝对定位元素在 CSS 里的 left/top 是 auto（即 0,0），render() 一上来
  // 就要把它们挪到 (45,40) 这类位置。不关过渡的话，每次打开弹窗都能看到五官从左上角滑过去。
  // 直接改 DOM class 而不走响应式，免得顺手触发一次重渲染。
  const root = holderEl.value
  root?.classList.add('chars-no-transition')

  // 首帧直接把静态姿态落位（此时 active 还是 false，render() 走 frozen 分支：
  // 五官回正、身体不倾斜）。鼠标没进弹窗之前一直保持这个样子，
  // 所以不会出现"弹窗一冒出来四个角色脸被顶到一边"的情况。
  render()
  fit()

  // 等首帧真的画出来再放开过渡，之后的状态变化才有动画
  requestAnimationFrame(() => {
    requestAnimationFrame(() => root?.classList.remove('chars-no-transition'))
  })
})

onBeforeUnmount(() => {
  cancelled = true
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('resize', fit)
  resizeObserver?.disconnect()
  clearTimeout(lookTimer)
  timers.forEach(clearTimeout)
  timers.clear()
  if (rafId !== null) cancelAnimationFrame(rafId)
  rafId = null
})
</script>

<style scoped>
/* 首帧专用：让角色直接出现在最终位置。由 onMounted 手动加、下一帧摘掉 */
.chars-no-transition,
.chars-no-transition * {
  transition: none !important;
}
</style>
