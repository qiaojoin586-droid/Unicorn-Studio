<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">留言管理</span>
    </div>

    <el-card shadow="never">
      <div class="table-toolbar">
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="search">
          <el-option label="未回复" value="new" />
          <el-option label="已回复" value="replied" />
          <el-option label="已关闭" value="closed" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="姓名 / 联系方式 / 内容" clearable style="width: 240px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column label="留言人" width="120">
          <template #default="{ row }">
            <div class="msg-name">{{ row.name }}</div>
            <div class="msg-contact">{{ row.contact || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="280" show-overflow-tooltip />
        <el-table-column label="来源" width="110">
          <template #default="{ row }">{{ row.source === 'guestbook' ? '留言板' : '联系表单' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" v-permission="'message:reply'" @click="openReply(row)">回复</el-button>
            <el-button size="small" link type="warning" v-permission="'message:reply'" @click="close(row)">关闭</el-button>
            <el-button size="small" link type="danger" v-permission="'message:delete'" @click="remove(row)">删除</el-button>
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

    <el-dialog v-model="replyVisible" title="回复留言" width="520px">
      <el-descriptions :column="1" border size="small" class="msg-desc">
        <el-descriptions-item label="姓名">{{ replyTarget?.name }}</el-descriptions-item>
        <el-descriptions-item label="联系方式">{{ replyTarget?.contact || '-' }}</el-descriptions-item>
        <el-descriptions-item label="内容">{{ replyTarget?.content }}</el-descriptions-item>
      </el-descriptions>
      <el-input
        v-model="replyContent"
        type="textarea"
        :rows="4"
        maxlength="2000"
        show-word-limit
        placeholder="回复内容（将发送邮件至留言人邮箱，若填写）"
        class="reply-input"
      />
      <template #footer>
        <el-button @click="replyVisible = false">取消</el-button>
        <el-button type="primary" :loading="replying" @click="submitReply">回复并发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { messageApi } from '@/api/message'
import { useTable } from '@/composables/useTable'
import StatusTag from '@/components/StatusTag.vue'
import { formatDateTime } from '@/utils/format'

const table = useTable((params) => messageApi.list(params))
const { loading, list, total, query, load, search } = table

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
    await messageApi.reply(replyTarget.value.id, replyContent.value.trim())
    ElMessage.success('回复成功')
    replyVisible.value = false
    load()
  } finally {
    replying.value = false
  }
}

async function close(row: any) {
  await messageApi.changeStatus(row.id, 'closed')
  ElMessage.success('已关闭')
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm('确定删除该留言吗？', '提示', { type: 'warning' })
  await messageApi.remove(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.msg-name {
  font-weight: 600;
}
.msg-contact {
  font-size: 12px;
  color: #94a3b8;
}
.msg-desc {
  margin-bottom: 12px;
}
.reply-input {
  margin-top: 12px;
}
</style>
