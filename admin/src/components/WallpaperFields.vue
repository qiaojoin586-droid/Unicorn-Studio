<template>
  <el-form-item label="背景类型">
    <el-select v-model="mode" :empty-values="[null, undefined]">
      <el-option label="默认动态壁纸" value="" />
      <el-option label="自定义静态图片" value="image" />
      <el-option label="自定义动态视频" value="video" />
    </el-select>
  </el-form-item>
  <p class="wallpaper-tip">手机只显示封面。桌面按设备的省流量和减少动态效果设置决定是否播放视频。</p>
  <el-form-item v-if="mode" :label="mode === 'video' ? '视频封面' : '背景图片'" required>
    <ImageUpload v-model="imageUrl" />
  </el-form-item>
  <el-form-item v-if="mode === 'video'" label="背景视频" required>
    <VideoUpload v-model="videoUrl" />
  </el-form-item>
</template>

<script setup lang="ts">
import ImageUpload from './ImageUpload.vue'
import VideoUpload from './VideoUpload.vue'
import type { PresentationBackground } from '@/api/presentation'

const mode = defineModel<PresentationBackground['backgroundMode']>('mode', { required: true })
const imageUrl = defineModel<string>('imageUrl', { required: true })
const videoUrl = defineModel<string>('videoUrl', { required: true })
</script>

<style scoped>
.wallpaper-tip { margin: 0 0 18px; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
</style>
