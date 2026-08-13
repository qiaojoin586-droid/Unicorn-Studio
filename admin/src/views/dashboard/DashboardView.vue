<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">仪表盘</span>
      <el-radio-group v-model="days" size="small" @change="load">
        <el-radio-button :value="7">7 天</el-radio-button>
        <el-radio-button :value="30">30 天</el-radio-button>
        <el-radio-button :value="90">90 天</el-radio-button>
      </el-radio-group>
    </div>

    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <StatCard label="今日 PV" :value="summary?.todayPv ?? 0" icon="View" color="#6366f1" />
      </el-col>
      <el-col :span="6">
        <StatCard label="今日 UV" :value="summary?.todayUv ?? 0" icon="User" color="#a78bfa" />
      </el-col>
      <el-col :span="6">
        <StatCard label="作品数（已发布）" :value="`${summary?.publishedWorks ?? 0}/${summary?.workCount ?? 0}`" icon="Picture" color="#f59e0b" />
      </el-col>
      <el-col :span="6">
        <StatCard label="待审评论" :value="summary?.pendingComments ?? 0" icon="ChatDotRound" color="#ef4444" />
      </el-col>
    </el-row>

    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <StatCard label="新留言" :value="summary?.newMessages ?? 0" icon="Message" color="#22c55e" />
      </el-col>
      <el-col :span="6">
        <StatCard label="注册用户" :value="summary?.userCount ?? 0" icon="UserFilled" color="#64748b" />
      </el-col>
      <el-col :span="6">
        <StatCard label="今日新增用户" :value="summary?.todayNewUsers ?? 0" icon="User" color="#06b6d4" />
      </el-col>
      <el-col :span="6">
        <StatCard label="新闻数" :value="summary?.newsCount ?? 0" icon="Document" color="#8b5cf6" />
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :span="16">
        <ChartCard title="访问趋势（PV/UV）" :height="340" :option="trendOption" />
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="recent-card">
          <template #header><span class="chart-title">最近留言</span></template>
          <div v-if="summary?.recentMessages?.length" class="recent-list">
            <div v-for="m in summary.recentMessages" :key="m.id" class="recent-item">
              <div class="recent-head">
                <span class="recent-name">{{ m.name }}</span>
                <span class="recent-time">{{ formatDateTime(m.createdAt) }}</span>
              </div>
              <div class="recent-content">{{ m.content }}</div>
            </div>
          </div>
          <el-empty v-else description="暂无留言" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { dashboardApi, type DashboardSummary } from '@/api/dashboard'
import StatCard from '@/components/StatCard.vue'
import ChartCard from '@/components/ChartCard.vue'
import { formatDateTime } from '@/utils/format'

const days = ref(30)
const summary = ref<DashboardSummary | null>(null)

async function load() {
  summary.value = await dashboardApi.summary(days.value)
}

const trendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['PV', 'UV'], top: 0, right: 0 },
  grid: { left: 44, right: 20, top: 44, bottom: 32 },
  xAxis: { type: 'category', data: summary.value?.trend.map((t) => t.date) ?? [] },
  yAxis: { type: 'value' },
  series: [
    {
      name: 'PV',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2.5 },
      data: summary.value?.trend.map((t) => t.pv) ?? [],
      areaStyle: { opacity: 0.14 },
    },
    {
      name: 'UV',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2.5 },
      data: summary.value?.trend.map((t) => t.uv) ?? [],
      areaStyle: { opacity: 0.14 },
    },
  ],
}))

load()
</script>

<style scoped>
.stat-row {
  margin-bottom: 16px;
}
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
.recent-list {
  max-height: 340px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.recent-item {
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid var(--el-border-color-lighter);
  background: #fff;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.recent-item:hover {
  border-color: color-mix(in srgb, var(--el-color-primary) 30%, white);
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06);
}
.recent-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.recent-name {
  font-weight: 600;
  font-size: 13px;
  color: #334155;
}
.recent-content {
  font-size: 13px;
  color: #64748b;
  margin-top: 4px;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.recent-time {
  font-size: 12px;
  color: #cbd5e1;
  flex-shrink: 0;
}
</style>
