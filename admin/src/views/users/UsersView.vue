<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">用户管理</span>
    </div>

    <el-card shadow="never">
      <div class="table-toolbar">
        <el-input v-model="query.keyword" placeholder="昵称 / 邮箱" clearable style="width: 220px" @keyup.enter="searchWithRange" />
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="searchWithRange">
          <el-option label="正常" :value="1" />
          <el-option label="禁言" :value="0" />
          <el-option label="已注销" :value="2" />
        </el-select>
        <el-date-picker
          v-model="registerRange"
          type="daterange"
          range-separator="至"
          start-placeholder="注册开始"
          end-placeholder="注册结束"
          value-format="YYYY-MM-DD"
          style="width: 260px"
        />
        <el-button type="primary" @click="searchWithRange">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="昵称" min-width="140">
          <template #default="{ row }">
            <div class="user-name">{{ row.nickname }}</div>
            <div class="user-email">{{ row.email }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : row.status === 0 ? 'danger' : 'info'" size="small">
              {{ row.status === 1 ? '正常' : row.status === 0 ? '禁言' : '已注销' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="禁言原因" min-width="140">
          <template #default="{ row }">{{ row.banReason || '-' }}</template>
        </el-table-column>
        <el-table-column label="注册时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" v-permission="'user:view'" @click="$router.push(`/users/${row.id}`)">详情</el-button>
            <el-button v-if="row.status !== 0" size="small" link type="warning" v-permission="'user:ban'" @click="openBan(row)">禁言</el-button>
            <el-button v-else size="small" link type="success" v-permission="'user:ban'" @click="unban(row)">解禁</el-button>
            <el-button size="small" link type="danger" v-permission="'user:delete'" @click="remove(row)">注销</el-button>
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

    <el-dialog v-model="banVisible" title="禁言用户" width="420px">
      <el-form label-width="80px">
        <el-form-item label="时长">
          <el-radio-group v-model="banForm.duration">
            <el-radio value="1d">1 天</el-radio>
            <el-radio value="7d">7 天</el-radio>
            <el-radio value="30d">30 天</el-radio>
            <el-radio value="forever">永久</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="banForm.reason" type="textarea" :rows="2" placeholder="禁言原因（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="banVisible = false">取消</el-button>
        <el-button type="warning" :loading="banning" @click="submitBan">确认禁言</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { userApi } from '@/api/user'
import { useTable } from '@/composables/useTable'
import { formatDateTime } from '@/utils/format'

const table = useTable((params) => userApi.list(params))
const { loading, list, total, query, load, search } = table

const registerRange = ref<string[]>([])
const banVisible = ref(false)
const banning = ref(false)
const banTarget = ref<any>(null)
const banForm = ref({ duration: '7d', reason: '' })

// A-05：注册时间区间是页面本地状态，useTable 不认识它。
// 因此所有触发查询的入口（关键词回车 / 状态下拉 / 查询按钮）都必须走这里，
// 否则一旦先选了区间再改状态或按回车，区间条件会被静默丢弃。
function searchWithRange() {
  if (registerRange.value?.length === 2) {
    query.registerFrom = registerRange.value[0]
    query.registerTo = registerRange.value[1]
  } else {
    delete query.registerFrom
    delete query.registerTo
  }
  search()
}

// A-05：useTable.reset() 只清理它自己管的 query 字段，不会清 registerRange。
// 不在此处一并清掉的话，「重置」后区间控件仍显示旧值，而请求参数已清空，界面与结果不一致。
function reset() {
  registerRange.value = []
  table.reset()
}

function openBan(row: any) {
  banTarget.value = row
  banForm.value = { duration: '7d', reason: '' }
  banVisible.value = true
}

async function submitBan() {
  banning.value = true
  try {
    await userApi.ban(banTarget.value.id, banForm.value)
    ElMessage.success('已禁言')
    banVisible.value = false
    load()
  } finally {
    banning.value = false
  }
}

async function unban(row: any) {
  await userApi.unban(row.id)
  ElMessage.success('已解禁')
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定注销用户「${row.nickname}」吗？该操作不可恢复。`, '警告', { type: 'warning' })
  await userApi.remove(row.id)
  ElMessage.success('已注销')
  load()
}

onMounted(load)
</script>

<style scoped>
.user-name {
  font-weight: 600;
}
.user-email {
  font-size: 12px;
  color: #94a3b8;
}
</style>
