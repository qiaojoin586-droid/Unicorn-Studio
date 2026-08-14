<template>
  <el-card shadow="never">
    <template #header>
      <span class="chart-title">待办事项</span>
    </template>

    <div v-if="actions.length" class="quick-grid">
      <div
        v-for="item in actions"
        :key="item.path"
        class="quick-item"
        tabindex="0"
        @click="router.push(item.path)"
        @keydown.enter="router.push(item.path)"
      >
        <div
          class="quick-icon"
          :style="{
            background: `linear-gradient(135deg, ${item.color}26, ${item.color}0d)`,
            color: item.color,
          }"
        >
          <el-icon :size="20"><component :is="item.icon" /></el-icon>
          <span
            v-if="item.badge != null && item.badge > 0"
            class="quick-dot"
            :style="{ background: item.color }"
          >
            {{ item.badge }}
          </span>
        </div>
        <div class="quick-meta">
          <div class="quick-name">{{ item.label }}</div>
          <div class="quick-desc">{{ item.desc }}</div>
        </div>
        <el-icon class="quick-arrow"><ArrowRight /></el-icon>
      </div>
    </div>
    <el-empty v-else description="暂无可用待办" :image-size="80" />

    <div class="quick-summary">
      <span>近 {{ days }} 天累计</span>
      <span class="qs-item">PV <b>{{ totalPv }}</b></span>
      <span class="qs-item">UV <b>{{ totalUv }}</b></span>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{
  /** 当前统计窗口天数 */
  days: number
  /** 窗口内总 PV */
  totalPv: number
  /** 窗口内总 UV */
  totalUv: number
  /** 待审评论数（角标） */
  pendingComments: number
  /** 新留言数（角标） */
  newMessages: number
}>()

const router = useRouter()
const auth = useAuthStore()

interface QuickAction {
  /** 权限码（无权限时不展示该入口） */
  permission: string
  /** 跳转路径 */
  path: string
  /** 标题 */
  label: string
  /** 副标题 */
  desc: string
  /** Element Plus 图标组件名 */
  icon: string
  /** 图标颜色 */
  color: string
  /** 待办角标数量（null 表示不显示） */
  badge: number | null
}

/** 待办入口列表：按权限码过滤，无对应权限的入口不展示 */
const actions = computed<QuickAction[]>(() =>
  [
    {
      permission: 'comment:list',
      path: '/comments',
      label: '评论审核',
      desc: '待审核评论处理',
      icon: 'ChatDotRound',
      color: '#ef4444',
      badge: props.pendingComments,
    },
    {
      permission: 'message:list',
      path: '/messages',
      label: '留言管理',
      desc: '新留言待回复',
      icon: 'Message',
      color: '#22c55e',
      badge: props.newMessages,
    },
    {
      permission: 'system:backup',
      path: '/backups',
      label: '数据备份',
      desc: '数据库备份维护',
      icon: 'FolderOpened',
      color: '#6366f1',
      badge: null,
    },
  ].filter((a) => auth.hasPerm(a.permission)),
)
</script>

<style scoped>
.chart-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 15px;
  color: #0f172a;
}
.chart-title::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1, #a855f7);
  box-shadow: 0 0 8px rgba(99, 102, 241, 0.5);
}
.quick-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.quick-item {
  flex: 1;
  min-width: 180px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}
.quick-item:hover {
  border-color: color-mix(in srgb, var(--el-color-primary) 30%, white);
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06);
  transform: translateY(-2px);
}
.quick-icon {
  position: relative;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.quick-dot {
  position: absolute;
  top: -6px;
  right: -6px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  color: #fff;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.quick-meta {
  flex: 1;
  min-width: 0;
}
.quick-name {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}
.quick-desc {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 2px;
}
.quick-arrow {
  color: #cbd5e1;
  transition: transform 0.2s ease, color 0.2s ease;
}
.quick-item:hover .quick-arrow {
  color: var(--el-color-primary);
  transform: translateX(2px);
}
.quick-summary {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--el-border-color);
  font-size: 12px;
  color: #94a3b8;
}
.qs-item b {
  margin-left: 4px;
  font-size: 14px;
  color: #6366f1;
  font-variant-numeric: tabular-nums;
}
</style>
