<template>
  <div id="app" class="flex flex-col min-h-screen text-gray-900">
    <AppHeader />

    <!-- Main content: flex-1 fills remaining space, pt-20 offsets fixed header -->
    <main class="flex-1 pt-20">
      <router-view />
    </main>

    <AppFooter />
    <AiAssistant />
    <MouseTrail />
  </div>
</template>

<script setup>
import { defineAsyncComponent } from 'vue'
import AppHeader from '@/components/AppHeader.vue'
import AppFooter from '@/components/AppFooter.vue'
import MouseTrail from '@/components/MouseTrail.vue'

// AI 助手（头像+对话框）非首屏必需，异步加载减小首屏入口包（优化方案 F-11）
const AiAssistant = defineAsyncComponent(() => import('@/components/AiAssistant.vue'))
</script>
