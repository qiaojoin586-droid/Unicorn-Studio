<template>
  <div class="image-upload">
    <el-upload
      :show-file-list="false"
      :http-request="doUpload"
      accept="image/jpeg,image/png,image/webp,image/gif"
      :before-upload="beforeUpload"
    >
      <div v-if="modelValue" class="preview">
        <img :src="modelValue" />
        <div class="actions">
          <span class="hint">点击更换图片</span>
          <el-button size="small" type="danger" text @click.stop="clear">移除</el-button>
        </div>
      </div>
      <div v-else class="placeholder">
        <div class="plus-icon">
          <el-icon><Plus /></el-icon>
        </div>
        <span>点击上传</span>
      </div>
    </el-upload>
  </div>
</template>

<script setup lang="ts">
import { fileApi } from '@/api/file'

const props = defineProps<{ modelValue?: string; maxSize?: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const maxSize = props.maxSize ?? 5 * 1024 * 1024

function beforeUpload(file: File) {
  if (file.size > maxSize) {
    ElMessage.error('图片不能超过 5MB')
    return false
  }
  return true
}

async function doUpload({ file }: { file: File }) {
  const result = await fileApi.upload(file)
  emit('update:modelValue', (result as any).url)
  ElMessage.success('上传成功')
}

function clear() {
  emit('update:modelValue', '')
}
</script>

<style scoped>
.image-upload {
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
}
.preview img {
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
  gap: 6px;
  color: #94a3b8;
  cursor: pointer;
  font-size: 12px;
  background: #fafbfd;
  transition: all 0.2s ease;
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
