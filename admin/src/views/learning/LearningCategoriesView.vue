<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">资料分类管理</span>
      <el-button type="primary" v-permission="'learning:category'" @click="openDialog()">
        <el-icon><Plus /></el-icon>&nbsp;新增分类
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="list" v-loading="loading">
        <el-table-column prop="category.name" label="分类名称" min-width="140" />
        <el-table-column prop="category.description" label="描述" min-width="220">
          <template #default="{ row }">{{ row.category.description || '-' }}</template>
        </el-table-column>
        <el-table-column label="资料数" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.resourceCount > 0 ? 'primary' : 'info'">{{ row.resourceCount }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="排序" prop="category.sortOrder" width="70" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.category.status === 1 ? 'success' : 'info'" size="small">
              {{ row.category.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.category.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <!-- el-table-column 插槽行类型固定为 DefaultRow，不会从 :data 推断 ⇒ 断言为具体行类型 -->
          <template #default="{ row }">
            <el-button size="small" link type="primary" v-permission="'learning:category'" @click="openDialog(row as LearningCategoryItem)">编辑</el-button>
            <el-button size="small" link type="danger" v-permission="'learning:category'" @click="remove(row as LearningCategoryItem)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑分类' : '新增分类'" width="480px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="分类名称" required>
          <el-input v-model="form.name" maxlength="50" placeholder="如：算法竞赛" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" maxlength="255" />
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
import { learningApi, type LearningCategoryItem } from '@/api/learning'
import { formatDateTime } from '@/utils/format'

const loading = ref(false)
const list = ref<LearningCategoryItem[]>([])

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<any>({
  name: '',
  description: '',
  sortOrder: 0,
  enabled: true,
})

async function load() {
  loading.value = true
  try {
    list.value = await learningApi.categories()
  } finally {
    loading.value = false
  }
}

function openDialog(row?: LearningCategoryItem) {
  editingId.value = row?.category.id ?? null
  Object.assign(form, {
    name: row?.category.name || '',
    description: row?.category.description || '',
    sortOrder: row?.category.sortOrder ?? 0,
    enabled: row ? row.category.status === 1 : true,
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.name?.trim()) {
    ElMessage.warning('请填写分类名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name,
      description: form.description,
      sortOrder: form.sortOrder,
      status: form.enabled ? 1 : 0,
    }
    if (editingId.value) {
      await learningApi.updateCategory(editingId.value, payload)
    } else {
      await learningApi.createCategory(payload)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function remove(row: LearningCategoryItem) {
  await ElMessageBox.confirm(
    `确定删除分类「${row.category.name}」吗？${row.resourceCount > 0 ? `（该分类下有 ${row.resourceCount} 份资料，删除将被拒绝）` : ''}`,
    '提示',
    { type: 'warning' },
  )
  await learningApi.removeCategory(row.category.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>
