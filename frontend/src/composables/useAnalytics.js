const BASE_URL = import.meta.env.VITE_API_BASE || ''

function reportPv(path, referer) {
  const body = JSON.stringify({ path, referer })
  // sendBeacon 需以 Blob 显式指定 JSON 类型：默认 text/plain 会被后端 @RequestBody 以 415 拒收
  const queued =
    typeof navigator.sendBeacon === 'function' &&
    navigator.sendBeacon(BASE_URL + '/api/stats/pv', new Blob([body], { type: 'application/json' }))
  if (!queued) {
    // sendBeacon 返回 false（队列满等）时降级 fetch keepalive
    fetch(BASE_URL + '/api/stats/pv', {
      method: 'POST',
      keepalive: true,
      headers: { 'Content-Type': 'application/json' },
      body,
    }).catch(() => {})
  }
}

/**
 * PV 埋点（后端 /api/stats/pv 已就绪，优化方案 F-02）：
 * - 初始导航（vue-router START_LOCATION，from.matched.length === 0）用 document.referrer 记真实外部来源；
 * - 其余站内跳转（含从首页跳出）一律记站内来源，避免把首页跳转误记为外部 referer；
 * - DNT 由后端兜底处理，前端短路减少无效请求。
 */
export function initPvTracking(router) {
  router.afterEach((to, from) => {
    if (navigator.doNotTrack === '1') return
    const referer = from.matched.length === 0 ? document.referrer : from.fullPath
    reportPv(to.fullPath, referer)
  })
}
