<template>
  <header class="site-header" :class="{ 'is-scrolled': scrolled, 'is-open': mobileMenuOpen }">
    <div class="page-shell header-inner">
      <router-link to="/" class="header-brand" :aria-label="siteName + ' 首页'">
        <BrandMark />
        <span class="brand-type"><strong>{{ siteName }}</strong><span>{{ slogan || 'UNICORN STUDIO' }}</span></span>
      </router-link>
      <nav class="desktop-nav" aria-label="主要导航">
        <router-link v-for="item in navItems" :key="item.path" :to="item.path" :class="{ 'is-current': isCurrent(item.path) }" :aria-current="isCurrent(item.path) ? 'page' : undefined">
          <span class="nav-dot" aria-hidden="true"></span>{{ item.label }}
        </router-link>
      </nav>
      <div class="header-create">
        <router-link to="/join" class="header-join">一起创造<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 19 19 5M5 5h14v14" stroke="currentColor" stroke-width="1.5" /></svg></router-link>
        <!-- 配色切换（小太阳）：点一下在深色 / 浅色之间换 -->
        <button
          class="theme-toggle"
          type="button"
          :aria-label="isLight ? '切换到深色配色' : '切换到浅色配色'"
          :title="isLight ? '切换到深色配色' : '切换到浅色配色'"
          @click="cycleTheme"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="4" fill="none" stroke="currentColor" stroke-width="1.6" />
            <g stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <path d="M12 1.9v2.6M12 19.5v2.6M1.9 12h2.6M19.5 12h2.6" />
              <path d="M4.9 4.9l1.9 1.9M17.2 17.2l1.9 1.9M19.1 4.9l-1.9 1.9M6.8 17.2l-1.9 1.9" />
            </g>
          </svg>
        </button>
      </div>
      <!-- 登录 / 退出入口（前台站点用户体系，与后台管理员完全独立） -->
      <button v-if="!userStore.isLoggedIn" class="header-auth" type="button" @click="openLogin">登录</button>
      <!-- 已登录：昵称本身是触发器，点开下拉后里面才是「退出登录」 -->
      <div v-else ref="userMenuEl" class="user-menu-wrap">
        <button
          class="header-auth"
          type="button"
          :aria-expanded="userMenuOpen"
          aria-haspopup="menu"
          @click.stop="userMenuOpen = !userMenuOpen"
        >
          {{ userStore.displayName }}
          <svg class="user-menu-caret" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          </svg>
        </button>
        <div v-if="userMenuOpen" class="user-menu" role="menu">
          <div class="user-menu-head">
            <p class="user-menu-name">{{ userStore.displayName }}</p>
            <p v-if="userStore.maskedPhone" class="user-menu-phone">
              {{ userStore.maskedPhone }}
            </p>
          </div>
          <div class="user-menu-sep" />
          <button class="user-menu-item" type="button" role="menuitem" @click="onLogout">退出登录</button>
        </div>
      </div>
      <button ref="menuTrigger" class="menu-trigger" type="button" :aria-expanded="mobileMenuOpen" aria-controls="mobile-nav" :aria-label="mobileMenuOpen ? '关闭导航菜单' : '打开导航菜单'" @click="mobileMenuOpen = !mobileMenuOpen">
        <span>{{ mobileMenuOpen ? '关闭' : '菜单' }}</span><span class="menu-lines" aria-hidden="true"><i></i><i></i></span>
      </button>
    </div>
    <transition name="mobile-menu">
      <nav v-if="mobileMenuOpen" id="mobile-nav" class="mobile-nav" aria-label="手机导航" @keydown.esc.stop.prevent="closeMenu(true)">
        <div class="page-shell">
          <router-link v-for="(item, index) in [...navItems, { label: '加入我们', path: '/join' }]" :key="item.path" :to="item.path" :aria-current="isCurrent(item.path) ? 'page' : undefined" @click="closeMenu()">
            <span class="menu-index">0{{ index + 1 }}</span><span>{{ item.label }}</span><span aria-hidden="true">↗</span>
          </router-link>
          <!-- 登录 / 退出：不用 .mobile-nav a —— 那个是三列网格（序号|文字|箭头），
               只有文字的按钮塞进去会落进 24px 的序号列被挤扁。
               这里单列左对齐，字号与下边框跟导航项一致。 -->
          <!-- 配色切换：桌面端那个按钮在窄屏被隐藏（跟「一起创造」一起），
               ⇒ 移动端在这里补一个，菜单收起时用紧凑的一行。 -->
          <button class="mobile-theme-row" type="button" @click="cycleTheme">
            <span>配色</span>
            <span class="mobile-theme-value">{{ isLight ? '浅色' : '深色' }}</span>
          </button>
          <button v-if="!userStore.isLoggedIn" class="mobile-nav-action" type="button" @click="openLogin">登录</button>
          <button
            v-if="userStore.isLoggedIn"
            class="mobile-nav-action"
            type="button"
            :aria-expanded="mobileUserMenuOpen"
            @click="mobileUserMenuOpen = !mobileUserMenuOpen"
          >
            {{ userStore.displayName }}
            <svg class="mobile-caret" :class="{ 'is-open': mobileUserMenuOpen }" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
            </svg>
          </button>
          <!-- 展开的子菜单：插在列表里（不是浮层），全宽、缩进一级 -->
          <div v-if="userStore.isLoggedIn && mobileUserMenuOpen" class="mobile-user-panel">
            <p v-if="userStore.maskedPhone" class="mobile-user-phone">
              {{ userStore.maskedPhone }}
            </p>
            <button class="mobile-user-action" type="button" @click="onLogout">退出登录</button>
          </div>
          <p>保持好奇，一起创造。<span>STAY CURIOUS. MAKE THINGS.</span></p>
        </div>
      </nav>
    </transition>
  </header>
</template>
<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import BrandMark from './BrandMark.vue'
import { useSiteSettings } from '@/composables/useSiteSettings'
import { useAiChatStore } from '@/stores/aiChat'
import { useUserStore } from '@/stores/user'
import { useTheme } from '@/composables/useTheme'
import { useApi } from '@/composables/useApi'
const { siteName, slogan } = useSiteSettings()
const aiChatStore = useAiChatStore()
const userStore = useUserStore()
const { isLight, cycleTheme } = useTheme()

/** 用户菜单（昵称 → 下拉 → 退出登录） */
const userMenuOpen = ref(false)
const userMenuEl = ref(null)

/**
 * 移动端菜单里的用户展开状态。
 * ⚠️ 为什么和桌面端分成两个：桌面端是页头里的浮层、移动端是全屏菜单里插入的块，
 *    两者形态与生命周期都不同 ⇒ 共用一个状态会出现"开了移动端、桌面端也跟着开"。
 */
const mobileUserMenuOpen = ref(false)

/**
 * 点菜单外面就关。
 * ⚠️ 用 pointerdown 而不是 click：click 要等鼠标抬起才触发，
 *    而菜单项自己的 pointerdown 会先到 ⇒ 会被判成"点到外面"⇒ 菜单项点不动。
 * ⚠️ 用 pointerdown 且菜单在 userMenuEl 内部 ⇒ contains 判断为 true ⇒ 不会误关。
 */
function onDocPointerDown(e) {
  if (userMenuEl.value && !userMenuEl.value.contains(e.target)) {
    userMenuOpen.value = false
  }
}

function onUserMenuKeydown(e) {
  if (e.key === 'Escape') {
    userMenuOpen.value = false
  }
}
const { logout } = useApi()

/** 未登录点「登录」：只弹登录框，不改别的 */
function openLogin() {
  aiChatStore.openLoginPrompt()
}

/**
 * 退出登录。
 * ⚠️ 退出后要把 AI 助手这边一起收干净：会话列表清空、面板关掉 ——
 * 否则退出后屏幕上还留着上一个账号的会话标题。
 */
function onLogout() {
  userMenuOpen.value = false
  mobileUserMenuOpen.value = false
  logout(userStore.token)
  userStore.clearSession()
  aiChatStore.startNewSession()
  aiChatStore.closeDrawer()
}
const route = useRoute()
const scrolled = ref(false)
const mobileMenuOpen = ref(false)
const menuTrigger = ref(null)
const navItems = [
  { label: '首页', path: '/' }, { label: '作品档案', path: '/works' },
  { label: '关于我们', path: '/about' }, { label: '学习资料', path: '/learning' },
]
function isCurrent(path) { return path === '/' ? route.path === '/' : route.path.startsWith(path) }
function closeMenu(restoreFocus = false) {
  mobileMenuOpen.value = false
  mobileUserMenuOpen.value = false
  if (restoreFocus) menuTrigger.value?.focus()
}
function onScroll() { scrolled.value = window.scrollY > 16 }
function onResize() { if (window.innerWidth >= 1024) closeMenu() }
function onKeydown(event) { if (event.key === 'Escape' && mobileMenuOpen.value) closeMenu(true) }
watch(() => route.path, () => closeMenu())
onMounted(() => {
  document.addEventListener('pointerdown', onDocPointerDown)
  document.addEventListener('keydown', onUserMenuKeydown)
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onResize, { passive: true })
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointerDown)
  document.removeEventListener('keydown', onUserMenuKeydown)
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onResize)
  window.removeEventListener('keydown', onKeydown)
})
</script>
<style scoped>
.site-header { position: fixed; inset: 0 0 auto; z-index: var(--z-header); background: var(--header-bg); color: var(--header-text); border-bottom: 1px solid var(--header-line-soft); transition: box-shadow var(--duration-ui); }
.is-scrolled { box-shadow: 0 8px 32px #00000012; }
.header-inner { height: var(--header-height); display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.header-create { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.header-brand { display: inline-flex; align-items: center; gap: 11px; flex-shrink: 0; }
.brand-type { display: grid; gap: 5px; min-width: 0; }
.brand-type strong { font-size: 17px; font-weight: 650; letter-spacing: .03em; line-height: 1.25; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.brand-type > span { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--font-mono); font-size: 9px; letter-spacing: .17em; color: var(--color-muted-dark); }
.desktop-nav { display: flex; gap: clamp(20px, 2.4vw, 40px); }
.desktop-nav a { min-height: 44px; display: inline-flex; align-items: center; color: var(--header-text-dim); font-size: 13px; gap: 8px; transition: color var(--duration-fast); }
.desktop-nav a:hover, .desktop-nav .is-current { color: var(--header-text); }
.nav-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--feature-accent); transform: scale(0); transition: transform var(--duration-ui) var(--ease-out); }
.is-current .nav-dot { transform: scale(1); }
.header-join { font-size: 13px; display: inline-flex; align-items: center; gap: 24px; border: 1px solid var(--header-line); padding: 0 18px; min-height: 44px; border-radius: 3px; transition: background var(--duration-ui), border-color var(--duration-ui); }
.header-join:hover { background: var(--color-accent); border-color: var(--color-accent); color: #fff; }
.header-join svg { width: 15px; height: 15px; transition: transform var(--duration-ui); }
.header-join:hover svg { transform: translate(2px, -2px); }
/* 登录/退出入口：与旁边「一起创造」同一套视觉（同边框/圆角/悬停填充），
   只因文案更短而收窄了左右内边距 */
.header-auth { font-size: 13px; display: inline-flex; align-items: center; border: 1px solid var(--header-line); padding: 0 14px; min-height: 44px; border-radius: 3px; color: var(--header-text-dim); transition: background var(--duration-ui), border-color var(--duration-ui), color var(--duration-ui); }
.header-auth:hover { background: var(--color-accent); border-color: var(--color-accent); color: #fff; }
/* 用户菜单：昵称是触发器，下拉里放「退出登录」 */
.user-menu-wrap { position: relative; }
.user-menu-caret { width: 12px; height: 12px; margin-left: 4px; flex-shrink: 0; }
.user-menu {
  position: absolute;
  right: 0;
  top: calc(100% + 8px);
  min-width: 180px;
  z-index: var(--z-header);
  padding: 6px;
  border: 1px solid var(--color-line);
  border-radius: 6px;
  background: var(--header-bg);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 35%);
}
.user-menu-head { padding: 8px 10px 6px; }
.user-menu-name { margin: 0; font-size: 13px; font-weight: 600; color: var(--header-text); }
.user-menu-phone { margin: 3px 0 0; font-size: 11px; color: var(--color-muted-dark); word-break: break-all; }
.user-menu-sep { height: 1px; margin: 4px 6px; background: var(--color-line); }
.user-menu-item {
  display: block;
  width: 100%;
  padding: 9px 10px;
  text-align: left;
  font-size: 13px;
  color: var(--header-text);
  border-radius: 4px;
  transition: background var(--duration-fast);
}
.user-menu-item:hover { background: rgba(255, 255, 255, 8%); }

/* 仅将主题按钮靠近「一起创造」，登录入口保持独立。 */
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  padding: 0;
  line-height: 0;
  flex-shrink: 0;
  color: var(--header-text-dim);
  border: 0;
  background: transparent;
  transition: color var(--duration-ui);
}
.theme-toggle svg { width: 20px; height: 20px; transition: transform var(--duration-ui); }
.theme-toggle:hover { color: var(--feature-accent); }
.theme-toggle:hover svg { transform: rotate(12deg); }
.theme-toggle:focus-visible { outline: 2px solid var(--feature-accent); outline-offset: 3px; }

.menu-trigger { display: none; align-items: center; gap: 12px; min-height: 44px; min-width: 64px; font-size: 12px; }
.menu-lines { width: 22px; display: grid; gap: 5px; }
.menu-lines i { display: block; height: 1px; background: currentColor; transition: transform var(--duration-ui); }
.is-open .menu-lines i:first-child { transform: translateY(3px) rotate(45deg); }
.is-open .menu-lines i:last-child { transform: translateY(-3px) rotate(-45deg); }
.mobile-nav { max-height: calc(100dvh - var(--header-height)); overflow-y: auto; border-top: 1px solid var(--header-line-soft); background: var(--color-ink); padding-block: 20px 32px; }
.mobile-nav a { display: grid; grid-template-columns: 24px 1fr 24px; align-items: center; gap: 20px; min-height: 65px; font-size: clamp(22px, 5vw, 32px); border-bottom: 1px solid var(--header-line-soft); }
.mobile-nav a[aria-current] { color: var(--feature-accent); }
.menu-index { color: var(--color-muted-dark); font-family: var(--font-mono); font-size: 10px; }
.mobile-nav a > span:last-child { font-size: 22px; }

/* 登录 / 退出：跟导航项同样的大字与下边框，但单列左对齐
   （.mobile-nav a 是三列网格，只有文字的按钮塞进去会被 24px 的序号列挤扁） */
/* 移动端的配色切换：一行小字（跟导航项的大字区分开，它是设置不是导航） */
.mobile-theme-row {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  font-size: 13px;
  color: var(--color-muted-dark);
  border-bottom: 1px solid var(--header-line-soft);
}
.mobile-theme-value { color: var(--header-text-dim); }

.mobile-nav-action {
  display: grid;
  grid-template-columns: 1fr;
  align-items: center;
  gap: 20px;
  width: 100%;
  min-height: 65px;
  font-size: clamp(22px, 5vw, 32px);
  text-align: left;
  color: inherit;
  border-bottom: 1px solid var(--header-line-soft);
  transition: color var(--duration-fast);
}
.mobile-nav-action:hover { color: var(--feature-accent); }
.mobile-caret { width: 22px; height: 22px; justify-self: end; transition: transform var(--duration-ui); }
.mobile-caret.is-open { transform: rotate(180deg); }

/* 展开出来的用户区：插在列表里（全宽、缩进一级），不是浮层 */
.mobile-user-panel {
  padding: 16px 0 8px;
  border-bottom: 1px solid var(--header-line-soft);
}
.mobile-user-phone {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--color-muted-dark);
  word-break: break-all;
}
.mobile-user-action {
  display: block;
  width: 100%;
  padding: 12px 0;
  font-size: 16px;
  text-align: left;
  color: var(--color-muted-light);
  transition: color var(--duration-fast);
}
.mobile-user-action:hover { color: var(--feature-accent); }
.mobile-nav p { margin-top: 26px; font-size: 12px; color: var(--color-muted-dark); }
.mobile-nav p span { display: block; margin-top: 8px; font-family: var(--font-mono); font-size: 9px; letter-spacing: .08em; }
.mobile-menu-enter-active, .mobile-menu-leave-active { transition: opacity 180ms, transform 220ms var(--ease-out); }
.mobile-menu-enter-from, .mobile-menu-leave-to { opacity: 0; transform: translateY(-8px); }
@media(max-width: 1023px) { .desktop-nav, .header-create { display: none; } .menu-trigger { display: inline-flex; } .header-brand { max-width: calc(100% - 140px); min-width: 0; } .brand-type strong { max-width: 100%; } }
@media(max-width: 374px) { .header-brand { gap: 7px; } .brand-type strong { font-size: 15px; } .brand-type > span { font-size: 8px; } .header-brand :deep(svg) { width: 36px; height: 36px; } .header-inner { gap: 10px; } }
</style>
