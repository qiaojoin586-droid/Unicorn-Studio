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

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 路由切换滚动复位（优化方案 F-03）：浏览器前进/后退保留原位置，锚点平滑滚动，其余回到顶部
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  },
})

export default router
