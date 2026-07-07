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
    path: '/learning',
    name: 'Learning',
    component: () => import('@/pages/LearningPage.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
