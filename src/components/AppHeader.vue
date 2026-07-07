<template>
  <header
    class="fixed top-0 left-0 right-0 z-50 transition-all duration-300"
    :class="scrolled ? 'bg-gray-900/95 shadow-lg backdrop-blur-sm' : 'bg-transparent'"
  >
    <div class="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
      <router-link to="/" class="text-xl font-bold text-white flex items-center gap-2">
        <span class="text-2xl">🦄</span>
        <span>独角兽工作室</span>
      </router-link>

      <!-- Desktop Nav -->
      <nav class="hidden md:flex items-center space-x-8">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="text-white/80 hover:text-white font-medium transition-colors relative after:absolute after:bottom-[-4px] after:left-0 after:h-[2px] after:w-0 after:bg-indigo-400 after:transition-all hover:after:w-full"
        >
          {{ item.label }}
        </router-link>
        <router-link
          to="/join"
          class="bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold px-5 py-2 rounded-full transition"
          active-class="!bg-indigo-400 ring-2 ring-indigo-400/50"
        >
          加入我们
        </router-link>
      </nav>

      <!-- Mobile menu button -->
      <button
        class="md:hidden text-white p-2"
        @click="mobileMenuOpen = !mobileMenuOpen"
        aria-label="切换菜单"
      >
        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            v-if="!mobileMenuOpen"
            stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
            d="M4 6h16M4 12h16M4 18h16"
          />
          <path
            v-else
            stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
            d="M6 18L18 6M6 6l12 12"
          />
        </svg>
      </button>
    </div>

    <!-- Mobile Nav -->
    <transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0 -translate-y-2"
      enter-to-class="opacity-100 translate-y-0"
      leave-active-class="transition duration-150 ease-in"
      leave-from-class="opacity-100 translate-y-0"
      leave-to-class="opacity-0 -translate-y-2"
    >
      <div v-if="mobileMenuOpen" class="md:hidden bg-gray-900/95 backdrop-blur-sm border-t border-white/10 px-6 py-4 space-y-3">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="block text-white/80 hover:text-white font-medium py-2 transition"
          @click="mobileMenuOpen = false"
        >
          {{ item.label }}
        </router-link>
        <router-link
          to="/join"
          class="block text-center bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold px-5 py-2 rounded-full transition"
          active-class="!bg-indigo-400 ring-2 ring-indigo-400/50"
          @click="mobileMenuOpen = false"
        >
          加入我们
        </router-link>
      </div>
    </transition>
  </header>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const scrolled = ref(false)
const mobileMenuOpen = ref(false)

const navItems = [
  { label: '首页', path: '/' },
  { label: '作品', path: '/works' },
  { label: '关于我们', path: '/about' },
  { label: '学习资料', path: '/learning' },
]

function handleScroll() {
  scrolled.value = window.scrollY > 20
}

onMounted(() => {
  window.addEventListener('scroll', handleScroll, { passive: true })
  handleScroll()
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
})
</script>
