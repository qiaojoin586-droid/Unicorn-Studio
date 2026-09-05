import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/** 静态路由：登录/403/404 */
export const staticRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { title: '无权限', public: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', public: true },
  },
]

/** 权限路由：meta.permission 为权限码，无码的登录即可见 */
export const permissionRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        meta: { title: '仪表盘', icon: 'Odometer', permission: 'dashboard:view' },
      },
      {
        path: 'works',
        name: 'Works',
        component: () => import('@/views/works/WorksListView.vue'),
        meta: { title: '作品管理', icon: 'Picture', permission: 'work:list' },
      },
      {
        path: 'works/edit',
        name: 'WorkCreate',
        component: () => import('@/views/works/WorkEditView.vue'),
        meta: { title: '新增作品', hidden: true, permission: 'work:create' },
      },
      {
        path: 'works/edit/:id',
        name: 'WorkEdit',
        component: () => import('@/views/works/WorkEditView.vue'),
        meta: { title: '编辑作品', hidden: true, permission: 'work:edit' },
      },
      {
        path: 'news',
        name: 'News',
        component: () => import('@/views/news/NewsListView.vue'),
        meta: { title: '新闻管理', icon: 'Document', permission: 'news:list' },
      },
      {
        path: 'news/edit',
        name: 'NewsCreate',
        component: () => import('@/views/news/NewsEditView.vue'),
        meta: { title: '新增新闻', hidden: true, permission: 'news:create' },
      },
      {
        path: 'news/edit/:id',
        name: 'NewsEdit',
        component: () => import('@/views/news/NewsEditView.vue'),
        meta: { title: '编辑新闻', hidden: true, permission: 'news:edit' },
      },
      {
        path: 'carousel',
        name: 'Carousel',
        component: () => import('@/views/carousel/CarouselListView.vue'),
        meta: { title: '轮播图管理', icon: 'Monitor', permission: 'carousel:list' },
      },
      {
        path: 'services',
        name: 'Services',
        component: () => import('@/views/services/ServicesView.vue'),
        meta: { title: '招收方向管理', icon: 'Compass', permission: 'service:list' },
      },
      {
        path: 'capabilities',
        name: 'Capabilities',
        component: () => import('@/views/capabilities/CapabilitiesView.vue'),
        meta: { title: '培养能力管理', icon: 'MagicStick', permission: 'capability:list' },
      },
      {
        path: 'learning',
        name: 'Learning',
        component: () => import('@/views/learning/LearningListView.vue'),
        meta: { title: '学习资料', icon: 'Collection', permission: 'learning:list' },
      },
      {
        path: 'learning/categories',
        name: 'LearningCategories',
        component: () => import('@/views/learning/LearningCategoriesView.vue'),
        meta: { title: '资料分类', icon: 'Files', permission: 'learning:category' },
      },
      {
        path: 'comments',
        name: 'Comments',
        component: () => import('@/views/comments/CommentsView.vue'),
        meta: { title: '评论审核', icon: 'ChatDotRound', permission: 'comment:list' },
      },
      {
        path: 'messages',
        name: 'Messages',
        component: () => import('@/views/messages/MessagesView.vue'),
        meta: { title: '留言管理', icon: 'Message', permission: 'message:list' },
      },
      {
        path: 'users',
        name: 'Users',
        component: () => import('@/views/users/UsersView.vue'),
        meta: { title: '用户管理', icon: 'User', permission: 'user:list' },
      },
      {
        path: 'users/:id',
        name: 'UserDetail',
        component: () => import('@/views/users/UserDetailView.vue'),
        meta: { title: '用户详情', hidden: true, permission: 'user:view' },
      },
      {
        path: 'admins',
        name: 'Admins',
        component: () => import('@/views/admins/AdminsView.vue'),
        meta: { title: '管理员管理', icon: 'UserFilled', permission: 'admin:list' },
      },
      {
        path: 'roles',
        name: 'Roles',
        component: () => import('@/views/roles/RolesView.vue'),
        meta: { title: '角色权限', icon: 'Key', permission: 'role:list' },
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/settings/SettingsView.vue'),
        meta: { title: '系统设置', icon: 'Setting', permission: 'system:settings' },
      },
      {
        path: 'backups',
        name: 'Backups',
        component: () => import('@/views/backups/BackupsView.vue'),
        meta: { title: '数据备份', icon: 'FolderOpened', permission: 'system:backup' },
      },
      {
        path: 'logs',
        name: 'Logs',
        component: () => import('@/views/logs/LogsView.vue'),
        meta: { title: '审计日志', icon: 'Tickets', permission: 'log:list' },
      },
      {
        path: 'sql-monitor',
        name: 'SqlMonitor',
        component: () => import('@/views/sql-monitor/SqlMonitorView.vue'),
        meta: { title: 'SQL 监控', icon: 'DataLine', permission: 'system:sql' },
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/profile/ProfileView.vue'),
        meta: { title: '个人中心', icon: 'Avatar', hidden: true },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [...staticRoutes, ...permissionRoutes],
})

/** 守卫：登录态 → 拉取 me/权限 → 按权限过滤动态路由 → 无权限 403。 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    return true
  }
  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (!auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      auth.clear()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }
  // 权限校验：路由声明了 permission 但用户没有 → 403
  const required = to.meta.permission as string | undefined
  if (required && !auth.hasPerm(required)) {
    return { path: '/403' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 独角兽工作室后台` : '独角兽工作室后台'
})

export default router
