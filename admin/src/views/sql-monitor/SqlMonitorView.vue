<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">SQL 监控</span>
      <div class="header-actions">
        <el-button :loading="loading" @click="refreshAll">
          <el-icon><Refresh /></el-icon>
          <span>刷新</span>
        </el-button>
        <el-button type="danger" plain @click="handleReset">重置统计</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="统计数据为 Druid 内存统计，自服务启动累计，重启后清零；本页面仅超级管理员可见。"
      class="monitor-alert"
    />

    <!-- 连接池概览 -->
    <el-row :gutter="16" class="stat-row" v-loading="statLoading">
      <el-col :xs="12" :sm="8" :lg="4">
        <StatCard label="活跃连接" :value="stat?.activeCount ?? 0" icon="Connection" color="#6366f1" />
      </el-col>
      <el-col :xs="12" :sm="8" :lg="4">
        <StatCard label="空闲连接" :value="stat?.poolingCount ?? 0" icon="CircleCheck" color="#22c55e" />
      </el-col>
      <el-col :xs="12" :sm="8" :lg="4">
        <StatCard label="等待线程" :value="stat?.waitThreadCount ?? 0" icon="Timer" color="#f59e0b" />
      </el-col>
      <el-col :xs="12" :sm="8" :lg="4">
        <StatCard label="累计执行" :value="stat?.executeCount ?? 0" icon="Odometer" color="#a78bfa" />
      </el-col>
      <el-col :xs="12" :sm="8" :lg="4">
        <StatCard label="累计错误" :value="stat?.errorCount ?? 0" icon="Warning" color="#ef4444" />
      </el-col>
      <el-col :xs="12" :sm="8" :lg="4">
        <StatCard label="事务提交" :value="stat?.commitCount ?? 0" icon="Finished" color="#06b6d4" />
      </el-col>
    </el-row>

    <!-- SQL 统计 -->
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="SQL 关键词"
          clearable
          style="width: 220px"
          @keyup.enter="search"
        />
        <el-select v-model="query.sortKey" style="width: 140px" @change="search">
          <el-option label="按总耗时" value="totalTime" />
          <el-option label="按执行次数" value="executeCount" />
          <el-option label="按错误次数" value="errorCount" />
          <el-option label="按最慢单次" value="maxTimespan" />
          <el-option label="按最后执行" value="lastTime" />
        </el-select>
        <el-button :title="query.order === 'desc' ? '当前降序，点击切换升序' : '当前升序，点击切换降序'" @click="toggleOrder">
          <el-icon>
            <CaretBottom v-if="query.order === 'desc'" />
            <CaretTop v-else />
          </el-icon>
        </el-button>
        <div class="toolbar-spacer"></div>
        <div class="auto-refresh">
          <span>自动刷新(30s)</span>
          <el-switch v-model="autoRefresh" @change="toggleAutoRefresh" />
        </div>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column label="SQL" min-width="280">
          <template #default="{ row }">
            <code class="sql-text" :class="{ 'is-slow': row.slowSql }">{{ row.sql }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="executeCount" label="执行次数" width="90" align="right" />
        <el-table-column label="错误" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.errorCount > 0" type="danger" size="small">{{ row.errorCount }}</el-tag>
            <span v-else class="muted">0</span>
          </template>
        </el-table-column>
        <el-table-column label="总耗时(ms)" width="100" align="right">
          <template #default="{ row }">{{ row.totalTime }}</template>
        </el-table-column>
        <el-table-column label="平均耗时(ms)" width="100" align="right">
          <template #default="{ row }">{{ avgTime(row) }}</template>
        </el-table-column>
        <el-table-column label="最慢单次(ms)" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ 'is-slow': row.slowSql }">{{ row.maxTimespan }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最后执行" width="150">
          <template #default="{ row }">{{ formatDateTime(row.lastTime) }}</template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="total === 0 && !loading"
        description="暂无 SQL 执行记录，请先访问页面触发查询"
        :image-size="80"
        class="monitor-empty"
      />

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @change="load"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sqlMonitorApi, type DataSourceStat, type SqlStatItem } from '@/api/sqlMonitor'
import StatCard from '@/components/StatCard.vue'
import { useTable } from '@/composables/useTable'
import { formatDateTime } from '@/utils/format'

const table = useTable((params) => sqlMonitorApi.sqls(params))
const { loading, list, total, query, load, search } = table

const stat = ref<DataSourceStat | null>(null)
const statLoading = ref(false)
const autoRefresh = ref(false)
let refreshTimer: number | null = null

/** 平均耗时 = 总耗时 / 执行次数 */
function avgTime(row: SqlStatItem): number {
  return row.executeCount > 0 ? Math.round(row.totalTime / row.executeCount) : 0
}

async function loadStat() {
  statLoading.value = true
  try {
    stat.value = await sqlMonitorApi.stat()
  } finally {
    statLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadStat(), load()])
}

function toggleOrder() {
  query.order = query.order === 'desc' ? 'asc' : 'desc'
  search()
}

async function handleReset() {
  await ElMessageBox.confirm('确定清空 SQL 与连接池统计吗？此操作不可恢复。', '重置统计', {
    type: 'warning',
    confirmButtonText: '重置',
    cancelButtonText: '取消',
  })
  await sqlMonitorApi.reset()
  ElMessage.success('统计已重置')
  refreshAll()
}

function toggleAutoRefresh(checked: string | number | boolean) {
  if (checked) {
    refreshTimer = window.setInterval(refreshAll, 30000)
  } else if (refreshTimer) {
    window.clearInterval(refreshTimer)
    refreshTimer = null
  }
}

onMounted(() => {
  query.sortKey = 'totalTime'
  query.order = 'desc'
  refreshAll()
})

onUnmounted(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
})
</script>

<style scoped>
.monitor-alert {
  margin-bottom: 16px;
}
.stat-row .el-col {
  margin-bottom: 16px;
}
.toolbar-spacer {
  flex: 1;
}
.auto-refresh {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #64748b;
}
.sql-text {
  font-family: 'JetBrains Mono', Consolas, Monaco, monospace;
  font-size: 12px;
  color: #334155;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 3px 8px;
  word-break: break-all;
}
.is-slow {
  color: #ef4444;
  font-weight: 600;
}
.muted {
  color: #cbd5e1;
}
.monitor-empty {
  padding-top: 8px;
}
</style>
