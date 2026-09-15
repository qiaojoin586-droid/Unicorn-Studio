<template>
  <!-- A-02：locale 改由 config-provider 提供（原先随 app.use(ElementPlus, { locale: zhCn }) 全量注册） -->
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>
