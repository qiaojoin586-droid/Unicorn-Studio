<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">轮播图管理</span>
      <el-button type="primary" v-permission="'carousel:create'" @click="openDialog()">
        <el-icon><Plus /></el-icon>&nbsp;新增轮播图
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="list" v-loading="loading">
        <el-table-column label="预览" width="180">
          <template #default="{ row }">
            <img v-if="row.imageUrl && !row.imgError" :src="row.imageUrl" class="thumb" @error="row.imgError = true" />
            <span v-else class="no-thumb">无图</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="160" />
        <el-table-column prop="subtitle" label="副标题" min-width="180" />
        <el-table-column label="排序" prop="sortOrder" width="70" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" v-permission="'carousel:edit'" @click="openDialog(row)">编辑</el-button>
            <el-button size="small" link type="danger" v-permission="'carousel:delete'" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @change="load"
        />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑轮播图' : '新增轮播图'" width="520px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="图片" required>
          <ImageUpload v-model="form.imageUrl" />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="100" />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="form.subtitle" maxlength="200" />
        </el-form-item>
        <el-form-item label="跳转链接">
          <el-input v-model="form.linkUrl" placeholder="https://…（可选）" />
        </el-form-item>
        <el-form-item label="排序值">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { carouselApi } from '@/api/carousel'
import { useTable } from '@/composables/useTable'
import ImageUpload from '@/components/ImageUpload.vue'

const table = useTable((params) => carouselApi.list(params))
const { loading, list, total, query, load } = table

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<any>({
  imageUrl: '',
  title: '',
  subtitle: '',
  linkUrl: '',
  sortOrder: 0,
  enabled: true,
})

function openDialog(row?: any) {
  editingId.value = row?.id ?? null
  Object.assign(form, {
    imageUrl: row?.imageUrl || '',
    title: row?.title || '',
    subtitle: row?.subtitle || '',
    linkUrl: row?.linkUrl || '',
    sortOrder: row?.sortOrder ?? 0,
    enabled: row ? row.status === 1 : true,
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.imageUrl) {
    ElMessage.warning('请上传图片')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await carouselApi.update(editingId.value, form)
    } else {
      await carouselApi.create(form)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除该轮播图吗？`, '提示', { type: 'warning' })
  await carouselApi.remove(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.thumb {
  width: 120px;
  height: 60px;
  object-fit: cover;
  border-radius: 8px;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.08);
}
.no-thumb {
  color: #94a3b8;
  font-size: 12px;
}
</style>
