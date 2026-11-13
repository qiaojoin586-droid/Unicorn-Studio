<template>
  <el-card shadow="never" class="chart-card">
    <template #header>
      <span class="chart-title">{{ title }}</span>
    </template>
    <div ref="chartEl" class="chart-body" :style="{ height: height + 'px' }"></div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useChart } from '@/composables/useChart'

const props = defineProps<{
  title: string
  option: any
  height?: number
}>()

const chartEl = ref<HTMLElement | null>(null)
// computed 而非 ref：父组件更新 option prop 时自动重绘（useChart 内部 deep watch 捕获）
const optionRef = computed(() => props.option)

useChart(chartEl, optionRef)
</script>

<style scoped>
.chart-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 15px;
  color: var(--admin-ink);
}
.chart-title::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--admin-accent);
}
.chart-body {
  width: 100%;
}
</style>
