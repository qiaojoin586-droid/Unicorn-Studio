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
    <div v-if="store.showLoginPrompt" class="fixed inset-0 z-[1000] flex items-center justify-center px-4 py-6">
      <!-- 遮罩不禁用，只是完全透明：只负责"点空白处关闭"，不给后面的主页压任何黑、也不加模糊。
           弹窗本身靠白底 + 阴影 + 描边跟主页区分开。 -->
      <div class="absolute inset-0" @click="close"></div>

      <div
        data-ai-login-panel
        class="relative grid max-h-[92vh] w-full max-w-[420px] overflow-hidden rounded-2xl border border-black/10 bg-white shadow-2xl md:h-[620px] md:max-w-[960px] md:grid-cols-2"
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
          class="auth-panel relative flex flex-col overflow-y-auto overflow-x-hidden p-8"
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
            <div class="mb-6 flex items-center justify-center gap-3 md:hidden">
              <div class="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 text-base">🦄</div>
              <span class="text-lg font-semibold text-gray-900">独角兽工作室</span>
            </div>

            <div class="mb-8 text-center">
              <h1 class="text-2xl font-semibold text-gray-900">
                {{ isRegister ? '创建账号' : '账号登录' }}
              </h1>
              <p class="mt-1.5 text-sm text-gray-700">
                {{ isRegister ? '注册成功会自动登录，并继续回答你刚才的问题' : '登录后会继续回答你刚才的问题' }}
              </p>
            </div>

            <form class="space-y-5" @submit.prevent="submit">
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
                ref="emailInput"
                v-model="form.email"
                label="邮箱"
                type="email"
                placeholder="邮箱"
                autocomplete="email"
                :error="errors.email"
                :disabled="loading"
              >
                <template #icon>
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24">
                    <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
                    <polyline points="22,6 12,13 2,6" />
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

              <!-- 手机验证码：先留个空位装上，后端还没有短信接口，所以不参与校验、也不挡提交 -->
              <AuthInput
                v-if="isRegister"
                v-model="form.phoneCode"
                label="手机验证码"
                placeholder="手机验证码"
                autocomplete="one-time-code"
                inputmode="numeric"
                maxlength="6"
                :disabled="loading"
              >
                <template #icon>
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24">
                    <path d="M22 16.92v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6A19.79 19.79 0 012.12 4.18 2 2 0 014.11 2h3a2 2 0 012 1.72c.13.96.36 1.9.7 2.81a2 2 0 01-.45 2.11L8.09 9.91a16 16 0 006 6l1.27-1.27a2 2 0 012.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0122 16.92z" />
                  </svg>
                </template>
              </AuthInput>

              <!-- 验证码：输入框 + 画布同高并排 -->
              <div class="flex items-start gap-3">
                <div class="min-w-0 flex-1">
                  <AuthInput
                    v-model="form.captcha"
                    label="验证码"
                    placeholder="验证码"
                    autocomplete="off"
                    maxlength="4"
                    :error="errors.captcha"
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
                <AiLoginCaptcha ref="captchaRef" @generated="onCaptchaGenerated" />
              </div>

              <!-- 选项栏 -->
              <div class="flex items-center justify-between">
                <label class="flex cursor-pointer select-none items-center gap-2 text-sm text-gray-700">
                  <input v-model="remember" type="checkbox" class="h-4 w-4 cursor-pointer accent-indigo-500" />
                  记住账号
                </label>
                <button
                  type="button"
                  class="text-sm text-indigo-600 transition-opacity hover:opacity-80"
                  @click="onForgotPassword"
                >
                  忘记密码？
                </button>
              </div>

              <!-- 失败原因 / 中性提示，就地展示 -->
              <p v-if="notice.text" class="text-xs" :class="notice.tone === 'error' ? 'text-red-600' : 'text-violet-700'">
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

            <p class="mt-8 text-center text-sm text-gray-700">
              {{ isRegister ? '已有账号？' : '还没有账号？' }}
              <button
                type="button"
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
import AiLoginCaptcha from './AiLoginCaptcha.vue'
import AiLoginCharacters from './AiLoginCharacters.vue'

const store = useAiChatStore()
const userStore = useUserStore()
const { login, register } = useApi()
// 登录成功后要靠它把刚才被拦下的那条提问自动重发
const { resendPending } = useAiChat()

/** 「记住账号」只存邮箱，明文密码一律不落盘 */
const REMEMBER_KEY = 'unicorn.auth.rememberedEmail'

const mode = ref('login')
const isRegister = computed(() => mode.value === 'register')
const loading = ref(false)
/** 任一个输入框聚焦中，驱动角色的「对视」动作 */
const isTyping = ref(false)
const showPassword = ref(false)
const remember = ref(true)

const form = reactive({ nickname: '', email: '', password: '', phoneCode: '', captcha: '' })
const errors = reactive({ nickname: '', email: '', password: '', captcha: '' })
const notice = reactive({ text: '', tone: 'error' })

const emailInput = ref(null)
const captchaRef = ref(null)
/** 首帧稳定后再聚焦的延时句柄 */
let focusTimer = 0
/**
 * 「这次聚焦是程序自动做的」标记。
 * 弹窗打开时会自动聚焦邮箱框，那不是用户在输入；但 focusin 照样会触发，
 * isTyping 一变 true 角色就切到「打字 / 对视」姿态 —— 小人物会突然大幅前倾 + 右移，
 * 看着就像"鼠标一进来整个人飘走了"，而且会一直保持到输入框失焦为止（=用户点一下才恢复）。
 * focus() 派发 focus 事件是同步的，所以围着它上下夹一下就够了。
 */
let autoFocusing = false
/** 当前验证码答案，由子组件通过 generated 事件回传（纯前端本地校验） */
let captchaCode = ''

/** 与后端 PublicAuthService.register 的正则保持一致，前端先挡一道省掉无效请求 */
const EMAIL_RE = /^[\w.+-]+@[\w-]+(\.[\w-]+)+$/

function clearErrors() {
  errors.nickname = ''
  errors.email = ''
  errors.password = ''
  errors.captcha = ''
  notice.text = ''
}

function onCaptchaGenerated(code) {
  captchaCode = code
}

function switchMode(next) {
  mode.value = next
  form.password = ''
  form.phoneCode = ''
  form.captcha = ''
  showPassword.value = false
  clearErrors()
  captchaRef.value?.refresh()
}

function onFocusIn(e) {
  // 程序自动聚焦的那一次不算「用户在打字」
  if (autoFocusing) return
  // 只让输入框驱动 isTyping：按钮 / 链接 / 验证码画布获得焦点时不该触发「打字」动作
  if (e.target?.tagName === 'INPUT') isTyping.value = true
}

function onFocusOut(e) {
  if (e.target?.tagName === 'INPUT') isTyping.value = false
}

/**
 * 关闭弹窗（点遮罩 / 叉 / ESC）。
 * 密码与验证码输入不留在内存里；邮箱保留，方便用户改一处再试。
 */
function close() {
  store.closeLoginPrompt()
  clearTimeout(focusTimer)
  form.password = ''
  form.phoneCode = ''
  form.captcha = ''
  showPassword.value = false
  // 输入框随弹窗一起卸载时不一定补发 focusout，这里显式复位，避免下次打开角色还处于「打字」姿态
  isTyping.value = false
  clearErrors()
}

function onForgotPassword() {
  // 后端没有找回密码接口（前台认证只有 register / login 两个），所以这里只能如实说明
  notice.tone = 'info'
  notice.text = '暂未开放自助找回，请联系工作室管理员重置密码'
}

async function submit() {
  if (loading.value) return
  clearErrors()

  const nickname = form.nickname.trim()
  const email = form.email.trim()

  // 本地校验口径与后端一致：注册要昵称 + 8 位密码；登录只要求非空
  if (isRegister.value) {
    if (!nickname || nickname.length > 50) {
      errors.nickname = '昵称 1-50 字'
      return
    }
    if (form.password.length < 8) {
      errors.password = '密码至少 8 位'
      return
    }
  } else if (!form.password) {
    errors.password = '请输入密码'
    return
  }
  if (!EMAIL_RE.test(email)) {
    errors.email = '邮箱格式不正确'
    return
  }

  // 验证码不区分大小写；错了就地换一张并清空输入
  if (!form.captcha || form.captcha.toLowerCase() !== captchaCode.toLowerCase()) {
    errors.captcha = '验证码错误'
    form.captcha = ''
    captchaRef.value?.refresh()
    return
  }

  loading.value = true
  const res = isRegister.value
    ? await register(nickname, email, form.password)
    : await login(email, form.password)

  if (res.error) {
    loading.value = false
    notice.tone = 'error'
    notice.text = res.message
    form.captcha = ''
    captchaRef.value?.refresh()
    return
  }

  // 后端注册也直接签发 token（isNew=true），所以注册成功等价于登录成功
  const token = res.data?.token
  if (!token) {
    loading.value = false
    notice.tone = 'error'
    notice.text = '登录响应异常，请稍后重试'
    return
  }

  try {
    if (remember.value) window.localStorage.setItem(REMEMBER_KEY, email)
    else window.localStorage.removeItem(REMEMBER_KEY)
  } catch {
    /* 隐私模式禁用存储：忽略，只是记不住而已 */
  }

  userStore.setSession(token, res.data.user)
  loading.value = false
  form.password = ''
  form.phoneCode = ''
  form.captcha = ''
  // 把被拦下的那条提问接着问完；没有待重发消息时它只负责关掉弹窗
  await resendPending()
}

// 打开时清掉密码与旧报错（邮箱留着），换一张验证码，并把焦点交给第一个可见输入框
watch(
  () => store.showLoginPrompt,
  (open) => {
    if (!open) return
    form.password = ''
    form.phoneCode = ''
    form.captcha = ''
    showPassword.value = false
    clearErrors()
    // 等 DOM 真的渲染出来再动 ref：v-if 刚为 true 的这一刻子组件还没挂载
    nextTick(() => {
      captchaRef.value?.refresh()
      // 延后聚焦：等弹窗渲染稳定了再把光标放进去（用户一进来就能直接敲）。
      // 这一下是程序行为，不算用户在输入，所以用 autoFocusing 把 focusin 挡掉 ——
      // 否则 isTyping 会在弹窗一打开就变 true，角色莫名其妙进入「打字」姿态。
      clearTimeout(focusTimer)
      focusTimer = setTimeout(() => {
        autoFocusing = true
        emailInput.value?.focus()
        autoFocusing = false
      }, 240)
    })
  },
  // 组件随对话面板一起被 v-if 卸载过：重新挂载时弹窗可能本来就是开着的，也得补一次
  { immediate: true },
)

function onKeydown(e) {
  if (e.key === 'Escape' && store.showLoginPrompt) close()
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  // 恢复上次记住的邮箱（只恢复邮箱，不恢复密码）
  try {
    const saved = window.localStorage.getItem(REMEMBER_KEY)
    if (saved) {
      form.email = saved
      remember.value = true
    }
  } catch {
    /* 存储不可用：忽略 */
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  clearTimeout(focusTimer)
})
</script>

<style scoped>
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
