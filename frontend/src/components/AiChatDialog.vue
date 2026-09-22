<template>
  <!--
    定位改由 useResizablePanel 的共享布局状态给出（`panelStyle` 的 left/top）。
    图标与对话框共用同一份坐标 ⇒ 拖任意一方，另一方同步位移。
    原先的 `max-w-[calc(100vw-4rem)]` 已移除：宽度上限现在由 composable 的
    `bounds.maxW` 统一按视口夹紧；两处同时约束会让「实际渲染宽度」与
    「参与定位/拉伸计算的宽度」不一致，导致拖拽边缘时错位。
  -->
  <div
    v-if="store.isOpen"
    data-ai-panel
    class="fixed z-50"
    :style="[panelStyle, sizeStyle]"
  >
    <transition
      enter-active-class="transition duration-300 ease-out"
      enter-from-class="opacity-0 translate-y-4 scale-95"
      enter-to-class="opacity-100 translate-y-0 scale-100"
      leave-active-class="transition duration-200 ease-in"
      leave-from-class="opacity-100 translate-y-0 scale-100"
      leave-to-class="opacity-0 translate-y-4 scale-95"
    >
      <div
        ref="dialogPanel"
        role="dialog"
        aria-label="AI 助手对话"
        class="relative bg-gray-900/95 backdrop-blur-xl border border-white/10 rounded-2xl shadow-2xl overflow-hidden flex flex-col w-full h-full"
      >
        <!-- Header：同时是「整体拖动」的抓手（光标变 move）。
             `touch-none` 让触屏拖动不被页面滚动抢走；`select-none` 防止拖出选区。
             ⚠️ 拖动不调用 preventDefault —— 那会抑制 click 等兼容鼠标事件，
                而图标开合对话正是靠 click（见 useResizablePanel.startPairDrag 注释）。 -->
        <div
          class="flex items-center justify-between px-5 py-4 border-b border-white/10 flex-shrink-0 cursor-move select-none touch-none"
          @pointerdown="startPairDrag"
          title="按住拖动整个对话框"
        >
          <div class="flex items-center gap-3">
            <div class="w-8 h-8 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-sm">
              🦄
            </div>
            <div>
              <h3 class="text-white font-semibold text-sm">AI 助手</h3>
              <p class="text-gray-500 text-xs">独角兽工作室智能助手</p>
            </div>
          </div>
          <!-- 按钮区不参与拖动，否则点「清空/关闭」时轻微抖动会把面板挪走 -->
          <div class="flex items-center gap-2" @pointerdown.stop>
            <button
              v-if="store.hasMessages"
              @click="store.clearMessages()"
              class="text-gray-500 hover:text-gray-300 transition p-1"
              title="清空对话"
              aria-label="清空对话"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
              </svg>
            </button>
            <button
              @click="store.closeChat()"
              class="text-gray-500 hover:text-gray-300 transition p-1"
              title="关闭"
              aria-label="关闭对话"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        <!-- Messages area -->
        <div
          ref="messagesContainer"
          class="flex-1 min-h-0 overflow-y-auto px-5 py-4 space-y-4"
          @scroll="onMessagesScroll"
        >
          <!-- Welcome message -->
          <div v-if="!store.hasMessages && !store.streamingText" class="text-center py-8">
            <div class="text-4xl mb-3">🦄</div>
            <p class="text-gray-400 text-sm leading-relaxed">
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
            <div class="w-7 h-7 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-xs flex-shrink-0 mt-0.5">
              🦄
            </div>
            <!-- 气泡样式必须与 ChatMessage 的 assistant 分支保持一致（含 text-gray-200），
                 否则流式阶段文字会继承默认黑色，在深色气泡上变成「一片黑框」。 -->
            <div class="bg-white/5 border border-white/10 text-gray-200 rounded-2xl rounded-tl-sm px-4 py-3 max-w-[85%] min-w-0">
              <div ref="streamingBody" class="md-body" v-html="streamingHtml" />
              <span class="inline-block w-1.5 h-4 bg-indigo-400 animate-pulse ml-0.5 align-middle" />
            </div>
          </div>

          <!-- Thinking indicator -->
          <div v-if="store.isLoading && !store.streamingText" class="flex gap-3">
            <div class="w-7 h-7 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-xs flex-shrink-0">
              🦄
            </div>
            <div class="bg-white/5 border border-white/10 rounded-2xl rounded-tl-sm px-4 py-3">
              <div class="flex gap-1">
                <span class="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style="animation-delay: 0ms" />
                <span class="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style="animation-delay: 150ms" />
                <span class="w-2 h-2 bg-gray-500 rounded-full animate-bounce" style="animation-delay: 300ms" />
              </div>
            </div>
          </div>
        </div>

        <!-- 停止生成（优化方案 F-11）：流式响应期间可中断，保留已生成部分 -->
        <div v-if="store.isLoading" class="px-4 pt-2.5 flex justify-center flex-shrink-0">
          <button
            type="button"
            @click="stopStream"
            class="inline-flex items-center gap-1.5 text-xs text-gray-400 hover:text-white bg-white/5 hover:bg-white/10 border border-white/10 rounded-full px-3 py-1 transition"
          >
            <span class="w-2 h-2 rounded-sm bg-current inline-block"></span>
            停止生成
          </button>
        </div>

        <!-- Input area -->
        <ChatInput @send="handleSend" :disabled="store.isLoading" />

        <!-- 拉伸把手（八向）：四条边 + 四个角。
             边把手平时透明、hover 才显出一道浅色条；角把手常驻淡色斜纹（沿用改造前
             左下角那枚视觉），hover 高亮。角把手 DOM 在后 ⇒ 叠在边把手之上。
             双击角把手在「默认尺寸 / 放大尺寸」间切换。
             注意：视觉层都是 `pointer-events-none`，hover 态只能靠父级 group 触发。 -->
        <div
          v-for="handle in RESIZE_HANDLES"
          :key="handle.dir"
          class="group absolute z-10 touch-none"
          :class="handle.cls"
          :data-resize="handle.dir"
          @pointerdown="startResize(handle.dir, $event)"
          @dblclick="onHandleDblClick(handle)"
          :role="handle.corner ? 'separator' : null"
          :aria-label="handle.corner ? '拖拽调整对话框大小' : null"
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
              class="w-full h-full text-gray-500 transition group-hover:text-indigo-400"
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
    </transition>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useAiChatStore } from '@/stores/aiChat'
import { useAiChat } from '@/composables/useAiChat'
import { renderMarkdown, hardenLinks } from '@/composables/useMarkdown'
import { useResizablePanel } from '@/composables/useResizablePanel'
import ChatMessage from './ChatMessage.vue'
import ChatInput from './ChatInput.vue'

const store = useAiChatStore()
const { streamChat, stopStream } = useAiChat()
const messagesContainer = ref(null)
const streamingBody = ref(null)
const dialogPanel = ref(null)
const stickToBottom = ref(true)

// 布局（位置 + 尺寸）由 useResizablePanel 统一管理，并与对话图标共享**同一份单例状态**：
//   · panelStyle —— 对话框定位（left/top）。拖动图标或拖动本面板的表头，两边一起位移；
//   · sizeStyle  —— 对话框尺寸（width/height），由八向把手改变。
// 位置与尺寸都持久化到 localStorage，刷新后保持。
const {
  style: sizeStyle,
  panelStyle,
  startPairDrag,
  startResize,
  toggleDefaultSize,
  attach,
} = useResizablePanel({
  storageKey: 'unicorn.aiChat.panelSize',
  defaultWidth: 384, // 与改造前的 w-96 保持一致
  defaultHeight: 500,
  minWidth: 320,
  minHeight: 320,
  maxWidth: 900,
  maxHeight: 900,
  viewportMargin: 24,
})

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
// W-07 方案 B（rAF 尾沿节流）：原先用 computed，每个 SSE 分片都会把【累积全文】重跑一遍
// marked.parse + DOMPurify.sanitize 并整块替换 v-html 的 DOM，一段 2000 字回答 = 几百次
// 全量解析 + DOM 重建，越到后面越卡（O(n²)）。节流后：
//   - 距上次渲染已超过 THROTTLE_MS → 立即渲染（首片、以及稀疏分片无额外延迟）；
//   - 窗口内 → 合并到下一个动画帧，只在最后一帧真正渲染（尾沿）。
// 回调里实时读 store.streamingText 而不是闭包捕获，因此永远不会写入过期内容。
const streamingHtml = ref('')
const THROTTLE_MS = 100
let streamRafId = 0
let lastRenderAt = 0

function flushStreamingHtml() {
  streamRafId = 0
  lastRenderAt = performance.now()
  streamingHtml.value = renderMarkdown(store.streamingText)
}

watch(
  () => store.streamingText,
  () => {
    if (performance.now() - lastRenderAt >= THROTTLE_MS) {
      cancelAnimationFrame(streamRafId)
      flushStreamingHtml()
      return
    }
    cancelAnimationFrame(streamRafId)
    streamRafId = requestAnimationFrame(flushStreamingHtml)
  },
  { immediate: true },
)

function handleSend(message) {
  streamChat(message)
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
  () => [store.messages.length, store.streamingText],
  async () => {
    await nextTick()
    const el = messagesContainer.value
    // W-07：改用 scrollTo + behavior:'auto'。原先裸赋 scrollTop，而消息容器的
    // scroll-behavior 是 smooth，于是每个分片都会重新触发一次平滑滚动动画并立刻被
    // 下一片打断，表现为「追着滚但一卡一卡」。模板里的平滑滚动类已一并移除；
    // 这里仍显式写 behavior:'auto'，避免将来有人把它加回来时再次踩坑。
    // （注意：注释里不要再出现那个样式类名 —— Tailwind 的提取器会把注释里的字符串
    //   也当作候选类名，凭空生成一段用不到的死 CSS。）
    if (el && stickToBottom.value) el.scrollTo({ top: el.scrollHeight, behavior: 'auto' })
  },
)

// v-html 注入后补外链安全属性（流式气泡同样需要）
watch(streamingHtml, () => nextTick(() => hardenLinks(streamingBody.value)))

// ESC 关闭 + 打开时焦点移入输入框（优化方案 F-16）
function onKeydown(e) {
  if (e.key === 'Escape' && store.isOpen) store.closeChat()
}

watch(
  () => store.isOpen,
  (open) => {
    if (open) {
      window.addEventListener('keydown', onKeydown)
      nextTick(() => {
        dialogPanel.value?.querySelector('input')?.focus()
      })
    } else {
      window.removeEventListener('keydown', onKeydown)
    }
  },
)

onMounted(() => {
  attach()
  hardenLinks(streamingBody.value)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  // 取消挂起的节流帧，避免卸载后仍触发一次渲染
  cancelAnimationFrame(streamRafId)
})
</script>
