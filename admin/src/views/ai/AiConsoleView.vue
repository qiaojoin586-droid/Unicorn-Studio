<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">AI 会话</span>
    </div>

    <el-alert
      title="会话与消息仅供排查使用。列表里的正文是摘要（约 100 字），点「查看消息」才会加载全文；用户已清空的消息仍会显示，并带「已清空」标记。"
      type="info"
      :closable="false"
      class="block-gap"
    />

    <el-tabs v-model="activeTab">
      <!-- ============ 会话列表（接口 1） ============ -->
      <el-tab-pane label="会话列表" name="sessions">
        <el-card shadow="never">
          <div class="table-toolbar">
            <el-input v-model="sessionQuery.userId" placeholder="用户 ID" clearable style="width: 120px" @keyup.enter="searchSessions" />
            <el-date-picker
              v-model="sessionRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYY-MM-DD"
              style="width: 260px"
            />
            <el-checkbox v-model="includeDeleted" @change="searchSessions">包含已清空</el-checkbox>
            <el-button type="primary" @click="searchSessionsWithRange">查询</el-button>
          </div>

          <el-table :data="sessions" v-loading="sessionsLoading">
            <el-table-column prop="id" label="会话 ID" width="200" show-overflow-tooltip />
            <el-table-column prop="userId" label="用户 ID" width="90">
              <template #default="{ row }">{{ row.userId ?? '游客' }}</template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
            <el-table-column prop="messageCount" label="消息数" width="80" />
            <el-table-column label="最后活跃" width="150">
              <template #default="{ row }">{{ formatDateTime(row.lastActiveAt) }}</template>
            </el-table-column>
            <el-table-column label="创建时间" width="150">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openMessages(row as AdminSession)">查看消息</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-wrap">
            <el-pagination
              v-model:current-page="sessionQuery.page"
              v-model:page-size="sessionQuery.size"
              :total="sessionsTotal"
              layout="total, prev, pager, next"
              @change="loadSessions"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ============ 消息检索（接口 3） ============ -->
      <el-tab-pane label="消息检索" name="search">
        <el-card shadow="never">
          <div class="table-toolbar">
            <el-input v-model="messageQuery.userId" placeholder="用户 ID" clearable style="width: 120px" @keyup.enter="searchMessages" />
            <el-input v-model="messageQuery.conversationId" placeholder="会话 ID" clearable style="width: 200px" @keyup.enter="searchMessages" />
            <el-select v-model="messageQuery.status" placeholder="状态" clearable style="width: 140px" @change="searchMessages">
              <el-option v-for="s in MESSAGE_STATUSES" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
            <el-date-picker
              v-model="messageRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYY-MM-DD"
              style="width: 260px"
            />
            <el-button type="primary" @click="searchMessagesWithRange">查询</el-button>
            <el-button @click="resetMessages">重置</el-button>
          </div>

          <el-alert
            v-if="isBlockedFilter"
            title="当前只看被敏感词拦截的提问（status=blocked）"
            type="warning"
            :closable="false"
            class="block-gap"
            show-icon
          />

          <el-table :data="messages" v-loading="messagesLoading">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="conversationId" label="会话 ID" width="170" show-overflow-tooltip />
            <el-table-column prop="userId" label="用户" width="80">
              <template #default="{ row }">{{ row.userId ?? '游客' }}</template>
            </el-table-column>
            <el-table-column label="角色" width="90">
              <template #default="{ row }">
                <el-tag :type="row.role === 'user' ? 'primary' : 'success'" size="small">
                  {{ row.role === 'user' ? '用户' : '助手' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="content" label="内容摘要" min-width="260" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'blocked'" type="danger" size="small">已拦截</el-tag>
                <el-tag v-else-if="row.deleted" type="info" size="small">已清空</el-tag>
                <span v-else class="muted">{{ row.status }}</span>
              </template>
            </el-table-column>
            <el-table-column label="命中词" width="100">
              <template #default="{ row }">{{ row.blockedWord || '-' }}</template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>

          <div class="pagination-wrap">
            <el-pagination
              v-model:current-page="messageQuery.page"
              v-model:page-size="messageQuery.size"
              :total="messagesTotal"
              layout="total, prev, pager, next"
              @change="loadMessages"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ============ 清理卡片（接口 4、5） ============ -->
      <el-tab-pane label="历史清理" name="cleanup">
        <el-row :gutter="16">
          <el-col :xs="24" :md="12">
            <el-card shadow="never" v-loading="cleanupLoading">
              <template #header><span class="card-title">执行状态</span></template>
              <el-descriptions :column="1" border>
                <el-descriptions-item label="保留期">{{ cleanupStatus?.retentionDays ?? '-' }} 天</el-descriptions-item>
                <el-descriptions-item label="执行周期">{{ cronText }}</el-descriptions-item>
                <el-descriptions-item label="下次自动执行">{{ formatDateTime(cleanupStatus?.nextRunAt) }}</el-descriptions-item>
                <el-descriptions-item label="上次执行">{{ lastRunText }}</el-descriptions-item>
                <el-descriptions-item label="上次删除条数">
                  {{ cleanupStatus?.lastRunDeleted ?? '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="上次失败原因">
                  <span :class="{ 'error-text': cleanupStatus?.lastError }">
                    {{ cleanupStatus?.lastError || '无' }}
                  </span>
                </el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>

          <el-col :xs="24" :md="12">
            <el-card shadow="never">
              <template #header><span class="card-title">手动清理</span></template>
              <p class="desc">
                清理会<strong>物理删除</strong>超过保留期的 AI 消息与会话行（不可从库内恢复）。
                保留期固定 {{ cleanupStatus?.retentionDays ?? 90 }} 天不可调整；建议先「预演」确认影响范围。
              </p>
              <div class="actions">
                <el-button :loading="previewing" @click="previewCleanup">预演</el-button>
                <el-button
                  type="danger"
                  :loading="running"
                  :disabled="!hasCleanupPerm"
                  @click="runCleanup"
                >
                  <el-icon><Delete /></el-icon>&nbsp;立即执行清理
                </el-button>
                <span v-if="!hasCleanupPerm" class="hint">当前账号没有 retention:run 权限，无法执行</span>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <el-card shadow="never" class="block-gap-top">
          <el-alert
            v-if="cleanupReport"
            :type="cleanupReport.dryRun ? 'info' : 'success'"
            :closable="false"
            show-icon
          >
            <template #title>
              {{ cleanupReport.dryRun ? '预演结果' : '清理完成' }}：{{ cleanupReport.message }}
            </template>
            <div class="report-meta">
              截止 {{ formatDateTime(cleanupReport.cutoff) }} · 消息 {{ cleanupReport.deletedMessages }} 条 ·
              会话 {{ cleanupReport.deletedConversations }} 个 · {{ cleanupReport.batches }} 批 · 耗时
              {{ cleanupReport.durationMs }} ms
            </div>
          </el-alert>

          <ul class="notes">
            <li>
              <strong>清理对象一：消息</strong>（<code>ai_message</code>）——按 <code>created_at</code> 分批物理删除，
              单批 2000 条；不区分是否被用户软删，软删的消息同样会被清掉。
            </li>
            <li>
              <strong>清理对象二：会话</strong>（<code>ai_conversation</code>）——按 <code>last_active_at</code> 删除
              <strong>已无任何消息</strong>的会话，所以必须先删消息、再删会话，顺序不可颠倒。
            </li>
            <li>
              <strong>失败可见</strong>——执行失败时错误原因会写入 <code>ai.cleanup.last_error</code> 并显示在左侧卡片上，
              不必翻服务器日志。清理动作本身也会记入审计日志。
            </li>
            <li>
              <strong>单轮上限</strong>——消息与会话两张表共用一份删除预算；触顶后剩余数据留到下次，
              页面会提示「达到单轮上限」。
            </li>
          </ul>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- ============ 会话消息抽屉（接口 2，全文） ============ -->
    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="60%" destroy-on-close>
      <el-table :data="drawerMessages" v-loading="drawerLoading" border>
        <el-table-column prop="role" label="角色" width="80">
          <template #default="{ row }">
            <el-tag :type="row.role === 'user' ? 'primary' : 'success'" size="small">
              {{ row.role === 'user' ? '用户' : '助手' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容（全文）" min-width="320">
          <template #default="{ row }">
            <div class="msg-content">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'blocked'" type="danger" size="small">已拦截</el-tag>
            <el-tag v-else-if="row.deleted" type="info" size="small">已清空</el-tag>
            <span v-else class="muted">{{ row.status }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Token" width="80">
          <template #default="{ row }">{{ row.tokenUsage ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="drawerQuery.page"
          v-model:page-size="drawerQuery.size"
          :total="drawerTotal"
          layout="total, prev, pager, next"
          @change="loadDrawerMessages"
        />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Delete } from '@element-plus/icons-vue'
import {
  aiAdminApi,
  type AdminMessage,
  type AdminSession,
  type AiCleanupReport,
  type AiCleanupStatus,
} from '@/api/ai'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'

/** 后端 §5.5 的四种消息状态，白名单外的值后端会直接 400，这里不给选项 */
const MESSAGE_STATUSES = [
  { value: 'completed', label: '已完成' },
  { value: 'interrupted', label: '已中断' },
  { value: 'empty', label: '空回复' },
  { value: 'blocked', label: '被敏感词拦截' },
]

const auth = useAuthStore()
const activeTab = ref('sessions')

// ---- 会话列表（接口 1）----
const sessionsLoading = ref(false)
const sessions = ref<AdminSession[]>([])
const sessionsTotal = ref(0)
const includeDeleted = ref(true)
const sessionRange = ref<string[]>([])
const sessionQuery = reactive<Record<string, any>>({ page: 1, size: 10 })

async function loadSessions() {
  sessionsLoading.value = true
  try {
    const params: Record<string, any> = { ...sessionQuery, includeDeleted: includeDeleted.value }
    const result = await aiAdminApi.conversations(params)
    sessions.value = result.list
    sessionsTotal.value = result.total
  } finally {
    sessionsLoading.value = false
  }
}

function searchSessions() {
  sessionQuery.page = 1
  loadSessions()
}

function searchSessionsWithRange() {
  if (sessionRange.value?.length === 2) {
    sessionQuery.from = sessionRange.value[0]
    sessionQuery.to = sessionRange.value[1]
  } else {
    delete sessionQuery.from
    delete sessionQuery.to
  }
  searchSessions()
}

// ---- 消息检索（接口 3）----
const messagesLoading = ref(false)
const messages = ref<AdminMessage[]>([])
const messagesTotal = ref(0)
const messageRange = ref<string[]>([])
const messageQuery = reactive<Record<string, any>>({ page: 1, size: 10 })

const isBlockedFilter = computed(() => messageQuery.status === 'blocked')

async function loadMessages() {
  messagesLoading.value = true
  try {
    const result = await aiAdminApi.searchMessages({ ...messageQuery })
    messages.value = result.list
    messagesTotal.value = result.total
  } finally {
    messagesLoading.value = false
  }
}

function searchMessages() {
  messageQuery.page = 1
  loadMessages()
}

function searchMessagesWithRange() {
  if (messageRange.value?.length === 2) {
    messageQuery.from = messageRange.value[0]
    messageQuery.to = messageRange.value[1]
  } else {
    delete messageQuery.from
    delete messageQuery.to
  }
  searchMessages()
}

function resetMessages() {
  Object.keys(messageQuery).forEach((k) => {
    if (k !== 'page' && k !== 'size') delete messageQuery[k]
  })
  messageRange.value = []
  searchMessages()
}

// ---- 会话消息抽屉（接口 2，content 是全文）----
const drawerVisible = ref(false)
const drawerLoading = ref(false)
const drawerMessages = ref<AdminMessage[]>([])
const drawerTotal = ref(0)
const drawerQuery = reactive({ page: 1, size: 20 })
const drawerSessionId = ref('')

const drawerTitle = computed(() => `会话消息 · ${drawerSessionId.value}`)

async function loadDrawerMessages() {
  drawerLoading.value = true
  try {
    const result = await aiAdminApi.conversationMessages(drawerSessionId.value, { ...drawerQuery })
    drawerMessages.value = result.list
    drawerTotal.value = result.total
  } finally {
    drawerLoading.value = false
  }
}

function openMessages(row: AdminSession) {
  drawerSessionId.value = row.id
  drawerQuery.page = 1
  drawerVisible.value = true
  loadDrawerMessages()
}

// ---- 清理（接口 4、5）----
const cleanupLoading = ref(false)
const previewing = ref(false)
const running = ref(false)
const cleanupStatus = ref<AiCleanupStatus | null>(null)
const cleanupReport = ref<AiCleanupReport | null>(null)

/** 执行清理的权限码与后端 @PreAuthorize 一致；没有就禁用按钮而不是点了报 403 */
const hasCleanupPerm = computed(() => auth.hasPerm('retention:run'))

/** cron → 人类可读（只识别「每日 HH:mm」这一常见形式，其余原样展示），同 RetentionView 的做法 */
const cronText = computed(() => {
  const cron = cleanupStatus.value?.cron
  if (!cron) return '-'
  const match = /^0\s+(\d{1,2})\s+(\d{1,2})\s+\*\s+\*\s+\*$/.exec(cron)
  if (!match) return cron
  return `每日 ${match[2].padStart(2, '0')}:${match[1].padStart(2, '0')}（cron: ${cron}）`
})

const lastRunText = computed(() => {
  const s = cleanupStatus.value
  if (!s?.lastRunAt) return '暂无记录'
  const trigger = s.lastRunTrigger === 'manual' ? '手动' : s.lastRunTrigger === 'scheduled' ? '定时' : s.lastRunTrigger
  return `${formatDateTime(s.lastRunAt)}（${trigger}）`
})

async function loadCleanupStatus() {
  cleanupLoading.value = true
  try {
    cleanupStatus.value = await aiAdminApi.cleanupStatus()
  } finally {
    cleanupLoading.value = false
  }
}

async function previewCleanup() {
  previewing.value = true
  try {
    cleanupReport.value = await aiAdminApi.cleanup(true)
  } finally {
    previewing.value = false
  }
}

async function runCleanup() {
  await ElMessageBox.confirm(
    `将物理删除超过 ${cleanupStatus.value?.retentionDays ?? 90} 天的 AI 消息与已无消息的会话，操作不可恢复。确定继续吗？`,
    '确认清理 AI 历史',
    { type: 'warning', confirmButtonText: '确定清理' },
  )
  running.value = true
  try {
    cleanupReport.value = await aiAdminApi.cleanup(false)
    ElMessage.success('清理已执行')
    await loadCleanupStatus()
  } finally {
    running.value = false
  }
}

onMounted(() => {
  loadSessions()
  // ⚠️ 消息列表也要在打开时加载：messageQuery 初始只有 {page,size}、没有 status 过滤，
  // 所以这就是「全部消息」。不加载的话表格初始是空的，必须点一次「查询」才出数据。
  loadMessages()
  loadCleanupStatus()
})
</script>

<style scoped>
.block-gap {
  margin-bottom: 16px;
}

.block-gap-top {
  margin-top: 16px;
}

.card-title {
  font-weight: 600;
  color: #0f172a;
}

.hint {
  margin-left: 4px;
  font-size: 12px;
  color: #94a3b8;
}

.muted {
  color: #94a3b8;
  font-size: 12px;
}

.error-text {
  color: #ef4444;
}

.desc {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.7;
  color: #475569;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.report-meta {
  margin-top: 6px;
  font-size: 12px;
  color: #64748b;
}

.msg-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  font-size: 13px;
}

.notes {
  margin: 12px 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 2;
  color: #475569;
}

.notes code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #4f46e5;
  font-size: 12px;
}
</style>
