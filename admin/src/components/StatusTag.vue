<template>
  <el-tag :type="type" size="small">{{ text }}</el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { statusText } from '@/utils/format'

const props = defineProps<{ status: string | number | null | undefined }>()

const text = computed(() => statusText(props.status))

const type = computed(() => {
  const s = String(props.status ?? '')
  const map: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
    published: 'success',
    approved: 'success',
    success: 'success',
    replied: 'success',
    running: 'warning',
    pending: 'warning',
    draft: 'info',
    new: 'warning',
    offline: 'info',
    rejected: 'danger',
    failed: 'danger',
    closed: 'info',
  }
  return map[s] ?? 'info'
})
</script>
