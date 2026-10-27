<template>
  <div class="page-container">
    <div class="page-header">
      <div><span class="page-title">{{ isEdit ? '编辑作品' : '新增作品' }}</span><p class="page-description">编辑内容与主站真实模板同步预览，保存后按发布状态生效。</p></div>
      <div class="edit-header-actions"><span class="save-state" role="status">{{ saving ? '正在保存…' : saveError ? '保存失败，修改已保留' : dirty ? '有未保存修改' : savedMessage || '内容已同步' }}</span><el-button @click="$router.back()">返回</el-button><el-button type="primary" :loading="saving" :disabled="loading" @click="save">{{ isEdit ? '保存修改' : '创建作品' }}</el-button></div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="work-load-error" />
    <div class="work-edit-layout">
    <el-card shadow="never" v-loading="loading">
      <el-form :model="form" label-width="90px" class="work-form">
        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="标题" required>
              <el-input v-model="form.title" placeholder="作品标题" maxlength="200" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Slug" required>
              <el-input v-model="form.slug" placeholder="URL 标识，如 acm-icpc-gold" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="分类">
              <el-select v-model="form.category" clearable placeholder="决定前台卡片徽章" style="width: 100%">
                <el-option v-for="c in WORK_CATEGORIES" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="排序值">
              <el-input-number v-model="form.sortOrder" :min="0" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width: 100%">
                <el-option label="草稿" value="draft" />
                <el-option label="发布" value="published" />
                <el-option label="下线" value="offline" />
              </el-select>
            </el-form-item>
          </el-col>
          <!-- 卡片字段：直接决定前台作品卡片的展示（日期与作者）。
               两者原先都没有入口，新建作品落库恒为 NULL，前台卡片上表现为
               「日期不显示、作者一片空白」，故一并补齐。 -->
          <el-col :span="12">
            <el-form-item label="卡片日期">
              <el-input v-model="form.dateLabel" placeholder="前台卡片上展示的日期，如 2026-03" maxlength="20" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="作者">
              <el-input v-model="form.authors" placeholder="前台卡片上展示，如 张三、李四（竞赛填参赛人员）" maxlength="100" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="委托方">
              <div class="internal-field"><el-input v-model="form.clientName" placeholder="客户/委托方名称（可选）" /><p class="field-note">仅内部使用，不会传给主站或预览。</p></div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="外链">
              <el-input v-model="form.externalLink" placeholder="https://…（可选）" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="摘要">
              <el-input v-model="form.summary" type="textarea" :rows="2" maxlength="500" show-word-limit />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="封面图">
              <ImageUpload v-model="form.coverUrl" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="正文">
              <Editor v-model="form.content" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">SEO 设置</el-divider>
        <el-row :gutter="24">
          <el-col :span="8">
            <el-form-item label="SEO 标题">
              <el-input v-model="form.seo.title" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="SEO 描述">
              <el-input v-model="form.seo.description" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="关键词">
              <el-input v-model="form.seo.keywords" placeholder="逗号分隔" />
            </el-form-item>
          </el-col>
        </el-row>

        <div class="form-actions">
          <el-checkbox v-model="form.featured">首页推荐</el-checkbox>
          <el-button type="primary" :loading="saving" :disabled="loading || !!loadError" @click="save">{{ isEdit ? '保存修改' : '创建作品' }}</el-button>
        </div>
        <p v-if="saveError" class="save-error" role="alert">{{ saveError }}</p>
      </el-form>
    </el-card>
    <aside class="work-preview-panel">
      <section class="placement-panel" aria-labelledby="placement-title"><div class="placement-heading"><h2 id="placement-title">首页展示位置</h2><button type="button" :disabled="slotsLoading" @click="loadSlots">{{ slotsLoading ? '核验中…' : '刷新核验' }}</button></div><p class="placement-current">{{ currentPlacement }}</p><p>{{ plannedPlacement }}</p><p class="placement-footnote">首页当前展示 {{ featuredLimit }} 件，推荐标记不会因超额而自动取消。{{ usingDefaultLimit ? '展示数量暂按共享默认值，公开配置恢复后将重新核验。' : '展示数量来自主站公开配置。' }}</p></section>
      <SitePreview kind="work" :payload="{ work: publicWork }" :public-path="savedPublicPath" title="作品 · 主站真实模板" />
    </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { workApi } from '@/api/work'
import { WORK_CATEGORIES } from '@/constants/workOptions'
import Editor from '@/components/Editor.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import SitePreview from '@/components/SitePreview.vue'
import { publicSiteUrl } from '@/utils/site'
import presentationDefaults from '@/generated/presentation-defaults.json'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)

const loading = ref(false)
const saving = ref(false)
const saveError = ref('')
const loadError = ref('')
const savedMessage = ref('')
const savedSnapshot = ref('')
const savedWork = ref<any>(null)
const publicCandidates = ref<any[]>([])
const featuredLimit = ref(presentationDefaults.home.featuredLimit)
const slotsLoading = ref(false)
const slotsError = ref('')
const usingDefaultLimit = ref(true)
const form = reactive<any>({
  title: '',
  slug: '',
  category: '',
  sortOrder: 0,
  status: 'draft',
  clientName: '',
  externalLink: '',
  summary: '',
  coverUrl: '',
  content: '',
  dateLabel: '',
  authors: '',
  featured: false,
  seo: { title: '', description: '', keywords: '' },
})
savedSnapshot.value = JSON.stringify(form)
const dirty = computed(() => JSON.stringify(form) !== savedSnapshot.value)
const categoryTags: Record<string, string> = { '竞赛': 'award', '论文': 'paper', '项目': 'project', '其他': 'other' }
// Whitelist public fields; clientName, SEO and internal workflow data never leave this editor.
const publicWork = computed(() => ({ title: form.title, slug: form.slug, summary: form.summary, category: form.category, coverUrl: form.coverUrl, content: form.content, tagType: categoryTags[form.category] || '', authors: form.authors, dateLabel: form.dateLabel, externalLink: form.externalLink }))
const savedPublicPath = computed(() => savedWork.value?.status === 'published' && savedWork.value?.slug ? '/works/' + encodeURIComponent(savedWork.value.slug) : '/works')
const currentPlacement = computed(() => {
  if (!savedWork.value) return '新作品尚未保存，没有当前线上位置。'
  if (savedWork.value.featured !== 1) return '当前线上：未加入首页推荐。'
  if (savedWork.value.status !== 'published') return '当前线上：' + (savedWork.value.status === 'offline' ? '已下线' : '草稿') + '，推荐标记保留为候选。'
  if (slotsLoading.value) return '正在读取主站当前推荐位置…'
  if (slotsError.value) return slotsError.value
  const index = publicCandidates.value.findIndex(work => Number(work.id) === Number(savedWork.value.id))
  if (index < 0) return '当前公开推荐列表暂未包含此作品，请保存后刷新核验。'
  if (usingDefaultLimit.value) return '当前公开推荐排序第 ' + (index + 1) + ' 位，首页实际展示数量待核验。'
  return index < featuredLimit.value ? '当前线上：首页第 ' + (index + 1) + ' 位。' : '当前线上：推荐候选，公开排序第 ' + (index + 1) + ' 位，超出展示数量。'
})
const plannedPlacement = computed(() => !form.featured ? '当前表单：未勾选首页推荐。' : form.status !== 'published' ? '当前表单：保存为' + (form.status === 'offline' ? '下线' : '草稿') + '状态，不在主站展示。' : '当前表单：已加入推荐候选；保存后按排序重新计算，不预估未保存内容的精确位置。')
async function readPublic(path: string) {
  const response = await fetch(publicSiteUrl(path), { credentials: 'omit', headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(12000) })
  if (!response.ok) throw new Error('公开内容暂时无法读取')
  const body = await response.json()
  if (body?.code != null && body.code !== 200) throw new Error(body.message || '公开内容暂时无法读取')
  return body?.data ?? body
}
async function loadSlots() {
  if (slotsLoading.value) return
  slotsLoading.value = true
  slotsError.value = ''
  const results = await Promise.allSettled([readPublic('/api/works?featured=true'), readPublic('/api/site/presentation')])
  if (results[0].status === 'fulfilled' && Array.isArray(results[0].value?.list)) publicCandidates.value = results[0].value.list
  else slotsError.value = '当前线上位置未能核验，请稍后刷新。'
  if (results[1].status === 'fulfilled' && Number.isInteger(results[1].value?.home?.featuredLimit)) { featuredLimit.value = results[1].value.home.featuredLimit; usingDefaultLimit.value = false }
  else usingDefaultLimit.value = true
  slotsLoading.value = false
}

onMounted(async () => {
  loadSlots()
  if (!isEdit.value) return
  loading.value = true
  try {
    const data = await workApi.detail(Number(route.params.id))
    const work = data.work
    Object.assign(form, {
      title: work.title,
      slug: work.slug,
      category: work.category || '',
      sortOrder: work.sortOrder ?? 0,
      status: work.status || 'draft',
      clientName: work.clientName || '',
      externalLink: work.externalLink || '',
      summary: work.summary || '',
      coverUrl: work.coverUrl || '',
      content: work.content || '',
      dateLabel: work.dateLabel || '',
      authors: work.authors || '',
      featured: work.featured === 1,
      seo: data.seo || { title: '', description: '', keywords: '' },
    })
    savedWork.value = { ...work }
    savedSnapshot.value = JSON.stringify(form)
  } catch (error: any) {
    loadError.value = error?.message || '作品未能加载，请返回列表后重新打开。'
  } finally {
    loading.value = false
  }
})

async function save() {
  if (saving.value || loading.value || loadError.value) return
  if (!form.title?.trim() || !form.slug?.trim()) {
    ElMessage.warning('标题与 Slug 为必填项')
    return
  }
  saving.value = true
  saveError.value = ''
  const submitted = JSON.parse(JSON.stringify(form))
  try {
    if (isEdit.value) {
      await workApi.update(Number(route.params.id), submitted)
      savedWork.value = { ...submitted, id: Number(route.params.id), featured: submitted.featured ? 1 : 0 }
      savedSnapshot.value = JSON.stringify(submitted)
      savedMessage.value = '保存成功'
      loadSlots()
      ElMessage.success('保存成功')
    } else {
      const created = await workApi.create(submitted)
      savedWork.value = { ...submitted, ...created, featured: submitted.featured ? 1 : 0 }
      savedSnapshot.value = JSON.stringify(submitted)
      ElMessage.success('创建成功')
      if (dirty.value && created?.id) router.replace('/works/edit/' + created.id)
      else router.push('/works')
    }
  } catch (error: any) {
    saveError.value = error?.message || '保存失败，当前修改已保留，请稍后重试。'
  } finally {
    saving.value = false
  }
}
onBeforeRouteLeave(async () => {
  if (!dirty.value || loading.value) return true
  try { await ElMessageBox.confirm('当前作品有未保存修改，离开会丢弃本次编辑。', '离开编辑页', { confirmButtonText: '离开并放弃', cancelButtonText: '继续编辑', type: 'warning' }); return true } catch { return false }
})
function onBeforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => window.addEventListener('beforeunload', onBeforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))
</script>

<style scoped>
.work-form {
  max-width: 1100px;
}
.work-edit-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(330px, .64fr); gap: 22px; align-items: start; }
.work-preview-panel { min-width: 0; position: sticky; top: 20px; display: grid; gap: 18px; }
.edit-header-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.save-state { font-size: 12px; color: var(--admin-muted); margin-right: 5px; }
.internal-field { width: 100%; }
.work-load-error { margin-bottom: 18px; }
.save-error { margin-top: 14px; font-size: 13px; color: var(--el-color-danger); }
.placement-panel { padding: 18px; border: 1px solid var(--el-border-color); background: #fff; border-radius: 6px; }
.placement-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.placement-heading h2 { font-size: 14px; font-weight: 600; }
.placement-heading button { border: 0; background: transparent; color: var(--el-color-primary); font-size: 12px; min-height: 32px; cursor: pointer; }
.placement-panel > p { font-size: 12px; color: var(--admin-muted); line-height: 1.8; margin-top: 10px; }
.placement-panel .placement-current { font-size: 13px; color: var(--admin-ink); }
.placement-panel .placement-footnote { padding-top: 12px; border-top: 1px solid var(--el-border-color-lighter); font-size: 11px; }
@media (max-width: 1250px) { .work-edit-layout { grid-template-columns: minmax(0, 1fr); } .work-preview-panel { position: static; } }
@media (max-width: 767px) { .work-form :deep(.el-col) { flex: 0 0 100%; max-width: 100%; } .work-form :deep(.el-form-item) { flex-direction: column; align-items: stretch; } .work-form :deep(.el-form-item__label) { width: auto !important; justify-content: flex-start; margin-bottom: 6px; } .work-form :deep(.el-form-item__content) { margin-left: 0 !important; } .save-state { width: 100%; } }
.form-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 8px;
}
</style>
