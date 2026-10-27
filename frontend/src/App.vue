<template>
  <div class="studio-app" :class="{ 'is-preview': route.meta.preview }">
    <a v-if="!route.meta.preview" class="skip-link" href="#main-content">跳到主要内容</a>
    <AppHeader v-if="!route.meta.preview" />
    <main id="main-content" class="site-main" :style="route.meta.preview ? { paddingTop: 0 } : undefined" tabindex="-1">
      <router-view />
    </main>

    <AppFooter v-if="!route.meta.preview" />
    <AiAssistant v-if="!route.meta.preview" />
    <BackToTop v-if="!route.meta.preview" />
    <MouseTrail v-if="!route.meta.preview" />
  </div>
</template>

<script setup>
import { defineAsyncComponent, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import AppFooter from '@/components/AppFooter.vue'
import BackToTop from '@/components/BackToTop.vue'
import MouseTrail from '@/components/MouseTrail.vue'

// AI 助手（头像+对话框）非首屏必需，异步加载减小首屏入口包（优化方案 F-11）
const AiAssistant = defineAsyncComponent(() => import('@/components/AiAssistant.vue'))
const route = useRoute()
watch(() => route.path, async () => {
  await nextTick()
  document.getElementById('main-content')?.focus({ preventScroll: true })
}, { flush: 'post' })
</script>
