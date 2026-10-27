<template>
  <el-card v-if="!data.length" shadow="never">
    <template #header><span class="chart-title">来源分布</span></template>
    <el-empty description="暂无来源数据" :image-size="80" :style="{ height: height + 'px' }" />
  </el-card>
  <ChartCard v-else title="来源分布" :height="height" :option="donutOption" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import ChartCard from '@/components/ChartCard.vue'
import type { DashboardSummary } from '@/api/dashboard'

const props = defineProps<{
  /** 来源分布数据（name/value 列表） */
  data: DashboardSummary['sourceDist']
  /** 图表高度（px） */
  height?: number
}>()

/** 环形图 option：品牌主题由 useChart 自动注入 */
const donutOption = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, itemGap: 16 },
  series: [
    {
      name: '来源分布',
      type: 'pie',
      radius: ['52%', '76%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      emphasis: {
        label: { show: true, fontWeight: 600, fontSize: 13, color: '#334155' },
      },
      data: props.data,
    },
  ],
}))
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
  background: var(--admin-accent);
}
</style>
