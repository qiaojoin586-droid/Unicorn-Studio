import { ref, computed, onBeforeUnmount } from 'vue'

/**
 * 对话框拖拽拉伸（resize）。
 *
 * 设计要点：
 * - 面板以 `bottom/right` 定位（贴右下角），因此**向左拖 = 变宽、向下拖 = 变矮**，
 *   尺寸计算要按这个方向取反，不能照搬「左上角定位」的公式。
 * - 拉伸只改变宽高，不改变锚点（右下角不动），避免窗口跳动。
 * - 尺寸写入 localStorage，刷新后保持用户习惯；读取时用 try/catch + 数值校验兜底，
 *   存储被禁用或数据损坏都不影响使用。
 * - 全程 pointer 事件 + `setPointerCapture`，鼠标移出窗口也不丢跟踪；
 *   拖拽期间给 body 加 `select-none` 防误选文本。
 *
 * @param {object} opts
 * @param {string} opts.storageKey 尺寸持久化 key
 * @param {number} opts.defaultWidth 初始宽
 * @param {number} opts.defaultHeight 初始高
 * @param {number} opts.minWidth 最小宽
 * @param {number} opts.minHeight 最小高
 * @param {number} opts.maxWidth 最大宽（同时受视口宽度约束）
 * @param {number} opts.maxHeight 最大高（同时受视口高度约束）
 * @param {number} opts.viewportMargin 与视口边缘的安全间距（px）
 */
export function useResizablePanel({
  storageKey,
  defaultWidth = 384,
  defaultHeight = 500,
  minWidth = 320,
  minHeight = 320,
  maxWidth = 900,
  maxHeight = 900,
  viewportMargin = 24,
} = {}) {
  const width = ref(defaultWidth)
  const height = ref(defaultHeight)
  const resizing = ref(false)

  /** 视口允许的实际上限：取「配置上限」与「视口可用区域」的较小值 */
  const bounds = computed(() => {
    // 非浏览器环境（SSR/构建期）不做裁剪
    if (typeof window === 'undefined') {
      return { maxW: maxWidth, maxH: maxHeight }
    }
    return {
      maxW: Math.max(minWidth, Math.min(maxWidth, window.innerWidth - viewportMargin * 2)),
      maxH: Math.max(minHeight, Math.min(maxHeight, window.innerHeight - viewportMargin * 2)),
    }
  })

  const style = computed(() => ({
    width: width.value + 'px',
    height: height.value + 'px',
  }))

  const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi)

  function load() {
    if (typeof window === 'undefined') return
    try {
      const raw = window.localStorage.getItem(storageKey)
      if (!raw) return
      const saved = JSON.parse(raw)
      if (Number.isFinite(saved?.width)) width.value = saved.width
      if (Number.isFinite(saved?.height)) height.value = saved.height
      normalize()
    } catch {
      /* 存储被禁用 / JSON 损坏：忽略，用默认尺寸 */
    }
  }

  /** 把当前尺寸夹回合法区间（视口变化、读取存档后都要跑一次） */
  function normalize() {
    const { maxW, maxH } = bounds.value
    width.value = clamp(width.value, minWidth, maxW)
    height.value = clamp(height.value, minHeight, maxH)
  }

  function persist() {
    if (typeof window === 'undefined') return
    try {
      window.localStorage.setItem(
        storageKey,
        JSON.stringify({ width: width.value, height: height.value }),
      )
    } catch {
      /* 隐私模式等场景写入失败：静默忽略，不影响本次会话 */
    }
  }

  // ---- 拖拽主逻辑 ----------------------------------------------------------
  let startX = 0
  let startY = 0
  let startW = 0
  let startH = 0

  function onPointerMove(e) {
    if (!resizing.value) return
    // 面板锚在右下角：鼠标左移(dx<0) => 变宽；鼠标上移(dy<0) => 变高
    const dx = e.clientX - startX
    const dy = e.clientY - startY
    const { maxW, maxH } = bounds.value
    width.value = clamp(startW - dx, minWidth, maxW)
    height.value = clamp(startH - dy, minHeight, maxH)
  }

  function stopResize(e) {
    if (!resizing.value) return
    resizing.value = false
    document.body.classList.remove('select-none')
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', stopResize)
    window.removeEventListener('pointercancel', stopResize)
    if (e?.pointerId != null && e?.target?.releasePointerCapture) {
      try {
        e.target.releasePointerCapture(e.pointerId)
      } catch {
        /* 捕获已释放，忽略 */
      }
    }
    persist()
  }

  /**
   * 在拖拽手柄上调用，开始拉伸。
   * @param {PointerEvent} e
   */
  function startResize(e) {
    // 只响应主键（鼠标左键 / 触控）
    if (e.button !== undefined && e.button !== 0) return
    e.preventDefault()
    startX = e.clientX
    startY = e.clientY
    startW = width.value
    startH = height.value
    resizing.value = true
    document.body.classList.add('select-none')
    // 用 pointer capture 保证指针移出面板/窗口仍能收到事件
    if (e.currentTarget?.setPointerCapture && e.pointerId != null) {
      try {
        e.currentTarget.setPointerCapture(e.pointerId)
      } catch {
        /* 不支持捕获时退回 window 监听 */
      }
    }
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', stopResize)
    window.addEventListener('pointercancel', stopResize)
  }

  /** 双击手柄：在「默认尺寸」与「当前尺寸」之间切换，便于一键还原 */
  function toggleDefaultSize() {
    if (width.value === defaultWidth && height.value === defaultHeight) {
      width.value = clamp(defaultWidth * 1.6, minWidth, bounds.value.maxW)
      height.value = clamp(defaultHeight * 1.4, minHeight, bounds.value.maxH)
    } else {
      width.value = clamp(defaultWidth, minWidth, bounds.value.maxW)
      height.value = clamp(defaultHeight, minHeight, bounds.value.maxH)
    }
    persist()
  }

  function onViewportResize() {
    normalize()
  }

  function attach() {
    load()
    if (typeof window !== 'undefined') {
      window.addEventListener('resize', onViewportResize)
    }
  }

  function detach() {
    if (typeof window !== 'undefined') {
      window.removeEventListener('resize', onViewportResize)
      window.removeEventListener('pointermove', onPointerMove)
      window.removeEventListener('pointerup', stopResize)
      window.removeEventListener('pointercancel', stopResize)
      document.body.classList.remove('select-none')
    }
  }

  onBeforeUnmount(detach)

  return {
    width,
    height,
    style,
    resizing,
    startResize,
    toggleDefaultSize,
    normalize,
    attach,
    detach,
  }
}
