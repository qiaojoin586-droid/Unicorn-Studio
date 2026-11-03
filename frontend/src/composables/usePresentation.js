import { inject, reactive, onMounted } from 'vue'
import { createPresentation, mergePresentation } from '../../../shared/presentation-contract.js'
import { useApi } from './useApi'

const state = reactive(createPresentation())
let pending = null
let lastLoaded = 0
let listening = false

export { mergePresentation }

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
