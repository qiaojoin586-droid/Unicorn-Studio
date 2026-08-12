<template>
  <div class="flex gap-3" :class="isUser ? 'flex-row-reverse' : ''">
    <!-- Avatar -->
    <div
      class="w-7 h-7 rounded-full flex items-center justify-center text-xs flex-shrink-0 mt-0.5"
      :class="isUser
        ? 'bg-indigo-600'
        : 'bg-gradient-to-br from-indigo-500 to-purple-600'"
    >
      {{ isUser ? '🧑' : '🦄' }}
    </div>

    <!-- Bubble -->
    <div
      class="rounded-2xl px-4 py-3 max-w-[80%]"
      :class="isUser
        ? 'bg-indigo-600 text-white rounded-tr-sm'
        : 'bg-white/5 border border-white/10 text-gray-200 rounded-tl-sm'"
    >
      <p class="text-sm leading-relaxed whitespace-pre-wrap">{{ content }}</p>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  role: {
    type: String,
    required: true,
    validator: (v) => ['user', 'assistant'].includes(v),
  },
  content: {
    type: String,
    required: true,
  },
})

const isUser = computed(() => props.role === 'user')
</script>
