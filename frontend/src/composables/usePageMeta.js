import { watchEffect, onUnmounted } from 'vue'

export const DEFAULT_TITLE = '独角兽工作室'
export const DEFAULT_DESCRIPTION =
  '独角兽工作室以技术为笔、以创意为墨，专注于 AI 智能应用开发、Agent 开发与大模型学术研究。'

let descriptionEl = null

function descriptionMetaEl() {
  if (descriptionEl) return descriptionEl
  descriptionEl = document.head.querySelector('meta[name="description"]')
  if (!descriptionEl) {
    descriptionEl = document.createElement('meta')
    descriptionEl.setAttribute('name', 'description')
    document.head.appendChild(descriptionEl)
  }
  return descriptionEl
}

/**
 * 每路由标题/描述管理（优化方案 F-04/F-09）。
 * meta 可为对象或返回 { title, description } 的函数（详情页传响应式源）。
 * 用 watch 而非 onMounted：vue-router 在同一路由记录间跳转会复用组件实例，
 * mounted 不会重跑；卸载时复位为站点默认值。
 */
export function usePageMeta(meta) {
  const stop = watchEffect(() => {
    const m = typeof meta === 'function' ? meta() : meta
    document.title = m?.title ? `${m.title} - ${DEFAULT_TITLE}` : DEFAULT_TITLE
    descriptionMetaEl().setAttribute('content', m?.description || DEFAULT_DESCRIPTION)
  })
  onUnmounted(() => {
    // 只停副作用，不复位标题/描述：SPA 站内导航时旧页面卸载钩子可能在新页面
    // 设置标题之后执行，复位会覆盖新标题（Edge 真机实测）；全部路由均已接入
    // usePageMeta 并在挂载时全量设置，无需卸载复位。
    stop()
  })
}
