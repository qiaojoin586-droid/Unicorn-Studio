import { onMounted, ref } from 'vue'
import { useApi } from '@/composables/useApi'

/**
 * 页面背景（V11）：读取后台「轮播图管理 → 页面背景」为指定页面配置的静态/动态壁纸。
 *
 * 复用 carousel 表，接口只返回单条生效背景：
 *   { enabled, mediaType: 'image'|'video', imageUrl, videoUrl }
 *
 * 降级约定：无配置、接口失败、字段不完整时 background 保持 null，
 * 调用方据此不渲染任何背景层，从而保持页面原有默认样式（零配置零回归）。
 *
 * @param {'about'|'work-detail'} pageKey 页面标识，与后端白名单一致
 */
export function usePageBackground(pageKey) {
  const { get } = useApi()
  const background = ref(null)

  async function load() {
    try {
      const result = await get(`/api/page-background/${pageKey}`)
      if (result?.error) return
      const data = result?.data
      // 动态壁纸必须同时具备视频与封面图，缺一则不渲染（与后端 PageBackgroundVO 的收敛规则一致）
      if (!data?.enabled || !data.imageUrl) return
      if (data.mediaType === 'video' && !data.videoUrl) return
      background.value = data
    } catch {
      // 静默降级：背景属于增强项，失败不应影响页面其余内容
    }
  }

  onMounted(load)

  return { background }
}
