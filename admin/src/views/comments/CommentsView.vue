<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">评论审核</span>
    </div>

    <el-card shadow="never">
      <div class="table-toolbar">
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="search">
          <el-option label="待审核" value="pending" />
          <el-option label="已通过" value="approved" />
          <el-option label="已拒绝" value="rejected" />
        </el-select>
        <el-select v-model="query.targetType" placeholder="对象类型" clearable style="width: 120px" @change="search">
          <el-option label="作品" value="work" />
          <el-option label="新闻" value="news" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="内容 / 昵称" clearable style="width: 200px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button
          type="success"
          plain
          v-if="selection.length"
          v-permission="'comment:audit'"
          @click="batchAction('approve')"
        >批量通过</el-button>
        <el-button
          type="danger"
          plain
          v-if="selection.length"
          v-permission="'comment:audit'"
          @click="batchAction('delete')"
        >批量删除</el-button>
      </div>

      <el-table :data="list" v-loading="loading" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="42" />
        <el-table-column label="评论内容" min-width="280">
          <template #default="{ row }">
            <div class="comment-content">{{ row.content }}</div>
            <div class="comment-meta">
              {{ row.targetType === 'work' ? '作品' : '新闻' }} #{{ row.targetId }}
              <template v-if="row.parentId">（回复 #{{ row.parentId }}）</template>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="displayName" label="昵称" width="130" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'pending'"
              size="small" link type="success" v-permission="'comment:audit'"
              @click="changeStatus(row, 'approved')"
            >通过</el-button>
            <el-button
              v-if="row.status !== 'rejected'"
              size="small" link type="warning" v-permission="'comment:audit'"
              @click="changeStatus(row, 'rejected')"
            >拒绝</el-button>
            <el-button size="small" link type="primary" v-permission="'comment:reply'" @click="openReply(row)">回复</el-button>
            <el-button size="small" link type="danger" v-permission="'comment:delete'" @click="remove(row)">删除</el-button>
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

    <el-dialog v-model="replyVisible" title="回复评论" width="480px">
      <el-input v-model="replyContent" type="textarea" :rows="4" maxlength="1000" show-word-limit />
      <template #footer>
        <el-button @click="replyVisible = false">取消</el-button>
        <el-button type="primary" :loading="replying" @click="submitReply">回复</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { commentApi } from '@/api/comment'
import { useTable } from '@/composables/useTable'
import StatusTag from '@/components/StatusTag.vue'
import { formatDateTime } from '@/utils/format'

const table = useTable((params) => commentApi.list(params))
const { loading, list, total, query, selection, onSelectionChange, load, search } = table

const replyVisible = ref(false)
const replying = ref(false)
const replyTarget = ref<any>(null)
const replyContent = ref('')

function openReply(row: any) {
  replyTarget.value = row
  replyContent.value = ''
  replyVisible.value = true
}

async function submitReply() {
  if (!replyContent.value.trim()) return
  replying.value = true
  try {
    await commentApi.reply(replyTarget.value.id, replyContent.value.trim())
    ElMessage.success('回复成功')
    replyVisible.value = false
    load()
  } finally {
    replying.value = false
  }
}

async function changeStatus(row: any, status: string) {
  await commentApi.changeStatus(row.id, status)
  ElMessage.success(status === 'approved' ? '已通过' : '已拒绝')
  load()
}

async function batchAction(action: string) {
  const result = await commentApi.batch(selection.value.map((r) => r.id), action)
  ElMessage.success(`成功 ${result.successCount} 条，失败 ${result.failCount} 条`)
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm('确定删除该评论吗？', '提示', { type: 'warning' })
  await commentApi.remove(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.comment-content {
  white-space: pre-wrap;
}
.comment-meta {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}
</style>
