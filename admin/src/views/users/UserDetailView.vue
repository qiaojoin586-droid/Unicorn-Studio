<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">用户详情</span>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <el-card shadow="never" v-loading="loading" style="max-width: 640px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="ID">{{ detail?.user.id }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ detail?.user.nickname }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ detail?.user.email }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="detail?.user.status === 1 ? 'success' : 'danger'" size="small">
            {{ detail?.user.status === 1 ? '正常' : '禁言' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="禁言原因">{{ detail?.user.banReason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="注册 IP">{{ detail?.user.registerIp || '-' }}</el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ formatDateTime(detail?.user.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="最后登录">{{ formatDateTime(detail?.user.lastLoginAt) }}</el-descriptions-item>
        <el-descriptions-item label="评论数">{{ detail?.commentCount }}</el-descriptions-item>
        <el-descriptions-item label="留言数">{{ detail?.messageCount }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { userApi } from '@/api/user'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const loading = ref(false)
const detail = ref<any>(null)

onMounted(async () => {
  loading.value = true
  try {
    detail.value = await userApi.detail(Number(route.params.id))
  } finally {
    loading.value = false
  }
})
</script>
