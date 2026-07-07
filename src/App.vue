<template>
  <div id="app" class="flex flex-col min-h-screen bg-gray-50 text-gray-900">
    <AppHeader />

    <!-- Main content: flex-1 fills remaining space, pt-20 offsets fixed header -->
    <main class="flex-1 pt-20">
      <router-view />
    </main>

    <AppFooter />
  </div>
</template>

<script setup>
import AppHeader from '@/components/AppHeader.vue'
import AppFooter from '@/components/AppFooter.vue'
</script>
