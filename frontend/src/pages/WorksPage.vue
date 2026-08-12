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
      <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-2 gap-8">
        <WorkCard
          v-for="work in works"
          :key="work.title"
          :title="work.title"
          :description="work.description"
          :icon="work.icon"
          :tag-type="work.tagType"
          :authors="work.authors"
          :date="work.date"
          :cover-class="work.coverClass"
        />
      </div>
    </section>
  </div>
</template>

<script setup>
import WorkCard from '@/components/WorkCard.vue'

const works = [
  {
    title: 'ACM-ICPC 亚洲区域赛金牌',
    description: '在第 48 届 ACM-ICPC 亚洲区域赛中，团队凭借出色的算法设计与协作能力，从 300+ 支队伍中脱颖而出，斩获金牌。',
    icon: '🏆',
    tagType: 'award',
    authors: '张三、李四、王五',
    date: '2025-12',
    coverClass: 'bg-gradient-to-br from-amber-100 to-orange-100',
  },
  {
    title: 'SCI 论文：基于深度学习的图像超分辨率重建',
    description: '提出一种新型注意力机制网络架构，在 Set5、Set14 等基准数据集上 PSNR 指标达到 SOTA，被 IEEE TIP 期刊录用。',
    icon: '📄',
    tagType: 'paper',
    authors: '赵六、孙七 等',
    date: '2026-03',
    coverClass: 'bg-gradient-to-br from-emerald-100 to-teal-100',
  },
  {
    title: '中国大学生计算机设计大赛一等奖',
    description: '作品「智行」—— 基于 Vue 3 与 WebSocket 的实时协作白板应用，荣获交互设计组全国一等奖。',
    icon: '🥇',
    tagType: 'award',
    authors: '周八、吴九、郑十',
    date: '2025-08',
    coverClass: 'bg-gradient-to-br from-indigo-100 to-violet-100',
  },
  {
    title: 'SCI 论文：大语言模型在代码生成中的应用研究',
    description: '探索 GPT 系列模型在自动化代码生成任务中的表现，提出基于检索增强的微调策略，被 ACM TOSEM 期刊收录。',
    icon: '',
    tagType: 'paper',
    authors: '陈一、林二 等',
    date: '2026-01',
    coverClass: 'bg-gradient-to-br from-rose-100 to-pink-100',
  },
]
</script>
