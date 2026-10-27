<template>
  <router-link v-if="href.startsWith('/') || href.startsWith('#')" :to="href"><slot /></router-link>
  <a v-else-if="href" :href="href" target="_blank" rel="noopener noreferrer"><slot /><span class="sr-only">（新窗口打开）</span></a>
</template>
<script setup>
import { computed } from 'vue'
import { safeLink } from '@/utils/links'
const props = defineProps({ to: { type: String, default: '' } })
const href = computed(() => safeLink(props.to))
</script>
