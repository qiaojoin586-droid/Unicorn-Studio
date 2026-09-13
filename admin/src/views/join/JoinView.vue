<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">加入我们</span>
      <el-button type="primary" v-permission="'join:edit'" :loading="saving" @click="save">保存</el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <p class="page-tip">
        此处维护前台「加入我们」页面的全部文案与社群二维码，保存后前台刷新即可看到最新内容。
        正文段落之间用<b>空行</b>分隔。
      </p>

      <el-form :model="form" label-width="110px" class="join-form">
        <el-form-item label="顶部小标题">
          <el-input v-model="form.eyebrow" maxlength="50" placeholder="留空则不显示" />
        </el-form-item>

        <el-form-item label="主标题" required>
          <el-input v-model="form.title" maxlength="100" placeholder="加入我们" />
        </el-form-item>

        <el-form-item label="副标题">
          <el-input v-model="form.subtitle" maxlength="200" placeholder="欢迎加入独角兽工作室交流社群" />
        </el-form-item>

        <el-form-item label="正文">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="8"
            placeholder="段落之间用空行分隔，前台会渲染为多个段落"
          />
        </el-form-item>

        <el-form-item label="社群二维码">
          <div class="qr-field">
            <ImageUpload v-model="form.qrImage" />
            <div class="field-tip">留空则使用站点内置的默认二维码图片</div>
            <div v-if="form.qrImage" class="qr-preview">
              <span class="qr-preview-label">当前预览</span>
              <img :src="form.qrImage" alt="社群二维码预览" />
            </div>
          </div>
        </el-form-item>

        <el-form-item label="二维码说明">
          <el-input v-model="form.qrCaption" maxlength="50" placeholder="QQ扫码加入社群" />
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { joinApi, type JoinPageForm } from '@/api/join'
import ImageUpload from '@/components/ImageUpload.vue'

const loading = ref(false)
const saving = ref(false)

const form = reactive<JoinPageForm>({
  eyebrow: '',
  title: '',
  subtitle: '',
  content: '',
  qrImage: '',
  qrCaption: '',
})

/** 多行文本 → 段落数组（与后端 JoinPageService 的空行分段口径一致） */
function splitParagraphs(text: string): string[] {
  return text
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .split(/\n\s*\n/)
    .map((p) => p.trim())
    .filter((p) => p.length > 0)
}

onMounted(async () => {
  loading.value = true
  try {
    const data = await joinApi.get()
    Object.assign(form, {
      eyebrow: data?.eyebrow || '',
      title: data?.title || '',
      subtitle: data?.subtitle || '',
      content: (data?.paragraphs || []).join('\n\n'),
      qrImage: data?.qrImage || '',
      qrCaption: data?.qrCaption || '',
    })
  } finally {
    loading.value = false
  }
})

async function save() {
  if (!form.title.trim()) {
    ElMessage.warning('主标题不能为空')
    return
  }
  saving.value = true
  try {
    await joinApi.update({
      eyebrow: form.eyebrow,
      title: form.title.trim(),
      subtitle: form.subtitle,
      paragraphs: splitParagraphs(form.content),
      qrImage: form.qrImage,
      qrCaption: form.qrCaption,
    })
    ElMessage.success('保存成功，前台刷新后生效')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.join-form {
  max-width: 720px;
}
.page-tip {
  margin: 0 0 18px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.qr-field {
  width: 100%;
}
.field-tip {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.qr-preview {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.qr-preview-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.qr-preview img {
  width: 120px;
  height: 120px;
  object-fit: contain;
  border-radius: 8px;
  border: 1px solid var(--el-border-color-lighter);
  background: #fff;
}
</style>
