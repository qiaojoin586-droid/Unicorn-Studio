<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '220px'" class="aside">
      <div class="logo">
        <div class="logo-box">🦄</div>
        <div class="logo-text" v-if="!collapsed">
          <div class="logo-name">独角兽工作室</div>
          <div class="logo-sub">CONTENT ADMIN</div>
        </div>
      </div>

      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="side-menu"
      >
        <el-menu-item
          v-for="item in menuItems"
          :key="item.path"
          :index="'/' + item.path"
        >
          <el-icon><component :is="item.meta.icon" /></el-icon>
          <template #title>{{ item.meta.title }}</template>
        </el-menu-item>
      </el-menu>

      <div class="aside-foot" v-if="!collapsed">
        <span>Unicorn Studio · v1.0.0</span>
      </div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <Expand v-if="collapsed" />
            <Fold v-else />
          </el-icon>
          <el-breadcrumb separator="/" class="breadcrumb">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.meta.title">{{ route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <el-dropdown @command="onCommand">
          <span class="user-info">
            <el-avatar :size="32" :src="auth.user?.avatar" class="user-avatar">{{ avatarText }}</el-avatar>
            <span class="user-name">{{ auth.user?.nickname || auth.user?.username }}</span>
            <el-icon class="user-arrow"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">
                <el-icon><User /></el-icon>个人中心
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { permissionRoutes } from '@/router'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)

/** 菜单：权限路由中非 hidden 且用户有权限的项 */
const menuItems = computed(() => {
  const children = permissionRoutes[0].children || []
  return children.filter((r) => !r.meta?.hidden && (!r.meta?.permission || auth.hasPerm(r.meta.permission as string)))
})

const avatarText = computed(() => {
  const name = auth.user?.nickname || auth.user?.username || 'A'
  return name.charAt(0).toUpperCase()
})

async function onCommand(cmd: string) {
  if (cmd === 'profile') {
    router.push('/profile')
  } else if (cmd === 'logout') {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
    await auth.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  height: 100%;
}

/* ---------------- 侧边栏 ---------------- */
.aside {
  position: relative;
  overflow: hidden;
  background-color: #0d1220;
  background-image:
    radial-gradient(560px 260px at 10% -8%, rgba(99, 102, 241, 0.22), transparent 60%),
    radial-gradient(460px 240px at 108% 26%, rgba(168, 85, 247, 0.13), transparent 60%),
    linear-gradient(180deg, #0d1220 0%, #121730 100%);
  transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
}

.logo {
  height: 64px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  white-space: nowrap;
}

.logo-box {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  border-radius: 10px;
  background: linear-gradient(135deg, #6366f1, #a855f7);
  box-shadow: 0 4px 14px rgba(99, 102, 241, 0.4);
}

.logo-name {
  font-size: 15px;
  font-weight: 700;
  color: #fff;
  line-height: 1.2;
}

.logo-sub {
  font-size: 10px;
  letter-spacing: 1.5px;
  color: rgba(255, 255, 255, 0.4);
  margin-top: 2px;
}

.side-menu {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  background: transparent !important;
  border-right: none !important;
  padding: 10px 8px;
}

.side-menu :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
  margin: 2px 0;
  border-radius: 10px;
  color: rgba(255, 255, 255, 0.62);
  font-size: 14px;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.side-menu:not(.el-menu--collapse) :deep(.el-menu-item .el-icon) {
  font-size: 17px;
  margin-right: 4px;
}

.side-menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.side-menu :deep(.el-menu-item.is-active) {
  position: relative;
  color: #fff;
  background: linear-gradient(90deg, rgba(99, 102, 241, 0.38), rgba(168, 85, 247, 0.22));
  box-shadow: inset 0 0 0 1px rgba(139, 92, 246, 0.28);
}

.side-menu :deep(.el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 0 3px 3px 0;
  background: linear-gradient(180deg, #818cf8, #c084fc);
}

/* 折叠态：菜单项垂直居中 */
.side-menu :deep(.el-menu--collapse) {
  width: 64px;
}

.side-menu :deep(.el-menu--collapse .el-menu-item) {
  padding: 0 !important;
  justify-content: center;
}

.aside-foot {
  flex-shrink: 0;
  padding: 14px 0;
  text-align: center;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.aside-foot span {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.32);
  letter-spacing: 0.5px;
}

/* ---------------- 顶栏 ---------------- */
.header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid #eef1f6;
  padding: 0 20px;
  z-index: 10;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.collapse-btn {
  font-size: 18px;
  cursor: pointer;
  color: #64748b;
  padding: 6px;
  border-radius: 8px;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.collapse-btn:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.breadcrumb :deep(.el-breadcrumb__inner),
.breadcrumb :deep(.el-breadcrumb__inner a) {
  font-size: 13px;
  color: #94a3b8;
  font-weight: 400;
}

.breadcrumb :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #0f172a;
  font-weight: 600;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 5px 10px;
  border-radius: 10px;
  transition: background-color 0.2s ease;
  outline: none;
}

.user-info:hover {
  background: #f8fafc;
}

.user-avatar {
  border: 2px solid transparent;
  background:
    linear-gradient(#fff, #fff) padding-box,
    linear-gradient(135deg, #6366f1, #a855f7) border-box;
  color: #4f46e5;
  font-weight: 600;
  flex-shrink: 0;
}

.user-name {
  font-size: 14px;
  color: #334155;
  font-weight: 500;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-arrow {
  font-size: 12px;
  color: #94a3b8;
}

/* ---------------- 内容区 ---------------- */
.main {
  background: #f5f6fa;
  padding: 0;
  overflow-y: auto;
}
</style>
