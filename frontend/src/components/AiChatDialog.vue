<template>
  <!--
    定位改由 useResizablePanel 的共享布局状态给出（`panelStyle` 的 left/top）。
    图标与对话框共用同一份坐标 ⇒ 拖任意一方，另一方同步位移。
    原先的 `max-w-[calc(100vw-4rem)]` 已移除：宽度上限现在由 composable 的
    `bounds.maxW` 统一按视口夹紧；两处同时约束会让「实际渲染宽度」与
    「参与定位/拉伸计算的宽度」不一致，导致拖拽边缘时错位。
  -->
    <transition
      enter-active-class="transition duration-300 ease-out"
      enter-from-class="opacity-0 translate-y-4 scale-95"
      enter-to-class="opacity-100 translate-y-0 scale-100"
      leave-active-class="transition duration-200 ease-in"
      leave-from-class="opacity-100 translate-y-0 scale-100"
      leave-to-class="opacity-0 translate-y-4 scale-95"
    >
      <div v-if="store.isOpen" data-ai-panel class="ai-panel fixed" :style="[panelStyle, sizeStyle]">
      <div
        id="ai-dialog"
        ref="dialogPanel"
        role="dialog"
        :aria-modal="isCompact ? 'true' : undefined"
        aria-label="AI 助手对话"
        class="ai-dialog relative border border-[var(--ai-field-border)] rounded-xl shadow-2xl overflow-hidden flex flex-col w-full h-full"
      >
        <!-- Header：同时是「整体拖动」的抓手（光标变 move）。
             `touch-none` 让触屏拖动不被页面滚动抢走；`select-none` 防止拖出选区。
             ⚠️ 拖动不调用 preventDefault —— 那会抑制 click 等兼容鼠标事件，
                而图标开合对话正是靠 click（见 useResizablePanel.startPairDrag 注释）。 -->
        <div
          class="flex items-center justify-between px-5 py-4 border-b border-[var(--ai-panel-line)] flex-shrink-0 cursor-move select-none touch-none"
          @pointerdown="startPairDrag"
          title="按住拖动整个对话框"
        >
          <div class="ai-panel-heading flex items-center gap-3">
            <BrandMark class="ai-symbol" aria-hidden="true" />
            <div>
              <h3 class="text-[var(--ai-panel-text)] font-semibold text-sm">AI 助手</h3>
              <p class="text-[var(--ai-panel-muted)] text-xs">独角兽工作室智能助手</p>
            </div>
          </div>
          <!-- 按钮区不参与拖动，否则点「清空/关闭」时轻微抖动会把面板挪走 -->
          <div class="flex items-center gap-2" @pointerdown.stop>
            <button type="button" class="dialog-control" @click="resetToDefault" aria-label="重置对话框位置" title="重置位置"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 10a8 8 0 1 1 1.8 7M4 4v6h6" stroke="currentColor" stroke-width="1.5" /></svg></button>
            <button
              @click="store.drawerOpen ? store.closeDrawer() : openHistory()"
              class="dialog-control"
              title="历史对话"
              aria-label="历史对话"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </button>
            <button
              v-if="store.hasMessages"
              @click="handleClear"
              class="dialog-control"
              title="清空对话"
              aria-label="清空对话"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
              </svg>
            </button>
            <button
              @click="store.closeChat()"
              class="dialog-control"
              title="关闭"
              aria-label="关闭对话"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        <!-- 宽面板的历史栏与对话并排；对话列独立滚动，输入区固定在底部。 -->
        <div class="ai-dialog-body relative flex-1 min-h-0 flex">
          <AiHistoryDrawer v-if="store.drawerOpen" @close="store.closeDrawer()" />

        <div class="ai-conversation relative flex flex-1 min-w-0 min-h-0 flex-col overflow-hidden">
          <!-- Messages area -->
          <div
            ref="messagesContainer"
            class="ai-messages flex-1 min-h-0 overflow-y-auto px-5 py-4 space-y-4"
            @scroll="onMessagesScroll"
          >
            <!-- Welcome message -->
            <div v-if="!store.hasMessages && !store.streamingText" class="text-center py-8">
              <div class="welcome-mark" aria-hidden="true">AI<span> / </span>UNICORN</div>
              <p class="text-[var(--ai-panel-muted)] text-sm leading-relaxed">
                你好！我是独角兽工作室的 AI 助手。<br />
                有什么可以帮你的吗？
              </p>
            </div>

            <!-- Message bubbles -->
            <ChatMessage
              v-for="msg in store.messages"
              :key="msg.id"
              :role="msg.role"
              :content="msg.content"
            />

            <!-- Streaming message（同样走 Markdown 渲染，边流边排版） -->
            <div v-if="store.streamingText" class="flex gap-3">
              <AiMessageAvatar class="mt-0.5" />
              <!-- 气泡样式必须与 ChatMessage 的 assistant 分支保持一致（含 text-gray-200），
                   否则流式阶段文字会继承默认黑色，在深色气泡上变成「一片黑框」。 -->
              <div class="bg-[var(--ai-field-bg)] border border-[var(--ai-panel-line)] text-gray-200 rounded-2xl rounded-tl-sm px-4 py-3 max-w-[85%] min-w-0">
                <div ref="streamingBody" class="md-body" v-html="streamingHtml" />
                <span class="stream-cursor inline-block w-1.5 h-4 animate-pulse ml-0.5 align-middle" />
              </div>
            </div>

            <!-- Thinking indicator -->
            <div v-if="store.isLoading && !store.streamingText" class="flex gap-3">
              <AiMessageAvatar />
              <div class="bg-[var(--ai-field-bg)] border border-[var(--ai-panel-line)] rounded-2xl rounded-tl-sm px-4 py-3">
                <div class="flex gap-1">
                  <span class="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style="animation-delay: 0ms" />
                  <span class="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style="animation-delay: 150ms" />
                  <span class="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style="animation-delay: 300ms" />
                </div>
              </div>
            </div>
          </div>


          <!-- 免责提示放输出区末尾（不放输入框下面）——
               ⚠️ 放输入框下面的话，输入框就不是"最下面那条"了，视觉上会显得被压着。 -->
          <p class="text-[var(--ai-panel-muted)] text-[11px] text-center pt-1 pb-2 flex-shrink-0">
            AI 生成内容仅供参考
          </p>

          <button v-if="!stickToBottom && (store.hasMessages || store.streamingText)" type="button" class="latest-message" @click="scrollToLatest">回到最新消息 ↓</button>

          <!-- Input area -->
          <p class="sr-only" role="status">{{ store.isLoading ? '正在生成回复' : store.hasMessages ? '回复已完成' : '' }}</p>
          <!-- ⚠️ 生成中时，右侧那个提交按钮直接变成"停止"（位置不动、形态变）。
               比在别处单放一个停止按钮更省位置，也符合 ChatGPT 那种"发送键变停止键"的直觉。 -->
          <ChatInput
            @send="handleSend"
            @stop="stopStream"
            :loading="store.isLoading"
            :disabled="store.isLoading || store.clearing"
          />

        </div>

        </div>

        <!-- 拉伸把手（八向）：四条边 + 四个角。
             边把手平时透明、hover 才显出一道浅色条；角把手常驻淡色斜纹（沿用改造前
             左下角那枚视觉），hover 高亮。角把手 DOM 在后 ⇒ 叠在边把手之上。
             双击角把手在「默认尺寸 / 放大尺寸」间切换。
             注意：视觉层都是 `pointer-events-none`，hover 态只能靠父级 group 触发。 -->
        <div
          v-for="handle in RESIZE_HANDLES"
          :key="handle.dir"
          class="resize-handle group absolute z-10 touch-none"
          :class="handle.cls"
          :data-resize="handle.dir"
          @pointerdown="startResize(handle.dir, $event)"
          @dblclick="onHandleDblClick(handle)"
          aria-hidden="true"
          :title="handle.corner ? '拖拽调整大小（双击切换默认/放大）' : null"
        >
          <span
            v-if="handle.corner"
            class="pointer-events-none absolute inset-0 opacity-50 transition group-hover:opacity-100"
          >
            <!-- 斜纹朝向固定为「贴合左下角」，其余角用 transform 镜像，
                 省去手写四套 path。用内联 transform 而非 Tailwind 类，
                 避免动态类名被提取器漏掉。 -->
            <svg
              class="w-full h-full text-[var(--ai-panel-muted)] transition group-hover:text-indigo-400"
              :style="handle.flip ? { transform: handle.flip } : null"
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linecap="round"
            >
              <path d="M2 12L12 2" />
              <path d="M2 17L17 2" />
            </svg>
          </span>
          <span
            v-else
            class="pointer-events-none absolute inset-0 transition group-hover:bg-indigo-400/40"
          />
        </div>
      </div>
      </div>
    </transition>
</template>

<script setup>
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useAiChatStore } from '@/stores/aiChat'
import { useAiChat } from '@/composables/useAiChat'
import { renderMarkdown, hardenLinks } from '@/composables/useMarkdown'
import { useResizablePanel } from '@/composables/useResizablePanel'
import ChatMessage from './ChatMessage.vue'
import AiMessageAvatar from './AiMessageAvatar.vue'
import ChatInput from './ChatInput.vue'
  import BrandMark from './BrandMark.vue'
import AiHistoryDrawer from './AiHistoryDrawer.vue'

const store = useAiChatStore()
const { streamChat, stopStream, openHistory, loadAssistantAppearance } = useAiChat()

const messagesContainer = ref(null)
const streamingBody = ref(null)
const dialogPanel = ref(null)
const stickToBottom = ref(true)
const isCompact = ref(false)
let returnFocus = null

function syncCompact() { isCompact.value = window.innerWidth < 640 }
function focusDialog() {
  const panel = dialogPanel.value
  const field = panel?.querySelector('input:not(:disabled)')
  const fallback = panel?.querySelector('[data-stop-generation]') || panel?.querySelector('button:not(:disabled), a[href]')
  ;(field || fallback || panel)?.focus()
}
function scrollToLatest() {
  stickToBottom.value = true
  const el = messagesContainer.value
  if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'auto' })
}

// 切换会话 / 重新打开面板时把消息区滚到底。
// ⚠️ stickToBottom 表示「用户此刻是否停在底部」，上翻看旧消息后它是 false；
//   而上面那个滚动 watch 的条件正是 stickToBottom ⇒ 不重置的话，
//   加载完历史也不会滚，表现为「重新打开对话停在最上面的那条」。
// ⚠️ 面板是 v-if ⇒ 打开时整棵子树重建，必须等 DOM 更新完再滚（nextTick）。
watch(
  () => [store.sessionId, store.isOpen],
  async () => {
    await nextTick()
    scrollToLatest()
  },
)

// 布局（位置 + 尺寸）由 useResizablePanel 统一管理，并与对话图标共享**同一份单例状态**：
//   · panelStyle —— 对话框定位（left/top）。拖动图标或拖动本面板的表头，两边一起位移；
//   · sizeStyle  —— 对话框尺寸（width/height），由八向把手改变。
// 位置与尺寸都持久化到 localStorage，刷新后保持。
const {
  style: sizeStyle,
  panelStyle,
  startPairDrag,
  startResize,
  stopInteractions,
  toggleDefaultSize,
  resetToDefault,
  attach,
} = useResizablePanel()

// 八向拉伸把手的静态清单：四条边（细条命中区，平时透明）+ 四个角（常驻斜纹抓手）。
// 类名一律写成**字面量**，Tailwind 提取器才扫得到（拼字符串会生成不出 CSS）。
// flip 把同一份 SVG path 镜像到各个角，省掉手写四套 path。
const RESIZE_HANDLES = [
  // —— 四条边 ——
  { dir: 'n', cls: 'top-0 left-5 right-5 h-1.5 cursor-ns-resize' },
  { dir: 's', cls: 'bottom-0 left-5 right-5 h-1.5 cursor-ns-resize' },
  { dir: 'w', cls: 'left-0 top-5 bottom-5 w-1.5 cursor-ew-resize' },
  { dir: 'e', cls: 'right-0 top-5 bottom-5 w-1.5 cursor-ew-resize' },
  // —— 四个角（基样贴合左下角，其余角镜像）——
  { dir: 'sw', cls: 'bottom-0 left-0 w-5 h-5 cursor-nesw-resize', corner: true, flip: '' },
  { dir: 'se', cls: 'bottom-0 right-0 w-5 h-5 cursor-nwse-resize', corner: true, flip: 'scaleX(-1)' },
  { dir: 'nw', cls: 'top-0 left-0 w-5 h-5 cursor-nwse-resize', corner: true, flip: 'scaleY(-1)' },
  { dir: 'ne', cls: 'top-0 right-0 w-5 h-5 cursor-nesw-resize', corner: true, flip: 'scale(-1, -1)' },
]

// 流式文本同样走 Markdown 渲染，避免「流式阶段是纯文本、结束后才变格式」的跳变。
//
// 首片立即显示；连续分片至少间隔100ms才解析累计全文，末次更新不会因后续分片而推迟。
// 定时器等待剩余间隔，再在 rAF 中更新；仅合并到「下一帧」无法保证100ms的解析间隔。
// 结束/停止时完整正文由 ChatMessage 显示；关闭面板时取消解析，重新打开再读取最新全文。
const streamingHtml = ref('')
const THROTTLE_MS = 100
let streamRafId = 0
let streamTimer = 0
let lastRenderAt = -Infinity

function cancelStreamingRender() {
  clearTimeout(streamTimer)
  cancelAnimationFrame(streamRafId)
  streamTimer = 0
  streamRafId = 0
}

function flushStreamingHtml() {
  cancelStreamingRender()
  if (!store.isOpen) return
  lastRenderAt = performance.now()
  streamingHtml.value = renderMarkdown(store.streamingText)
}

watch(
  () => [store.streamingText, store.isOpen],
  ([text, open], previous) => {
    if (!text || !open) {
      cancelStreamingRender()
      streamingHtml.value = ''
      lastRenderAt = -Infinity
      return
    }
    const elapsed = performance.now() - lastRenderAt
    if (!previous?.[0] || !previous?.[1] || elapsed >= THROTTLE_MS) {
      flushStreamingHtml()
      return
    }
    if (streamTimer || streamRafId) return
    streamTimer = setTimeout(() => {
      streamTimer = 0
      streamRafId = requestAnimationFrame(flushStreamingHtml)
    }, THROTTLE_MS - elapsed)
  },
  { immediate: true },
)

function handleSend(message) {
  streamChat(message)
}

/**
 * 「清空对话」：软删服务端的历史（§7.3），并把本地状态清干净。
 * ⚠️ <b>为什么不能只调 store.clearMessages()</b>：那只是清掉页面上的文字，
 *    服务端的历史一条没动 ⇒ 下次打开这个会话，后端照常把老记录返回来
 *    （用户看到的现象就是"清空了又回来了"）。
 * ⚠️ <b>为什么清完要丢掉 sessionId</b>：软删只标记消息、会话行还在（§7.3），
 *    若继续用旧 sessionId，下一条消息会写回那个已被清空的会话 ⇒ 它又"复活"了。
 *    确认框调用 clearSession，成功后重置当前会话并保留其余历史。
 */
function handleClear() {
  if (!store.sessionId) {
    // 还没发过消息 ⇒ 没有会话可清
    store.clearMessages()
    return
  }
  store.openConfirm(store.sessions.find((session) => session.id === store.sessionId)
    || { id: store.sessionId, title: '当前对话' })
}

/** 双击角把手 → 在默认/放大尺寸间切换；边把手不参与（避免误触） */
function onHandleDblClick(handle) {
  if (handle?.corner) toggleDefaultSize()
}

// 滚动跟随（优化方案 F-12）：仅当用户停留在底部附近时自动滚，上翻阅读不被拽回
function onMessagesScroll() {
  const el = messagesContainer.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

watch(
  () => [store.messages.length, streamingHtml.value],
  async () => {
    await nextTick()
    const el = messagesContainer.value
    // 跟随已渲染的正文更新，避免每个原始分片都读取滚动尺寸；上翻阅读时保持位置。
    if (el && stickToBottom.value) el.scrollTo({ top: el.scrollHeight, behavior: 'auto' })
  },
)

// v-html 注入后补外链安全属性（流式气泡同样需要）
watch(streamingHtml, () => nextTick(() => hardenLinks(streamingBody.value)))

// ESC 关闭 + 打开时焦点移入输入框（优化方案 F-16）
function onKeydown(e) {
  if (e.key === 'Escape' && store.isOpen) store.closeChat()
  if (e.key === 'Tab' && isCompact.value && store.isOpen) {
    const items = [...(dialogPanel.value?.querySelectorAll('button:not(:disabled), input:not(:disabled), a[href], [tabindex="0"]') || [])].filter(el => el.getClientRects().length)
    const first = items[0]
    const last = items[items.length - 1]
    if (!dialogPanel.value?.contains(document.activeElement)) { e.preventDefault(); first?.focus() }
    else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus() }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus() }
  }
}

function syncBackground() {
  const modal = store.isOpen && isCompact.value
  for (const el of document.querySelectorAll('#main-content, .site-header, .site-footer')) el.inert = modal
  const launcher = document.querySelector('[data-ai-icon]')
  if (launcher) launcher.inert = modal
  document.body.classList.toggle('ai-modal-open', modal)
}
watch(isCompact, async compact => {
  syncBackground()
  if (compact && store.isOpen) { await nextTick(); focusDialog() }
})
watch(() => store.isLoading, async () => {
  await nextTick()
  if (store.isOpen && isCompact.value && !dialogPanel.value?.contains(document.activeElement)) focusDialog()
})

watch(
  () => store.isOpen,
  (open) => {
    if (open) {
      void loadAssistantAppearance()
      returnFocus = document.querySelector('[data-ai-trigger]') || document.activeElement
      syncCompact()
      syncBackground()
      window.addEventListener('keydown', onKeydown)
      nextTick(() => {
        focusDialog()
      })
    } else {
      stopInteractions()
      window.removeEventListener('keydown', onKeydown)
      syncBackground()
      if (returnFocus instanceof HTMLElement && returnFocus.isConnected) returnFocus.focus({ preventScroll: true })
    }
  },
)

onMounted(() => {
  if (store.isOpen) void loadAssistantAppearance()
  attach()
  syncCompact()
  window.addEventListener('resize', syncCompact, { passive: true })
  hardenLinks(streamingBody.value)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', syncCompact)
  for (const el of document.querySelectorAll('#main-content, .site-header, .site-footer, [data-ai-icon]')) el.inert = false
  document.body.classList.remove('ai-modal-open')
  cancelStreamingRender()
})
</script>
<style scoped>
/* ⚠️ 面板的底色与文字色用 --ai-* 固定值，不用 --color-*
   —— 面板是深色面，而站点主题切换会翻转 --color-surface-dark / --color-paper 的明暗
   ⇒ 若跟着变，浅色主题下就成了"浅底浅字"。 */
.ai-panel { z-index: var(--z-dialog); color: var(--ai-panel-text); }
.ai-dialog { background: var(--ai-panel); }
/* 头像：页头同一个独角兽标志 */
.ai-symbol { width: 32px; height: 32px; flex-shrink: 0; color: var(--ai-panel-text); }
.ai-symbol :deep(.brand-mark) { width: 32px; height: 32px; }
.ai-symbol-sm { width: 28px; height: 28px; }
.ai-symbol-sm :deep(.brand-mark) { width: 28px; height: 28px; }
.ai-panel-heading { min-width: 0; }
.ai-panel-heading > div:last-child { min-width: 0; }
.ai-panel-heading p { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dialog-control { display: grid; place-items: center; width: 32px; height: 44px; flex-shrink: 0; color: var(--ai-panel-muted); border-radius: 4px; transition: color var(--duration-fast), background var(--duration-fast); }
.dialog-control:hover { color: white; background: #ffffff0c; }
.welcome-mark { margin: 12px 0 24px; font: 15px var(--font-mono); letter-spacing: -.04em; color: var(--ai-panel-text); }
.welcome-mark span { color: var(--color-accent-on-dark); }
.stream-cursor { background: var(--color-accent-on-dark); }
.latest-message { position: absolute; bottom: 96px; left: 50%; transform: translateX(-50%); white-space: nowrap; padding: 8px 14px; font-size: 12px; color: var(--ai-panel-text); background: var(--ai-panel-raised); border: 1px solid var(--ai-panel-line-strong); border-radius: 22px; box-shadow: 0 4px 16px #0003; min-height: 36px; }
.latest-message:hover { border-color: var(--color-accent-on-dark); }
@media(max-width:639px) { .ai-panel { left: 12px !important; top: max(12px, env(safe-area-inset-top)) !important; width: calc(100% - 24px) !important; height: calc(100dvh - 24px - env(safe-area-inset-top) - env(safe-area-inset-bottom)) !important; } .resize-handle { display: none; } .dialog-control { width: 32px; } }
</style>
