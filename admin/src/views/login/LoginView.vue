<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-title">🦄 独角兽工作室</div>
      <div class="login-subtitle">后台管理系统</div>
      <el-form :model="form" :rules="rules" ref="formRef" size="large" @keyup.enter="submit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="loading" @click="submit">
          登 录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit() {
  await formRef.value?.validate()
  loading.value = true
  try {
    const data = await auth.login(form.username, form.password)
    if (data.mustChangePassword === 1) {
      ElMessage.warning('首次登录请先修改密码')
      router.push('/profile')
      return
    }
    router.push((route.query.redirect as string) || '/')
  } catch {
    // 错误信息已由拦截器提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #001529 100%);
}
.login-card {
  width: 380px;
  border-radius: 8px;
}
.login-title {
  font-size: 22px;
  font-weight: 700;
  text-align: center;
  color: #303133;
}
.login-subtitle {
  text-align: center;
  color: #909399;
  margin: 6px 0 24px;
  font-size: 14px;
}
.login-btn {
  width: 100%;
}
</style>
