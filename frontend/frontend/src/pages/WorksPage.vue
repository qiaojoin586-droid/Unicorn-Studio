<template>
  <div class="min-h-screen bg-gradient-to-br from-gray-950 via-indigo-950 to-gray-950">
    <!-- Hero banner -->
    <section class="relative -mt-20 pt-32 pb-16 overflow-hidden">
      <div class="absolute inset-0 pointer-events-none">
        <div class="absolute -top-1/3 -right-1/4 w-[500px] h-[500px] bg-indigo-600/15 rounded-full blur-3xl"></div>
        <div class="absolute -bottom-1/3 -left-1/4 w-[400px] h-[400px] bg-purple-600/15 rounded-full blur-3xl"></div>
      </div>
      <div class="relative max-w-6xl mx-auto px-4 text-center">
        <p class="text-indigo-400 text-sm font-semibold tracking-widest uppercase mb-4">Our Works</p>
        <h1 class="text-4xl sm:text-5xl font-extrabold text-white mb-4">我们的作品</h1>
        <p class="text-gray-400 max-w-xl mx-auto">竞赛获奖与学术论文，记录每一次突破与成长</p>
      </div>
    </section>

    <!-- Works grid -->
    <section class="max-w-6xl mx-auto px-4 pb-20">
      <!-- Loading -->
      <div v-if="loading" class="text-center py-16">
        <p class="text-gray-400">正在加载作品...</p>
      </div>

      <!-- Error -->
      <div v-else-if="error" class="text-center py-16">
        <p class="text-gray-400 mb-4">{{ error }}</p>
        <button
          type="button"
          @click="loadWorks"
          class="bg-indigo-600 hover:bg-indigo-500 text-white font-semibold px-6 py-2.5 rounded-lg transition"
        >
          重试
        </button>
      </div>

      <!-- Grid -->
      <div v-else-if="works.length" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-2 gap-8">
        <WorkCard
          v-for="work in works"
          :key="work.slug"
          :slug="work.slug"
          :title="work.title"
          :description="work.summary"
          :icon="work.icon"
          :tag-type="work.tagType"
          :authors="work.authors"
          :date="work.dateLabel"
          :cover-class="work.coverClass"
        />
      </div>

      <!-- Empty -->
      <div v-else class="text-center py-16">
        <p class="text-gray-400">暂无作品，敬请期待</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import WorkCard from '@/components/WorkCard.vue'
import { useApi } from '@/composables/useApi'

const { get } = useApi()

const loading = ref(true)
const error = ref('')
const works = ref([])

async function loadWorks() {
  loading.value = true
  error.value = ''
  try {
    const result = await get('/api/works')

    if (result.error) {
      throw new Error(result.message)
    }

    works.value = result.data?.list || []
  } catch (err) {
    error.value = err.message || '无法加载作品列表'
  } finally {
    loading.value = false
  }
}

onMounted(loadWorks)
</script>
