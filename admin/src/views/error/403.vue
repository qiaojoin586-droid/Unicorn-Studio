<template>
  <div class="error-page">
    <div class="error-code">403</div>
    <div class="error-text">抱歉，您没有权限访问该页面</div>
    <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
  </div>
</template>

<style scoped>
.error-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
}
.error-code {
  font-size: 80px;
  font-weight: 700;
  color: #409eff;
}
.error-text {
  color: #909399;
  font-size: 16px;
}
</style>
