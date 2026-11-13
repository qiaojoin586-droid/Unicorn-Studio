<template>
  <svg v-if="path" viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="path" /></svg>
  <span v-else-if="icon" class="legacy-icon" aria-hidden="true">{{ icon }}</span>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ icon: { type: String, default: '' } })
const paths = { code: 'M11 9 4 16l7 7m10-14 7 7-7 7M18 5l-4 22', ai: 'M16 4v24M8 8l8-4 8 4v16l-8 4-8-4V8Zm0 0 16 16M24 8 8 24M3 16h26', research: 'M8 4h16v24H8V4Zm4 7h8m-8 5h8m-8 5h5M4 8v17h4', design: 'm16 3 4 9 9 4-9 4-4 9-4-9-9-4 9-4 4-9Z' }
const path = computed(() => paths[props.icon?.trim().toLowerCase()] || '')
</script>
<style scoped>
svg { width: 25px; height: 25px; }
.legacy-icon { display: inline-grid; place-items: center; width: 28px; height: 28px; font-size: 23px; line-height: 1; }
</style>
