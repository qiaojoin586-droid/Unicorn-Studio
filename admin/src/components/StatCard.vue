<template>
  <el-card shadow="never" class="stat-card">
    <div class="stat-inner">
      <div class="stat-icon" :style="{ color: color || 'var(--admin-ink)' }">
        <el-icon :size="22"><component :is="icon" /></el-icon>
      </div>
      <div class="stat-info">
        <div class="stat-value">{{ value }}</div>
        <div class="stat-label">{{ label }}</div>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
defineProps<{
  label: string
  value: number | string
  icon: string
  color?: string
}>()
</script>

<style scoped>
.stat-card {
  position: relative;
  overflow: hidden;
  --el-card-padding: 18px;
  cursor: default;
}
.stat-inner {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 14px;
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 5px;
  background: var(--el-fill-color-light);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--admin-ink);
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 13px;
  color: var(--admin-muted);
  margin-top: 3px;
}
.stat-glow {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
</style>
