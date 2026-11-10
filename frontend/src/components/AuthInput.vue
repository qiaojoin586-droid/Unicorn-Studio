<template>
  <div>
    <div class="relative">
      <!-- 左图标：pointer-events-none 保证点到图标也能聚焦输入框 -->
      <span class="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-violet-400">
        <slot name="icon" />
      </span>

      <input
        ref="inputEl"
        v-bind="$attrs"
        :type="resolvedType"
        :value="modelValue"
        :placeholder="placeholder"
        :autocomplete="autocomplete"
        :disabled="disabled"
        :aria-label="label"
        :aria-invalid="error ? 'true' : 'false'"
        class="w-full h-12 rounded-full bg-white/75 border pl-11 text-sm text-gray-900 placeholder-gray-500 focus:outline-none focus:ring-1 transition disabled:opacity-50 disabled:cursor-not-allowed"
        :class="fieldClass"
        @input="$emit('update:modelValue', $event.target.value)"
        @focus="$emit('focus')"
      />

      <!-- 密码显隐。可见性由父组件持有 —— 登录页的角色动画要靠它决定「集体转头 / 偷看」 -->
      <button
        v-if="type === 'password'"
        type="button"
        :disabled="disabled"
        :aria-label="visible ? '隐藏密码' : '显示密码'"
        :aria-pressed="visible ? 'true' : 'false'"
        :title="visible ? '隐藏密码' : '显示密码'"
        class="absolute right-3 top-1/2 -translate-y-1/2 w-7 h-7 flex items-center justify-center rounded-full text-violet-400 hover:text-violet-700 disabled:opacity-50 transition"
        @click="$emit('update:visible', !visible)"
      >
        <svg v-if="visible" class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24">
          <path d="M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94M9.9 4.24A9.12 9.12 0 0112 4c7 0 11 8 11 8a18.5 18.5 0 01-2.16 3.19m-6.72-1.07a3 3 0 11-4.24-4.24" />
          <path d="M1 1l22 22" />
        </svg>
        <svg v-else class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24">
          <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
          <circle cx="12" cy="12" r="3" />
        </svg>
      </button>
    </div>

    <!-- 错误文案紧贴输入框，不用 toast：弹窗就一个，就地提示最不容易漏看 -->
    <p v-if="error" role="alert" class="text-xs text-red-600 mt-1.5 pl-4">{{ error }}</p>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

// 关掉自动继承：本组件根节点是外层 div，但 maxlength / inputmode 这类属性属于 <input>。
// 不关的话它们会落到 div 上，等于失效。
defineOptions({ inheritAttrs: false })

const props = defineProps({
  modelValue: { type: String, default: '' },
  /** HTML 输入类型，密码的显隐由组件控制。 */
  type: { type: String, default: 'text' },
  /** 无可见 <label>，故同时用作 aria-label */
  label: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  autocomplete: { type: String, default: '' },
  error: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  /** 密码是否明文可见（受控，由父组件持有） */
  visible: { type: Boolean, default: false },
})

defineEmits(['update:modelValue', 'update:visible', 'focus'])

const inputEl = ref(null)

const resolvedType = computed(() => {
  if (props.type !== 'password') return props.type
  return props.visible ? 'text' : 'password'
})

// 一个 :class 绑定（而不是并列写两个）—— 模板里写两个 :class 会被后者覆盖
const fieldClass = computed(() => [
  props.error
    ? 'border-red-400 focus:border-red-500 focus:ring-red-500/40'
    : 'border-violet-200 hover:border-violet-300 focus:border-indigo-500/60 focus:ring-indigo-500/40',
  props.type === 'password' ? 'pr-11' : 'pr-4',
])

// 暴露给父组件：弹窗打开时要把焦点落到第一个输入框上
defineExpose({ focus: () => inputEl.value?.focus() })
</script>
