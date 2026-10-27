<template>
  <header
    class="fixed top-0 left-0 right-0 z-50 transition-all duration-300"
    :class="scrolled ? 'bg-gray-900/95 shadow-lg backdrop-blur-sm' : 'bg-transparent'"
  >
    <div class="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
      <router-link to="/" class="text-xl font-bold text-white flex items-center gap-2" aria-label="独角兽工作室 首页">
        <span class="text-2xl" aria-hidden="true">🦄</span>
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

        <!--
          账号区：登录/注册与昵称用同一个胶囊（同一套 class，只是内容不同），
          字体规格跟导航项一致（text-sm font-medium）。颜色用描边而非实心：
          实心 indigo 已经被「加入我们」占了，两个实心胶囊会互相抢焦点。
        -->
        <div class="flex items-center gap-5 border-l border-white/15 pl-5">
          <button
            v-if="!userStore.isLoggedIn"
            type="button"
            class="rounded-full border border-white/25 px-4 py-1.5 text-sm font-medium text-white/85 transition hover:bg-white/10 hover:text-white"
            @click="openLogin"
          >
            登录 / 注册
          </button>

          <div v-else ref="accountMenu" class="relative">
            <button
              type="button"
              class="flex items-center gap-1.5 rounded-full border border-white/25 px-4 py-1.5 text-sm font-medium text-white/85 transition hover:bg-white/10 hover:text-white"
              :aria-expanded="accountMenuOpen"
              aria-haspopup="menu"
              @click="accountMenuOpen = !accountMenuOpen"
            >
              <span class="max-w-[8rem] truncate">{{ userStore.displayName }}</span>
              <svg
                class="h-3.5 w-3.5 shrink-0 transition-transform"
                :class="accountMenuOpen ? 'rotate-180' : ''"
                fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
                viewBox="0 0 24 24" aria-hidden="true"
              >
                <path d="m6 9 6 6 6-6" />
              </svg>
            </button>

            <transition
              enter-active-class="transition duration-150 ease-out"
              enter-from-class="opacity-0 -translate-y-1"
              enter-to-class="opacity-100 translate-y-0"
              leave-active-class="transition duration-100 ease-in"
              leave-from-class="opacity-100 translate-y-0"
              leave-to-class="opacity-0 -translate-y-1"
            >
              <div
                v-if="accountMenuOpen"
                role="menu"
                class="absolute right-0 top-[calc(100%+0.5rem)] z-50 w-52 overflow-hidden rounded-xl border border-white/10 bg-gray-900/95 shadow-xl shadow-black/40 backdrop-blur-sm"
              >
                <div class="border-b border-white/10 px-4 py-3">
                  <p class="truncate text-sm font-medium text-white">{{ userStore.displayName }}</p>
                  <p v-if="userStore.user?.email" class="mt-0.5 truncate text-xs text-white/50">
                    {{ userStore.user.email }}
                  </p>
                </div>
                <button
                  type="button"
                  role="menuitem"
                  class="block w-full px-4 py-2.5 text-left text-sm text-white/85 transition hover:bg-white/10 hover:text-white"
                  @click="onLogout"
                >
                  退出登录
                </button>
              </div>
            </transition>
          </div>
        </div>
      </nav>

      <!-- Mobile menu button -->
      <button
        class="md:hidden text-white p-2"
        :aria-expanded="mobileMenuOpen"
        aria-controls="mobile-nav"
        aria-label="切换菜单"
        @click="mobileMenuOpen = !mobileMenuOpen"
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
      <div v-if="mobileMenuOpen" id="mobile-nav" class="md:hidden bg-gray-900/95 backdrop-blur-sm border-t border-white/10 px-6 py-4 space-y-3">
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

        <button
          v-if="!userStore.isLoggedIn"
          type="button"
          class="block w-full rounded-full border border-white/25 px-4 py-1.5 text-center text-sm font-medium text-white/85 transition hover:bg-white/10 hover:text-white"
          @click="openLogin(); mobileMenuOpen = false"
        >
          登录 / 注册
        </button>
        <div v-else class="flex items-center justify-between gap-3 border-t border-white/10 pt-3">
          <span class="truncate text-sm font-medium text-white/85">{{ userStore.displayName }}</span>
          <button
            type="button"
            class="rounded-full border border-white/25 px-4 py-1.5 text-sm font-medium text-white/85 transition hover:bg-white/10 hover:text-white"
            @click="onLogout(); mobileMenuOpen = false"
          >
            退出登录
          </button>
        </div>
      </div>
    </transition>
  </header>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useUserStore } from '@/stores/user'
import { useAiChatStore } from '@/stores/aiChat'
import { useApi } from '@/composables/useApi'

const userStore = useUserStore()
const aiChatStore = useAiChatStore()
const { logout } = useApi()

const scrolled = ref(false)
const mobileMenuOpen = ref(false)
const accountMenuOpen = ref(false)
const accountMenu = ref(null)

// 点空白处 / 按 Esc 收起账号下拉：胶囊是纯文字按钮，不加这个的话菜单只能靠再点一次自己关
function onDocumentClick(e) {
  if (accountMenu.value && !accountMenu.value.contains(e.target)) {
    accountMenuOpen.value = false
  }
}

function onKeydown(e) {
  if (e.key === 'Escape') {
    accountMenuOpen.value = false
  }
}

// 复用 AI 那套登录/注册弹窗：它内部已接好 login/register 并写入 userStore，
// 这里只负责把它打开，避免做两套表单
function openLogin() {
  aiChatStore.openLoginPrompt()
}

/**
 * 退出登录：先清本地再通知后端 —— 本地清完用户目标已达成，后端撤销失败不该把用户卡在登录态。
 * 顺带清掉 AI 会话：sessionId 与消息属于登出前的那个用户，不清的话下一个登录的人
 * 会接着用旧 sessionId 调后端（后端按归属校验，会直接 404）。
 */
function onLogout() {
  accountMenuOpen.value = false
  mobileMenuOpen.value = false
  logout(userStore.token)
  userStore.clearSession()
  aiChatStore.startNewSession()
  // ⚠️ 历史抽屉要一起关：它是「已登录用户的历史」，退出后继续开着，
  // 用户会看到一个还停留在屏幕上的历史面板（内容是退出前的）。
  aiChatStore.closeDrawer()
}

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
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
  handleScroll()
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})
</script>
