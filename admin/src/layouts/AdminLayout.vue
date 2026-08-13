<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '220px'" class="aside">
      <div class="logo">
        <span class="logo-text" v-if="!collapsed">🦄 独角兽工作室</span>
        <span class="logo-text" v-else>🦄</span>
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        background-color="#001529"
        text-color="rgba(255,255,255,0.68)"
        active-text-color="#ffffff"
      >
        <el-menu-item
          v-for="item in menuItems"
          :key="item.path"
          :index="item.path"
        >
          <el-icon><component :is="item.meta.icon" /></el-icon>
          <template #title>{{ item.meta.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <Expand v-if="collapsed" />
            <Fold v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.meta.title">{{ route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <el-dropdown @command="onCommand">
          <span class="user-info">
            <el-avatar :size="30" :src="auth.user?.avatar">{{ avatarText }}</el-avatar>
            <span class="user-name">{{ auth.user?.nickname || auth.user?.username }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
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
.aside {
  background: #001529;
  overflow-x: hidden;
  transition: width 0.2s;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  white-space: nowrap;
}
.aside :deep(.el-menu) {
  border-right: none;
}
.aside :deep(.el-menu-item.is-active) {
  background: #409eff;
}
.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  z-index: 10;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.collapse-btn {
  font-size: 20px;
  cursor: pointer;
  color: #606266;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #303133;
}
.user-name {
  font-size: 14px;
}
.main {
  background: #f0f2f5;
  padding: 16px;
}
</style>
