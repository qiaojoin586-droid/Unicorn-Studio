<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">作品档案背景</h1>
      <el-button type="primary" v-permission="'carousel:create'" @click="openDialog()">
        <el-icon><Plus /></el-icon>&nbsp;新增背景
      </el-button>
    </div>

    <el-card shadow="never">
      <p class="field-tip">
        背景只显示在主站作品档案的顶部。启用新背景会停用原背景，手机仅显示封面。
        <a :href="publicSiteUrl('/works')" target="_blank" rel="noopener noreferrer">查看主站作品档案 ↗</a>
      </p>
      <el-table :data="list" v-loading="loading">
        <el-table-column label="封面" width="180">
          <template #default="{ row }">
            <img v-if="row.imageUrl && !row.imgError" :src="row.imageUrl" class="thumb" alt="" @error="row.imgError = true" />
            <span v-else>封面不可用</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="125">
          <template #default="{ row }">{{ row.mediaType === 'video' ? '动态视频' : '静态图片' }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" v-permission="'carousel:edit'" @click="openDialog(row as ArchiveBackground)">编辑</el-button>
            <el-button link type="danger" v-permission="'carousel:delete'" @click="remove(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrap">
        <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" layout="total, prev, pager, next" @change="load" />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑档案背景' : '新增档案背景'" width="min(560px, 94vw)">
      <el-form :model="form" label-position="top" :disabled="saving">
        <el-form-item label="背景类型" required>
          <el-select v-model="form.mediaType">
            <el-option label="静态图片" value="image" />
            <el-option label="动态视频" value="video" />
          </el-select>
        </el-form-item>
        <el-form-item :label="form.mediaType === 'video' ? '视频封面' : '背景图片'" required>
          <ImageUpload v-model="form.imageUrl" />
        </el-form-item>
        <el-form-item v-if="form.mediaType === 'video'" label="背景视频" required>
          <VideoUpload v-model="form.videoUrl" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { carouselApi } from '@/api/carousel'
import { useTable } from '@/composables/useTable'
import { publicSiteUrl } from '@/utils/site'
import ImageUpload from '@/components/ImageUpload.vue'
import VideoUpload from '@/components/VideoUpload.vue'

type ArchiveBackground = { id: number; imageUrl: string; videoUrl?: string; mediaType: string; status: number }
const { loading, list, total, query, load } = useTable(params => carouselApi.list({ ...params, group: 'works' }))
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ imageUrl: '', mediaType: 'image', videoUrl: '', enabled: true })

function openDialog(row?: ArchiveBackground) {
  editingId.value = row?.id ?? null
  Object.assign(form, {
    imageUrl: row?.imageUrl || '',
    mediaType: row?.mediaType || 'image',
    videoUrl: row?.videoUrl || '',
    enabled: row ? row.status === 1 : true,
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.imageUrl) { ElMessage.warning('请上传背景图片或视频封面'); return }
  if (form.mediaType === 'video' && !form.videoUrl) { ElMessage.warning('请上传背景视频'); return }
  saving.value = true
  const payload = { ...form, pageKey: 'works', videoUrl: form.mediaType === 'video' ? form.videoUrl : '' }
  try {
    if (editingId.value) await carouselApi.update(editingId.value, payload)
    else await carouselApi.create(payload)
    ElMessage.success('档案背景已保存')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  await ElMessageBox.confirm('确定删除这条档案背景吗？', '删除背景', { type: 'warning' })
  await carouselApi.remove(id)
  if (list.value.length === 1 && query.page > 1) query.page--
  ElMessage.success('档案背景已删除')
  load()
}

onMounted(load)
</script>

<style scoped>
.field-tip { margin: 0 0 16px; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.7; }
.field-tip a { color: var(--el-color-primary); }
.thumb { width: 120px; height: 68px; object-fit: cover; border-radius: 6px; }
</style>
