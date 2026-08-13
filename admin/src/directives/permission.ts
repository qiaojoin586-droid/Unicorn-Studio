import type { Directive } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 按钮级权限：v-permission="'work:create'" 或 v-permission="['work:create','work:edit']"
 * 无权限时移除元素。
 */
const permissionDirective: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const auth = useAuthStore()
    const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
    if (codes.length && !auth.hasAnyPerm(codes)) {
      el.parentNode?.removeChild(el)
    }
  },
}

export default permissionDirective
