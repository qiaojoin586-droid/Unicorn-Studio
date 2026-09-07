<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">关于我们</span>
      <el-button type="primary" v-permission="'about:edit'" :loading="saving" @click="save">保存</el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <p class="page-tip">
        此处维护前台「关于我们」页面的全部文案与配图，保存后前台刷新即可看到最新内容。
        正文段落之间用<b>空行</b>分隔。
      </p>

      <el-form :model="form" label-width="110px" class="about-form">
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

        <el-form-item label="右侧配图">
          <div>
            <ImageUpload v-model="form.imageUrl" />
            <div class="field-tip">留空则使用站点默认 LOGO</div>
          </div>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aboutApi, type AboutPage } from '@/api/about'
import ImageUpload from '@/components/ImageUpload.vue'

/** 与后端 AboutPageService.MAX_STATS 保持一致 */
const MAX_STATS = 8

const loading = ref(false)
const saving = ref(false)

const form = reactive<AboutPage>({
  eyebrow: '',
  title: '',
  subtitle: '',
  content: '',
  imageUrl: '',
  stats: [],
})

function addStat() {
  if (form.stats.length >= MAX_STATS) return
  form.stats.push({ value: '', label: '' })
}

function removeStat(idx: number) {
  form.stats.splice(idx, 1)
}

onMounted(async () => {
  loading.value = true
  try {
    const data = await aboutApi.get()
    Object.assign(form, {
      eyebrow: data?.eyebrow || '',
      title: data?.title || '',
      subtitle: data?.subtitle || '',
      content: data?.content || '',
      imageUrl: data?.imageUrl || '',
      stats: (data?.stats || []).map((s) => ({ value: s.value || '', label: s.label || '' })),
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
    await aboutApi.update({
      eyebrow: form.eyebrow,
      title: form.title,
      subtitle: form.subtitle,
      content: form.content,
      imageUrl: form.imageUrl,
      // 过滤掉全空的指标行，避免渲染出空统计块
      stats: form.stats.filter((s) => s.value.trim() || s.label.trim()),
    })
    ElMessage.success('保存成功，前台刷新后生效')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.about-form {
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
