<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">{{ isEdit ? '编辑新闻' : '新增新闻' }}</span>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-form :model="form" label-width="90px" class="news-form">
        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="标题" required>
              <el-input v-model="form.title" placeholder="新闻标题" maxlength="200" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Slug" required>
              <el-input v-model="form.slug" placeholder="URL 标识" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="分类">
              <el-select v-model="form.category" clearable placeholder="选择分类" style="width: 100%">
                <el-option label="工作室动态" value="工作室动态" />
                <el-option label="学术活动" value="学术活动" />
                <el-option label="招新信息" value="招新信息" />
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

        <div class="form-actions">
          <el-checkbox v-model="form.featured">置顶</el-checkbox>
          <el-button type="primary" :loading="saving" @click="save">{{ isEdit ? '保存修改' : '创建新闻' }}</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { newsApi } from '@/api/news'
import Editor from '@/components/Editor.vue'
import ImageUpload from '@/components/ImageUpload.vue'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)

const loading = ref(false)
const saving = ref(false)
const form = reactive<any>({
  title: '',
  slug: '',
  category: '',
  sortOrder: 0,
  status: 'draft',
  summary: '',
  coverUrl: '',
  content: '',
  featured: false,
})

onMounted(async () => {
  if (!isEdit.value) return
  loading.value = true
  try {
    const data = await newsApi.detail(Number(route.params.id))
    const news = data.news
    Object.assign(form, {
      title: news.title,
      slug: news.slug,
      category: news.category || '',
      sortOrder: news.sortOrder ?? 0,
      status: news.status || 'draft',
      summary: news.summary || '',
      coverUrl: news.coverUrl || '',
      content: news.content || '',
      featured: news.featured === 1,
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
      await newsApi.update(Number(route.params.id), form)
      ElMessage.success('保存成功')
    } else {
      await newsApi.create(form)
      ElMessage.success('创建成功')
      router.push('/news')
    }
  } catch {
    // 错误已提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.news-form {
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
