<template>
  <!--
    Teleport 到 body，而不是留在原地。
    原因：本组件挂在 AiChatDialog 内部，而那个面板根节点带 `overflow-hidden`，内层在入场/离场
    过渡期间还会被加上 `transform`（translate/scale）—— `position: fixed` 的遮罩一旦处在带
    transform 的祖先里，就会被当成相对那个祖先定位并缩放。Teleport 之后彻底解耦。

    布局沿用参考包 AuthShell 的两栏结构：左栏品牌区 + 动画角色，右栏表单。
    左栏用中性冷灰渐变：四个角色本身是彩色的，底色必须中性才不会跟他们抢。
    右栏是淡紫底 + 一片紫色极光。色彩语言来自主页大标题的 .gradient-flow
    （indigo-400 → violet-400 → purple-400 循环滚动），极光用的就是同一组色。

    标志用项目自己的 🦄 / 独角兽工作室。
  -->
  <Teleport to="body">
    <div
      v-if="store.showLoginPrompt"
      class="auth-viewport fixed inset-0 z-[1000] grid justify-items-center overflow-y-auto overscroll-contain px-4 py-4"
      @click.self="close"
    >
      <div
        data-ai-login-panel
        role="dialog"
        aria-modal="true"
        aria-labelledby="auth-dialog-title"
        class="relative grid w-full max-w-[420px] overflow-hidden rounded-2xl border border-black/10 bg-white shadow-2xl md:min-h-[540px] md:max-w-[960px] md:grid-cols-2"
      >
        <button
          type="button"
          class="absolute right-3 top-3 z-20 rounded-full p-1.5 text-gray-600 transition hover:bg-black/5 hover:text-gray-900"
          title="关闭"
          aria-label="关闭登录弹窗"
          @click="close"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24">
            <path d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>

        <!-- ── 左栏：窄屏整栏隐藏 ── -->
        <div
          class="relative hidden flex-col justify-between overflow-hidden bg-gradient-to-br from-slate-400 via-slate-500 to-slate-600 p-8 text-[var(--ai-panel-text)] md:flex"
        >
          <!-- 网格纹理 + 两个光斑（纯装饰，不参与交互） -->
          <div
            class="pointer-events-none absolute inset-0"
            style="background-image: linear-gradient(rgba(255,255,255,0.10) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.10) 1px, transparent 1px); background-size: 32px 32px"
            aria-hidden="true"
          />
          <div class="pointer-events-none absolute -left-16 top-1/4 h-72 w-72 rounded-full bg-white/30 blur-3xl" aria-hidden="true" />
          <div class="pointer-events-none absolute -right-10 bottom-10 h-64 w-64 rounded-full bg-indigo-200/40 blur-3xl" aria-hidden="true" />

          <!-- 上：品牌（项目自己的标志） -->
          <div class="relative z-10 flex items-center gap-3">
            <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 text-lg shadow-lg shadow-indigo-500/30">
              🦄
            </div>
            <span class="text-lg font-semibold">独角兽工作室</span>
          </div>

          <!-- 中：角色舞台（吃掉剩余高度，窄时角色自动缩小） -->
          <div class="relative z-10 flex min-h-0 flex-1 items-end justify-center py-6">
            <AiLoginCharacters
              :is-typing="isTyping"
              :show-password="showPassword"
              :password-length="form.password.length"
            />
          </div>

          <!-- 下：页脚 -->
          <p class="relative z-10 text-sm opacity-70">独角兽工作室 · 智能助手</p>
        </div>

        <!-- ── 右栏：表单（淡紫底 + 极光，浅色主题） ── -->
        <div
          class="auth-panel relative flex flex-col px-5 py-6 sm:px-8"
          @focusin="onFocusIn"
          @focusout="onFocusOut"
        >
          <!-- 极光背景：会走的渐变 + 一层斜向光帘 + 三团超大模糊色块各自漂移明灭，
               最后用一点白色柔光把表单托出来（纯装饰） -->
          <div class="auth-aurora" aria-hidden="true">
            <span class="aurora-sheen"></span>
            <span class="aurora-curtain"></span>
            <span class="aurora aurora-1"></span>
            <span class="aurora aurora-2"></span>
            <span class="aurora aurora-3"></span>
            <span class="aurora-veil"></span>
          </div>

          <div class="relative z-10 m-auto w-full max-w-[380px]">
            <!-- 窄屏 logo（左栏已隐藏） -->
            <div class="mb-4 flex items-center justify-center gap-3 md:hidden">
              <div class="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 text-base">🦄</div>
              <span class="text-lg font-semibold text-gray-900">独角兽工作室</span>
            </div>

            <div class="mb-5 text-center">
              <h1 id="auth-dialog-title" class="text-2xl font-semibold text-gray-900">
                {{ isRegister ? '创建账号' : '账号登录' }}
              </h1>
              <p class="mt-1.5 text-sm text-gray-700">
                {{ isRegister ? '验证手机号后创建账号' : '使用手机号和密码登录' }}
              </p>
            </div>

            <form class="space-y-3" @submit.prevent="submit">
              <AuthInput
                v-if="isRegister"
                v-model="form.nickname"
                label="昵称"
                placeholder="昵称（1-50 字）"
                autocomplete="nickname"
                :error="errors.nickname"
                :disabled="loading"
              >
                <template #icon>
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24">
                    <path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2" />
                    <circle cx="12" cy="7" r="4" />
                  </svg>
                </template>
              </AuthInput>

              <AuthInput
                ref="phoneInput"
                v-model="form.phone"
                label="手机号"
                type="tel"
                placeholder="11 位手机号"
                inputmode="numeric"
                autocomplete="username"
                :error="errors.phone"
                :disabled="loading"
              >
                <template #icon>
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24">
                    <rect x="6" y="2" width="12" height="20" rx="2" />
                    <path d="M11 18h2" />
                  </svg>
                </template>
              </AuthInput>

              <AuthInput
                v-model="form.password"
                v-model:visible="showPassword"
                label="密码"
                type="password"
                :placeholder="isRegister ? '密码（至少 8 位）' : '密码'"
                :autocomplete="isRegister ? 'new-password' : 'current-password'"
                :error="errors.password"
                :disabled="loading"
              >
                <template #icon>
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24">
                    <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                    <path d="M7 11V7a5 5 0 0110 0v4" />
                  </svg>
                </template>
              </AuthInput>

              <div v-if="isRegister" class="flex items-start gap-2">
                <div class="min-w-0 flex-1">
                  <AuthInput
                    v-model="form.phoneCode"
                    label="手机验证码"
                    placeholder="6 位验证码"
                    autocomplete="one-time-code"
                    inputmode="numeric"
                    maxlength="6"
                    :error="errors.phoneCode"
                    :disabled="loading"
                  >
                    <template #icon>
                      <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24">
                        <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" />
                        <path d="m9 12 2 2 4-4" />
                      </svg>
                    </template>
                  </AuthInput>
                </div>
                <button
                  type="button"
                  class="h-12 shrink-0 rounded-full border border-violet-200 bg-white/75 px-3 text-sm font-medium text-indigo-700 hover:border-violet-400 disabled:opacity-60"
                  :disabled="loading || sendingCode || secondsLeft > 0"
                  @click="requestCode"
                >
                  {{ sendingCode ? '获取中…' : secondsLeft > 0 ? secondsLeft + ' 秒后重试' : '获取验证码' }}
                </button>
              </div>

              <!-- 选项栏 -->
              <div class="flex items-center justify-between">
                <label class="flex cursor-pointer select-none items-center gap-2 text-sm text-gray-700">
                  <input v-model="remember" type="checkbox" class="h-4 w-4 cursor-pointer accent-indigo-500" />
                  记住账号
                </label>
                <button
                  v-if="!isRegister"
                  type="button"
                  class="text-sm text-indigo-600 transition-opacity hover:opacity-80"
                  @click="onForgotPassword"
                >
                  忘记密码？
                </button>
              </div>

              <!-- 失败原因 / 中性提示，就地展示 -->
              <p v-if="notice.text" role="status" class="text-xs" :class="notice.tone === 'error' ? 'text-red-600' : 'text-violet-700'">
                {{ notice.text }}
              </p>

              <div class="pt-1">
                <AuthButton
                  type="submit"
                  :label="isRegister ? '注册并继续' : '登录并继续'"
                  :loading-label="isRegister ? '注册中…' : '登录中…'"
                  :loading="loading"
                />
              </div>
            </form>

            <p class="mt-5 text-center text-sm text-gray-700">
              {{ isRegister ? '已有账号？' : '还没有账号？' }}
              <button
                type="button"
                :disabled="loading || sendingCode"
                class="font-medium text-indigo-600 transition-opacity hover:opacity-80"
                @click="switchMode(isRegister ? 'login' : 'register')"
              >
                {{ isRegister ? '去登录' : '立即注册' }}
              </button>
            </p>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useAiChatStore } from '@/stores/aiChat'
import { useUserStore } from '@/stores/user'
import { useAiChat } from '@/composables/useAiChat'
import { useApi } from '@/composables/useApi'
import AuthInput from './AuthInput.vue'
import AuthButton from './AuthButton.vue'
import AiLoginCharacters from './AiLoginCharacters.vue'

const store = useAiChatStore()
const userStore = useUserStore()
const { login, register, sendRegistrationCode } = useApi()
const { resendPending } = useAiChat()

const REMEMBER_KEY = 'unicorn.auth.rememberedPhone'
const PHONE_RE = /^1[3-9][0-9]{9}$/
const mode = ref('login')
const isRegister = computed(() => mode.value === 'register')
const loading = ref(false)
const sendingCode = ref(false)
const isTyping = ref(false)
const showPassword = ref(false)
const remember = ref(true)
const phoneInput = ref(null)

const form = reactive({ nickname: '', phone: '', password: '', phoneCode: '' })
const errors = reactive({ nickname: '', phone: '', password: '', phoneCode: '' })
const notice = reactive({ text: '', tone: 'error' })
const cooldown = reactive({ phone: '', until: 0 })
const now = ref(Date.now())
const secondsLeft = computed(() => form.phone.trim() === cooldown.phone
  ? Math.max(0, Math.ceil((cooldown.until - now.value) / 1000))
  : 0)
let cooldownTimer
let autoFocusing = false

function clearErrors() {
  errors.nickname = ''
  errors.phone = ''
  errors.password = ''
  errors.phoneCode = ''
  notice.text = ''
}

function resetFormSecrets() {
  form.password = ''
  form.phoneCode = ''
  showPassword.value = false
  isTyping.value = false
  clearErrors()
}

function switchMode(next) {
  mode.value = next
  resetFormSecrets()
}

function onFocusIn(event) {
  if (!autoFocusing && event.target?.tagName === 'INPUT') isTyping.value = true
}

function onFocusOut(event) {
  if (event.target?.tagName === 'INPUT') isTyping.value = false
}

function close() {
  store.closeLoginPrompt()
  resetFormSecrets()
}

function onForgotPassword() {
  notice.tone = 'info'
  notice.text = '暂未开放自助找回，请联系工作室管理员重置密码'
}

function validatePhone() {
  const phone = form.phone.trim()
  errors.phone = PHONE_RE.test(phone) ? '' : '请输入有效的 11 位手机号'
  return errors.phone ? '' : phone
}

async function requestCode() {
  if (loading.value || sendingCode.value || secondsLeft.value > 0) return
  const phone = validatePhone()
  if (!phone) return

  sendingCode.value = true
  notice.text = ''
  errors.phoneCode = ''
  const response = await sendRegistrationCode(phone)
  sendingCode.value = false

  if (response.error || !response.data?.retryAfterSeconds) {
    if (form.phone.trim() === phone) {
      notice.tone = 'error'
      notice.text = response.message || '获取验证码失败，请稍后重试'
    }
    return
  }

  now.value = Date.now()
  cooldown.phone = phone
  cooldown.until = now.value + response.data.retryAfterSeconds * 1000
  clearInterval(cooldownTimer)
  cooldownTimer = setInterval(() => {
    now.value = Date.now()
    if (now.value >= cooldown.until) clearInterval(cooldownTimer)
  }, 250)

  if (form.phone.trim() === phone) {
    form.phoneCode = ''
    notice.tone = 'info'
    notice.text = '请填写获取到的 6 位验证码，5 分钟内有效'
  }
}

async function submit() {
  if (loading.value || sendingCode.value) return
  clearErrors()

  const phone = validatePhone()
  if (!phone) return

  const nickname = form.nickname.trim()
  if (isRegister.value && (!nickname || nickname.length > 50)) {
    errors.nickname = '昵称 1-50 字'
    return
  }
  if (!form.password.trim()) {
    errors.password = '请输入密码'
    return
  }
  if (isRegister.value && form.password.length < 8) {
    errors.password = '密码至少 8 位'
    return
  }
  if (isRegister.value && new TextEncoder().encode(form.password).length > 72) {
    errors.password = '密码不能超过 72 字节'
    return
  }
  const code = form.phoneCode.trim()
  if (isRegister.value && !/^[0-9]{6}$/.test(code)) {
    errors.phoneCode = '请输入 6 位验证码'
    return
  }

  loading.value = true
  const response = isRegister.value
    ? await register(nickname, phone, form.password, code)
    : await login(phone, form.password)
  loading.value = false

  const token = response.data?.token
  if (!token) {
    notice.tone = 'error'
    notice.text = response.message || '登录响应异常，请稍后重试'
    return
  }

  try {
    if (remember.value) window.localStorage.setItem(REMEMBER_KEY, phone)
    else window.localStorage.removeItem(REMEMBER_KEY)
  } catch {
    // 存储不可用时仍可登录。
  }

  userStore.setSession(token, response.data.user)
  resetFormSecrets()
  await resendPending()
}

watch(() => form.phone.trim(), () => {
  form.phoneCode = ''
  errors.phone = ''
  errors.phoneCode = ''
  notice.text = ''
})

watch(() => store.showLoginPrompt, async (open) => {
  if (!open) return
  resetFormSecrets()
  await nextTick()
  if (!store.showLoginPrompt || window.matchMedia('(any-pointer: coarse)').matches) return
  autoFocusing = true
  phoneInput.value?.focus()
  autoFocusing = false
}, { immediate: true })

function onKeydown(event) {
  if (event.key === 'Escape' && store.showLoginPrompt) close()
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  try {
    window.localStorage.removeItem('unicorn.auth.rememberedEmail')
    const saved = window.localStorage.getItem(REMEMBER_KEY)
    if (PHONE_RE.test(saved || '')) form.phone = saved
  } catch {
    // 存储不可用时继续使用空表单。
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  clearInterval(cooldownTimer)
})
</script>

<style scoped>
.auth-viewport { align-items: start; align-items: safe center; scrollbar-width: none; }
.auth-viewport::-webkit-scrollbar { display: none; }

/*
  右栏背景 = 淡紫底 + 紫色极光。

  极光是几层叠出来的：
    1) sheen   —— 整块面板铺一层"会走"的渐变。直接照搬主页大标题的 .gradient-flow：
                  同一组紫色、同样把图拉成 200%、同样让 background-position 从 0% 走到 200%，
                  3s 一圈。一个周期正好走完一整张图而首尾同色，所以循环点上不会跳。
    2) curtain —— 一排很密的斜向光带，重模糊之后就是极光那种"帘幕"。用 alternate 来回摆，
                  摆到头速度归零，所以掉头也不顿。
    3~5) blob  —— 三团超大的模糊色块，各自漂移 + 旋转 + 明灭，负责把颜色搅乱、堆出厚度。
    6) veil    —— 一点白色柔光，把表单托出来，顺便兜住文字对比度。

  动画只用 transform / opacity / background-position，全部走合成层。
*/
.auth-panel {
  background-color: #f8f6ff;
}

.auth-aurora {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

/* 1) 会走的渐变 —— 与主页 HomePage.vue 的 .gradient-flow 同一套写法 */
.aurora-sheen {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(90deg, #818cf8, #a78bfa, #c084fc, #818cf8);
  background-size: 200% 100%;
  opacity: .26;
  animation: gradient-flow 3s linear infinite;
}

/* 2) 极光帘幕 */
.aurora-curtain {
  position: absolute;
  inset: -30%;
  background-image: repeating-linear-gradient(
    96deg,
    rgba(129, 140, 248, 0) 0px,
    rgba(129, 140, 248, 0) 30px,
    rgba(167, 139, 250, .85) 62px,
    rgba(232, 121, 249, .55) 96px,
    rgba(129, 140, 248, 0) 148px
  );
  filter: blur(34px);
  opacity: .42;
  animation: aurora-curtain 17s ease-in-out infinite alternate;
}

/* 3~5) 三团模糊色块 */
.aurora {
  position: absolute;
  display: block;
  border-radius: 50%;
  filter: blur(54px);
  will-change: transform, opacity;
}

.aurora-1 {
  width: 128%;
  height: 76%;
  left: -22%;
  top: -16%;
  background: conic-gradient(from 200deg at 50% 50%, #818cf8, #a78bfa, #c084fc, #e879f9, #818cf8);
  animation: aurora-drift-1 24s ease-in-out infinite -4s;
}

.aurora-2 {
  width: 122%;
  height: 72%;
  right: -26%;
  bottom: -18%;
  background: conic-gradient(from 20deg at 50% 50%, #c084fc, #e879f9, #a78bfa, #818cf8, #c084fc);
  animation: aurora-drift-2 33s ease-in-out infinite -11s;
}

.aurora-3 {
  width: 62%;
  height: 62%;
  right: 4%;
  top: 6%;
  /* 一点青做冷调，不然全是紫会显脏 */
  background: radial-gradient(circle at 50% 50%, #22d3ee 0%, rgba(34, 211, 238, 0) 68%);
  animation: aurora-drift-3 29s ease-in-out infinite -16s;
}

/* 6) 白色柔光：中间亮、边缘化开，表单落在这块亮区上 */
.aurora-veil {
  position: absolute;
  inset: 0;
  background: radial-gradient(70% 62% at 50% 46%, rgba(255, 255, 255, .52) 0%, rgba(255, 255, 255, .24) 48%, rgba(255, 255, 255, 0) 78%);
}

@keyframes gradient-flow {
  0%   { background-position: 0% 50%; }
  100% { background-position: 200% 50%; }
}

@keyframes aurora-curtain {
  from { transform: translate3d(-6%, 0, 0); }
  to   { transform: translate3d(6%, 2%, 0); }
}

@keyframes aurora-drift-1 {
  0%, 100% { transform: translate3d(0, 0, 0) rotate(0deg) scale(1); opacity: .5; }
  33%      { transform: translate3d(9%, 7%, 0) rotate(14deg) scale(1.16); opacity: .64; }
  66%      { transform: translate3d(-6%, 13%, 0) rotate(-9deg) scale(.92); opacity: .42; }
}

@keyframes aurora-drift-2 {
  0%, 100% { transform: translate3d(0, 0, 0) rotate(0deg) scale(1); opacity: .44; }
  30%      { transform: translate3d(-11%, -8%, 0) rotate(-12deg) scale(1.12); opacity: .58; }
  65%      { transform: translate3d(-4%, -16%, 0) rotate(8deg) scale(.94); opacity: .36; }
}

@keyframes aurora-drift-3 {
  0%, 100% { transform: translate3d(0, 0, 0) rotate(0deg) scale(1); opacity: .3; }
  45%      { transform: translate3d(-9%, 12%, 0) rotate(-14deg) scale(1.22); opacity: .44; }
  78%      { transform: translate3d(6%, -7%, 0) rotate(10deg) scale(.9); opacity: .24; }
}

@media (prefers-reduced-motion: reduce) {
  .aurora-sheen,
  .aurora-curtain,
  .aurora {
    animation: none;
  }
}
</style>
