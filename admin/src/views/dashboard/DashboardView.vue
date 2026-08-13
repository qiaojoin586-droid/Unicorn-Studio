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
        <StatCard label="今日 PV" :value="summary?.todayPv ?? 0" icon="View" color="#409eff" />
      </el-col>
      <el-col :span="6">
        <StatCard label="今日 UV" :value="summary?.todayUv ?? 0" icon="User" color="#67c23a" />
      </el-col>
      <el-col :span="6">
        <StatCard label="作品数（已发布）" :value="`${summary?.publishedWorks ?? 0}/${summary?.workCount ?? 0}`" icon="Picture" color="#e6a23c" />
      </el-col>
      <el-col :span="6">
        <StatCard label="待审评论" :value="summary?.pendingComments ?? 0" icon="ChatDotRound" color="#f56c6c" />
      </el-col>
    </el-row>

    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <StatCard label="新留言" :value="summary?.newMessages ?? 0" icon="Message" color="#f56c6c" />
      </el-col>
      <el-col :span="6">
        <StatCard label="注册用户" :value="summary?.userCount ?? 0" icon="UserFilled" color="#909399" />
      </el-col>
      <el-col :span="6">
        <StatCard label="今日新增用户" :value="summary?.todayNewUsers ?? 0" icon="User" color="#67c23a" />
      </el-col>
      <el-col :span="6">
        <StatCard label="新闻数" :value="summary?.newsCount ?? 0" icon="Document" color="#409eff" />
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :span="16">
        <ChartCard title="访问趋势（PV/UV）" :height="320" :option="trendOption" />
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="recent-card">
          <template #header><span class="chart-title">最近留言</span></template>
          <div v-if="summary?.recentMessages?.length" class="recent-list">
            <div v-for="m in summary.recentMessages" :key="m.id" class="recent-item">
              <div class="recent-name">{{ m.name }}</div>
              <div class="recent-content">{{ m.content }}</div>
              <div class="recent-time">{{ formatDateTime(m.createdAt) }}</div>
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
  legend: { data: ['PV', 'UV'] },
  grid: { left: 40, right: 20, top: 40, bottom: 30 },
  xAxis: { type: 'category', data: summary.value?.trend.map((t) => t.date) ?? [] },
  yAxis: { type: 'value' },
  series: [
    {
      name: 'PV',
      type: 'line',
      smooth: true,
      data: summary.value?.trend.map((t) => t.pv) ?? [],
      areaStyle: { opacity: 0.15 },
    },
    {
      name: 'UV',
      type: 'line',
      smooth: true,
      data: summary.value?.trend.map((t) => t.uv) ?? [],
      areaStyle: { opacity: 0.15 },
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
  font-weight: 600;
  font-size: 15px;
}
.recent-list {
  max-height: 320px;
  overflow-y: auto;
}
.recent-item {
  padding: 8px 0;
  border-bottom: 1px solid #f0f2f5;
}
.recent-name {
  font-weight: 600;
  font-size: 13px;
}
.recent-content {
  font-size: 13px;
  color: #606266;
  margin: 4px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.recent-time {
  font-size: 12px;
  color: #c0c4cc;
}
</style>
