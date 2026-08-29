<template>
  <div class="file-upload">
    <el-upload
      :show-file-list="false"
      :http-request="doUpload"
      :before-upload="beforeUpload"
    >
      <div v-if="modelValue" class="file-chip">
        <el-icon class="file-icon"><Document /></el-icon>
        <span class="file-name" :title="modelValue">{{ fileName }}</span>
        <el-button size="small" type="danger" text @click.stop="clear">移除</el-button>
      </div>
      <el-button v-else :loading="uploading">
        <el-icon><UploadFilled /></el-icon>&nbsp;上传附件
      </el-button>
    </el-upload>
    <div class="tip">支持 pdf / zip / office / txt / md，不超过 {{ maxMb }}MB</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fileApi } from '@/api/file'

const props = defineProps<{ modelValue?: string; maxSize?: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const uploading = ref(false)
const maxSize = props.maxSize ?? 10 * 1024 * 1024
const maxMb = Math.round(maxSize / 1024 / 1024)

const fileName = computed(() => {
  if (!props.modelValue) return ''
  const parts = props.modelValue.split('/')
  return parts[parts.length - 1] || props.modelValue
})

function beforeUpload(file: File) {
  if (file.size > maxSize) {
    ElMessage.error(`附件不能超过 ${maxMb}MB`)
    return false
  }
  return true
}

async function doUpload({ file }: { file: File }) {
  uploading.value = true
  try {
    const result = await fileApi.upload(file)
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
.file-upload {
  display: inline-block;
}
.file-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #fafbfd;
}
.file-icon {
  color: var(--el-color-primary);
}
.file-name {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: #475569;
}
.tip {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
}
</style>
