import { ref, reactive, computed, onBeforeUnmount } from 'vue'

/**
 * AI 助手悬浮组件（对话图标 + 对话框）的**布局引擎**。
 *
 * ---------------------------------------------------------------------------
 * 为什么是「单例」而不是「每次调用一份状态」
 * ---------------------------------------------------------------------------
 * 图标（AiAvatar）与对话框（AiChatDialog）是**两个兄弟组件**，却必须共享同一份坐标：
 *   - 拖动图标   ⇒ 对话框要跟着走；
 *   - 拖动对话框 ⇒ 图标要跟着走；
 *   - 对话框被 `v-if="store.isOpen"` 卸载再挂载后，位置也不能丢。
 * 因此本模块的坐标/尺寸状态定义在**模块作用域**（`layout`），
 * `useResizablePanel()` / `useFloatingPanelState()` 都只是把同一份状态包一层返回。
 *
 * ---------------------------------------------------------------------------
 * 位置模型：图标与对话框是「刚性配对」
 * ---------------------------------------------------------------------------
 * 两者各自保存左上角坐标（`icon*` / `panel*`），**移动时施加同一个位移**，
 * 所以相对关系永远不变 —— 图标就是这对组合的锚点。
 * 拉伸（resize）只改对话框自身，不动图标：这是窗口类 UI 的常规手感，
 * 也让四个角都能听话地跟着指针走。
 *
 * 视觉默认值与改造前完全一致（图标 `bottom-8 right-8`、对话框 `bottom-28 right-8`）：
 *   iconX = vw - 32 - 64
 *   iconY = vh - 32 - 64
 *   panelX = iconX + 64 - panelWidth     （右边缘与图标对齐）
 *   panelY = iconY - 16 - panelHeight    （底边在图标上方 16px）
 *
 * ---------------------------------------------------------------------------
 * 局部坐标 vs 视口坐标
 * ---------------------------------------------------------------------------
 * 坐标一律是**视口坐标**（等价于 `position: fixed` 的 left/top）。
 * 组件只用 `left/top` 定位，**不再用 `bottom/right`** —— 混用两套锚点会让
 * 拉伸公式出现「方向取反」的经典 bug（历史注释见 git）。
 */

/** 尺寸/持久化的默认配置。两处调用点共用同一份，避免「谁先 mount 谁定配置」的不确定性。 */
export const AI_PANEL_OPTIONS = {
  storageKey: 'unicorn.aiChat.panelSize',
  defaultWidth: 384, // 与改造前的 w-96 保持一致
  defaultHeight: 500,
  minWidth: 320,
  minHeight: 320,
  maxWidth: 900,
  maxHeight: 900,
  viewportMargin: 24,
}

/** 图标直径，与 `AiAvatar.vue` 的 `w-16 h-16` 一致 */
const ICON_SIZE = 64
/** 对话框底边与图标顶边的间距，复刻改造前 bottom-28(112) - (8+64+40) 的观感 */
const PAIR_GAP = 16
/** 视口最小安全边距 */
const EDGE = 8
/** 图标默认内缩，复刻改造前 `bottom-8 right-8` */
const DEFAULT_ICON_INSET = 32
/** 超过这个位移才算「拖动」，否则按点击处理（保证点图标能开合对话） */
const DRAG_THRESHOLD = 3
/** 拿不到 window（构建期/SSR）时的兜底视口，只为让样式算得出具体数值 */
const FALLBACK_VIEWPORT = { w: 1280, h: 800 }

const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi)

// ---- 配置（首次调用可覆盖，之后忽略） -------------------------------------
let opts = { ...AI_PANEL_OPTIONS }
let optsLocked = false

// ---- 单例状态 -------------------------------------------------------------
const layout = reactive({
  iconX: 0,
  iconY: 0,
  panelX: 0,
  panelY: 0,
  width: AI_PANEL_OPTIONS.defaultWidth,
  height: AI_PANEL_OPTIONS.defaultHeight,
})

const viewport = reactive({ w: FALLBACK_VIEWPORT.w, h: FALLBACK_VIEWPORT.h })

const resizing = ref(false)
const dragging = ref(false)
/** 本次指针交互是否已构成「拖动」。点击处理里用它区分「点一下」和「拖了一把」。 */
const didDrag = ref(false)
const ready = ref(false)

let initialized = false
let subscribers = 0

// ---- 派生 -----------------------------------------------------------------
/** 视口允许的尺寸上限：配置上限与视口可用区域的较小值（窄屏自动收窄） */
const bounds = computed(() => {
  const availW = Math.max(200, viewport.w - opts.viewportMargin * 2)
  const availH = Math.max(200, viewport.h - opts.viewportMargin * 2)
  return {
    maxW: Math.min(opts.maxWidth, availW),
    maxH: Math.min(opts.maxHeight, availH),
  }
})

/** 兼容旧调用：外层容器尺寸 */
const style = computed(() => ({
  width: layout.width + 'px',
  height: layout.height + 'px',
}))

/** 对话框定位（视口坐标，配合 `position: fixed`） */
const panelStyle = computed(() => ({
  left: layout.panelX + 'px',
  top: layout.panelY + 'px',
}))

/** 图标定位（视口坐标） */
const iconStyle = computed(() => ({
  left: layout.iconX + 'px',
  top: layout.iconY + 'px',
}))

// ---- 内部工具 -------------------------------------------------------------
function syncViewport() {
  if (typeof window === 'undefined') return
  viewport.w = window.innerWidth
  viewport.h = window.innerHeight
}

function snapshot() {
  return {
    iconX: layout.iconX,
    iconY: layout.iconY,
    panelX: layout.panelX,
    panelY: layout.panelY,
    width: layout.width,
    height: layout.height,
  }
}

/** 把尺寸夹回合法区间（视口变化、读取存档后都要跑一次） */
function normalize() {
  layout.width = clamp(layout.width, opts.minWidth, bounds.value.maxW)
  layout.height = clamp(layout.height, opts.minHeight, bounds.value.maxH)
}

/**
 * 计算「刚性配对」允许的位移区间。
 * 图标与对话框都必须完整留在视口内，所以取两者约束的**交集**；
 * 若交集为空（视口极小）则退化为「以右/下边界为准」，至少不让面板跑到屏幕外。
 */
function clampPairDelta(dx, dy, snap) {
  const loX = Math.max(EDGE - snap.iconX, EDGE - snap.panelX)
  const hiX = Math.min(
    viewport.w - EDGE - ICON_SIZE - snap.iconX,
    viewport.w - EDGE - snap.width - snap.panelX,
  )
  const loY = Math.max(EDGE - snap.iconY, EDGE - snap.panelY)
  const hiY = Math.min(
    viewport.h - EDGE - ICON_SIZE - snap.iconY,
    viewport.h - EDGE - snap.height - snap.panelY,
  )
  return {
    dx: hiX < loX ? hiX : clamp(dx, loX, hiX),
    dy: hiY < loY ? hiY : clamp(dy, loY, hiY),
  }
}

/** 按快照 + 位移整体移动图标与对话框（两者位移相同，相对关系不变） */
function applyPairDelta(dx, dy, snap) {
  const { dx: cdx, dy: cdy } = clampPairDelta(dx, dy, snap)
  layout.iconX = snap.iconX + cdx
  layout.iconY = snap.iconY + cdy
  layout.panelX = snap.panelX + cdx
  layout.panelY = snap.panelY + cdy
}

function defaultIconX() {
  return Math.max(EDGE, viewport.w - DEFAULT_ICON_INSET - ICON_SIZE)
}

function defaultIconY() {
  return Math.max(EDGE, viewport.h - DEFAULT_ICON_INSET - ICON_SIZE)
}

/** 默认状态下对话框右边缘与图标对齐、底边在图标上方 PAIR_GAP */
function defaultPanelX(iconX) {
  return iconX + ICON_SIZE - layout.width
}

function defaultPanelY(iconY) {
  return iconY - PAIR_GAP - layout.height
}

/** 回到默认位置（尺寸不变） */
function resetToDefault() {
  normalize()
  layout.iconX = defaultIconX()
  layout.iconY = defaultIconY()
  layout.panelX = defaultPanelX(layout.iconX)
  layout.panelY = defaultPanelY(layout.iconY)
  ensureVisible()
}

/**
 * 把对话框整体拉回视口（图标随动）。
 * 视口缩小 / 存档来自更大屏幕 / 首次打开都必须跑一次，否则面板可能整个在屏幕外。
 */
function ensureVisible() {
  normalize()
  const snap = snapshot()
  let dx = 0
  let dy = 0
  if (snap.panelX + snap.width > viewport.w - EDGE) {
    dx = viewport.w - EDGE - snap.width - snap.panelX
  } else if (snap.panelX < EDGE) {
    dx = EDGE - snap.panelX
  }
  if (snap.panelY + snap.height > viewport.h - EDGE) {
    dy = viewport.h - EDGE - snap.height - snap.panelY
  } else if (snap.panelY < EDGE) {
    dy = EDGE - snap.panelY
  }
  if (dx || dy) applyPairDelta(dx, dy, snap)
}

// ---- 持久化 ---------------------------------------------------------------
function persist() {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(
      opts.storageKey,
      JSON.stringify({
        width: layout.width,
        height: layout.height,
        iconX: layout.iconX,
        iconY: layout.iconY,
        panelX: layout.panelX,
        panelY: layout.panelY,
      }),
    )
  } catch {
    /* 隐私模式等场景写入失败：静默忽略，不影响本次会话 */
  }
}

function load() {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(opts.storageKey)
    return raw ? JSON.parse(raw) : null
  } catch {
    /* 存储被禁用 / JSON 损坏：忽略，用默认值 */
    return null
  }
}

/** 首次调用即完成初始化，保证首帧就有具体坐标（不会闪一下再跳到右下角） */
function ensureInit() {
  if (initialized) return
  initialized = true
  syncViewport()

  const saved = load()
  if (Number.isFinite(saved?.width)) layout.width = saved.width
  if (Number.isFinite(saved?.height)) layout.height = saved.height
  normalize()

  // 兼容旧存档：早期只存 {width, height}，没有坐标 —— 此时按默认位置摆放
  const hasPos =
    Number.isFinite(saved?.iconX) &&
    Number.isFinite(saved?.iconY) &&
    Number.isFinite(saved?.panelX) &&
    Number.isFinite(saved?.panelY)

  if (hasPos) {
    layout.iconX = saved.iconX
    layout.iconY = saved.iconY
    layout.panelX = saved.panelX
    layout.panelY = saved.panelY
    ensureVisible()
  } else {
    resetToDefault()
  }
  ready.value = true
}

// ---- 拖动（图标与对话框共用） ---------------------------------------------
/**
 * 开始整体拖动。图标与对话框的 `@pointerdown` 都绑它。
 *
 * ⚠️ 这里**故意不调用 `e.preventDefault()`**：在 pointerdown 上 preventDefault 会
 * 抑制浏览器派发的兼容鼠标事件（含 `click`），而图标的「点一下开合对话」正是靠
 * `click` 触发的 —— 一旦被抑制，图标就再也点不开了。
 * 防选中改由元素上的 `select-none` + 拖拽期给 `body` 加 `select-none` 解决；
 * 触屏滚动由元素上的 `touch-none`（`touch-action: none`）解决。
 */
function startPairDrag(e) {
  if (e.button !== undefined && e.button !== 0) return
  ensureInit()
  const snap = snapshot()
  const startX = e.clientX
  const startY = e.clientY
  let moved = false

  didDrag.value = false
  dragging.value = true

  const onMove = (ev) => {
    const dx = ev.clientX - startX
    const dy = ev.clientY - startY
    if (!moved && Math.abs(dx) < DRAG_THRESHOLD && Math.abs(dy) < DRAG_THRESHOLD) return
    moved = true
    didDrag.value = true
    applyPairDelta(dx, dy, snap)
  }

  const finish = () => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', finish)
    window.removeEventListener('pointercancel', finish)
    dragging.value = false
    document.body.classList.remove('select-none')
    if (moved) persist()
  }

  document.body.classList.add('select-none')
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', finish)
  window.addEventListener('pointercancel', finish)

  // ⚠️ 这里**故意不做 `setPointerCapture`**。
  // pointer capture 会把 pointerup / 兼容鼠标事件重定向到捕获元素，而图标的
  // 「点一下开合对话」是靠**内层 div** 的 click 处理器触发的（外层只有 pointerdown）。
  // 一旦捕获设在外层，实测事件序列会变成：
  //     pointerdown -> SPAN(内层 emoji)
  //     pointerup   -> DIV[data-ai-icon](外层，被重定向)
  //     click       -> DIV[data-ai-icon](外层)
  // 于是内层的 @click 永远收不到 —— 图标再也点不开。
  // 只靠 window 监听即可覆盖「指针移出元素」；代价是指针移出**浏览器窗口**后
  // 会丢跟踪，这与改造前图标拖动的行为一致，可接受。
}

// ---- 拉伸（八向） ---------------------------------------------------------
let resizeCleanup = null

/**
 * 开始拉伸面板。
 * @param {string|PointerEvent} a 方向（`n`/`s`/`e`/`w`/`ne`/`nw`/`se`/`sw`）；旧签名直接传事件
 * @param {PointerEvent} [b] 方向为字符串时的事件
 */
function startResize(a, b) {
  const direction = typeof a === 'string' ? a : 'nw'
  const e = typeof a === 'string' ? b : a
  if (!e) return
  if (e.button !== undefined && e.button !== 0) return
  e.preventDefault()
  e.stopPropagation()
  ensureInit()

  const snap = snapshot()
  const startX = e.clientX
  const startY = e.clientY
  const hasE = direction.includes('e')
  const hasW = direction.includes('w')
  const hasS = direction.includes('s')
  const hasN = direction.includes('n')

  resizing.value = true
  document.body.classList.add('select-none')

  const onMove = (ev) => {
    if (!resizing.value) return
    const dx = ev.clientX - startX
    const dy = ev.clientY - startY
    const { maxW, maxH } = bounds.value

    let w = snap.width
    let h = snap.height
    if (hasE) w = snap.width + dx
    if (hasW) w = snap.width - dx
    if (hasS) h = snap.height + dy
    if (hasN) h = snap.height - dy

    const cw = clamp(w, opts.minWidth, maxW)
    const ch = clamp(h, opts.minHeight, maxH)

    // 被夹住时「对面那条边」必须保持不动，否则手感会飘
    let x = snap.panelX
    let y = snap.panelY
    if (hasW) x = snap.panelX + (snap.width - cw)
    if (hasN) y = snap.panelY + (snap.height - ch)

    layout.width = cw
    layout.height = ch
    // 变大后可能顶出视口，钳回边界（尺寸上限本身已受视口约束，正常不会触发）
    layout.panelX = clamp(x, EDGE, Math.max(EDGE, viewport.w - EDGE - cw))
    layout.panelY = clamp(y, EDGE, Math.max(EDGE, viewport.h - EDGE - ch))
  }

  const finish = (ev) => {
    if (!resizing.value) return
    resizing.value = false
    document.body.classList.remove('select-none')
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', finish)
    window.removeEventListener('pointercancel', finish)
    resizeCleanup = null
    if (ev?.pointerId != null && ev?.target?.releasePointerCapture) {
      try {
        ev.target.releasePointerCapture(ev.pointerId)
      } catch {
        /* 捕获已释放，忽略 */
      }
    }
    persist()
  }

  resizeCleanup = finish
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', finish)
  window.addEventListener('pointercancel', finish)

  if (e.currentTarget?.setPointerCapture && e.pointerId != null) {
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      /* 不支持捕获时退回 window 监听 */
    }
  }
}

/** 双击角handle：在「默认尺寸」与「放大尺寸」之间切换，便于一键还原 */
function toggleDefaultSize() {
  ensureInit()
  if (layout.width === opts.defaultWidth && layout.height === opts.defaultHeight) {
    layout.width = clamp(opts.defaultWidth * 1.6, opts.minWidth, bounds.value.maxW)
    layout.height = clamp(opts.defaultHeight * 1.4, opts.minHeight, bounds.value.maxH)
  } else {
    layout.width = clamp(opts.defaultWidth, opts.minWidth, bounds.value.maxW)
    layout.height = clamp(opts.defaultHeight, opts.minHeight, bounds.value.maxH)
  }
  // 尺寸变化后右下角可能顶出视口；面板锚在左上角，只需把它拉回来
  layout.panelX = clamp(
    layout.panelX,
    EDGE,
    Math.max(EDGE, viewport.w - EDGE - layout.width),
  )
  layout.panelY = clamp(
    layout.panelY,
    EDGE,
    Math.max(EDGE, viewport.h - EDGE - layout.height),
  )
  persist()
}

function onViewportResize() {
  syncViewport()
  ensureVisible()
  persist()
}

/** 引用计数：对话框会被 v-if 反复卸载，不能让它把图标还在用的监听器摘掉 */
function attach() {
  ensureInit()
  subscribers += 1
  if (subscribers === 1 && typeof window !== 'undefined') {
    window.addEventListener('resize', onViewportResize)
  }
}

function detach() {
  subscribers = Math.max(0, subscribers - 1)
  if (subscribers === 0 && typeof window !== 'undefined') {
    window.removeEventListener('resize', onViewportResize)
    resizeCleanup?.()
    document.body.classList.remove('select-none')
  }
}

/**
 * 面板布局入口（对话框用）。
 * `options` 只在**首次**调用时生效 —— 状态是单例，后调用者无法再改配置。
 */
export function useResizablePanel(options) {
  if (options && !optsLocked) {
    opts = { ...opts, ...options }
  }
  optsLocked = true
  ensureInit()
  onBeforeUnmount(detach)

  return {
    width: computed(() => layout.width),
    height: computed(() => layout.height),
    style,
    panelStyle,
    iconStyle,
    resizing,
    dragging,
    didDrag,
    ready,
    startPairDrag,
    startResize,
    toggleDefaultSize,
    resetToDefault,
    normalize,
    ensureVisible,
    attach,
    detach,
  }
}

/**
 * 只读+可拖的轻量入口（图标用）。与 `useResizablePanel` 共享同一份单例状态，
 * 因此图标拖动会实时反映到对话框上。
 */
export function useFloatingPanelState() {
  ensureInit()
  onBeforeUnmount(detach)

  return {
    resizing,
    dragging,
    didDrag,
    ready,
    iconStyle,
    panelStyle,
    startPairDrag,
    attach,
    detach,
  }
}
