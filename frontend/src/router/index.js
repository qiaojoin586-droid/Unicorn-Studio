import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/pages/HomePage.vue'),
  },
  {
    path: '/about',
    name: 'About',
    component: () => import('@/pages/AboutPage.vue'),
  },
  {
    path: '/works',
    name: 'Works',
    component: () => import('@/pages/WorksPage.vue'),
  },
  {
    path: '/works/:slug',
    name: 'WorkDetail',
    component: () => import('@/pages/WorkDetailPage.vue'),
  },
  {
    path: '/learning',
    name: 'Learning',
    component: () => import('@/pages/LearningPage.vue'),
  },
  {
    path: '/learning/:id',
    name: 'LearningDetail',
    component: () => import('@/pages/LearningDetailPage.vue'),
  },
  {
    path: '/join',
    name: 'Join',
    component: () => import('@/pages/JoinPage.vue'),
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/pages/NotFoundPage.vue'),
  },
]

/**
 * 判断「当前文档是不是被刷新（F5 / Ctrl-R / 强制重新加载）加载进来的」。
 *
 * 为什么必须单独判一次：vue-router 在页面隐藏/卸载时会把当时的滚动位置写进
 * `history.state.scroll`（createWebHistory 里挂在 `pagehide` / `visibilitychange` 上的钩子），
 * 而 **history.state 是跨刷新保留的**；文档重新加载后的第一次导航满足 `isFirstNavigation`，
 * 它就原样把这个值当作 `savedPosition` 交给我们（见 vue-router 源码 `handleScroll`：
 * `(isFirstNavigation || !isPush) && history.state && history.state.scroll`）。
 * 于是「刷新」与「前进/后退」拿到的 `savedPosition` 长得一模一样，光看参数没法区分 ——
 * 只能借助 Navigation Timing 的 `type`（`reload` / `back_forward` / `navigate`）来分。
 * 老浏览器没有该 API 时返回 false：宁可维持原有语义，也不要误伤前进/后退。
 */
function isReloadNavigation() {
  const navEntry = performance?.getEntriesByType?.('navigation')?.[0]
  if (navEntry) {
    return navEntry.type === 'reload'
  }
  // 老浏览器（Safari ≤13 等）没有 Navigation Timing Level 2 ⇒ 回退到已废弃但仍可用的
  // performance.navigation.type（1 = TYPE_RELOAD）。取不到就返回 false，维持原语义。
  return performance?.navigation?.type === 1
}

// 只有「文档加载后的第一次导航」需要按刷新处理；同一次会话里后续的前进/后退照旧恢复原位置
let firstNavigationHandled = false

const router = createRouter({
  history: createWebHistory(),
  routes,
  /**
   * 路由切换滚动复位（优化方案 F-03）：浏览器前进/后退保留原位置，锚点平滑滚动，其余回到顶部。
   *
   * ⚠️ 刷新（F5）必须单独判掉，否则「刷新后回到页面顶部」这条需求不会生效：
   * 只要用户滚到过页面中途再刷新，vue-router 就会把刷新前的位置当成 savedPosition 交回来，
   * 下面那句 `if (savedPosition) return savedPosition` 会老老实实滚回去 —— 表现为
   * 「刷新后停在半中腰」。成因与判别方式见上面 isReloadNavigation() 的注释。
   *
   * 另注：浏览器原生的滚动恢复（`history.scrollRestoration`）不用我们操心 ——
   * vue-router 只要配置了 scrollBehavior 就会把它置为 `'manual'`，
   * 所以这里的现象不是浏览器自作主张，而是上面那条 savedPosition 分支。
   */
  scrollBehavior(to, from, savedPosition) {
    if (!firstNavigationHandled) {
      firstNavigationHandled = true
      if (isReloadNavigation()) return { top: 0 }
    }
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  },
})

export default router
