<template>
  <div class="flex gap-3" :class="isUser ? 'flex-row-reverse' : ''">
    <!-- Avatar：助手用独角兽标志（跟页头/对话框左上角同一个）；
         用户侧保留「我」字样的红色圆点 —— 那是"人"的标识，不是品牌标识。 -->
    <div
      class="w-7 h-7 rounded-full flex items-center justify-center text-xs flex-shrink-0 mt-0.5"
      :class="isUser
        ? 'bg-[#C92D3B] text-[var(--ai-panel-text)]'
        : 'bg-[var(--ai-panel-hover)] text-[var(--ai-panel-text)]'"
    >
      <BrandMark v-if="!isUser" aria-hidden="true" />
      <span v-else aria-hidden="true">我</span>
    </div>

    <!-- Bubble -->
    <div
      class="rounded-2xl px-4 py-3 max-w-[85%] min-w-0"
      :class="isUser
        ? 'bg-[#C92D3B] text-[var(--ai-panel-text)] rounded-tr-sm'
        : 'bg-[var(--ai-field-bg)] border border-[var(--ai-panel-line)] text-gray-200 rounded-tl-sm'"
    >
      <!--
        Markdown 渲染：html 来自 useMarkdown（marked 解析 + DOMPurify 消毒），
        是页面内唯一允许 v-html 的对话内容入口。
        用户消息同样走 Markdown（保留其换行/代码块），视觉上以 is-user 反色区分。
      -->
      <div
        ref="bubbleBody"
        class="md-body"
        :class="isUser ? 'is-user' : ''"
        v-html="renderedHtml"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, onMounted, nextTick } from 'vue'
import { renderMarkdown, hardenLinks } from '@/composables/useMarkdown'

const props = defineProps({
  role: {
    type: String,
    required: true,
    validator: (v) => ['user', 'assistant'].includes(v),
  },
  content: {
    type: String,
    required: true,
  },
})

const isUser = computed(() => props.role === 'user')
const bubbleBody = ref(null)
const renderedHtml = computed(() => renderMarkdown(props.content))

// v-html 注入后 a 标签的 target/rel 需要后置补写（模板无法触及内部节点）
function applyLinkHardening() {
  hardenLinks(bubbleBody.value)
}

watch(renderedHtml, () => nextTick(applyLinkHardening))
onMounted(applyLinkHardening)
</script>
