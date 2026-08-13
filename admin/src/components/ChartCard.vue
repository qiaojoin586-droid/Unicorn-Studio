<template>
  <el-card shadow="hover" class="chart-card">
    <template #header>
      <span class="chart-title">{{ title }}</span>
    </template>
    <div ref="chartEl" class="chart-body" :style="{ height: height + 'px' }"></div>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useChart } from '@/composables/useChart'

const props = defineProps<{
  title: string
  option: any
  height?: number
}>()

const chartEl = ref<HTMLElement | null>(null)
const optionRef = ref(props.option)

useChart(chartEl, optionRef)
</script>

<style scoped>
.chart-title {
  font-weight: 600;
  font-size: 15px;
}
.chart-body {
  width: 100%;
}
</style>
