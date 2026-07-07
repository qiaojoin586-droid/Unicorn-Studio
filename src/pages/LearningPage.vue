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

    <!-- Password Modal -->
    <div
      v-if="!authenticated"
      class="fixed inset-0 z-50 flex items-center justify-center px-4"
    >
      <div class="absolute inset-0 bg-black/70 backdrop-blur-sm"></div>
      <div class="relative bg-gray-900/90 border border-white/10 rounded-2xl p-8 max-w-sm w-full backdrop-blur-sm shadow-2xl">
        <div class="text-center mb-6">
          <div class="text-4xl mb-3">🔒</div>
          <h2 class="text-2xl font-bold text-white mb-2">需要验证</h2>
          <p class="text-gray-400 text-sm">请输入访问密码以查看学习资料</p>
        </div>
        <form @submit.prevent="handleLogin">
          <input
            v-model="password"
            type="password"
            placeholder="请输入密码"
            class="w-full bg-white/5 border border-white/10 rounded-lg px-4 py-3 text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 mb-3 transition"
            autofocus
          />
          <p v-if="error" class="text-red-400 text-sm mb-3">{{ error }}</p>
          <button
            type="submit"
            class="w-full bg-indigo-600 hover:bg-indigo-500 text-white font-semibold py-3 rounded-lg transition"
          >
            确认
          </button>
        </form>
      </div>
    </div>

    <!-- Page content (only shown when authenticated) -->
    <template v-if="authenticated">
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
    </template>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import homeVideoUrl from '@/image/home.mp4'

const CORRECT_PASSWORD = 'unicorn'   // 设置的学习资料访问的密码
const STORAGE_KEY = 'learning_authenticated'

const authenticated = ref(localStorage.getItem(STORAGE_KEY) === 'true')
const password = ref('')
const error = ref('')

function handleLogin() {
  if (password.value === CORRECT_PASSWORD) {
    authenticated.value = true
    localStorage.setItem(STORAGE_KEY, 'true')
    error.value = ''
  } else {
    error.value = '密码错误，请重试'
  }
}

const resources = [
  {
    title: '后端入门开发手册',
    description: '涵盖 java/SpringBoot/SpringCloud 核心基础，适合零基础快速入门',
  },
  {
    title: 'Agent 开发实战指南',
    description: '从 0 到 1 搭建智能体应用，包含完整案例与可运行代码',
  },
  {
    title: '大模型应用开发教程',
    description: '基于大模型的应用开发指南，涵盖 API 调用、数据处理与优化策略',
  },
  {
    title: '前端开发进阶手册',
    description: '深入理解 Vue3、React、TypeScript 等前端技术栈，提升开发效率',
  }

]
</script>
