<template>
  <div class="page-container home-config">
    <div class="page-header">
      <h1 class="page-title">首页配置</h1>
      <div class="config-actions"><span class="draft-state">{{ dirty ? '未保存修改' : '已加载配置' }}</span><el-button type="primary" v-permission="'carousel:edit'" :loading="saving" :disabled="loading || !ready || !!loadError" @click="save">保存并更新主站</el-button></div>
    </div>
    <el-alert title="这里维护首页文案与展示规则；保存即时生效。右侧预览使用当前未保存表单，不会更改线上内容。" type="info" :closable="false" show-icon />
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="config-alert"><el-button link @click="load">重新加载配置</el-button></el-alert>
    <el-alert v-if="saveStatus" :title="saveStatus" :type="dirty ? 'warning' : 'success'" :closable="false" class="config-alert" />
    <div class="config-workspace">
      <el-card shadow="never" v-loading="loading">
        <el-form :model="form" label-position="top" :disabled="saving || !canEdit || !ready || !!loadError" @submit.prevent="save">
          <section v-for="group in fieldGroups" :key="group.id" :id="'config-' + group.id" class="config-section">
            <h2>{{ group.title }}</h2><p class="field-tip">{{ group.tip }}</p>
            <el-form-item v-for="field in group.fields" :key="field.key" :label="field.label" :required="field.required">
              <el-input v-model="form[field.key]" :type="field.multiline ? 'textarea' : 'text'" :rows="field.multiline ? 3 : undefined" :maxlength="fieldMaxlength(field.key)" :placeholder="field.required ? '必填' : '可选，清空后隐藏对应文案或入口'" />
            </el-form-item>
            <template v-if="group.id === 'hero'">
              <el-form-item label="首屏媒体模式"><el-radio-group v-model="form.mediaMode"><el-radio-button value="single">精选单图</el-radio-button><el-radio-button value="carousel">可控轮播</el-radio-button></el-radio-group></el-form-item>
              <p class="field-tip">单图使用第一张已启用首页媒体；轮播展示全部有效媒体，提供前后切换，不自动播放。图片、标题、副标题和跳转链接在「首页媒体」维护。</p>
              <router-link to="/carousel" class="config-manage-link">管理首页媒体与页面背景 →</router-link>
            </template>
            <template v-if="group.id === 'works'">
              <el-form-item label="首页精选展示数量"><el-input-number v-if="ready" v-model="form.featuredLimit" :min="1" :max="6" /></el-form-item>
              <p class="field-tip">从已发布且标记为首页推荐的作品中，按公开接口的排序依次展示；超出数量的推荐作品保留为候选。</p>
              <router-link to="/works" class="config-manage-link">管理作品与推荐候选 →</router-link>
            </template>
            <router-link v-if="group.id === 'capabilities'" to="/capabilities" class="config-manage-link">管理能力条目与图标 →</router-link>
            <router-link v-if="group.id === 'directions'" to="/services" class="config-manage-link">管理技术方向与图标 →</router-link>
          </section>
        </el-form>
      </el-card>
      <aside class="config-preview"><SitePreview kind="home" :payload="{ presentation: { home: form } }" public-path="/" title="首页 · 未保存预览" /></aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { presentationApi, clonePresentation, isPresentationUrl, useConfigDraft, type HomePresentation } from '@/api/presentation'
import { useAuthStore } from '@/stores/auth'
import SitePreview from '@/components/SitePreview.vue'
const auth = useAuthStore()
const canEdit = computed(() => auth.hasPerm('carousel:edit'))
const form = reactive<HomePresentation>(clonePresentation('home'))
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const saveStatus = ref('')
const { ready, dirty, markSaved } = useConfigDraft(() => form)
type TextKey = Exclude<keyof HomePresentation, 'mediaMode' | 'featuredLimit'>
type TextField = { key: TextKey; label: string; required?: boolean; multiline?: boolean }
function fieldMaxlength(key: TextKey) {
  const name = key.toLowerCase()
  if (name.endsWith('url')) return 2048
  if (name.endsWith('eyebrow')) return 120
  if (name.endsWith('description')) return 2000
  if (name.endsWith('label')) return 80
  return 160
}
const fieldGroups: { id: string; title: string; tip: string; fields: TextField[] }[] = [
  { id: 'hero', title: '01 / 首屏', tip: '主标题中的换行会保留。入口文字或链接为空时隐藏对应入口；可选说明清空后隐藏。', fields: [
    { key: 'eyebrow', label: '顶部小标签' }, { key: 'title', label: '主标题', required: true, multiline: true }, { key: 'description', label: '首屏说明', multiline: true },
    { key: 'primaryLabel', label: '主入口文字' }, { key: 'primaryUrl', label: '主入口链接' }, { key: 'secondaryLabel', label: '次入口文字' }, { key: 'secondaryUrl', label: '次入口链接' },
  ] },
  { id: 'works', title: '02 / 精选作品', tip: '维护作品画廊的章节文案，具体作品与推荐位置在作品管理中维护。', fields: [
    { key: 'worksEyebrow', label: '章节小标签' }, { key: 'worksTitle', label: '章节标题', required: true, multiline: true }, { key: 'worksDescription', label: '章节说明', multiline: true },
  ] },
  { id: 'capabilities', title: '03 / 成长方式', tip: '能力条目的编号按当前展示顺序生成，图标与内容使用已启用记录。', fields: [
    { key: 'capabilitiesEyebrow', label: '章节小标签' }, { key: 'capabilitiesTitle', label: '章节标题', required: true, multiline: true }, { key: 'capabilitiesDescription', label: '章节说明', multiline: true },
  ] },
  { id: 'directions', title: '04 / 技术方向', tip: '维护招收与探索方向章节的标题和导语。', fields: [
    { key: 'directionsEyebrow', label: '章节小标签' }, { key: 'directionsTitle', label: '章节标题', required: true, multiline: true }, { key: 'directionsDescription', label: '章节说明', multiline: true },
  ] },
  { id: 'invitation', title: '05 / 加入邀请', tip: '这是首页结尾的邀请区域，独立于加入页面的正文。', fields: [
    { key: 'invitationEyebrow', label: '邀请小标签' }, { key: 'invitationTitle', label: '邀请标题', required: true, multiline: true }, { key: 'invitationDescription', label: '邀请说明', multiline: true }, { key: 'invitationLabel', label: '入口文字' }, { key: 'invitationUrl', label: '入口链接' },
  ] },
]
async function load() {
  loading.value = true
  loadError.value = ''
  try { Object.assign(form, clonePresentation('home'), await presentationApi.get('home')); markSaved() }
  catch { loadError.value = '首页配置加载失败。为避免覆盖线上配置，请重新加载后再保存。' }
  finally { loading.value = false }
}
async function save() {
  if (saving.value || !ready.value || loadError.value || !canEdit.value) return
  for (const group of fieldGroups) for (const field of group.fields) {
    if (field.required && !String(form[field.key]).trim()) { ElMessage.warning(group.title + '：' + field.label + '不能为空'); return }
    if (field.key.toLowerCase().includes('url') && !isPresentationUrl(String(form[field.key]))) { ElMessage.warning(field.label + '只支持站内路径、锚点或 HTTP(S) 地址'); return }
  }
  saving.value = true
  saveStatus.value = ''
  try { await presentationApi.update('home', form); markSaved(); saveStatus.value = '首页配置已保存并更新主站，请刷新线上页面核验。'; ElMessage.success('首页配置已保存') }
  finally { saving.value = false }
}
onMounted(load)
</script>

<style scoped>
.config-actions { display: flex; align-items: center; gap: 16px; }
.draft-state { font-size: 12px; color: var(--el-text-color-secondary); }
.config-alert { margin-top: 16px; }
.config-workspace { display: grid; grid-template-columns: minmax(0, 1fr) minmax(360px, .85fr); gap: 20px; margin-top: 20px; align-items: start; }
.config-section + .config-section { border-top: 1px solid var(--el-border-color-lighter); margin-top: 32px; padding-top: 28px; }
.config-section h2 { font-size: 17px; margin: 0 0 12px; }
.field-tip { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.8; margin: 4px 0 18px; }
.config-manage-link { display: inline-flex; min-height: 40px; align-items: center; color: var(--el-color-primary); font-size: 13px; }
.config-preview { position: sticky; top: 20px; min-width: 0; }
@media (max-width: 1150px) { .config-workspace { grid-template-columns: 1fr; } .config-preview { position: static; } }
@media (max-width: 640px) { .config-actions { width: 100%; flex-wrap: wrap; justify-content: space-between; gap: 8px; } .page-header { flex-wrap: wrap; gap: 14px; } }
</style>

