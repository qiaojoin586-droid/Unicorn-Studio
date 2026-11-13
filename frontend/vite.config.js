import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080'

export default defineConfig({
  base: '/',
  envDir: false,
  plugins: [vue()],
  build: {
    outDir: 'frontend',
  },
  server: {
    fs: { allow: [fileURLToPath(new URL('.', import.meta.url)), fileURLToPath(new URL('../shared', import.meta.url))] },
    proxy: {
      '/api': apiProxyTarget,
      // 后台上传的图片（轮播图等）存于后端 /uploads，需一并代理
      '/uploads': apiProxyTarget,
    },
  },
  resolve: {
    alias: {
      '@': '/src',
    },
  },
})
