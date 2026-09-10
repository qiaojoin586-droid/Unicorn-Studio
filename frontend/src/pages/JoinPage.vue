<template>
  <div class="min-h-screen relative">
    <!-- Fixed full-page video background -->
    <div class="fixed inset-0 z-0 overflow-hidden">
      <BackgroundVideo :src="homeVideoUrl" :poster="homeVideoPosterUrl" />
      <div class="absolute inset-0 bg-gray-950/50"></div>
    </div>

    <!-- Main content -->
    <div class="relative z-10 flex items-center justify-center min-h-screen -mt-20 pt-32 pb-20 px-4">
      <div class="max-w-5xl w-full grid grid-cols-1 md:grid-cols-5 gap-12 items-center">
        <!-- Left: Text area (60%) -->
        <div class="md:col-span-3">
          <h1 class="text-4xl sm:text-5xl font-extrabold text-white mb-6">加入我们</h1>
          <p class="text-xl font-semibold text-indigo-300 mb-4">欢迎加入独角兽工作室交流社群</p>
          <p class="text-gray-300 text-lg leading-relaxed mb-4">
            在这里我们分享前沿技术、实战项目与设计创意，共同成长、一起探索数字世界的更多可能。
          </p>
          <p class="text-gray-400 leading-relaxed">
            扫描下方二维码，即可加入社群与我们直接交流。
          </p>
        </div>

        <!-- Right: QR code area (40%) -->
        <div class="md:col-span-2 flex flex-col items-center">
          <div class="bg-white/10 border border-white/10 rounded-2xl p-6 backdrop-blur-sm shadow-lg">
            <img
              :src="qrUrl"
              alt="社群二维码"
              width="990"
              height="1258"
              class="w-72 h-72 object-contain rounded-lg"
            />
          </div>
          <p class="text-gray-400 text-sm mt-4">QQ扫码加入社群</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import BackgroundVideo from '@/components/BackgroundVideo.vue'
import { usePageMeta } from '@/composables/usePageMeta'
import homeVideoUrl from '@/image/home.mp4'
import homeVideoPosterUrl from '@/image/home-poster.jpg'
import qrUrl from '@/image/community-qr.jpg'

usePageMeta(() => ({
  title: '加入我们',
  description: '欢迎加入独角兽工作室交流社群，分享前沿技术、实战项目与设计创意。',
}))
</script>
