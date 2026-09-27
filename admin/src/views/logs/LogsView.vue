<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">审计日志</span>
    </div>

    <el-card shadow="never">
      <div class="table-toolbar">
        <el-input v-model="query.username" placeholder="操作人" clearable style="width: 140px" @keyup.enter="search" />
        <el-input v-model="query.module" placeholder="模块" clearable style="width: 120px" @keyup.enter="search" />
        <el-select v-model="query.status" placeholder="结果" clearable style="width: 110px" @change="search">
          <el-option label="成功" value="success" />
          <el-option label="失败" value="failed" />
        </el-select>
        <el-date-picker
          v-model="range"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          style="width: 260px"
        />
        <el-button type="primary" @click="searchWithRange">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="操作人" width="110" />
        <el-table-column label="模块 / 操作" min-width="150">
          <template #default="{ row }">
            <div class="log-module">{{ row.module }} · {{ row.action }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="method" label="方法" width="70" />
        <el-table-column prop="path" label="路径" min-width="200" show-overflow-tooltip />
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'success' ? 'success' : 'danger'" size="small">
              {{ row.status === 'success' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="120" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="90" />
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

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
import { onMounted, ref } from 'vue'
import { logApi } from '@/api/log'
import { useTable } from '@/composables/useTable'
import { formatDateTime } from '@/utils/format'

const table = useTable((params) => logApi.list(params))
const { loading, list, total, query, load, search } = table

const range = ref<string[]>([])

function searchWithRange() {
  if (range.value?.length === 2) {
    query.from = range.value[0]
    query.to = range.value[1]
  } else {
    delete query.from
    delete query.to
  }
  search()
}

onMounted(load)
</script>

<style scoped>
.log-module {
  font-weight: 500;
}
</style>
