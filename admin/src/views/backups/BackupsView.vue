<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">数据备份</span>
      <el-button type="primary" :loading="triggering" @click="trigger">
        <el-icon><Refresh /></el-icon>&nbsp;立即备份
      </el-button>
    </div>

    <el-alert
      title="备份为数据库快照（mysqldump / H2 BACKUP），保留最近 7 份，每日 02:00 自动执行。恢复演练见部署文档。"
      type="info"
      :closable="false"
      class="backup-alert"
    />

    <el-card shadow="never">
      <el-table :data="list" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="fileName" label="文件名" min-width="220" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 'manual' ? 'primary' : 'info'">
              {{ row.type === 'manual' ? '手动' : '定时' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" :disabled="row.status !== 'success'" @click="download(row)">
              下载
            </el-button>
            <el-button size="small" link type="danger" @click="remove(row)">删除</el-button>
          </template>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { backupApi } from '@/api/backup'
import { useTable } from '@/composables/useTable'
import StatusTag from '@/components/StatusTag.vue'
import { formatDateTime, formatSize } from '@/utils/format'

const table = useTable((params) => backupApi.list(params))
const { loading, list, total, query, load } = table

const triggering = ref(false)

async function trigger() {
  triggering.value = true
  try {
    await backupApi.trigger()
    ElMessage.success('备份任务已触发，稍后刷新查看状态')
    setTimeout(load, 3000)
  } finally {
    triggering.value = false
  }
}

function download(row: any) {
  const token = localStorage.getItem('admin_token')
  const url = backupApi.downloadUrl(row.id)
  const link = document.createElement('a')
  link.href = url
  link.setAttribute('download', row.fileName)
  // 下载接口需要 Bearer token，走 fetch 流式保存
  fetch(url, { headers: { Authorization: `Bearer ${token}` } })
    .then((res) => {
      if (!res.ok) throw new Error('下载失败')
      return res.blob()
    })
    .then((blob) => {
      const objectUrl = URL.createObjectURL(blob)
      link.href = objectUrl
      document.body.appendChild(link)
      link.click()
      URL.revokeObjectURL(objectUrl)
    })
    .catch(() => ElMessage.error('下载失败'))
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除备份「${row.fileName}」吗？`, '提示', { type: 'warning' })
  await backupApi.remove(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.backup-alert {
  margin-bottom: 16px;
}
</style>
