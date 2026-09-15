<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">{{ isEdit ? '编辑作品' : '新增作品' }}</span>
      <el-button @click="$router.back()">返回</el-button>
    </div>

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
              <el-select v-model="form.category" clearable placeholder="选择分类" style="width: 100%">
                <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
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
          <el-col :span="12">
            <el-form-item label="委托方">
              <el-input v-model="form.clientName" placeholder="客户/委托方名称（可选）" />
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
          <el-button type="primary" :loading="saving" @click="save">{{ isEdit ? '保存修改' : '创建作品' }}</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { workApi } from '@/api/work'
import Editor from '@/components/Editor.vue'
import ImageUpload from '@/components/ImageUpload.vue'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)

const categories = ['竞赛', '论文', '项目', '其他']

const loading = ref(false)
const saving = ref(false)
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
  featured: false,
  seo: { title: '', description: '', keywords: '' },
})

onMounted(async () => {
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
      featured: work.featured === 1,
      seo: data.seo || { title: '', description: '', keywords: '' },
    })
  } finally {
    loading.value = false
  }
})

async function save() {
  if (!form.title?.trim() || !form.slug?.trim()) {
    ElMessage.warning('标题与 Slug 为必填项')
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await workApi.update(Number(route.params.id), form)
      ElMessage.success('保存成功')
    } else {
      await workApi.create(form)
      ElMessage.success('创建成功')
      router.push('/works')
    }
  } catch {
    // 错误已提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.work-form {
  max-width: 1100px;
}
.form-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 8px;
}
</style>
