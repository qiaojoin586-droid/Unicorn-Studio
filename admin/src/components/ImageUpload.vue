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
        <el-icon><Plus /></el-icon>
        <span>点击上传</span>
      </div>
    </el-upload>
  </div>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
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
  border-radius: 4px;
  overflow: hidden;
  border: 1px solid #dcdfe6;
  cursor: pointer;
}
.preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.preview .actions {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  background: rgba(0, 0, 0, 0.45);
  opacity: 0;
  transition: opacity 0.2s;
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
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
  cursor: pointer;
  font-size: 12px;
}
.placeholder:hover {
  border-color: #409eff;
  color: #409eff;
}
</style>
