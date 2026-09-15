<template>
  <div class="video-upload">
    <el-upload
      :show-file-list="false"
      :http-request="doUpload"
      accept="video/mp4,video/webm"
      :before-upload="beforeUpload"
    >
      <div v-if="modelValue" class="preview">
        <video :src="modelValue" muted playsinline preload="metadata" />
        <div class="actions">
          <span class="hint">点击更换视频</span>
          <el-button size="small" type="danger" text @click.stop="clear">移除</el-button>
        </div>
      </div>
      <div v-else class="placeholder">
        <div class="plus-icon">
          <el-icon><Plus /></el-icon>
        </div>
        <span>{{ uploading ? `上传中 ${percent}%` : '点击上传视频' }}</span>
        <span class="sub">MP4 / WebM</span>
      </div>
    </el-upload>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { fileApi } from '@/api/file'

const props = defineProps<{ modelValue?: string; maxSize?: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

// 与后端 app.upload.video-max-size 对齐（50MB）
const maxSize = props.maxSize ?? 50 * 1024 * 1024
const uploading = ref(false)
const percent = ref(0)

function beforeUpload(file: File) {
  if (!/\.(mp4|webm)$/i.test(file.name)) {
    ElMessage.error('仅支持 MP4 / WebM 视频')
    return false
  }
  if (file.size > maxSize) {
    ElMessage.error(`视频不能超过 ${Math.round(maxSize / 1024 / 1024)}MB`)
    return false
  }
  return true
}

async function doUpload({ file }: { file: File }) {
  uploading.value = true
  percent.value = 0
  try {
    const result = await fileApi.upload(file, (p) => {
      percent.value = p
    })
    emit('update:modelValue', (result as any).url)
    ElMessage.success('上传成功')
  } finally {
    uploading.value = false
  }
}

function clear() {
  emit('update:modelValue', '')
}
</script>

<style scoped>
.video-upload {
  display: inline-block;
}
.preview {
  position: relative;
  width: 160px;
  height: 90px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.06);
  cursor: pointer;
  background: #0f172a;
}
.preview video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.preview .actions {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  background: rgba(13, 18, 32, 0.55);
  backdrop-filter: blur(2px);
  opacity: 0;
  transition: opacity 0.2s ease;
}
.preview:hover .actions {
  opacity: 1;
}
.hint {
  color: #fff;
  font-size: 12px;
}
.placeholder {
  width: 160px;
  height: 90px;
  border: 1.5px dashed var(--el-border-color);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: #94a3b8;
  cursor: pointer;
  font-size: 12px;
  background: #fafbfd;
  transition: all 0.2s ease;
}
.placeholder .sub {
  font-size: 11px;
  color: #b4b2a9;
}
.plus-icon {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--el-color-primary) 10%, white);
  color: var(--el-color-primary);
}
.placeholder:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 3%, white);
}
</style>
