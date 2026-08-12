<template>
  <div class="min-h-screen relative">
    <!-- Fixed full-page video background -->
    <div class="fixed inset-0 z-0 overflow-hidden">
      <BackgroundVideo :src="homeVideoUrl" :poster="homeVideoPosterUrl" />
      <div class="absolute inset-0 bg-gray-950/50"></div>
    </div>

    <!-- Loading state -->
    <div
      v-if="loading"
      class="fixed inset-0 z-50 flex items-center justify-center px-4"
    >
      <div class="absolute inset-0 bg-black/70 backdrop-blur-sm"></div>
      <div class="relative bg-gray-900/90 border border-white/10 rounded-2xl p-8 max-w-sm w-full text-center backdrop-blur-sm shadow-2xl">
        <div class="text-white font-semibold mb-2">正在验证访问权限</div>
        <p class="text-gray-400 text-sm">请稍候...</p>
      </div>
    </div>

    <!-- Password Modal -->
    <div
      v-else-if="!authenticated"
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
            autocomplete="current-password"
            maxlength="128"
            placeholder="请输入密码"
            class="w-full bg-white/5 border border-white/10 rounded-lg px-4 py-3 text-white placeholder-gray-500 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 mb-3 transition"
            autofocus
          />
          <p v-if="error" role="alert" class="text-red-400 text-sm mb-3">{{ error }}</p>
          <button
            type="submit"
            :disabled="submitting"
            class="w-full bg-indigo-600 hover:bg-indigo-500 disabled:bg-indigo-900 disabled:cursor-not-allowed text-white font-semibold py-3 rounded-lg transition"
          >
            {{ submitting ? '验证中...' : '确认' }}
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
          <button
            type="button"
            class="mt-6 text-sm text-gray-400 hover:text-white transition"
            @click="handleLogout"
          >
            退出访问
          </button>
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
            <a
              v-if="item.url"
              :href="item.url"
              target="_blank"
              rel="noopener noreferrer"
              class="inline-flex items-center gap-1.5 text-indigo-400 hover:text-indigo-300 font-semibold text-sm transition"
            >
              查看详情
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
              </svg>
            </a>
            <span
              v-else
              class="inline-flex items-center text-sm font-medium text-gray-500"
            >
              资料整理中
            </span>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import BackgroundVideo from '@/components/BackgroundVideo.vue'
import { useApi } from '@/composables/useApi'
import homeVideoUrl from '@/image/home.mp4'
import homeVideoPosterUrl from '@/image/home-poster.jpg'

const { get, post } = useApi()

const authenticated = ref(false)
const loading = ref(true)
const submitting = ref(false)
const password = ref('')
const error = ref('')
const resources = ref([])

async function loadResources() {
  try {
    const result = await get('/api/learning/resources')

    if (result.error && result.status === 401) {
      authenticated.value = false
      resources.value = []
      return
    }

    if (result.error) {
      throw new Error(result.message)
    }

    // Support both wrapped ({ code, data: { resources } }) and flat ({ resources }) formats
    const payload = result.data || result
    authenticated.value = true
    resources.value = payload.resources || []
    error.value = ''
  } catch (err) {
    authenticated.value = false
    error.value = err.message || '无法连接学习资料服务'
  } finally {
    loading.value = false
  }
}

async function handleLogin() {
  if (!password.value) {
    error.value = '请输入密码'
    return
  }

  submitting.value = true
  error.value = ''

  try {
    const result = await post('/api/learning/login', { password: password.value })

    if (result.error) {
      throw new Error(result.message)
    }

    const payload = result.data || result
    authenticated.value = true
    resources.value = payload.resources || []
    password.value = ''
  } catch (err) {
    authenticated.value = false
    resources.value = []
    error.value = err.message || '验证失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

async function handleLogout() {
  try {
    const result = await post('/api/learning/logout')

    if (result.error) {
      throw new Error(result.message)
    }

    authenticated.value = false
    resources.value = []
    error.value = ''
  } catch (err) {
    error.value = err.message || '退出失败，请稍后重试'
  }
}

onMounted(loadResources)
</script>
