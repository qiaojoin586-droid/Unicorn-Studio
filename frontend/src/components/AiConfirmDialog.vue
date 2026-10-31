<template>
  <!--
    清空对话的二次确认框（§8.2）。抽屉里的垃圾桶与对话框右上角的垃圾桶共用这一个。
    Teleport 到 body 是必须的：对话框根节点带 overflow-hidden，拖动时外层还有 transform，
    fixed 遮罩留在原地会被裁剪、也会跟着缩放（同 AiLoginPrompt 的处理）。
  -->
  <Teleport to="body">
    <transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition duration-150 ease-in"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="store.confirmTarget"
        class="fixed inset-0 z-[1100] flex items-center justify-center px-4"
      >
        <!-- 遮罩：带一点模糊把背后的对话框压下去，比纯黑柔和 -->
        <div
          class="absolute inset-0 bg-black/60 backdrop-blur-[2px]"
          @click="store.closeConfirm()"
        ></div>

        <div
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="ai-clear-title"
          class="relative w-full max-w-[380px] overflow-hidden rounded-2xl border border-[var(--ai-panel-line)] bg-gray-900/95 shadow-2xl shadow-black/50 backdrop-blur-xl"
        >
          <!-- 顶部一条红→透明细线：不用更多颜色就点明"这是个不可逆操作" -->
          <div class="h-[3px] w-full bg-gradient-to-r from-red-500/80 via-red-500/25 to-transparent"></div>

          <div class="p-5">
            <div class="flex items-start gap-3.5">
              <span
                class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-red-500/12 text-red-300 ring-1 ring-red-400/20"
                aria-hidden="true"
              >
                <svg class="w-[18px] h-[18px]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
              </span>
              <div class="min-w-0 flex-1 pt-0.5">
                <h4 id="ai-clear-title" class="text-[15px] font-semibold text-[var(--ai-panel-text)]">清空这段对话？</h4>
                <p class="mt-1 truncate text-[13px] leading-relaxed text-[var(--ai-panel-muted)]" :title="targetTitle">
                  {{ targetTitle }}
                </p>
              </div>
            </div>

            <!-- §7.1 要求把「清空 ≠ 删除」写进 UI，否则用户会以为清了就没了 -->
            <div class="mt-4 flex gap-2.5 rounded-xl border border-amber-300/15 bg-amber-300/[0.04] px-3.5 py-3">
              <svg class="mt-px h-3.5 w-3.5 shrink-0 text-amber-300/70" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
              </svg>
              <p class="text-[12px] leading-relaxed text-amber-50/70">
                清空只是把这段对话<strong class="font-medium text-amber-100/90">从你的列表中移除</strong>；
                为满足平台内容合规要求，记录会在服务端保留最多 90 天后自动删除。
              </p>
            </div>

            <div class="mt-5 flex justify-end gap-2">
              <button
                type="button"
                class="rounded-full border border-white/15 px-4 py-1.5 text-[13px] text-[var(--ai-panel-muted)] transition hover:bg-white/10"
                @click="store.closeConfirm()"
              >
                取消
              </button>
              <button
                type="button"
                :disabled="store.clearing"
                class="rounded-full bg-red-500/90 px-4 py-1.5 text-[13px] font-medium text-[var(--ai-panel-text)] transition hover:bg-red-500 disabled:cursor-not-allowed disabled:opacity-60"
                @click="onConfirm"
              >
                {{ store.clearing ? '清空中…' : '确认清空' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useAiChatStore } from '@/stores/aiChat'
import { useAiChat } from '@/composables/useAiChat'

const store = useAiChatStore()
const { clearSession } = useAiChat()

const targetTitle = computed(() => store.confirmTarget?.title || '当前对话')

async function onConfirm() {
  const id = store.confirmTarget?.id
  if (!id) return
  store.clearing = true
  try {
    const done = await clearSession(id)
    store.confirmTarget = null
    if (!done) {
      store.historyError = '清空失败，请稍后重试'
    }
  } finally {
    store.clearing = false
  }
}

/**
 * ESC 取消。用捕获阶段 + stopImmediatePropagation 抢在 AiChatDialog 的 window keydown 之前跑完 ——
 * 否则按 ESC 会先把整个对话框关掉，而不是只关这个确认框。
 */
function onKeydown(e) {
  if (e.key !== 'Escape' || !store.confirmTarget) return
  e.stopImmediatePropagation()
  store.closeConfirm()
}

onMounted(() => window.addEventListener('keydown', onKeydown, true))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown, true))
</script>
