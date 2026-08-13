<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">新闻管理</span>
      <el-button type="primary" v-permission="'news:create'" @click="$router.push('/news/edit')">
        <el-icon><Plus /></el-icon>&nbsp;新增新闻
      </el-button>
    </div>

    <el-card shadow="never">
      <div class="table-toolbar">
        <el-input v-model="query.keyword" placeholder="标题 / slug" clearable style="width: 220px" @keyup.enter="search" />
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="search">
          <el-option label="草稿" value="draft" />
          <el-option label="已发布" value="published" />
          <el-option label="已下线" value="offline" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column label="标题" min-width="240">
          <template #default="{ row }">
            <div class="news-title">{{ row.title }}</div>
            <div class="news-slug">{{ row.slug }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="分类" width="110" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column prop="viewCount" label="浏览" width="70" />
        <el-table-column label="发布时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="$router.push(`/news/edit/${row.id}`)">编辑</el-button>
            <el-button size="small" link type="warning" v-if="row.status === 'published'" v-permission="'news:publish'" @click="toggleStatus(row, 'offline')">下线</el-button>
            <el-button size="small" link type="success" v-else v-permission="'news:publish'" @click="toggleStatus(row, 'published')">发布</el-button>
            <el-button size="small" link type="danger" v-permission="'news:delete'" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @change="load"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { newsApi } from '@/api/news'
import { useTable } from '@/composables/useTable'
import StatusTag from '@/components/StatusTag.vue'
import { formatDateTime } from '@/utils/format'

const table = useTable((params) => newsApi.list(params))
const { loading, list, total, query, load, search, reset } = table

async function toggleStatus(row: any, status: string) {
  await newsApi.changeStatus(row.id, status)
  ElMessage.success(status === 'published' ? '已发布' : '已下线')
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除新闻「${row.title}」吗？关联评论将一并删除。`, '提示', { type: 'warning' })
  await newsApi.remove(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.news-title {
  font-weight: 600;
}
.news-slug {
  font-size: 12px;
  color: #94a3b8;
}
</style>
