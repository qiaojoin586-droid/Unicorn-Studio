<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">作品管理</span>
      <el-button type="primary" v-permission="'work:create'" @click="$router.push('/works/edit')">
        <el-icon><Plus /></el-icon>&nbsp;新增作品
      </el-button>
    </div>

    <el-card shadow="never">
      <div class="table-toolbar">
        <el-input v-model="query.keyword" placeholder="标题 / slug" clearable style="width: 220px" @keyup.enter="search" />
        <el-select v-model="query.category" placeholder="分类" clearable style="width: 140px" @change="search">
          <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="search">
          <el-option label="草稿" value="draft" />
          <el-option label="已发布" value="published" />
          <el-option label="已下线" value="offline" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-table :data="list" v-loading="loading" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="42" />
        <el-table-column label="标题" min-width="220">
          <template #default="{ row }">
            <div class="work-title">{{ row.title }}</div>
            <div class="work-slug">{{ row.slug }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="分类" width="100" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="推荐" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.featured === 1" type="warning" size="small">推荐</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="viewCount" label="浏览" width="70" />
        <el-table-column label="发布时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="$router.push(`/works/edit/${row.id}`)">编辑</el-button>
            <el-button
              size="small"
              link
              type="warning"
              v-if="row.status === 'published'"
              v-permission="'work:publish'"
              @click="toggleStatus(row, 'offline')"
            >下线</el-button>
            <el-button
              size="small"
              link
              type="success"
              v-else-if="row.status !== 'draft' || true"
              v-permission="'work:publish'"
              @click="toggleStatus(row, 'published')"
            >发布</el-button>
            <el-button size="small" link type="danger" v-permission="'work:delete'" @click="remove(row)">删除</el-button>
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
import { workApi } from '@/api/work'
import { useTable } from '@/composables/useTable'
import StatusTag from '@/components/StatusTag.vue'
import { formatDateTime } from '@/utils/format'

const categories = ['竞赛', '论文', '项目', '其他']

const table = useTable((params) => workApi.list(params))
const { loading, list, total, query, selection, onSelectionChange, load, search, reset } = table

async function toggleStatus(row: any, status: string) {
  await workApi.changeStatus(row.id, status)
  ElMessage.success(status === 'published' ? '已发布' : '已下线')
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除作品「${row.title}」吗？关联评论将一并删除。`, '提示', { type: 'warning' })
  await workApi.remove(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.work-title {
  font-weight: 600;
}
.work-slug {
  font-size: 12px;
  color: #94a3b8;
}
</style>
