import { computed, ref, watchEffect } from 'vue'

/**
 * 站点配色切换（深色 ⇄ 浅色）。
 *
 * ⚠️ <b>为什么不用 generated/brand.css</b>：那份是 scripts/sync-design.mjs 生成的，
 *    重新跑一次设计同步就会被覆盖 ⇒ 主题变量必须写在 tokens.css 这一层。
 *
 * ⚠️ <b>为什么主题只存"深/浅"两个字</b>：具体色值在 CSS 里（[data-theme='light']），
 *    JS 只负责把选择写到 <html data-theme> 上 ⇒ ⇒ 以后调色只改 CSS，不用动 JS。
 *
 * ⚠️ <b>为什么初始要读一次 localStorage 再写回</b>：刷新后要保持用户上次选的主题，
 *    否则每次刷新都回到默认深色。
 */

const STORAGE_KEY = 'unicorn.theme'

/** 可选主题：深色（默认）/ 浅色 */
const THEMES = ['dark', 'light']

const current = ref('dark')

try {
  const saved = window.localStorage.getItem(STORAGE_KEY)
  if (saved && THEMES.includes(saved)) {
    current.value = saved
  }
} catch {
  /* 隐私模式 / 存储被禁：忽略，用默认主题 */
}

watchEffect(() => {
  document.documentElement.setAttribute('data-theme', current.value)
  try {
    window.localStorage.setItem(STORAGE_KEY, current.value)
  } catch {
    /* 写不进去也不影响本次会话内的切换 */
  }
})

export function useTheme() {
  const isLight = computed(() => current.value === 'light')

  /** 切到下一个主题（点太阳用） */
  function cycleTheme() {
    current.value = current.value === 'dark' ? 'light' : 'dark'
  }

  return { theme: current, isLight, cycleTheme }
}
