<template>
  <!--
    提交按钮的悬停变形：常态文字右移淡出，主色层淡入并显示箭头。
    纯 CSS 实现（group / group-hover），无 JS 状态。
    ⚠️ loading / disabled 时不挂 `group` —— 移出主色层，避免"禁用还能悬停变形"。
  -->
  <button
    :disabled="inactive"
    class="relative h-12 w-full overflow-hidden rounded-full border text-sm font-semibold transition-colors duration-200"
    :class="inactive
      ? 'cursor-not-allowed border-violet-200 bg-white/50 text-gray-400'
      : 'group cursor-pointer border-indigo-400 bg-white/70 text-indigo-700 hover:border-indigo-600 hover:bg-white/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/40'"
  >
    <span
      class="inline-block transition-all duration-300"
      :class="inactive ? '' : 'group-hover:translate-x-12 group-hover:opacity-0'"
    >
      {{ loading ? (loadingLabel || label) : label }}
    </span>

    <span
      aria-hidden="true"
      class="pointer-events-none absolute inset-0 flex items-center justify-center gap-2 rounded-full bg-indigo-600 text-white opacity-0 transition-opacity duration-300"
      :class="inactive ? '' : 'group-hover:opacity-100'"
    >
      {{ label }}
      <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24">
        <line x1="5" y1="12" x2="19" y2="12" />
        <polyline points="12 5 19 12 12 19" />
      </svg>
    </span>
  </button>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 常态文案 */
  label: { type: String, required: true },
  /** 加载态文案（默认沿用 label） */
  loadingLabel: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  /** 外部强制禁用（声明成 prop 是为了不重复落到原生 button 上，统一由 inactive 控制） */
  disabled: { type: Boolean, default: false },
})

// 禁用态与加载态视觉一致，都靠这一处判断
const inactive = computed(() => props.loading || props.disabled)
</script>
