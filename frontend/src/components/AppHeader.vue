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
      <router-link to="/join" class="header-join">一起创造<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 19 19 5M5 5h14v14" stroke="currentColor" stroke-width="1.5" /></svg></router-link>
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
const { siteName, slogan } = useSiteSettings()
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
  if (restoreFocus) menuTrigger.value?.focus()
}
function onScroll() { scrolled.value = window.scrollY > 16 }
function onResize() { if (window.innerWidth >= 1024) closeMenu() }
function onKeydown(event) { if (event.key === 'Escape' && mobileMenuOpen.value) closeMenu(true) }
watch(() => route.path, () => closeMenu())
onMounted(() => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onResize, { passive: true })
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onResize)
  window.removeEventListener('keydown', onKeydown)
})
</script>
<style scoped>
.site-header { position: fixed; inset: 0 0 auto; z-index: var(--z-header); background: var(--color-ink); color: var(--color-paper); border-bottom: 1px solid #ffffff14; transition: box-shadow var(--duration-ui); }
.is-scrolled { box-shadow: 0 8px 32px #00000012; }
.header-inner { height: var(--header-height); display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.header-brand { display: inline-flex; align-items: center; gap: 11px; flex-shrink: 0; }
.brand-type { display: grid; gap: 5px; min-width: 0; }
.brand-type strong { font-size: 17px; font-weight: 650; letter-spacing: .03em; line-height: 1.25; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.brand-type > span { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--font-mono); font-size: 9px; letter-spacing: .17em; color: var(--color-muted-dark); }
.desktop-nav { display: flex; gap: clamp(20px, 2.4vw, 40px); }
.desktop-nav a { min-height: 44px; display: inline-flex; align-items: center; color: #c0c0c2; font-size: 13px; gap: 8px; transition: color var(--duration-fast); }
.desktop-nav a:hover, .desktop-nav .is-current { color: white; }
.nav-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--color-accent-on-dark); transform: scale(0); transition: transform var(--duration-ui) var(--ease-out); }
.is-current .nav-dot { transform: scale(1); }
.header-join { font-size: 13px; display: inline-flex; align-items: center; gap: 24px; border: 1px solid #ffffff36; padding: 0 18px; min-height: 44px; border-radius: 3px; transition: background var(--duration-ui), border-color var(--duration-ui); }
.header-join:hover { background: var(--color-accent); border-color: var(--color-accent); }
.header-join svg { width: 15px; height: 15px; transition: transform var(--duration-ui); }
.header-join:hover svg { transform: translate(2px, -2px); }
.menu-trigger { display: none; align-items: center; gap: 12px; min-height: 44px; min-width: 64px; font-size: 12px; }
.menu-lines { width: 22px; display: grid; gap: 5px; }
.menu-lines i { display: block; height: 1px; background: currentColor; transition: transform var(--duration-ui); }
.is-open .menu-lines i:first-child { transform: translateY(3px) rotate(45deg); }
.is-open .menu-lines i:last-child { transform: translateY(-3px) rotate(-45deg); }
.mobile-nav { max-height: calc(100dvh - var(--header-height)); overflow-y: auto; border-top: 1px solid #ffffff1a; background: var(--color-ink); padding-block: 20px 32px; }
.mobile-nav a { display: grid; grid-template-columns: 24px 1fr 24px; align-items: center; gap: 20px; min-height: 65px; font-size: clamp(22px, 5vw, 32px); border-bottom: 1px solid #ffffff1a; }
.mobile-nav a[aria-current] { color: var(--color-accent-on-dark); }
.menu-index { color: var(--color-muted-dark); font-family: var(--font-mono); font-size: 10px; }
.mobile-nav a > span:last-child { font-size: 22px; }
.mobile-nav p { margin-top: 26px; font-size: 12px; color: var(--color-muted-dark); }
.mobile-nav p span { display: block; margin-top: 8px; font-family: var(--font-mono); font-size: 9px; letter-spacing: .08em; }
.mobile-menu-enter-active, .mobile-menu-leave-active { transition: opacity 180ms, transform 220ms var(--ease-out); }
.mobile-menu-enter-from, .mobile-menu-leave-to { opacity: 0; transform: translateY(-8px); }
@media(max-width: 1023px) { .desktop-nav, .header-join { display: none; } .menu-trigger { display: inline-flex; } .header-brand { max-width: calc(100% - 140px); min-width: 0; } .brand-type strong { max-width: 100%; } }
@media(max-width: 374px) { .header-brand { gap: 7px; } .brand-type strong { font-size: 15px; } .brand-type > span { font-size: 8px; } .header-brand :deep(svg) { width: 36px; height: 36px; } .header-inner { gap: 10px; } }
</style>
