import { inject, reactive, onMounted } from 'vue'
import defaults from '@/generated/presentation-defaults.json'
import { useApi } from './useApi'

const clone = value => JSON.parse(JSON.stringify(value))
const state = reactive(clone(defaults))
let pending = null
let lastLoaded = 0
let listening = false

export function mergePresentation(value = {}) {
  const result = clone(defaults)
  for (const section of Object.keys(defaults)) {
    const incoming = value?.[section]
    if (!incoming || typeof incoming !== 'object') continue
    for (const key of Object.keys(defaults[section])) {
      if (Object.prototype.hasOwnProperty.call(incoming, key) && incoming[key] !== null) result[section][key] = incoming[key]
    }
  }
  return result
}

async function refresh(force = false) {
  if (pending || (!force && Date.now() - lastLoaded < 30000)) return pending
  pending = useApi().get('/api/site/presentation').then(result => {
    if (result?.error || !result?.data) return
    Object.assign(state, mergePresentation(result.data))
    lastLoaded = Date.now()
  }).catch(() => {}).finally(() => { pending = null })
  return pending
}

export function usePresentation() {
  const preview = inject('studio-preview', null)
  const presentation = preview ? reactive(mergePresentation(preview.presentation)) : state
  if (!preview) onMounted(() => {
    refresh()
    if (!listening) {
      listening = true
      window.addEventListener('focus', () => { if (Date.now() - lastLoaded > 5000) refresh(true) })
    }
  })
  return { presentation }
}
