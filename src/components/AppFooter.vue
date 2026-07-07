<template>
  <footer class="relative z-10 bg-gray-900 text-gray-300">
    <div class="max-w-6xl mx-auto px-6 py-12">
      <div class="grid grid-cols-1 md:grid-cols-3 gap-10">
        <!-- Brand -->
        <div>
          <div class="flex items-center gap-2 text-white text-xl font-bold mb-4">
            <span class="text-2xl">🦄</span>
            <span>独角兽工作室</span>
          </div>
          <p class="text-gray-400 leading-relaxed text-sm">
            用技术与创意，打造卓越的数字产品。<br />
            我们专注于AI智能开发、Agent开发与大模型学术研究。
          </p>
        </div>

        <!-- Quick Links -->
        <div>
          <h4 class="text-white font-semibold mb-4">快速链接</h4>
          <ul class="space-y-2 text-sm">
            <li v-for="link in quickLinks" :key="link.path">
              <router-link :to="link.path" class="hover:text-indigo-400 transition">
                {{ link.label }}
              </router-link>
            </li>
          </ul>
        </div>

        <!-- Social -->
        <div>
          <h4 class="text-white font-semibold mb-4">关注我们</h4>
          <div class="flex space-x-4">
            <a
              v-for="social in socials"
              :key="social.name"
              :href="social.url"
              :aria-label="social.name"
              target="_blank"
              rel="noopener noreferrer"
              class="w-10 h-10 flex items-center justify-center rounded-full bg-gray-800 hover:bg-indigo-600 text-gray-400 hover:text-white transition-all"
            >
              <!-- GitHub -->
              <svg v-if="social.name === 'GitHub'" class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/>
              </svg>
              <!-- QQ -->
              <svg v-else-if="social.name === 'QQ'" class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M21.395 15.035a39.548 39.548 0 00-1.453-3.563c-.033-.065-.067-.13-.1-.195l-.014-.028c-.476-.92-1.048-1.812-1.68-2.623.16-.56.256-1.155.256-1.776 0-.862-.172-1.685-.48-2.437-.307-.75-.746-1.42-1.286-1.978A6.035 6.035 0 0014.52 1.38 6.077 6.077 0 0012 1.001c-.882 0-1.72.188-2.478.525a6.035 6.035 0 00-2.118 1.553c-.54.558-.979 1.228-1.286 1.978-.308.752-.48 1.575-.48 2.437 0 .621.096 1.216.256 1.776-.632.811-1.204 1.703-1.68 2.623l-.014.028c-.033.065-.067.13-.1.195a39.548 39.548 0 00-1.453 3.563c-.572 1.564-.95 2.87-.95 3.374 0 .39.164.658.41.813.247.155.588.22.97.22.614 0 1.37-.164 2.07-.41.47-.17.906-.376 1.24-.574.155-.092.29-.184.394-.27a4.872 4.872 0 004.24 2.47 4.872 4.872 0 004.24-2.47c.104.086.24.178.394.27.334.198.77.404 1.24.574.7.246 1.456.41 2.07.41.382 0 .723-.065.97-.22.246-.155.41-.423.41-.813 0-.504-.378-1.81-.95-3.374zM12 2.6c.652 0 1.27.132 1.834.37a4.44 4.44 0 011.557 1.147c.433.448.78.986 1.016 1.57.237.583.363 1.22.363 1.88 0 .466-.066.917-.19 1.344a13.42 13.42 0 00-2.104-1.27 4.89 4.89 0 001.088-2.876c0-.18-.02-.356-.057-.527a4.358 4.358 0 01-1.353 1.28 4.87 4.87 0 00-2.154-.502c-.79 0-1.524.186-2.154.502a4.358 4.358 0 01-1.353-1.28 2.47 2.47 0 00-.057.527c0 1.088.398 2.082 1.088 2.876a13.42 13.42 0 00-2.104 1.27 4.855 4.855 0 01-.19-1.344c0-.66.126-1.297.363-1.88.236-.584.583-1.122 1.016-1.57A4.44 4.44 0 0110.166 2.97 4.474 4.474 0 0112 2.6zm-4.2 9.088c.378-.56.81-1.082 1.288-1.554.39.37.83.686 1.308.932a6.89 6.89 0 00-2.596.622zm8.4 0c-.378-.56-.81-1.082-1.288-1.554-.39.37-.83.686-1.308.932a6.89 6.89 0 012.596.622z"/>
              </svg>
            </a>
          </div>
          <p class="mt-4 text-sm text-gray-500">
            邮箱：1351094759@qq.com
          </p>
        </div>
      </div>

      <!-- Divider & Copyright -->
      <div class="border-t border-gray-800 mt-10 pt-6 flex flex-col md:flex-row items-center justify-between text-sm text-gray-500">
        <p>&copy; {{ currentYear }} 独角兽工作室 · 用创意点亮未来</p>
        <p class="mt-2 md:mt-0">每一个人都有一颗炽热的 ❤️ and You</p>
      </div>
    </div>
  </footer>
</template>

<script setup>
import { computed } from 'vue'

const currentYear = computed(() => new Date().getFullYear())

const quickLinks = [
  { label: '首页', path: '/' },
  { label: '作品', path: '/works' },
  { label: '关于我们', path: '/about' },
  { label: '学习资料', path: '/learning' },
]

const socials = [
  { name: 'GitHub', url: 'https://github.com' },
  { name: 'QQ', url: 'https://qq.com' },
]
</script>
