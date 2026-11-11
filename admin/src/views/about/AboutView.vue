<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">关于我们</span>
      <div class="config-actions"><span>{{ dirty ? '未保存修改' : '已加载配置' }}</span><el-button type="primary" v-permission="'about:edit'" :disabled="!ready || loading || !!loadError" :loading="saving" @click="save">保存并更新主站</el-button></div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false"><el-button link @click="load">重新加载</el-button></el-alert>
    <el-alert v-if="saveStatus" :title="saveStatus" :type="saveSucceeded ? 'success' : 'warning'" :closable="false" />
    <el-card shadow="never" v-loading="loading">
      <p class="page-tip">
        此处维护主站「关于我们」的介绍、配图、整页背景、理念条目和加入邀请。桌面与手机预览随表单更新，点击保存后发布到主站。
        正文段落之间用<b>空行</b>分隔。
      </p>

      <el-form :model="form" label-width="110px" class="about-form" :disabled="saving || !ready || !!loadError || !canEdit">
        <el-form-item label="顶部小标签">
          <el-input v-model="form.eyebrow" maxlength="50" placeholder="About Us" />
        </el-form-item>

        <el-form-item label="主标题" required>
          <el-input v-model="form.title" maxlength="100" placeholder="关于独角兽工作室" />
        </el-form-item>

        <el-form-item label="副标题">
          <el-input v-model="form.subtitle" maxlength="200" />
        </el-form-item>

        <el-form-item label="正文">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="8"
            placeholder="段落之间用空行分隔，前台会渲染为多个段落"
          />
        </el-form-item>

        <el-form-item label="统计指标">
          <div class="stats-editor">
            <div v-for="(stat, idx) in form.stats" :key="idx" class="stat-row">
              <el-input v-model="stat.value" class="stat-value" maxlength="16" placeholder="数值，如 50+" />
              <el-input v-model="stat.label" class="stat-label" maxlength="20" placeholder="标签，如 完成项目" />
              <el-button
                link
                type="danger"
                v-permission="'about:edit'"
                @click="removeStat(idx)"
              >删除</el-button>
            </div>
            <p v-if="!form.stats.length" class="empty-hint">暂无统计指标（前台将不渲染该区域）</p>
            <el-button
              link
              type="primary"
              v-permission="'about:edit'"
              :disabled="form.stats.length >= MAX_STATS"
              @click="addStat"
            >+ 添加指标</el-button>
          </div>
        </el-form-item>

        <el-form-item label="介绍配图">
          <div>
            <ImageUpload v-model="form.imageUrl" />
            <div class="field-tip">主站介绍区域的配图；桌面当前位于左侧，手机按阅读顺序重排。留空使用默认 LOGO。</div>
          </div>
        </el-form-item>
      </el-form>
      <el-divider content-position="left">整页背景</el-divider>
      <el-form :model="extra" label-position="top" class="about-form" :disabled="saving || !ready || !!loadError || !canEdit">
        <p class="field-tip">四个区块共用一张背景。第二块的介绍配图仍在上方「介绍配图」中维护。</p>
        <WallpaperFields
          v-model:mode="extra.backgroundMode"
          v-model:image-url="extra.backgroundImageUrl"
          v-model:video-url="extra.backgroundVideoUrl"
        />
      </el-form>
      <el-divider content-position="left">理念与加入邀请</el-divider>
      <el-form :model="extra" label-position="top" class="about-form" :disabled="saving || !ready || !!loadError || !canEdit">
        <p class="field-tip">可选标签、说明与入口文字清空后隐藏；标题为必填。基础介绍与扩展配置由两个独立接口保存。</p>
        <el-form-item label="理念章节小标签"><el-input v-model="extra.principlesEyebrow" maxlength="120" /></el-form-item>
        <el-form-item label="理念章节标题" required><el-input v-model="extra.principlesTitle" type="textarea" :rows="2" maxlength="160" /></el-form-item>
        <div v-for="(item, index) in extra.principles" :key="index" class="principle-row">
          <el-form-item :label="'理念 ' + (index + 1) + ' 标题'" required><el-input v-model="item.title" maxlength="160" /></el-form-item>
          <el-form-item label="理念说明"><el-input v-model="item.description" type="textarea" :rows="2" maxlength="1200" /></el-form-item>
          <el-button type="danger" link @click="extra.principles.splice(index, 1)">删除此理念</el-button>
        </div>
        <el-button :disabled="extra.principles.length >= 8" @click="extra.principles.push({ title: '', description: '' })">添加理念（最多 8 条）</el-button>
        <el-divider />
        <el-form-item label="加入邀请标题" required><el-input v-model="extra.invitationTitle" type="textarea" :rows="2" maxlength="160" /></el-form-item>
        <el-form-item label="加入邀请说明"><el-input v-model="extra.invitationDescription" type="textarea" :rows="2" maxlength="2000" /></el-form-item>
        <el-form-item label="邀请入口文字"><el-input v-model="extra.invitationLabel" maxlength="80" /></el-form-item>
        <el-form-item label="邀请入口链接"><el-input v-model="extra.invitationUrl" maxlength="2048" placeholder="/join；清空隐藏入口" /></el-form-item>
      </el-form>
    </el-card>
    <SitePreview class="config-preview" kind="about" :payload="{ about: form, presentation: { about: extra } }" public-path="/about" title="关于我们 · 未保存预览" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { aboutApi, type AboutPage } from '@/api/about'
import ImageUpload from '@/components/ImageUpload.vue'
import SitePreview from '@/components/SitePreview.vue'
import WallpaperFields from '@/components/WallpaperFields.vue'
import { presentationApi, clonePresentation, describeSectionSave, isPresentationUrl, useConfigDraft, wallpaperError } from '@/api/presentation'
import { useAuthStore } from '@/stores/auth'

/** 与后端 AboutPageService.MAX_STATS 保持一致 */
const MAX_STATS = 8

const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const saveStatus = ref('')
const saveSucceeded = ref(false)
const extra = reactive(clonePresentation('about'))
const auth = useAuthStore()
const canEdit = computed(() => auth.hasPerm('about:edit'))

const form = reactive<AboutPage>({
  eyebrow: '',
  title: '',
  subtitle: '',
  content: '',
  imageUrl: '',
  stats: [],
})
const { ready, dirty, markSaved } = useConfigDraft(() => ({ form, extra }))
let savedLegacy = ''
let savedExtra = ''

function addStat() {
  if (form.stats.length >= MAX_STATS) return
  form.stats.push({ value: '', label: '' })
}

function removeStat(idx: number) {
  form.stats.splice(idx, 1)
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [data, presentation] = await Promise.all([aboutApi.get(), presentationApi.get('about')])
    Object.assign(form, {
      eyebrow: data?.eyebrow || '',
      title: data?.title || '',
      subtitle: data?.subtitle || '',
      content: data?.content || '',
      imageUrl: data?.imageUrl || '',
      stats: (data?.stats || []).map((s) => ({ value: s.value || '', label: s.label || '' })),
    })
    Object.assign(extra, clonePresentation('about'), presentation)
    savedLegacy = JSON.stringify(legacyPayload())
    savedExtra = JSON.stringify(extra)
    markSaved()
  } catch {
    loadError.value = '配置加载失败，为避免覆盖线上内容，请重新加载后再保存。'
  } finally {
    loading.value = false
  }
}
onMounted(load)

function legacyPayload(): AboutPage {
  return { ...form, stats: form.stats.filter((s) => s.value.trim() || s.label.trim()) }
}

async function save() {
  if (saving.value || !ready.value || loadError.value || !canEdit.value) return
  if (!form.title.trim()) {
    ElMessage.warning('主标题不能为空')
    return
  }
  if (!extra.principlesTitle.trim() || !extra.invitationTitle.trim() || extra.principles.some((item) => !item.title.trim())) { ElMessage.warning('请填写理念章节、各理念条目与加入邀请的标题'); return }
  if (!isPresentationUrl(extra.invitationUrl)) { ElMessage.warning('邀请链接只支持站内路径、锚点或 HTTP(S) 地址'); return }
  const backgroundError = wallpaperError(extra)
  if (backgroundError) { ElMessage.warning(backgroundError); return }
  const draft = JSON.parse(JSON.stringify({ form, extra }))
  saving.value = true
  try {
    const legacy = legacyPayload()
    const legacySnapshot = JSON.stringify(legacy)
    const extraSnapshot = JSON.stringify(extra)
    const results = await Promise.allSettled([
      legacySnapshot === savedLegacy ? Promise.resolve() : aboutApi.update(legacy),
      extraSnapshot === savedExtra ? Promise.resolve() : presentationApi.update('about', JSON.parse(extraSnapshot)),
    ])
    if (results[0].status === 'fulfilled') savedLegacy = legacySnapshot
    if (results[1].status === 'fulfilled') savedExtra = extraSnapshot
    const outcome = describeSectionSave(results, ['基础介绍', '背景、理念与邀请'])
    saveStatus.value = outcome.message
    saveSucceeded.value = outcome.success
    if (outcome.success) { markSaved(draft); ElMessage.success('关于页配置已保存') }
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.about-form {
  max-width: 720px;
}
.config-actions { display: flex; gap: 14px; align-items: center; }
.config-actions > span { font-size: 12px; color: var(--el-text-color-secondary); }
.config-preview { margin-top: 20px; }
.principle-row { border: 1px solid var(--el-border-color-lighter); padding: 18px; margin-bottom: 16px; border-radius: 6px; }
.page-container > :deep(.el-alert) { margin-bottom: 16px; }
@media(max-width:640px) { .page-header, .config-actions { flex-wrap: wrap; gap: 12px; } .stat-row { flex-wrap: wrap; } .stat-value, .stat-label { width: 100%; } .about-form :deep(.el-form-item) { display: block; } .about-form :deep(.el-form-item__label) { float: none; display: block; text-align: left; width: auto !important; } .about-form :deep(.el-form-item__content) { margin-left: 0 !important; } }
.page-tip {
  margin: 0 0 18px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.stats-editor {
  width: 100%;
}
.stat-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.stat-value {
  width: 140px;
}
.stat-label {
  width: 240px;
}
.empty-hint {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.field-tip {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
