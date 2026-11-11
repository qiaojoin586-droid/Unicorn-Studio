import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { http } from '@/utils/request'
import { createPresentationSection, type PresentationBackground, type PresentationSections, type PresentationSection } from '../../../shared/presentation-contract.js'
export type { HomePresentation, AboutPresentation, FooterPresentation, JoinPresentation, PresentationBackground, PresentationSections, PresentationSection } from '../../../shared/presentation-contract.js'

export function clonePresentation<K extends PresentationSection>(section: K): PresentationSections[K] {
  return createPresentationSection(section)
}

export const presentationApi = {
  get<K extends PresentationSection>(section: K) {
    return http.get<PresentationSections[K]>('/api/admin/presentation/' + section)
  },
  update<K extends PresentationSection>(section: K, value: PresentationSections[K]) {
    return http.put<void>('/api/admin/presentation/' + section, value)
  },
}

/** 与公开页面配置一致：空字符串允许隐藏可选项，链接只允许站内或 HTTP(S)。 */
export function isPresentationUrl(value: string): boolean {
  const url = String(value ?? '').trim()
  if (!url) return true
  if (/[\u0000-\u0020\u007f\\]/.test(url) || url.startsWith('//')) return false
  if (url.startsWith('/') || url.startsWith('#')) return true
  try { return ['http:', 'https:'].includes(new URL(url).protocol) } catch { return false }
}

export function wallpaperError(config: PresentationBackground) {
  if (config.backgroundMode && !config.backgroundImageUrl) return '请上传背景图片或视频封面'
  if (config.backgroundMode === 'video' && !config.backgroundVideoUrl) return '请上传背景视频'
  return ''
}

/** 两个独立配置端点不是事务；每个结果都要告诉编辑者。 */
export function describeSectionSave(results: PromiseSettledResult<unknown>[], labels: string[]) {
  const saved = labels.filter((_, i) => results[i].status === 'fulfilled')
  const failed = labels.filter((_, i) => results[i].status === 'rejected')
  if (!failed.length) return { success: true, message: '全部配置已保存并更新主站，请刷新线上页面核验。' }
  if (!saved.length) return { success: false, message: '保存未完成：' + failed.join('、') + '未保存。请检查错误并重试。' }
  return { success: false, message: saved.join('、') + '已保存；' + failed.join('、') + '未保存。两部分独立生效，请重试未完成配置。' }
}

/** 记录已确认保存的快照；路由切换和关闭窗口时保护未保存的表单。 */
export function useConfigDraft(source: () => unknown) {
  const ready = ref(false)
  const snapshot = ref('')
  const dirty = computed(() => ready.value && JSON.stringify(source()) !== snapshot.value)
  function markSaved(value = source()) { snapshot.value = JSON.stringify(value); ready.value = true }
  function onBeforeUnload(event: BeforeUnloadEvent) {
    if (!dirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  onBeforeRouteLeave(async () => {
    if (!dirty.value) return true
    try {
      await ElMessageBox.confirm('有尚未保存的修改，离开后这些修改将丢失。', '离开编辑页面', {
        confirmButtonText: '离开页面', cancelButtonText: '继续编辑', type: 'warning',
      })
      return true
    } catch { return false }
  })
  onMounted(() => window.addEventListener('beforeunload', onBeforeUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))
  return { ready, dirty, markSaved }
}

