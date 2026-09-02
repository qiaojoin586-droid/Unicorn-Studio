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
import AppHeader from '@/components/AppHeader.vue'
import AppFooter from '@/components/AppFooter.vue'
import AiAssistant from '@/components/AiAssistant.vue'
import MouseTrail from '@/components/MouseTrail.vue'
</script>
