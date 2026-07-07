<template>
  <div class="min-h-screen relative">
    <!-- Fixed full-page video background -->
    <div class="fixed inset-0 z-0 overflow-hidden">
      <video
        class="w-full h-full object-cover"
        autoplay
        muted
        loop
        playsinline
      >
        <source :src="homeVideoUrl" type="video/mp4" />
      </video>
      <div class="absolute inset-0 bg-gray-950/50"></div>
    </div>

    <!-- Hero banner -->
    <section class="relative z-10 -mt-20 pt-32 pb-16 overflow-hidden">
      <div class="absolute inset-0 pointer-events-none">
        <div class="absolute -top-1/3 -right-1/4 w-[500px] h-[500px] bg-indigo-600/15 rounded-full blur-3xl"></div>
        <div class="absolute -bottom-1/3 -left-1/4 w-[400px] h-[400px] bg-purple-600/15 rounded-full blur-3xl"></div>
      </div>
      <div class="relative max-w-6xl mx-auto px-4 text-center">
        <p class="text-indigo-400 text-sm font-semibold tracking-widest uppercase mb-4">Learning Resources</p>
        <h1 class="text-4xl sm:text-5xl font-extrabold text-white mb-4">学习资料</h1>
        <p class="text-gray-400 max-w-xl mx-auto">精选教程与手册，助力快速成长</p>
      </div>
    </section>

    <!-- Learning cards -->
    <section class="relative z-10 max-w-4xl mx-auto px-4 pb-20">
      <div class="space-y-6">
        <div
          v-for="item in resources"
          :key="item.title"
          class="bg-white/5 border border-white/10 rounded-xl p-6 backdrop-blur-sm hover:bg-white/10 hover:border-indigo-500/30 transition-all duration-300 hover:-translate-y-1"
        >
          <h3 class="text-xl font-bold text-white mb-2">{{ item.title }}</h3>
          <p class="text-gray-400 mb-4 leading-relaxed">{{ item.description }}</p>
          <button
            class="inline-flex items-center gap-1.5 text-indigo-400 hover:text-indigo-300 font-semibold text-sm transition"
          >
            查看详情
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
            </svg>
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import homeVideoUrl from '@/image/home.mp4'

const resources = [
  {
    title: '前端入门开发手册',
    description: '涵盖 HTML/CSS/JavaScript 核心基础，适合零基础快速入门',
  },
  {
    title: 'Agent 开发实战指南',
    description: '从 0 到 1 搭建智能体应用，包含完整案例与可运行代码',
  },
  {
    title: 'UI 设计规范手册',
    description: '深色科技风页面设计规范，通用组件库使用说明',
  },
]
</script>
