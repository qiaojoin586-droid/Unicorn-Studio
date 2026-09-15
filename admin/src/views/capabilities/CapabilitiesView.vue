<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">培养能力管理</h1>
      <el-button type="primary" v-permission="'capability:create'" @click="openDialog()">
        <el-icon aria-hidden="true"><Plus /></el-icon>
        新增培养能力
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="list" v-loading="loading" row-key="id">
        <el-table-column label="图标" width="72" align="center">
          <template #default="{ row }">
            <span class="capability-icon" :aria-label="row.icon ? `图标：${row.icon}` : '未设置图标'">
              {{ row.icon || '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="能力名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="description" label="能力说明" min-width="300" show-overflow-tooltip />
        <el-table-column prop="sortOrder" label="排序" width="80" align="center" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <!-- el-table-column 的作用域插槽行类型固定为 DefaultRow（Record<PropertyKey, any>），
               不会从 el-table 的 :data 推断，故此处显式断言为具体行类型后才能交给
               openDialog / remove（否则 TS2345）。 -->
          <template #default="{ row }">
            <el-button
              size="small"
              link
              type="primary"
              v-permission="'capability:edit'"
              @click="openDialog(row as Capability)"
            >
              编辑
            </el-button>
            <el-button
              size="small"
              link
              type="danger"
              v-permission="'capability:delete'"
              @click="remove(row as Capability)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无培养能力">
            <el-button type="primary" v-permission="'capability:create'" @click="openDialog()">
              新增培养能力
            </el-button>
          </el-empty>
        </template>
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

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑培养能力' : '新增培养能力'"
      width="min(560px, calc(100vw - 32px))"
      destroy-on-close
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        status-icon
        @submit.prevent="save"
      >
        <el-form-item label="能力名称" prop="title">
          <el-input
            v-model="form.title"
            maxlength="100"
            show-word-limit
            placeholder="例如：工程实践能力"
            autocomplete="off"
          />
        </el-form-item>
        <el-form-item label="能力说明" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            resize="vertical"
            placeholder="说明该能力的培养内容与收获"
          />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="展示图标" prop="icon">
            <el-input
              v-model="form.icon"
              maxlength="50"
              placeholder="可选，例如：🛠️"
              autocomplete="off"
            />
          </el-form-item>
          <el-form-item label="排序值" prop="sortOrder">
            <el-input-number v-model="form.sortOrder" :min="0" :max="9999" controls-position="right" />
          </el-form-item>
        </div>
        <el-form-item label="主站展示">
          <el-switch
            v-model="form.enabled"
            inline-prompt
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">
          {{ editingId ? '保存修改' : '创建能力' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { capabilityApi, type Capability, type CapabilityForm } from '@/api/capability'
import { useTable } from '@/composables/useTable'

const table = useTable((params) => capabilityApi.list({ page: params.page, size: params.size }))
const { loading, list, total, query, load } = table

const formRef = ref<FormInstance>()
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<CapabilityForm>(emptyForm())

const rules: FormRules<CapabilityForm> = {
  title: [
    {
      required: true,
      message: '请输入能力名称',
      transform: (value: string) => value?.trim(),
      trigger: 'blur',
    },
    { max: 100, message: '能力名称不能超过 100 个字符', trigger: 'blur' },
  ],
  description: [
    {
      required: true,
      message: '请输入能力说明',
      transform: (value: string) => value?.trim(),
      trigger: 'blur',
    },
    { max: 500, message: '能力说明不能超过 500 个字符', trigger: 'blur' },
  ],
  icon: [{ max: 50, message: '展示图标不能超过 50 个字符', trigger: 'blur' }],
  sortOrder: [{ required: true, message: '请输入排序值', trigger: 'change' }],
}

function emptyForm(): CapabilityForm {
  return {
    title: '',
    description: '',
    icon: '',
    sortOrder: 0,
    enabled: true,
  }
}

function openDialog(row?: Capability) {
  editingId.value = row?.id ?? null
  Object.assign(form, row
    ? {
        title: row.title,
        description: row.description,
        icon: row.icon || '',
        sortOrder: row.sortOrder,
        enabled: row.enabled,
      }
    : emptyForm())
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

function resetForm() {
  editingId.value = null
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
}

async function save() {
  if (saving.value || !formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  const payload: CapabilityForm = {
    ...form,
    title: form.title.trim(),
    description: form.description.trim(),
    icon: form.icon?.trim() || '',
  }
  try {
    if (editingId.value) {
      await capabilityApi.update(editingId.value, payload)
      ElMessage.success('培养能力已更新')
    } else {
      await capabilityApi.create(payload)
      ElMessage.success('培养能力已创建')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: Capability) {
  await ElMessageBox.confirm(
    `删除培养能力“${row.title}”后，主站将不再展示该内容。确定删除吗？`,
    '删除培养能力',
    {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
    },
  )
  await capabilityApi.remove(row.id)
  ElMessage.success('培养能力已删除')
  if (list.value.length === 1 && query.page > 1) {
    query.page -= 1
  }
  await load()
}

onMounted(load)
</script>

<style scoped>
.capability-icon {
  display: inline-flex;
  min-width: 32px;
  min-height: 32px;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}

.form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(140px, 0.55fr);
  gap: 16px;
}

.form-grid :deep(.el-input-number) {
  width: 100%;
}

@media (max-width: 640px) {
  .page-header {
    align-items: flex-start;
    gap: 12px;
  }

  .form-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
