<template>
  <div class="login-page">
    <aside class="login-brand-panel">
      <a :href="publicSiteUrl()" class="login-brand" aria-label="独角兽工作室主站"><img :src="brandMarkLight" alt="" width="42" height="42" /><span><strong>独角兽工作室</strong><small>UNICORN STUDIO</small></span></a>
      <div class="login-brand-copy"><span class="login-kicker">THE CONTENT WORKSPACE</span><h1>让好奇心，<br />有序发生。</h1><p>从一次编辑，到一个新的故事。<br />在这里，打理作品、分享知识，连接每一次探索。</p></div>
      <svg class="login-brand-art" viewBox="0 0 400 300" fill="none" aria-hidden="true"><circle cx="205" cy="155" r="119" stroke="#ffffff18" /><path d="M287 48C359 116 336 254 210 260C112 265 65 195 88 121C83 195 171 221 227 186C277 153 285 99 271 65" stroke="#d3d0c6" stroke-width="1.5"/><path d="M94 55 204 137 164 155Z" fill="#d3d0c6"/><circle cx="317" cy="70" r="7" fill="#c92d3b" /></svg>
      <div class="login-brand-foot"><span class="brand-signal" aria-hidden="true"></span> STAY CURIOUS. MAKE THINGS.</div>
    </aside>
    <main class="login-main">
      <div class="login-top"><a :href="publicSiteUrl()">查看主站 <span aria-hidden="true">↗</span></a></div>
      <div class="login-form-shell">
        <div class="login-form-brand"><img :src="brandMark" alt="" width="38" height="38" /><span>独角兽工作室</span></div>
        <p class="login-overline">WELCOME BACK</p>
        <h2>登录内容工作台</h2>
        <p class="login-subtitle">使用你的管理账户，继续今天的工作。</p>
        <div v-if="loginError" class="login-error" role="alert"><el-icon :size="16"><WarningFilled /></el-icon><span>{{ loginError }}</span></div>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent="submit">
          <el-form-item label="用户名" prop="username"><el-input v-model="form.username" name="username" placeholder="输入管理账户" autocomplete="username" :prefix-icon="User" :disabled="loading" /></el-form-item>
          <el-form-item label="密码" prop="password"><el-input v-model="form.password" name="password" type="password" placeholder="输入密码" autocomplete="current-password" :prefix-icon="Lock" :disabled="loading" show-password /></el-form-item>
          <el-button type="primary" native-type="submit" class="login-submit" :loading="loading">{{ loading ? '正在登录' : '登录工作台' }}<span v-if="!loading" aria-hidden="true">→</span></el-button>
        </el-form>
        <p class="login-help">仅限授权人员使用。账户权限由管理员分配。</p>
      </div>
      <footer class="login-footer"><span>© {{ currentYear }} Unicorn Studio</span><span>CONTENT / PEOPLE / POSSIBILITIES</span></footer>
    </main>
  </div>
</template>
<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { publicSiteUrl } from '@/utils/site'
import brandMark from '@/generated/brand-mark.svg'
import brandMarkLight from '@/generated/brand-mark-light.svg'
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const currentYear = new Date().getFullYear()
const formRef = ref()
const loading = ref(false)
const loginError = ref('')
const form = reactive({ username: '', password: '' })
const rules = { username: [{ required: true, message: '请输入用户名', trigger: 'blur' }], password: [{ required: true, message: '请输入密码', trigger: 'blur' }] }
function getLoginError(error: unknown) { const err = error as { response?: { data?: { message?: string } }; message?: string }; return err.response?.data?.message || err.message || '登录失败，请稍后重试' }
async function submit() {
  if (loading.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loginError.value = ''
  loading.value = true
  try {
    const data = await auth.login(form.username, form.password)
    if (data.mustChangePassword === 1) { ElMessage.warning('首次登录请先修改密码'); router.push('/profile'); return }
    let target = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    const base = import.meta.env.BASE_URL
    if (base !== '/' && target.startsWith(base)) target = target.slice(base.length - 1) || '/'
    if (!target.startsWith('/') || target.startsWith('//') || target.startsWith('/login')) target = '/'
    router.push(target)
  } catch (error) { loginError.value = getLoginError(error) }
  finally { loading.value = false }
}
watch([() => form.username, () => form.password], () => { loginError.value = '' })
</script>
<style scoped>
.login-page { min-height: 100dvh; display: grid; grid-template-columns: minmax(360px, .9fr) minmax(480px, 1.1fr); background: #f4f2ec; }
.login-brand-panel { position: relative; display: flex; flex-direction: column; overflow: hidden; color: #f4f2ec; background: #121316; padding: 42px 54px 30px; }
.login-brand { display: flex; align-items: center; gap: 13px; width: max-content; color: inherit; text-decoration: none; }
.login-brand strong { display: block; font-size: 17px; font-weight: 550; letter-spacing: .035em; }
.login-brand small { display: block; margin-top: 7px; font: 9px ui-monospace, monospace; letter-spacing: .14em; color: #a7a8af; }
.login-brand-copy { margin-block: auto; padding-top: 95px; padding-bottom: 280px; z-index: 1; }
.login-kicker { font: 10px ui-monospace, monospace; letter-spacing: .13em; color: #a7a8af; }
.login-brand-copy h1 { margin-top: 27px; font-size: clamp(42px, 4vw, 66px); font-weight: 550; line-height: 1.25; letter-spacing: -.045em; }
.login-brand-copy p { font-size: 14px; color: #a7a8af; line-height: 1.95; margin-top: 28px; }
.login-brand-art { position: absolute; width: 330px; height: 248px; right: -15px; bottom: 56px; }
.login-brand-foot { display: flex; align-items: center; gap: 10px; font: 9px ui-monospace, monospace; letter-spacing: .1em; color: #8e8f96; }
.brand-signal { width: 5px; height: 5px; background: #c92d3b; border-radius: 50%; }
.login-main { display: flex; flex-direction: column; min-width: 0; }
.login-top { padding: 32px 38px; display: flex; justify-content: flex-end; }
.login-top a { min-height: 40px; display: inline-flex; align-items: center; gap: 24px; color: #62636a; font-size: 12px; text-decoration: none; }
.login-top a:hover { color: #121316; }
.login-form-shell { width: 100%; max-width: 430px; padding: 35px 24px 56px; margin: auto; }
.login-form-brand { display: none; align-items: center; gap: 11px; margin-bottom: 38px; font-size: 17px; font-weight: 550; }
.login-overline { font: 10px ui-monospace, monospace; color: #76777d; letter-spacing: .13em; margin-bottom: 17px; }
.login-form-shell h2 { font-size: 29px; font-weight: 600; letter-spacing: -.04em; line-height: 1.4; }
.login-subtitle { margin: 12px 0 34px; font-size: 13px; line-height: 1.8; color: #62636a; }
.login-form-shell :deep(.el-form-item) { margin-bottom: 25px; }
.login-form-shell :deep(.el-form-item__label) { font-size: 13px; padding-bottom: 8px; }
.login-form-shell :deep(.el-input__wrapper) { min-height: 46px; box-shadow: 0 0 0 1px #d0cfc9 inset; background: #faf9f6; }
.login-error { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 22px; padding: 12px 14px; color: #9a1f2c; border: 1px solid #b4233430; background: #b4233408; border-radius: 4px; font-size: 12px; line-height: 1.7; }
.login-error .el-icon { margin-top: 2px; flex-shrink: 0; }
.login-submit { display: flex; width: 100%; min-height: 47px; font-size: 14px; border-radius: 4px; margin-top: 8px; }
.login-submit :deep(span) { display: inline-flex; align-items: center; justify-content: space-between; width: 100%; }
.login-submit :deep(span span) { display: inline; width: auto; margin-left: 18px; }
.login-help { margin-top: 21px; font-size: 11px; line-height: 1.8; color: #77787f; }
.login-footer { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 10px; padding: 25px 38px; color: #85868c; font: 9px ui-monospace, monospace; letter-spacing: .03em; }
@media (max-width: 1050px) { .login-brand-panel { padding-inline: 34px; } .login-brand-copy p { font-size: 13px; } .login-page { grid-template-columns: minmax(310px, .8fr) minmax(420px, 1.2fr); } }
@media (max-width: 767px) { .login-page { grid-template-columns: minmax(0, 1fr); } .login-brand-panel { display: none; } .login-main { min-height: 100dvh; } .login-top { padding: 16px 22px; } .login-form-shell { padding: 14px 27px 45px; } .login-form-brand { display: flex; } .login-form-shell h2 { font-size: 27px; } .login-footer { padding: 22px 27px; } .login-footer span:last-child { display: none; } }
@media (max-height: 720px) and (min-width: 768px) { .login-brand-copy { padding-top: 45px; padding-bottom: 210px; } .login-brand-art { width: 250px; height: 190px; bottom: 45px; } .login-form-shell { padding-block: 20px 32px; } }
</style>
