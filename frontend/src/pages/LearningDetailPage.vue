<template>
  <div class="min-h-screen bg-gradient-to-br from-gray-950 via-indigo-950 to-gray-950">
    <!-- Loading state -->
    <div v-if="loading" class="flex items-center justify-center min-h-screen -mt-20 pt-20 px-4">
      <div class="text-center">
        <div class="text-4xl mb-3 animate-bounce-subtle">🦄</div>
        <p class="text-gray-400">正在加载学习资料详情...</p>
      </div>
    </div>

    <!-- Error state -->
    <div v-else-if="error" class="flex items-center justify-center min-h-screen -mt-20 pt-20 px-4">
      <div class="bg-gray-900/90 border border-white/10 rounded-2xl p-8 max-w-sm w-full text-center backdrop-blur-sm shadow-2xl">
        <div class="text-4xl mb-3">⚠️</div>
        <h2 class="text-2xl font-bold text-white mb-2">加载失败</h2>
        <p class="text-gray-400 text-sm mb-4">{{ error }}</p>
        <div class="flex gap-3 justify-center">
          <button
            type="button"
            @click="loadResource"
            class="bg-indigo-600 hover:bg-indigo-500 text-white font-semibold px-6 py-2.5 rounded-lg transition"
          >
            重试
          </button>
          <router-link
            to="/learning"
            class="bg-white/10 hover:bg-white/20 text-white font-semibold px-6 py-2.5 rounded-lg transition"
          >
            返回学习资料列表
          </router-link>
        </div>
      </div>
    </div>

    <!-- Content -->
    <template v-else>
      <!-- Hero -->
      <section class="relative -mt-20 pt-32 pb-12 overflow-hidden">
        <div class="absolute inset-0 pointer-events-none">
          <div class="absolute -top-1/3 -right-1/4 w-[500px] h-[500px] bg-indigo-600/15 rounded-full blur-3xl"></div>
          <div class="absolute -bottom-1/3 -left-1/4 w-[400px] h-[400px] bg-purple-600/15 rounded-full blur-3xl"></div>
        </div>
        <div class="relative max-w-4xl mx-auto px-4">
          <router-link
            to="/learning"
            class="inline-flex items-center gap-1.5 text-indigo-300 hover:text-indigo-200 text-sm font-semibold transition mb-6"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
            </svg>
            返回学习资料
          </router-link>

          <div class="flex flex-wrap items-center gap-3 mb-4">
            <span v-if="resource.category" class="text-xs font-semibold px-3 py-1 rounded-full bg-indigo-500/20 text-indigo-300">
              {{ resource.category }}
            </span>
            <span
              v-for="tag in tags"
              :key="tag"
              class="text-xs font-semibold px-3 py-1 rounded-full bg-white/10 text-gray-300"
            >
              {{ tag }}
            </span>
          </div>

          <h1 class="text-3xl sm:text-4xl font-extrabold text-white mb-6 leading-tight">{{ resource.title }}</h1>

          <p class="text-gray-300 text-lg leading-relaxed mb-6">{{ resource.description }}</p>

          <div class="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm text-gray-400">
            <span v-if="updatedLabel" class="flex items-center gap-1.5">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
              </svg>
              {{ updatedLabel }}
            </span>
          </div>
        </div>
      </section>

      <!-- Cover + Content -->
      <section class="relative max-w-4xl mx-auto px-4 pb-20">
        <div class="bg-white/5 border border-white/10 rounded-2xl p-6 sm:p-10 backdrop-blur-sm">
          <!-- Cover -->
          <div class="relative h-56 sm:h-72 overflow-hidden rounded-xl mb-8 bg-gradient-to-br from-indigo-100 to-purple-100">
            <img
              v-if="resource.coverUrl"
              :src="resource.coverUrl"
              :alt="resource.title"
              class="w-full h-full object-cover"
            />
            <div v-else class="absolute inset-0 flex items-center justify-center text-7xl">
              🎓
            </div>
          </div>

          <!-- 正文（后台富文本，保存时已由后端 HtmlSanitizer 白名单消毒） -->
          <div v-if="resource.content" class="rich-content" v-html="resource.content"></div>
          <p v-else class="text-gray-500 leading-relaxed">正文正在整理中，敬请期待～</p>

          <!-- Actions -->
          <div class="flex flex-wrap gap-4 mt-8">
            <a
              v-if="resource.url"
              :href="resource.url"
              target="_blank"
              rel="noopener noreferrer"
              class="inline-flex items-center gap-2 bg-indigo-600 hover:bg-indigo-500 text-white font-semibold px-6 py-3 rounded-xl transition"
            >
              打开原文
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
              </svg>
            </a>
            <a
              v-if="resource.attachmentUrl"
              :href="resource.attachmentUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="inline-flex items-center gap-2 bg-white/10 hover:bg-white/20 text-white font-semibold px-6 py-3 rounded-xl transition"
            >
              下载附件
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
              </svg>
            </a>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useApi } from '@/composables/useApi'
import { usePageMeta } from '@/composables/usePageMeta'

const route = useRoute()
const { get } = useApi()

const loading = ref(true)
const error = ref('')
const resource = ref(null)

usePageMeta(() => ({
  title: resource.value?.title || '学习资料详情',
  description: resource.value?.description || '',
}))

const tags = computed(() =>
  resource.value?.tags
    ? resource.value.tags.split(',').map((t) => t.trim()).filter(Boolean)
    : [],
)

const updatedLabel = computed(() => {
  const time = resource.value?.updatedAt
  if (!time) return ''
  const date = new Date(time)
  if (Number.isNaN(date.getTime())) return ''
  return `更新于 ${date.toLocaleDateString('zh-CN')}`
})

async function loadResource() {
  loading.value = true
  error.value = ''
  try {
    const result = await get(`/api/learning/resources/${route.params.id}`)

    if (result.error) {
      throw new Error(result.status === 404 ? '学习资料不存在或已下线' : result.message)
    }

    resource.value = result.data?.resource || null
    if (!resource.value) {
      throw new Error('学习资料不存在或已下线')
    }
  } catch (err) {
    error.value = err.message || '无法加载学习资料详情'
  } finally {
    loading.value = false
  }
}

// 同路由记录间参数变化（/learning/1 → /learning/2）会复用组件实例，需 watch 参数重新加载
onMounted(loadResource)
watch(() => route.params.id, loadResource)
</script>

<style scoped>
@keyframes bounce-subtle {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}
.animate-bounce-subtle {
  animation: bounce-subtle 1.2s ease-in-out infinite;
}
</style>

<!-- v-html 注入的内容没有 scoped 属性，需全局样式约束富文本排版 -->
<style>
.rich-content {
  color: #d1d5db;
  line-height: 1.75;
}
.rich-content h2 {
  margin: 1.5em 0 0.75em;
  font-size: 1.5rem;
  font-weight: 700;
  color: #fff;
}
.rich-content h3 {
  margin: 1.25em 0 0.5em;
  font-size: 1.125rem;
  font-weight: 600;
  color: #e5e7eb;
}
.rich-content p {
  margin: 0.75em 0;
}
.rich-content ul {
  margin: 0.75em 0;
  padding-left: 1.5em;
  list-style: disc;
}
.rich-content li {
  margin: 0.25em 0;
}
.rich-content pre {
  margin: 1em 0;
  padding: 1em;
  border-radius: 0.75rem;
  background: #0f172a;
  border: 1px solid rgba(255, 255, 255, 0.1);
  overflow-x: auto;
}
.rich-content code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9em;
}
.rich-content pre code {
  background: none;
  padding: 0;
}
.rich-content a {
  color: #818cf8;
  text-decoration: underline;
}
.rich-content img {
  max-width: 100%;
  border-radius: 0.5rem;
}
</style>
