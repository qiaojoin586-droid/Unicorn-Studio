<template>
  <div class="admin-layout" :class="{ 'is-collapsed': collapsed, 'drawer-open': drawerOpen }">
    <a class="admin-skip" href="#admin-main">跳到工作区</a>
    <div v-if="isMobile && drawerOpen" class="sidebar-backdrop" aria-hidden="true" @click="closeDrawer()"></div>
    <aside id="admin-sidebar" ref="sidebar" class="sidebar" :aria-label="isMobile ? '管理导航抽屉' : '管理导航'" :aria-hidden="isMobile && !drawerOpen ? 'true' : undefined" :aria-modal="isMobile && drawerOpen ? 'true' : undefined" :role="isMobile && drawerOpen ? 'dialog' : undefined" :inert="isMobile && !drawerOpen">
      <router-link class="sidebar-brand" to="/" aria-label="独角兽工作室内容工作台"><img :src="brandMarkLight" alt="" width="36" height="36" /><span v-if="!collapsed || isMobile"><strong>独角兽工作室</strong><small>CONTENT WORKSPACE</small></span></router-link>
      <button v-if="isMobile" ref="drawerClose" class="drawer-close" type="button" aria-label="关闭管理导航" @click="closeDrawer()">×</button>
      <nav class="sidebar-navigation" aria-label="功能导航">
        <el-menu :default-active="activeMenu" :collapse="collapsed && !isMobile" :collapse-transition="false" router class="side-menu" @select="onMenuSelect">
          <el-menu-item-group v-for="group in menuGroups" :key="group.label" :title="collapsed && !isMobile ? '' : group.label">
            <el-menu-item v-for="item in group.items" :key="item.path" :index="'/' + item.path"><el-icon><component :is="item.meta?.icon || 'Document'" /></el-icon><template #title>{{ item.meta?.title }}</template></el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </nav>
      <div class="sidebar-foot"><span class="sidebar-signal" aria-hidden="true"></span><span v-if="!collapsed || isMobile">保持好奇，一起创造。</span></div>
    </aside>
    <div class="workspace" :inert="isMobile && drawerOpen">
      <header class="workspace-header">
        <div class="header-left"><button ref="menuTrigger" type="button" class="collapse-trigger" :aria-label="isMobile ? '打开管理导航' : collapsed ? '展开侧栏' : '收起侧栏'" :aria-expanded="isMobile ? drawerOpen : !collapsed" aria-controls="admin-sidebar" @click="toggleNavigation"><el-icon><Expand v-if="collapsed || isMobile" /><Fold v-else /></el-icon></button><el-breadcrumb separator="/" class="breadcrumb"><el-breadcrumb-item :to="{ path: '/' }">工作台</el-breadcrumb-item><el-breadcrumb-item v-if="route.meta.title">{{ route.meta.title }}</el-breadcrumb-item></el-breadcrumb></div>
        <div class="header-actions"><a :href="publicSiteUrl()" target="_blank" rel="noopener noreferrer" class="view-site">查看主站 <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 19 19 5M5 5h14v14" stroke="currentColor" stroke-width="1.5" /></svg><span class="sr-only">（新窗口）</span></a><el-dropdown @command="onCommand"><button type="button" class="user-info" aria-label="账户菜单"><el-avatar :size="30" :src="auth.user?.avatar" class="user-avatar">{{ avatarText }}</el-avatar><span class="user-name">{{ auth.user?.nickname || auth.user?.username }}</span><el-icon><ArrowDown /></el-icon></button><template #dropdown><el-dropdown-menu><el-dropdown-item command="profile"><el-icon><User /></el-icon>个人中心</el-dropdown-item><el-dropdown-item command="logout" divided><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
      </header>
      <main id="admin-main" ref="workspaceMain" class="workspace-main" tabindex="-1"><router-view /></main>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { permissionRoutes } from '@/router'
import { publicSiteUrl } from '@/utils/site'
import brandMarkLight from '@/generated/brand-mark-light.svg'
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const isMobile = ref(false)
const drawerOpen = ref(false)
const sidebar = ref<HTMLElement | null>(null)
const menuTrigger = ref<HTMLButtonElement | null>(null)
const drawerClose = ref<HTMLButtonElement | null>(null)
const workspaceMain = ref<HTMLElement | null>(null)
const menuItems = computed(() => (permissionRoutes[0].children || []).filter(item => !item.meta?.hidden && (!item.meta?.permission || auth.hasPerm(item.meta.permission as string))))
const sections = [
  { label: '工作概览', paths: ['dashboard'] },
  { label: '内容与页面', paths: ['presentation', 'site-presentation', 'works', 'news', 'carousel', 'capabilities', 'services', 'about', 'join', 'footer', 'learning', 'learning/categories'] },
  { label: '互动运营', paths: ['comments', 'messages', 'users'] },
  { label: '数据与系统', paths: ['settings', 'ai-config', 'backups', 'retention', 'logs', 'sql-monitor'] },
  { label: '账号权限', paths: ['admins', 'roles'] },
]
const menuGroups = computed(() => {
  const known = new Set(sections.flatMap(section => section.paths))
  return sections.map(section => ({ label: section.label, items: menuItems.value.filter(item => section.paths.includes(item.path) || (section.label === '内容与页面' && !known.has(item.path))) })).filter(group => group.items.length)
})
const activeMenu = computed(() => {
  const item = [...menuItems.value].sort((a, b) => b.path.length - a.path.length).find(item => route.path === '/' + item.path || route.path.startsWith('/' + item.path + '/'))
  return item ? '/' + item.path : route.path
})
const avatarText = computed(() => (auth.user?.nickname || auth.user?.username || 'A').charAt(0).toUpperCase())
function syncViewport() { isMobile.value = window.innerWidth < 960; if (!isMobile.value) drawerOpen.value = false }
function toggleNavigation() { if (isMobile.value) drawerOpen.value = !drawerOpen.value; else collapsed.value = !collapsed.value }
function closeDrawer(restoreFocus = true) { const wasOpen = drawerOpen.value; drawerOpen.value = false; if (wasOpen && restoreFocus) nextTick(() => menuTrigger.value?.focus()) }
async function onMenuSelect() { if (isMobile.value) { closeDrawer(false); await nextTick(); workspaceMain.value?.focus() } }
function onKeydown(event: KeyboardEvent) {
  if (!isMobile.value || !drawerOpen.value) return
  if (event.key === 'Escape') { event.preventDefault(); closeDrawer(); return }
  if (event.key !== 'Tab') return
  const items = [...(sidebar.value?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], [tabindex="0"]') || [])].filter(item => item.getClientRects().length)
  const first = items[0], last = items[items.length - 1]
  if (!sidebar.value?.contains(document.activeElement)) { event.preventDefault(); first?.focus() }
  else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
watch([drawerOpen, isMobile], async ([open, mobile]) => { if (open && mobile) { await nextTick(); drawerClose.value?.focus() } })
watch(() => route.path, async () => { if (isMobile.value) closeDrawer(false); await nextTick(); workspaceMain.value?.scrollTo({ top: 0 }); if (isMobile.value) workspaceMain.value?.focus() })
async function onCommand(command: string) {
  if (command === 'profile') { router.push('/profile'); return }
  if (command !== 'logout') return
  try { await ElMessageBox.confirm('确定退出当前账户吗？', '退出登录', { confirmButtonText: '退出登录', cancelButtonText: '继续工作', type: 'warning' }); await auth.logout(); router.push('/login') } catch { /* A cancelled confirmation keeps the current workspace. */ }
}
onMounted(() => { syncViewport(); window.addEventListener('resize', syncViewport, { passive: true }); window.addEventListener('keydown', onKeydown) })
onBeforeUnmount(() => { window.removeEventListener('resize', syncViewport); window.removeEventListener('keydown', onKeydown) })
</script>
<style scoped>
.admin-layout { display: grid; grid-template-columns: 240px minmax(0, 1fr); height: 100dvh; overflow: hidden; background: var(--admin-canvas); transition: grid-template-columns 150ms; }
.admin-layout.is-collapsed { grid-template-columns: 76px minmax(0, 1fr); }
.admin-skip { position: fixed; top: 10px; left: 12px; z-index: 3000; padding: 10px 15px; background: white; color: #121316; transform: translateY(-150%); }
.admin-skip:focus { transform: none; }
.sidebar { position: relative; min-height: 0; display: flex; flex-direction: column; background: var(--brand-ink, #121316); color: #f4f2ec; border-right: 1px solid #ffffff12; }
.sidebar-brand { display: flex; align-items: center; gap: 11px; height: 83px; flex-shrink: 0; padding: 20px 22px; text-decoration: none; white-space: nowrap; overflow: hidden; }
.sidebar-brand img { flex-shrink: 0; }
.sidebar-brand strong { display: block; font-size: 15px; font-weight: 550; letter-spacing: .02em; }
.sidebar-brand small { display: block; margin-top: 7px; font: 8px ui-monospace, monospace; letter-spacing: .1em; color: #a7a8af; }
.is-collapsed .sidebar-brand { padding-inline: 20px; }
.sidebar-navigation { flex: 1; overflow-y: auto; min-height: 0; padding: 10px 10px 24px; }
.side-menu { --el-menu-bg-color: transparent; --el-menu-text-color: #bcbcc2; --el-menu-active-color: #fff; --el-menu-hover-bg-color: #ffffff08; border: 0; }
.side-menu:not(.el-menu--collapse) { width: 100%; }
.side-menu :deep(.el-menu-item-group__title) { padding: 24px 12px 11px; color: #8c8d96; font-size: 10px; line-height: 1; letter-spacing: .08em; }
.side-menu :deep(.el-menu-item) { height: 43px; line-height: 43px; border-radius: 4px; margin-bottom: 3px; padding-inline: 12px !important; font-size: 13px; }
.side-menu :deep(.el-menu-item .el-icon) { font-size: 17px; margin-right: 11px; }
.side-menu :deep(.el-menu-item.is-active) { position: relative; background: #ffffff0e; color: #f4f2ec; }
.side-menu :deep(.el-menu-item.is-active)::after { content: ''; position: absolute; right: 13px; width: 5px; height: 5px; background: #ff8c95; border-radius: 50%; }
.side-menu.el-menu--collapse { width: 56px; }
.side-menu.el-menu--collapse :deep(.el-menu-item-group__title) { display: none; }
.side-menu.el-menu--collapse :deep(.el-menu-item) { justify-content: center; height: 46px; }
.side-menu.el-menu--collapse :deep(.el-menu-item .el-icon) { margin: 0; }
.side-menu.el-menu--collapse :deep(.el-menu-item.is-active)::after { right: 2px; }
.sidebar-foot { display: flex; align-items: center; justify-content: center; gap: 9px; min-height: 55px; border-top: 1px solid #ffffff12; color: #8c8d96; font-size: 10px; }
.sidebar-signal { width: 5px; height: 5px; border-radius: 50%; background: var(--brand-accent, #c92d3b); }
.drawer-close { position: absolute; right: 10px; top: 20px; width: 40px; height: 40px; border: 0; background: transparent; color: #f4f2ec; font-size: 28px; cursor: pointer; }
.workspace { display: flex; flex-direction: column; min-height: 0; min-width: 0; }
.workspace-header { display: flex; justify-content: space-between; align-items: center; gap: 22px; height: 64px; flex-shrink: 0; background: #faf9f6; border-bottom: 1px solid var(--admin-line); padding: 0 26px 0 18px; }
.header-left, .header-actions { display: flex; align-items: center; gap: 20px; min-width: 0; }
.collapse-trigger { display: grid; place-items: center; flex-shrink: 0; height: 40px; width: 40px; border: 1px solid transparent; background: transparent; color: #62636a; border-radius: 4px; cursor: pointer; }
.collapse-trigger:hover { border-color: var(--admin-line); background: #eeede8; color: #121316; }
.collapse-trigger .el-icon { font-size: 18px; }
.breadcrumb :deep(.el-breadcrumb__inner) { font-size: 12px; font-weight: 400; color: #77787e; }
.breadcrumb :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) { color: #303138; }
.view-site { display: inline-flex; align-items: center; gap: 10px; min-height: 40px; text-decoration: none; color: #62636a; font-size: 12px; white-space: nowrap; }
.view-site:hover { color: #121316; }
.user-info { display: flex; align-items: center; gap: 10px; min-height: 40px; max-width: 200px; border: 0; background: transparent; color: #414249; cursor: pointer; }
.user-avatar { background: #e5e2db; color: #414249; font-size: 12px; flex-shrink: 0; }
.user-name { max-width: 110px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.user-info > .el-icon { font-size: 10px; }
.workspace-main { min-height: 0; min-width: 0; overflow: auto; flex: 1; scroll-padding-top: 20px; }
.workspace-main:focus { outline: none; }
.sidebar-backdrop { position: fixed; inset: 0; z-index: 99; background: #12131688; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
@media (max-width: 959px) { .admin-layout, .admin-layout.is-collapsed { grid-template-columns: minmax(0, 1fr); } .sidebar { position: fixed; inset: 0 auto 0 0; width: 260px; z-index: 100; transform: translateX(-100%); transition: transform 180ms; } .drawer-open .sidebar { transform: none; } .is-collapsed .sidebar-brand { padding-inline: 20px; } .workspace-header { padding-inline: 8px 16px; } .header-left { gap: 4px; } .header-actions { gap: 14px; } .user-name { display: none; } .breadcrumb :deep(.el-breadcrumb__item:first-child) { display: none; } }
@media (max-width: 374px) { .view-site { font-size: 11px; gap: 5px; } .header-actions { gap: 8px; } .workspace-header { gap: 6px; } }
</style>
