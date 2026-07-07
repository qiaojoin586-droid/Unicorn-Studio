<template>
  <div class="min-h-screen bg-gradient-to-br from-gray-950 via-indigo-950 to-gray-950">
    <!-- Hero banner -->
    <section class="relative -mt-20 pt-32 pb-16 overflow-hidden">
      <div class="absolute inset-0 pointer-events-none">
        <div class="absolute -top-1/3 -right-1/4 w-[500px] h-[500px] bg-indigo-600/15 rounded-full blur-3xl"></div>
        <div class="absolute -bottom-1/3 -left-1/4 w-[400px] h-[400px] bg-purple-600/15 rounded-full blur-3xl"></div>
      </div>
      <div class="relative max-w-6xl mx-auto px-4 text-center">
        <p class="text-indigo-400 text-sm font-semibold tracking-widest uppercase mb-4">About Us</p>
        <h1 class="text-4xl sm:text-5xl font-extrabold text-white mb-4">关于独角兽工作室</h1>
        <p class="text-gray-400 max-w-xl mx-auto">一群热爱技术与创造力的伙伴，致力于在数字世界中创造价值</p>
      </div>
    </section>

    <!-- Main content -->
    <section class="max-w-6xl mx-auto px-4 pb-20">
      <div class="grid grid-cols-1 md:grid-cols-2 gap-12 items-stretch">
        <!-- Text -->
        <div>
          <p class="text-lg leading-relaxed mb-6 text-gray-300">
            独角兽工作室是一支充满激情的技术团队，专注于为客户提供高质量的数字产品与服务。
            我们相信，优秀的技术结合出色的设计，能够为用户带来非凡的体验。
          </p>
          <p class="text-lg leading-relaxed mb-8 text-gray-400">
            自创立以来，我们已为众多企业提供了 Web 开发、移动应用、大模型学术研究等方面的专业服务，
            赢得了客户的广泛认可与好评。
          </p>

          <!-- Stats -->
          <div class="flex space-x-6">
            <div class="text-center bg-white/5 rounded-xl px-6 py-4 border border-white/10">
              <div class="text-3xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-indigo-400 to-purple-400">50+</div>
              <div class="text-sm text-gray-500 mt-1">完成项目</div>
            </div>
            <div class="text-center bg-white/5 rounded-xl px-6 py-4 border border-white/10">
              <div class="text-3xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-indigo-400 to-purple-400">30+</div>
              <div class="text-sm text-gray-500 mt-1">合作客户</div>
            </div>
            <div class="text-center bg-white/5 rounded-xl px-6 py-4 border border-white/10">
              <div class="text-3xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-indigo-400 to-purple-400">5+</div>
              <div class="text-sm text-gray-500 mt-1">年行业经验</div>
            </div>
          </div>
        </div>

        <!-- Visual card -->
        <div class="bg-gradient-to-br from-indigo-900/50 to-purple-900/50 rounded-2xl border border-white/10 backdrop-blur-sm overflow-hidden">
          <img :src="logoUrl" alt="社团LOGO" class="w-full h-full object-contain rounded-lg" />
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import logoUrl from '@/image/LOGO.jpg'
</script>
