<template>
  <div class="error-page">
    <div class="error-glow" aria-hidden="true"></div>
    <div class="error-code">403</div>
    <div class="error-title">访问被拒绝</div>
    <div class="error-text">抱歉，您没有权限访问该页面</div>
    <el-button type="primary" class="error-btn" @click="$router.push('/')">返回首页</el-button>
  </div>
</template>

<style scoped>
.error-page {
  position: relative;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  overflow: hidden;
  background: #f5f6fa;
}
.error-glow {
  position: absolute;
  width: 480px;
  height: 480px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.14), transparent 65%);
  filter: blur(40px);
  pointer-events: none;
}
.error-code {
  position: relative;
  font-size: 96px;
  font-weight: 800;
  line-height: 1.1;
  background: linear-gradient(135deg, #6366f1, #a855f7);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  letter-spacing: 4px;
}
.error-title {
  position: relative;
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
  margin-top: 8px;
}
.error-text {
  position: relative;
  color: #94a3b8;
  font-size: 14px;
}
.error-btn {
  position: relative;
  margin-top: 20px;
  padding: 0 28px;
}
</style>
