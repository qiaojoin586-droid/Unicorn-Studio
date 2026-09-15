<template>
  <div class="login-page">
    <!-- 背景装饰 -->
    <div class="bg-deco" aria-hidden="true">
      <div class="blob blob-1"></div>
      <div class="blob blob-2"></div>
      <div class="blob blob-3"></div>
      <div class="grid-overlay"></div>
    </div>

    <el-card shadow="never" class="login-card">
      <div class="brand">
        <div class="brand-logo">🦄</div>
        <h1 class="login-title">独角兽工作室</h1>
        <p class="login-subtitle">后台管理系统 · 内容控制台</p>
      </div>

      <transition name="el-fade-in">
        <div v-if="loginError" class="login-error" role="alert">
          <el-icon :size="16"><WarningFilled /></el-icon>
          <span>{{ loginError }}</span>
        </div>
      </transition>

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

      <div class="login-footer">© 2026 Unicorn Studio</div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref()
const loading = ref(false)
const loginError = ref('')
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 从 axios/拦截器错误中提取可展示的消息 */
function getLoginError(e: unknown): string {
  const err = e as { response?: { data?: { message?: string } }; message?: string }
  return err.response?.data?.message || err.message || '登录失败，请稍后重试'
}

async function submit() {
  // A-08：按钮上的 :loading 只能挡住鼠标点击，回车绑定在 el-form 的 @keyup.enter，
  // 连按回车会并发发出多个登录请求（叠加后端登录失败计数，5 次即锁 30 分钟）。
  if (loading.value) return
  await formRef.value?.validate()
  loginError.value = ''
  loading.value = true
  try {
    const data = await auth.login(form.username, form.password)
    if (data.mustChangePassword === 1) {
      ElMessage.warning('首次登录请先修改密码')
      router.push('/profile')
      return
    }
    // 兼容历史/外部来源的含 base 前缀的 redirect（如 /admin/dashboard），
    // vue-router 路径不含 base，直接 push 会拼成 /admin/admin/... 命中 404
    let target = (route.query.redirect as string) || '/'
    const base = import.meta.env.BASE_URL
    if (base !== '/' && target.startsWith(base)) {
      target = target.slice(base.length - 1) || '/'
    }
    router.push(target)
  } catch (e) {
    // 登录接口走 silent 模式（不弹全局提示），错误改为表单内联展示
    loginError.value = getLoginError(e)
  } finally {
    loading.value = false
  }
}

// 用户重新输入时清除错误提示
watch([() => form.username, () => form.password], () => {
  loginError.value = ''
})
</script>

<style scoped>
.login-page {
  position: relative;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: linear-gradient(150deg, #0b0f1e 0%, #141830 50%, #1b1533 100%);
}

/* ---- 背景光斑 ---- */
.bg-deco {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.55;
}
.blob-1 {
  width: 480px;
  height: 480px;
  left: -120px;
  top: -140px;
  background: #6366f1;
}
.blob-2 {
  width: 420px;
  height: 420px;
  right: -100px;
  bottom: -140px;
  background: #a855f7;
  opacity: 0.45;
}
.blob-3 {
  width: 300px;
  height: 300px;
  right: 22%;
  top: -120px;
  background: #22d3ee;
  opacity: 0.22;
}
.grid-overlay {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.035) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse 70% 60% at 50% 45%, black 30%, transparent 100%);
}

/* ---- 玻璃卡片 ---- */
.login-card {
  position: relative;
  z-index: 1;
  width: 400px;
  border-radius: 20px;
  padding: 8px;
  background: rgba(255, 255, 255, 0.07);
  border: 1px solid rgba(255, 255, 255, 0.14);
  backdrop-filter: blur(24px);
  box-shadow: 0 32px 80px rgba(0, 0, 0, 0.45);
}
.login-card:hover {
  border-color: rgba(255, 255, 255, 0.14);
  box-shadow: 0 32px 80px rgba(0, 0, 0, 0.45);
}
.login-card :deep(.el-card__body) {
  padding: 40px 40px 28px;
}

.brand {
  text-align: center;
  margin-bottom: 32px;
}
.brand-logo {
  width: 64px;
  height: 64px;
  margin: 0 auto 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  border-radius: 18px;
  background: linear-gradient(135deg, #6366f1, #a855f7);
  box-shadow: 0 12px 32px rgba(99, 102, 241, 0.45);
}
.login-title {
  font-size: 22px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 2px;
}
.login-subtitle {
  color: rgba(255, 255, 255, 0.5);
  margin-top: 8px;
  font-size: 13px;
  letter-spacing: 1px;
}

/* ---- 深色表单 ---- */
.login-card :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.16) inset;
  border-radius: 10px;
  height: 44px;
  transition: box-shadow 0.2s ease, background-color 0.2s ease;
}
.login-card :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.28) inset;
}
.login-card :deep(.el-input__wrapper.is-focus) {
  background: rgba(255, 255, 255, 0.09);
  box-shadow:
    0 0 0 1px rgba(129, 140, 248, 0.85) inset,
    0 0 24px rgba(99, 102, 241, 0.35);
}
.login-card :deep(.el-input__inner) {
  color: #fff;
}
.login-card :deep(.el-input__inner::placeholder) {
  color: rgba(255, 255, 255, 0.38);
}
.login-card :deep(.el-input__prefix) {
  color: rgba(255, 255, 255, 0.42);
}
.login-card :deep(.el-input__suffix) {
  color: rgba(255, 255, 255, 0.42);
}

/* ---- 登录失败内联提示 ---- */
.login-error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  margin-bottom: 18px;
  border-radius: 10px;
  font-size: 13px;
  color: #fca5a5;
  background: rgba(239, 68, 68, 0.12);
  border: 1px solid rgba(239, 68, 68, 0.35);
}

.login-btn {
  width: 100%;
  height: 44px;
  margin-top: 4px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 6px;
  border-radius: 10px;
}

.login-footer {
  text-align: center;
  color: rgba(255, 255, 255, 0.28);
  font-size: 12px;
  margin-top: 26px;
  letter-spacing: 0.5px;
}
</style>
