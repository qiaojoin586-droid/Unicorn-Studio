<template>
  <div class="page-container">
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never" v-loading="loading">
          <template #header><span class="card-title">个人信息</span></template>
          <el-form :model="profileForm" label-width="80px">
            <el-form-item label="用户名">
              <el-input :model-value="auth.user?.username" disabled />
            </el-form-item>
            <el-form-item label="昵称">
              <el-input v-model="profileForm.nickname" />
            </el-form-item>
            <el-form-item label="角色">
              <el-tag v-for="r in auth.user?.roles || []" :key="r.code" size="small" class="role-tag">
                {{ r.name }}
              </el-tag>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveProfile">保存</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <span class="card-title">修改密码</span>
            <el-tag v-if="auth.user?.mustChangePassword === 1" type="warning" size="small" class="pwd-tag">
              首次登录请修改密码
            </el-tag>
          </template>
          <el-form :model="pwdForm" label-width="80px">
            <el-form-item label="原密码">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 8 位" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="changing" @click="changePassword">修改密码</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const changing = ref(false)

const profileForm = reactive({ nickname: '' })
const pwdForm = reactive({ oldPassword: '', newPassword: '' })

onMounted(async () => {
  loading.value = true
  try {
    const me = await auth.fetchMe()
    profileForm.nickname = me.nickname || ''
  } finally {
    loading.value = false
  }
})

async function saveProfile() {
  await authApi.updateProfile({ nickname: profileForm.nickname })
  ElMessage.success('保存成功')
  await auth.fetchMe()
}

async function changePassword() {
  if (!pwdForm.oldPassword || pwdForm.newPassword.length < 8) {
    ElMessage.warning('请填写原密码，新密码至少 8 位')
    return
  }
  changing.value = true
  try {
    await authApi.changePassword(pwdForm)
    ElMessage.success('密码已修改，请重新登录')
    await auth.logout()
    window.location.href = '/admin/login'
  } finally {
    changing.value = false
  }
}
</script>

<style scoped>
.card-title {
  font-weight: 600;
}
.role-tag {
  margin-right: 4px;
}
.pwd-tag {
  margin-left: 8px;
}
</style>
