<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">管理员管理</span>
      <el-button type="primary" v-permission="'admin:create'" @click="openDialog()">
        <el-icon><Plus /></el-icon>&nbsp;新增管理员
      </el-button>
    </div>

    <el-card shadow="never">
      <div class="table-toolbar">
        <el-input v-model="query.keyword" placeholder="用户名 / 昵称" clearable style="width: 220px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="用户名" min-width="130">
          <template #default="{ row }">{{ row.user.username }}</template>
        </el-table-column>
        <el-table-column label="昵称" min-width="130">
          <template #default="{ row }">{{ row.user.nickname || '-' }}</template>
        </el-table-column>
        <el-table-column label="角色" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="name in row.roleNames" :key="name" size="small" class="role-tag">{{ name }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.user.status === 1 ? 'success' : 'info'" size="small">
              {{ row.user.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后登录" width="150">
          <template #default="{ row }">{{ formatDateTime(row.user.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" v-permission="'admin:edit'" @click="openDialog(row)">编辑</el-button>
            <el-button size="small" link type="danger" v-permission="'admin:delete'" @click="remove(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑管理员' : '新增管理员'" width="480px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="!!editingId" placeholder="登录用户名" />
        </el-form-item>
        <el-form-item v-if="!editingId" label="初始密码" required>
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.roleIds" multiple placeholder="分配角色" style="width: 100%">
            <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用" />
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
import { adminApi } from '@/api/admin'
import { roleApi, type RoleItem } from '@/api/role'
import { useTable } from '@/composables/useTable'
import { formatDateTime } from '@/utils/format'

const table = useTable((params) => adminApi.list(params))
const { loading, list, total, query, load, search } = table

const roles = ref<{ id: number; name: string }[]>([])
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<any>({ username: '', password: '', nickname: '', roleIds: [], status: 1 })

function openDialog(row?: any) {
  editingId.value = row?.id ?? null
  Object.assign(form, {
    username: row?.user.username || '',
    password: '',
    nickname: row?.user.nickname || '',
    roleIds: row?.roleCodes ? roleCodesToIds(row.roleCodes) : [],
    status: row?.user.status ?? 1,
  })
  dialogVisible.value = true
}

function roleCodesToIds(codes: string[]): number[] {
  return roles.value.filter((r) => codes.includes(r.name)).map((r) => r.id)
}

async function save() {
  if (!form.username || (!editingId.value && !form.password)) {
    ElMessage.warning('用户名与密码为必填项')
    return
  }
  if (!form.roleIds.length) {
    ElMessage.warning('至少分配一个角色')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await adminApi.update(editingId.value, { nickname: form.nickname, status: form.status, roleIds: form.roleIds })
    } else {
      await adminApi.create({ username: form.username, password: form.password, nickname: form.nickname, roleIds: form.roleIds })
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除管理员「${row.user.username}」吗？`, '提示', { type: 'warning' })
  await adminApi.remove(row.user.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(async () => {
  const roleList = await roleApi.list()
  roles.value = roleList.map((r: RoleItem) => ({ id: r.role.id, name: r.role.name }))
  load()
})
</script>

<style scoped>
.role-tag {
  margin-right: 4px;
}
</style>
