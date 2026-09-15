<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">角色权限</span>
      <el-button
        type="primary"
        v-permission="'role:create'"
        :disabled="!permissionsReady"
        :title="permissionsReady ? '' : '权限数据加载中'"
        @click="openDialog()"
      >
        <el-icon><Plus /></el-icon>&nbsp;新增角色
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="list" v-loading="loading">
        <el-table-column prop="role.id" label="ID" width="70" />
        <el-table-column label="角色" min-width="150">
          <template #default="{ row }">
            <div class="role-name">{{ row.role.name }}</div>
            <div class="role-code">{{ row.role.code }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="role.description" label="描述" min-width="200" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.role.builtin === 1 ? 'warning' : 'info'" size="small">
              {{ row.role.builtin === 1 ? '内置' : '自定义' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="权限数" width="90">
          <template #default="{ row }">
            {{ row.role.code === 'SUPER_ADMIN' ? '全部' : row.permissionIds.length }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              link
              type="primary"
              v-permission="'role:edit'"
              :disabled="row.role.builtin === 1 || !permissionsReady"
              :title="!permissionsReady ? '权限数据加载中' : row.role.builtin === 1 ? '内置角色不可修改' : ''"
              @click="openDialog(row)"
            >
              编辑
            </el-button>
            <el-button
              size="small"
              link
              type="danger"
              v-permission="'role:delete'"
              :disabled="row.role.builtin === 1"
              :title="row.role.builtin === 1 ? '内置角色不可删除' : ''"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑角色' : '新增角色'"
      width="560px"
      @opened="syncPermissionSelection"
    >
      <el-form :model="form" label-width="80px">
        <el-form-item label="编码" required>
          <el-input v-model="form.code" :disabled="!!editingId" placeholder="如 CONTENT_EDITOR" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="权限">
          <el-tree
            ref="permTreeRef"
            :data="permTreeData"
            show-checkbox
            node-key="id"
            :props="{ label: 'name', children: 'children' }"
            class="perm-tree"
          />
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
import { roleApi } from '@/api/role'
import { useTable } from '@/composables/useTable'

const table = useTable(async (params) => {
  const rows = await roleApi.list()
  return { list: rows, total: rows.length }
})
const { loading, list, load } = table

const permTreeRef = ref()
const permTreeData = ref<any[]>([])
const permissionsReady = ref(false)
const pendingPermissionIds = ref<number[]>([])
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<any>({ code: '', name: '', description: '' })

function openDialog(row?: any) {
  if (!permissionsReady.value) return
  editingId.value = row?.role?.id ?? null
  form.code = row?.role.code || ''
  form.name = row?.role.name || ''
  form.description = row?.role.description || ''
  pendingPermissionIds.value = row?.permissionIds ? [...row.permissionIds] : []
  dialogVisible.value = true
}

function syncPermissionSelection() {
  permTreeRef.value?.setCheckedKeys(pendingPermissionIds.value)
}

async function save() {
  if (!form.code?.trim() || !form.name?.trim()) {
    ElMessage.warning('编码与名称为必填项')
    return
  }
  const permissionIds = permTreeRef.value?.getCheckedKeys(true) ?? []
  saving.value = true
  try {
    if (editingId.value) {
      await roleApi.update(editingId.value, { ...form, permissionIds })
    } else {
      await roleApi.create({ ...form, permissionIds })
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除角色「${row.role.name}」吗？`, '提示', { type: 'warning' })
  await roleApi.remove(row.role.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(async () => {
  const groups = await roleApi.permissions()
  permTreeData.value = Object.entries(groups).map(([module, perms]) => ({
    id: `module-${module}`,
    name: module,
    children: perms.map((p) => ({
      id: p.id,
      name: `${p.name}（${p.code}）`,
      type: p.type,
    })),
  }))
  permissionsReady.value = true
  await load()
})
</script>

<style scoped>
.role-name {
  font-weight: 600;
}
.role-code {
  font-size: 12px;
  color: #c0c4cc;
}
.perm-tree {
  width: 100%;
  max-height: 320px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 8px;
}
</style>
