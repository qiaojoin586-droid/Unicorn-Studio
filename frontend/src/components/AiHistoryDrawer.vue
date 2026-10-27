<template>
  <!--
    历史抽屉（文档 §8.2）。挂在 AiChatDialog 内部，作为「左侧栏」与消息区并排 ——
    不是覆盖层，打开时对话框整体不变形、输入框始终可用。
    ⚠️ 不能移出 AiChatDialog：那边根节点带 overflow-hidden 与 transform，
       移出去会脱离拖动/缩放坐标系；这里保持同一棵子树。
  -->
  <div
    class="flex h-full flex-shrink-0"
    :class="isNarrow ? 'absolute inset-0 z-20' : 'w-[260px] border-r border-white/10'"
  >
    <div class="flex h-full w-full flex-col">
      <!-- 头部 -->
      <div class="flex items-center justify-between px-3 py-3 border-b border-white/10 flex-shrink-0">
        <div class="flex items-center gap-2 min-w-0">
          <!-- 手机端是全屏覆盖层，需要返回按钮（§8.2） -->
          <button
            v-if="isNarrow"
            type="button"
            class="text-gray-400 hover:text-white transition p-1"
            title="返回对话"
            aria-label="返回对话"
            @click="emit('close')"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
            </svg>
          </button>
          <h4 class="text-white text-sm font-medium truncate">历史对话</h4>
        </div>
        <button
          type="button"
          class="text-gray-500 hover:text-gray-300 transition p-1"
          title="关闭"
          aria-label="关闭历史对话"
          @click="emit('close')"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <!-- 列表：滚到底自动加载下一页（§8.2） -->
      <!-- 滚动条用全局 .scrollbar-slim（见 assets/main.css），默认的亮白竖条在深色底上太刺眼 -->
      <div ref="listContainer" class="scrollbar-slim flex-1 min-h-0 overflow-y-auto" @scroll="onScroll">
        <!-- 错误提示条：原生 alert 太突兀也挡视线，改成抽屉内的一条，可手动关掉 -->
        <div
          v-if="store.historyError"
          class="mx-2 mt-2 flex items-start gap-2 rounded-lg border border-red-400/25 bg-red-400/10 px-2.5 py-2 text-[11px] leading-relaxed text-red-200/90"
        >
          <span class="flex-1 min-w-0">{{ store.historyError }}</span>
          <button
            type="button"
            class="shrink-0 text-red-300/60 transition hover:text-red-200"
            title="关闭提示"
            aria-label="关闭提示"
            @click="store.historyError = ''"
          >
            <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- 空态：不显示骨架屏（§8.2） -->
        <p
          v-if="!store.hasSessions && !store.historyLoading"
          class="px-4 py-8 text-center text-gray-500 text-xs"
        >
          还没有历史对话
        </p>

        <div
          v-for="s in store.sessions"
          :key="s.id"
          class="group px-2 py-1"
          :class="s.id === store.sessionId ? 'bg-white/5' : ''"
        >
          <div class="flex items-start gap-1">
            <!-- 点击：载入该会话最近一页，并继续在它里面对话（§8.2） -->
            <button
              type="button"
              class="flex-1 min-w-0 text-left px-2 py-2 rounded-lg transition hover:bg-white/5"
              @click="onPick(s)"
            >
              <p class="text-gray-200 text-xs truncate" :title="s.title">{{ shortTitle(s.title) }}</p>
              <p class="mt-0.5 text-[11px] text-gray-500 truncate">
                {{ s.messageCount }} 条 · {{ relativeTime(s.lastActiveAt) }}
              </p>
            </button>
            <!-- 清空：悬浮出现，点击走二次确认（§8.2 + §7.1 文案要求） -->
            <button
              type="button"
              class="opacity-0 group-hover:opacity-100 focus:opacity-100 transition p-1.5 text-gray-500 hover:text-red-400"
              title="清空对话"
              aria-label="清空对话"
              @click.stop="onAskClear(s)"
            >
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
              </svg>
            </button>
          </div>
        </div>

        <p v-if="store.historyLoading" class="px-4 py-3 text-center text-gray-500 text-xs">加载中…</p>
        <p
          v-else-if="store.hasSessions && !store.hasMoreSessions"
          class="px-4 py-3 text-center text-gray-600 text-[11px]"
        >
          没有更多了
        </p>
      </div>

      <!-- 新对话 -->
      <div class="px-2 py-2 border-t border-white/10 flex-shrink-0">
        <button
          type="button"
          class="w-full rounded-lg border border-white/10 px-3 py-2 text-xs text-gray-300 transition hover:bg-white/5"
          @click="onNewChat"
        >
          + 新对话
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useAiChatStore } from '@/stores/aiChat'
import { useAiChat } from '@/composables/useAiChat'

const store = useAiChatStore()
const { loadSessions, loadHistoryMessages } = useAiChat()

const emit = defineEmits(['close'])
const listContainer = ref(null)

/** 手机端（<640px）抽屉改为全屏覆盖层，带返回按钮（§8.2） */
const isNarrow = ref(false)
function syncViewport() {
  isNarrow.value = window.innerWidth < 640
}

onMounted(() => {
  syncViewport()
  window.addEventListener('resize', syncViewport)
  loadSessions(1)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncViewport)
})

/** 标题展示层截断到 20 字（§8.2；后端入库时已按 60 字上限截过） */
function shortTitle(title) {
  if (!title) return '新会话'
  return title.length > 20 ? title.slice(0, 20) + '…' : title
}

/**
 * 相对时间（§8.2）。≥365 天直接显示具体日期 —— 「1 年前」对用户没有信息量。
 */
function relativeTime(value) {
  if (!value) return ''
  const t = new Date(value).getTime()
  if (Number.isNaN(t)) return ''
  const diff = Date.now() - t
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day} 天前`
  if (day >= 365) return new Date(value).toLocaleDateString('zh-CN')
  return `${Math.floor(day / 30)} 个月前`
}

function onScroll(e) {
  const el = e.target
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 40 && store.hasMoreSessions && !store.historyLoading) {
    loadSessions(store.sessionPage + 1)
  }
}

async function onPick(s) {
  try {
    const items = await loadHistoryMessages(s.id, 0)
    // 404 / 未登录时 composable 已经处理过（移除该项或弹登录），这里不要切会话
    if (!items) return
    store.replaceMessages(items)
    store.setSessionId(s.id)
    emit('close')
  } catch (err) {
    store.historyError = err.message || '加载对话内容失败，请稍后重试'
  }
}

function onNewChat() {
  store.startNewSession()
  emit('close')
}

/** 清空对话：交给对话框层的确认框统一处理（抽屉与右上角垃圾桶共用同一个） */
function onAskClear(s) {
  store.openConfirm(s)
}
</script>
