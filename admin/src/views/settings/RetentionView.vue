<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">数据保留</span>
      <el-button type="primary" :loading="saving" @click="save">保存策略</el-button>
    </div>

    <el-alert
      title="审计日志与 SQL 监控数据仅保留最近 N 天，超期数据由清理任务自动删除；保留期限可在此调整（7 / 15 / 30 / 90 天），保存后即时生效，无需重启服务。"
      type="info"
      :closable="false"
      class="block-gap"
    />

    <el-row :gutter="16">
      <el-col :xs="24" :md="12">
        <el-card shadow="never" v-loading="loading">
          <template #header><span class="card-title">策略配置</span></template>
          <el-form label-width="130px">
            <el-form-item label="保留期限">
              <el-select v-model="form.days" style="width: 200px">
                <el-option v-for="d in config.options" :key="d" :label="`${d} 天`" :value="d" />
              </el-select>
              <span class="hint">超期数据将被清理任务删除</span>
            </el-form-item>
            <el-form-item label="自动清理">
              <el-switch v-model="form.enabled" />
              <span class="hint">{{ form.enabled ? '每日定时自动执行' : '已关闭，仅支持手动立即清理' }}</span>
            </el-form-item>
            <el-form-item label="执行周期">
              <span class="static-text">{{ scheduleText }}</span>
            </el-form-item>
            <el-form-item label="单轮删除上限">
              <span class="static-text">{{ config.maxDeletePerRun.toLocaleString() }} 条</span>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="12">
        <el-card shadow="never" v-loading="loading">
          <template #header><span class="card-title">运行状态</span></template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="当前生效保留期">{{ config.days }} 天</el-descriptions-item>
            <el-descriptions-item label="下次自动执行">{{ formatDateTime(status?.nextRunAt) }}</el-descriptions-item>
            <el-descriptions-item label="上次执行">{{ lastRunText }}</el-descriptions-item>
            <el-descriptions-item label="上次删除条数">
              {{ status?.lastRun ? status.lastRun.deletedAuditRows : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="SQL 监控上次重置">
              {{ formatDateTime(status?.sqlMonitorLastResetAt) }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block-gap-top">
      <template #header><span class="card-title">立即清理</span></template>
      <p class="desc">
        清理将<strong>物理删除</strong>截止时间之前产生的审计日志（不可从库内恢复），裁剪超期的审计日志文件目录，
        并按保留周期重置 SQL 监控内存统计。建议先「预览待清理数据」确认影响范围后再执行。
      </p>

      <div class="actions">
        <el-button :loading="previewing" @click="preview">预览待清理数据</el-button>
        <el-checkbox v-model="resetSqlMonitor" class="reset-check">同时重置 SQL 监控统计</el-checkbox>
        <el-button type="danger" :loading="running" @click="runCleanup">
          <el-icon><Delete /></el-icon>&nbsp;立即执行清理
        </el-button>
      </div>

      <el-alert
        v-if="previewData"
        type="warning"
        :closable="false"
        class="block-gap-top"
        show-icon
      >
        <template #title>
          截止 {{ formatDateTime(previewData.cutoff) }} 之前：审计日志 {{ previewData.expiredAuditRows }} 条待删除
          （现有共 {{ previewData.totalAuditRows }} 条），审计文件目录 {{ previewData.logDirsToDelete }} 个待裁剪（约
          {{ formatSize(previewData.logFilesBytes) }}）
        </template>
      </el-alert>

      <el-alert
        v-if="report"
        :type="report.skipped ? 'info' : 'success'"
        :closable="false"
        class="block-gap-top"
        show-icon
      >
        <template #title>
          <template v-if="report.skipped">{{ report.message }}</template>
          <template v-else>{{ report.dryRun ? '' : '清理完成：' }}{{ report.message }}</template>
        </template>
        <div v-if="!report.skipped" class="report-meta">
          触发方式：{{ report.trigger === 'manual' ? '手动' : '定时' }} · 保留 {{ report.retentionDays }} 天 · 截止
          {{ formatDateTime(report.cutoff) }} · 耗时 {{ report.durationMs }} ms
        </div>
      </el-alert>
    </el-card>

    <el-card shadow="never" class="block-gap-top">
      <template #header><span class="card-title">策略说明</span></template>
      <ul class="notes">
        <li>
          <strong>清理对象一：审计日志</strong>（数据库表 <code>sys_operation_log</code>）——按
          <code>created_at</code> 分批物理删除，命中时间索引，单批 2000 条以避免长事务与行锁。
        </li>
        <li>
          <strong>清理对象二：SQL 监控数据</strong>——Druid 统计为 JVM 内存聚合，无逐条时间戳，无法按记录裁剪；
          采用「距上次重置满 N 天则整体重置」的方式，保证统计窗口不超过保留期（重置时间落库，重启不丢）。
        </li>
        <li>
          <strong>清理对象三：清理操作自身的日志</strong>——清理任务本身是审计写接口，其产生的数据库审计行会在下一轮
          按同一规则删除；管理端审计文件（<code>logs/admin/yyyy-MM-dd/</code>）由本任务按同一保留期裁剪整个日期目录，
          因此清理日志不会自我堆积。
        </li>
        <li>
          <strong>触发方式</strong>——① 定时任务（执行周期见上，错开备份/统计等既有任务）；② 本页「立即执行清理」。
          手动执行不受「自动清理」开关限制。
        </li>
        <li>
          <strong>注意：审计日志文件另受 logback 30 天上限约束</strong>——<code>logs/admin/</code> 下的日期目录由本任务按
          保留期裁剪，但日志框架自身的 <code>maxHistory=30</code> 仍是硬上限，故保留期设为 90 天时文件侧实际仍为 30 天。
          数据库中的审计日志不受此限制。
        </li>
        <li>
          <strong>安全与可恢复性</strong>——删除为物理删除，无法从数据库回滚；后台提供预览（dry-run）与单轮删除上限，
          并依赖既有的每日数据库备份（数据备份页）作为恢复手段，因此<strong>保留期建议不短于备份保留份数</strong>。
        </li>
      </ul>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import { retentionApi, type RetentionConfig, type RetentionPreview, type RetentionRunReport, type RetentionStatus } from '@/api/retention'
import { formatDateTime, formatSize } from '@/utils/format'

const loading = ref(false)
const saving = ref(false)
const previewing = ref(false)
const running = ref(false)
const resetSqlMonitor = ref(true)

const status = ref<RetentionStatus | null>(null)
const previewData = ref<RetentionPreview | null>(null)
const report = ref<RetentionRunReport | null>(null)

/** 后端未加载完成时的占位，避免模板取空报错 */
const config = reactive<RetentionConfig>({
  days: 7,
  enabled: true,
  cron: '0 30 3 * * *',
  maxDeletePerRun: 50000,
  options: [7, 15, 30, 90],
})

const form = reactive<{ days: number; enabled: boolean }>({ days: 7, enabled: true })

/** cron → 人类可读（仅识别「每日 HH:mm」这一常见形式，其余原样展示） */
const scheduleText = computed(() => {
  const cron = config.cron
  const match = /^0\s+(\d{1,2})\s+(\d{1,2})\s+\*\s+\*\s+\*$/.exec(cron)
  if (match) {
    const minute = match[1].padStart(2, '0')
    const hour = match[2].padStart(2, '0')
    return `每日 ${hour}:${minute}（cron: ${cron}）`
  }
  return cron
})

const lastRunText = computed(() => {
  const last = status.value?.lastRun
  if (!last) return '暂无记录'
  const trigger = last.trigger === 'manual' ? '手动' : last.trigger === 'scheduled' ? '定时' : last.trigger
  return `${formatDateTime(last.startedAt)}（${trigger}）`
})

function applyStatus(data: RetentionStatus) {
  status.value = data
  Object.assign(config, data.config)
  form.days = data.config.days
  form.enabled = data.config.enabled
}

async function load() {
  loading.value = true
  try {
    applyStatus(await retentionApi.status())
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await retentionApi.update({ days: form.days, enabled: form.enabled })
    ElMessage.success('保留策略已保存，下次清理即时生效')
    await load()
  } finally {
    saving.value = false
  }
}

async function preview() {
  previewing.value = true
  try {
    previewData.value = await retentionApi.preview()
    report.value = null
  } finally {
    previewing.value = false
  }
}

async function runCleanup() {
  const cutoff = previewData.value?.cutoff
  const tip = cutoff
    ? `将删除 ${formatDateTime(cutoff)} 之前的审计日志（${previewData.value?.expiredAuditRows ?? 0} 条），操作不可恢复。确定继续吗？`
    : '将按当前保留期删除超期审计日志并裁剪日志目录，操作不可恢复。确定继续吗？'
  await ElMessageBox.confirm(tip, '确认立即清理', { type: 'warning', confirmButtonText: '确定清理' })
  running.value = true
  try {
    report.value = await retentionApi.cleanup({ dryRun: false, resetSqlMonitor: resetSqlMonitor.value })
    previewData.value = null
    ElMessage.success('清理已执行')
    await load()
  } finally {
    running.value = false
  }
}

onMounted(load)
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
  margin-left: 12px;
  font-size: 12px;
  color: #94a3b8;
}

.static-text {
  font-size: 13px;
  color: #334155;
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

.reset-check {
  margin-right: 4px;
}

.report-meta {
  margin-top: 6px;
  font-size: 12px;
  color: #64748b;
}

.notes {
  margin: 0;
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
