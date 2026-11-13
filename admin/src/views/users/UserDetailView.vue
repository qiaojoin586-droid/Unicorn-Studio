<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">用户详情</span>
      <div>
        <el-button v-if="canEdit" type="primary" @click="openEdit">编辑资料</el-button>
        <el-button @click="$router.back()">返回</el-button>
      </div>
    </div>

    <el-card shadow="never" v-loading="loading" style="max-width: 640px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="ID">{{ detail?.user.id }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ detail?.user.nickname }}</el-descriptions-item>
        <el-descriptions-item label="头像">
          <el-avatar v-if="detail?.user.avatar" :size="36" :src="detail.user.avatar" />
          <span v-else>未设置</span>
        </el-descriptions-item>
        <el-descriptions-item label="手机号">
          {{ detail?.user.phone || '未绑定' }}
          <el-button v-if="canBindPhone" link type="primary" @click="bindPhone">绑定手机号</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="联系邮箱">{{ detail?.user.email || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag v-if="detail" :type="detail.user.status === 1 ? 'success' : detail.user.status === 0 ? 'warning' : 'info'" size="small">
            {{ statusLabels[detail.user.status] }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="AI 助手">{{ detail?.user.aiEnabled ? '允许使用' : '禁止使用' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail?.user.status === 0" label="禁言原因">{{ detail.user.banReason || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail?.user.status === 0" label="禁言截止">{{ detail.user.banUntil ? formatDateTime(detail.user.banUntil) : '永久' }}</el-descriptions-item>
        <el-descriptions-item label="注册 IP">{{ detail?.user.registerIp || '-' }}</el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ formatDateTime(detail?.user.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="最后登录">{{ formatDateTime(detail?.user.lastLoginAt) }}</el-descriptions-item>
        <el-descriptions-item label="评论数">{{ detail?.commentCount }}</el-descriptions-item>
        <el-descriptions-item label="留言数">{{ detail?.messageCount }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-dialog
      v-model="editVisible"
      title="编辑用户资料"
      width="min(540px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      destroy-on-close
      @closed="edit.newPassword = ''"
    >
      <el-form ref="editForm" :model="edit" :rules="rules" :disabled="saving" label-width="100px" @submit.prevent="saveEdit">
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model.trim="edit.nickname" maxlength="50" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model.trim="edit.phone" type="tel" inputmode="numeric" maxlength="11" autocomplete="off" />
          <p class="field-note">更换手机号后，用户需要重新登录。清空后该账号无法使用手机号登录。</p>
        </el-form-item>
        <el-form-item label="联系邮箱" prop="email">
          <el-input v-model.trim="edit.email" type="email" maxlength="100" />
        </el-form-item>
        <el-form-item label="头像" prop="avatar">
          <el-input v-model.trim="edit.avatar" placeholder="上传路径或 HTTP(S) 链接" maxlength="255" clearable />
          <div v-if="auth.hasPerm('file:upload')" class="avatar-upload">
            <ImageUpload v-model="edit.avatar" />
          </div>
        </el-form-item>
        <el-form-item label="重置密码" prop="newPassword">
          <el-input v-model="edit.newPassword" type="password" autocomplete="new-password" show-password placeholder="留空则沿用原密码" />
          <p class="field-note">设置新密码后，用户需要重新登录。</p>
        </el-form-item>
        <el-form-item label="账号状态" prop="status">
          <el-select v-model="edit.status">
            <el-option v-for="(label, status) in statusLabels" :key="status" :label="label" :value="Number(status)" />
          </el-select>
        </el-form-item>
        <el-form-item label="AI 助手">
          <el-switch v-model="edit.aiEnabled" aria-label="允许使用 AI 助手" active-text="允许使用" inactive-text="禁止使用" />
        </el-form-item>
        <template v-if="edit.status === 0">
          <el-form-item label="禁言原因" prop="banReason">
            <el-input v-model.trim="edit.banReason" type="textarea" :rows="2" maxlength="255" />
          </el-form-item>
          <el-form-item label="禁言截止" prop="banUntil">
            <el-date-picker v-model="edit.banUntil" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空为永久禁言" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { userApi, type PublicUserDetail, type PublicUserEdit } from '@/api/user'
import ImageUpload from '@/components/ImageUpload.vue'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const auth = useAuthStore()
const loading = ref(false)
const binding = ref(false)
const detail = ref<PublicUserDetail | null>(null)
const editVisible = ref(false)
const saving = ref(false)
const editForm = ref<FormInstance>()
const statusLabels: Record<number, string> = { 0: '禁言', 1: '正常', 2: '已注销' }
const edit = reactive<PublicUserEdit>({
  nickname: '', phone: '', email: '', avatar: '', newPassword: '',
  status: 1, aiEnabled: true, banReason: '', banUntil: null,
})
const rules: FormRules<PublicUserEdit> = {
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  phone: [{ pattern: /^1[3-9][0-9]{9}$/, message: '请输入有效的 11 位手机号', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  newPassword: [{
    validator: (_rule, value: string) => !value || (
      value.trim() && value.length >= 8 && new TextEncoder().encode(value).length <= 72
    ) || new Error('请输入至少 8 位、最多 72 字节的新密码'),
    trigger: 'blur',
  }],
}
const canEdit = computed(() => detail.value !== null && auth.hasPerm('user:edit'))
const canBindPhone = computed(() => auth.hasPerm('user:bind-phone') && !binding.value
  && !canEdit.value && detail.value?.user.email && !detail.value.user.phone && detail.value.user.status !== 2)

async function load(id: number) {
  loading.value = true
  try {
    const result = await userApi.detail(id)
    if (Number(route.params.id) === id) detail.value = result
  } finally {
    if (Number(route.params.id) === id) loading.value = false
  }
}

async function bindPhone() {
  const id = detail.value?.user.id
  if (id == null) return
  binding.value = true
  try {
    const { value } = await ElMessageBox.prompt('请先核实旧账号用户身份及其对新手机号的使用权。绑定后，用户使用原密码和该手机号登录。', '绑定旧账号手机号', {
      inputPlaceholder: '11 位手机号',
      inputValidator: (value: string) => /^1[3-9][0-9]{9}$/.test(value?.trim() || '') || '请输入有效的 11 位手机号',
      confirmButtonText: '确认绑定',
      cancelButtonText: '取消',
    })
    await userApi.bindPhone(id, value.trim())
    ElMessage.success('手机号已绑定')
    if (Number(route.params.id) === id) await load(id)
  } catch {
    // 取消提示框直接返回，请求错误由 HTTP 拦截器展示。
  } finally {
    binding.value = false
  }
}

function openEdit() {
  const user = detail.value?.user
  if (!user || !canEdit.value) return
  Object.assign(edit, {
    nickname: user.nickname,
    phone: user.phone || '',
    email: user.email || '',
    avatar: user.avatar || '',
    newPassword: '',
    status: user.status,
    aiEnabled: user.aiEnabled,
    banReason: user.banReason || '',
    banUntil: user.banUntil,
  })
  editVisible.value = true
}

async function saveEdit() {
  const id = detail.value?.user.id
  if (id == null || !editForm.value || saving.value) return
  saving.value = true
  try {
    await editForm.value.validate()
    await userApi.update(id, { ...edit })
    edit.newPassword = ''
    editVisible.value = false
    ElMessage.success('用户资料已保存')
    if (Number(route.params.id) === id) await load(id)
  } catch {
    // 表单和请求层各自展示校验错误。
  } finally {
    saving.value = false
  }
}

watch(() => route.params.id, (id) => {
  editVisible.value = false
  detail.value = null
  if (id) void load(Number(id)).then(() => {
    if (Number(route.params.id) === Number(id) && route.query.edit === '1') openEdit()
  }).catch(() => {})
}, { immediate: true })
</script>

<style scoped>
.field-note { margin: 4px 0 0; line-height: 1.5; font-size: 12px; color: var(--el-text-color-secondary); }
.avatar-upload { margin-top: 8px; }
</style>
