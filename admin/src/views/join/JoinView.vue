<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">加入我们</span>
      <div class="config-actions"><span>{{ dirty ? '未保存修改' : '已加载配置' }}</span><el-button type="primary" v-permission="'join:edit'" :disabled="!ready || loading || !!loadError" :loading="saving" @click="save">保存并更新主站</el-button></div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false"><el-button link @click="load">重新加载</el-button></el-alert>
    <el-alert v-if="saveStatus" :title="saveStatus" :type="saveSucceeded ? 'success' : 'warning'" :closable="false" />
    <el-card shadow="never" v-loading="loading">
      <p class="page-tip">
        此处维护主站「加入我们」文案、二维码与同手机加入入口。保存即时生效，未保存预览不改变线上内容。
        正文段落之间用<b>空行</b>分隔。
      </p>

      <el-form :model="form" label-width="110px" class="join-form" :disabled="saving || !ready || !!loadError || !canEdit">
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
            <div class="field-tip">上传后请点击右上角「保存并更新主站」，新二维码才会替代内置图片。留空使用内置 QQ 二维码。自定义二维码加载失败时显示不可用提示，不回退到旧二维码或旧群号；请确认下方平台、群号与入口属于同一社群。</div>
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
      <el-divider content-position="left">社群与同手机加入</el-divider>
      <el-form :model="extra" label-position="top" class="join-form" :disabled="saving || !ready || !!loadError || !canEdit">
        <el-form-item label="社群平台"><el-input v-model="extra.platform" maxlength="40" placeholder="可选，例如 QQ、微信、Discord" /></el-form-item>
        <el-form-item label="群号 / 社群标识"><el-input v-model="extra.groupNumber" maxlength="40" placeholder="与当前二维码对应；可清空" /><p class="field-tip">只有使用内置默认二维码且此项为空时，主站沿用原群号 1107213369。自定义二维码且群号为空时隐藏群号；不会误配旧群。</p></el-form-item>
        <el-form-item label="同手机加入链接"><el-input v-model="extra.joinUrl" maxlength="2048" placeholder="https://…；清空则隐藏直达入口" /><p class="field-tip">仅支持站内路径、锚点或 HTTP(S) 地址，不支持自定义 qq:// 等协议。请使用平台提供的有效 HTTPS 加群链接。</p></el-form-item>
        <el-form-item label="手机加入说明"><el-input v-model="extra.phoneHelp" type="textarea" :rows="3" maxlength="1000" placeholder="可选，说明同一手机如何加入；清空后按当前二维码与可用入口显示适配说明" /></el-form-item>
        <el-form-item label="邀请文案" required><el-input v-model="extra.invitationText" type="textarea" :rows="3" maxlength="500" /></el-form-item>
        <p class="field-tip">文案中的换行会保留。主站联系方式来自页脚；若页脚启用「跟随全局联系方式」，以系统设置为准。基础内容与社群扩展配置独立保存，部分失败会明确提示。</p>
      </el-form>
    </el-card>
    <SitePreview class="config-preview" kind="join" :payload="{ join: previewJoin, presentation: { join: extra } }" public-path="/join" title="加入我们 · 未保存预览" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { joinApi, type JoinPageForm } from '@/api/join'
import ImageUpload from '@/components/ImageUpload.vue'
import SitePreview from '@/components/SitePreview.vue'
import { presentationApi, clonePresentation, describeSectionSave, isPresentationUrl, useConfigDraft } from '@/api/presentation'
import { useAuthStore } from '@/stores/auth'

const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const saveStatus = ref('')
const saveSucceeded = ref(false)
const extra = reactive(clonePresentation('join'))
const auth = useAuthStore()
const canEdit = computed(() => auth.hasPerm('join:edit'))

const form = reactive<JoinPageForm>({
  eyebrow: '',
  title: '',
  subtitle: '',
  content: '',
  qrImage: '',
  qrCaption: '',
})
const previewJoin = computed(() => ({ eyebrow: form.eyebrow, title: form.title, subtitle: form.subtitle, paragraphs: splitParagraphs(form.content), qrImage: form.qrImage, qrCaption: form.qrCaption }))
const { ready, dirty, markSaved } = useConfigDraft(() => ({ form, extra }))
let savedLegacy = ''
let savedExtra = ''

/** 多行文本 → 段落数组（与后端 JoinPageService 的空行分段口径一致） */
function splitParagraphs(text: string): string[] {
  return text
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .split(/\n\s*\n/)
    .map((p) => p.trim())
    .filter((p) => p.length > 0)
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [data, presentation] = await Promise.all([joinApi.get(), presentationApi.get('join')])
    Object.assign(form, {
      eyebrow: data?.eyebrow || '',
      title: data?.title || '',
      subtitle: data?.subtitle || '',
      content: (data?.paragraphs || []).join('\n\n'),
      qrImage: data?.qrImage || '',
      qrCaption: data?.qrCaption || '',
    })
    Object.assign(extra, clonePresentation('join'), presentation)
    savedLegacy = JSON.stringify(previewJoin.value)
    savedExtra = JSON.stringify(extra)
    markSaved()
  } catch {
    loadError.value = '配置加载失败，为避免覆盖线上社群信息，请重新加载后再保存。'
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function save() {
  if (saving.value || !ready.value || loadError.value || !canEdit.value) return
  if (!form.title.trim()) {
    ElMessage.warning('主标题不能为空')
    return
  }
  if (!extra.invitationText.trim()) { ElMessage.warning('邀请文案不能为空'); return }
  if (!isPresentationUrl(extra.joinUrl)) { ElMessage.warning('加入链接只支持站内路径、锚点或 HTTP(S) 地址'); return }
  saving.value = true
  try {
    const legacySnapshot = JSON.stringify(previewJoin.value)
    const extraSnapshot = JSON.stringify(extra)
    const results = await Promise.allSettled([
      legacySnapshot === savedLegacy ? Promise.resolve() : joinApi.update(JSON.parse(legacySnapshot)),
      extraSnapshot === savedExtra ? Promise.resolve() : presentationApi.update('join', JSON.parse(extraSnapshot)),
    ])
    if (results[0].status === 'fulfilled') savedLegacy = legacySnapshot
    if (results[1].status === 'fulfilled') savedExtra = extraSnapshot
    const outcome = describeSectionSave(results, ['页面文案与二维码', '社群与手机入口'])
    saveStatus.value = outcome.message
    saveSucceeded.value = outcome.success
    if (outcome.success) { markSaved(); ElMessage.success('加入页配置已保存') }
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.join-form {
  max-width: 720px;
}
.config-actions { display: flex; gap: 14px; align-items: center; }
.config-actions > span { font-size: 12px; color: var(--el-text-color-secondary); }
.config-preview { margin-top: 20px; }
.page-container > :deep(.el-alert) { margin-bottom: 16px; }
@media(max-width:640px) { .page-header, .config-actions { flex-wrap: wrap; gap: 12px; } .join-form :deep(.el-form-item) { display: block; } .join-form :deep(.el-form-item__label) { float: none; display: block; text-align: left; width: auto !important; } .join-form :deep(.el-form-item__content) { margin-left: 0 !important; } }
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
