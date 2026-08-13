<template>
  <el-card shadow="hover" class="stat-card">
    <div class="stat-inner">
      <div class="stat-icon" :style="{ background: color + '1a', color }">
        <el-icon :size="26"><component :is="icon" /></el-icon>
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
  --el-card-padding: 16px;
}
.stat-inner {
  display: flex;
  align-items: center;
  gap: 12px;
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
}
.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 2px;
}
</style>
