<template>
  <div class="avatar-settings">
    <el-radio-group :model-value="mode" :disabled="uploading" aria-label="助手消息头像来源" @update:model-value="value => emit('update:mode', value as 'default' | 'custom')">
      <el-radio value="default">内置默认头像</el-radio>
      <el-radio value="custom">自定义头像</el-radio>
    </el-radio-group>
    <div class="avatar-preview-row">
      <div class="avatar-preview" data-message-avatar-preview>
        <img :src="previewUrl" :alt="mode === 'custom' && url && !previewFailed ? '自定义助手消息头像预览' : '主页独角兽工作室默认图标'" @error="previewFailed = true" />
      </div>
      <div class="avatar-description">
        <strong>{{ mode === 'custom' && url ? '自定义头像' : '主页独角兽工作室图标' }}</strong>
        <p>仅影响助手消息头像，悬浮入口和对话框顶部保持原样。</p>
      </div>
    </div>
    <div v-if="mode === 'custom'" class="avatar-actions">
      <el-upload :show-file-list="false" :http-request="uploadAvatar" :before-upload="beforeUpload" :disabled="uploading" accept="image/jpeg,image/png,image/webp,image/gif">
        <el-button :loading="uploading">{{ url ? '更换图片' : '上传图片' }}</el-button>
      </el-upload>
      <el-button :disabled="uploading" @click="emit('update:mode', 'default')">恢复默认</el-button>
    </div>
    <p class="avatar-hint">支持 JPG、PNG、WebP、GIF，最大 5MB。上传或切换后点击「保存配置」。未上传或图片无法加载时使用默认头像。</p>
    <p v-if="uploadError" role="alert" class="avatar-error">{{ uploadError }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { UploadRawFile, UploadRequestOptions } from 'element-plus'
import { aiApi } from '@/api/ai'
import defaultAvatarUrl from '../../../shared/brand-mark.svg?url'

const props = withDefaults(defineProps<{ mode?: 'default' | 'custom'; url?: string }>(), { mode: 'default', url: '' })
const emit = defineEmits<{ 'update:mode': [value: 'default' | 'custom']; 'update:url': [value: string]; 'uploading-change': [value: boolean] }>()
const uploading = ref(false)
const uploadError = ref('')
const previewFailed = ref(false)
const previewUrl = computed(() => props.mode === 'custom' && props.url && !previewFailed.value ? props.url : defaultAvatarUrl)
watch(() => [props.mode, props.url], () => { previewFailed.value = false; uploadError.value = '' })

function beforeUpload(file: UploadRawFile) {
  uploadError.value = ''
  if (!['image/jpeg', 'image/png', 'image/webp', 'image/gif'].includes(file.type) || file.size > 5 * 1024 * 1024) {
    uploadError.value = '请上传不超过 5MB 的 JPG、PNG、WebP 或 GIF 图片'
    return false
  }
  return true
}

async function uploadAvatar({ file }: UploadRequestOptions) {
  uploading.value = true
  emit('uploading-change', true)
  uploadError.value = ''
  try {
    const result = await aiApi.uploadMessageAvatar(file)
    emit('update:url', result.url)
    emit('update:mode', 'custom')
  } catch {
    uploadError.value = '头像上传失败，请重试；原头像仍然保留'
  } finally {
    uploading.value = false
    emit('uploading-change', false)
  }
}
</script>

<style scoped>
.avatar-settings { display: grid; gap: 14px; }
.avatar-preview-row { display: flex; align-items: center; gap: 14px; }
.avatar-preview { width: 64px; height: 64px; flex-shrink: 0; border-radius: 50%; overflow: hidden; display: grid; place-items: center; background: var(--el-fill-color-light); border: 1px solid var(--el-border-color); }
.avatar-preview img { width: 100%; height: 100%; object-fit: cover; }
.avatar-description { min-width: 0; font-size: 13px; color: var(--el-text-color-primary); }
.avatar-description p, .avatar-hint { margin: 4px 0 0; font-size: 12px; line-height: 1.6; color: var(--el-text-color-secondary); }
.avatar-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.avatar-actions :deep(.el-button) { min-height: 40px; margin-left: 0; }
.avatar-error { margin: 0; font-size: 13px; color: var(--el-color-danger); }
</style>
