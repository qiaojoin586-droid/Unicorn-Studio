import { computed, reactive, onMounted } from 'vue'
import { useApi } from './useApi'

const settings = reactive({ basic: {}, contact: {}, social: {}, seo: {} })
let pending = null
let lastLoaded = 0
let listening = false
async function refresh(force = false) {
  if (pending || (!force && Date.now() - lastLoaded < 30000)) return pending
  pending = useApi().get('/api/site/settings').then(result => {
    if (result?.error || !result?.data) return
    for (const key of Object.keys(settings)) settings[key] = result.data[key] || {}
    lastLoaded = Date.now()
  }).catch(() => {}).finally(() => { pending = null })
  return pending
}
export function useSiteSettings() {
  const state = settings
  onMounted(() => {
    refresh()
    if (!listening) { listening = true; window.addEventListener('focus', () => { if (Date.now() - lastLoaded > 5000) refresh(true) }) }
  })
  const siteName = computed(() => state.basic['basic.site_name']?.trim() || '独角兽工作室')
  const slogan = computed(() => state.basic['basic.slogan'] ?? '')
  const email = computed(() => state.contact['contact.email'] ?? '')
  const description = computed(() => state.seo['seo.description'] ?? state.seo['seo.site_description'] ?? '')
  return { settings: state, siteName, slogan, email, description }
}
