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
  color: #0f172a;
}
.chart-title::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1, #a855f7);
  box-shadow: 0 0 8px rgba(99, 102, 241, 0.5);
}
.chart-body {
  width: 100%;
}
</style>
