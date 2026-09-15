import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080'

// base:'/admin/' 与生产 nginx 部署路径一致（实施方案 §1.2，不配则生产资源 404 白屏）
export default defineConfig({
  base: '/admin/',
  plugins: [
    vue(),
    // A-02：Element Plus 按需引入。两个插件缺一不可 ——
    //   AutoImport  管**函数式 API**（ElMessage / ElMessageBox / ElNotification / ElLoading…）
    //   Components  管**模板组件**（<el-*>）与**指令**（v-loading / v-infinite-scroll）
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: 'src/types/auto-imports.d.ts',
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/types/components.d.ts',
      // 本地组件保持显式 import（项目里各处都写全了，交给解析器反而掩盖拼写错误）
      dirs: [],
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
      '/uploads': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    // A-13：原先 chunkSizeWarningLimit: 1024 把阈值从默认 500 KB 抬到 1024 KB，
    // 恰好让 DashboardView（ECharts 全量，1021.4 KB）差 2.6 KB 逃过告警 —— 这就是这个
    // 1 MB 路由 chunk 长期没被发现的原因。回落默认值，让三个大 chunk 全部暴露。
    // （阶段一完成 A-02/A-03/A-04 后，此处仍保持默认，用告警守住不回归。）
    rollupOptions: {
      output: {
        // A-26：显式声明重量级依赖的落点，避免"谁先被引用就落进谁的路由 chunk"。
        //
        // ⚠️ 这里**刻意不把 element-plus 合成一个 vendor chunk**：A-02 改成按需引入后，
        // el-* 组件是**按路由分散**的（列表页要 table/pagination，表单页要 form/date-picker…）。
        // 若强行合并成一个 chunk，则任何页面都会把"全项目用到的所有 el-* 组件"一起拉下来，
        // **直接把 A-02 的按需收益抵消掉**。element-plus 交由 Rollup 默认算法按引用关系切分。
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('/echarts/') || id.includes('/zrender/')) return 'vendor-echarts'
          if (id.includes('@wangeditor')) return 'vendor-editor'
          if (
            /node_modules\/(vue|vue-router|pinia|@vue)\//.test(id) ||
            id.includes('/node_modules/@vue/')
          ) {
            return 'vendor-vue'
          }
          return
        },
      },
    },
  },
})
