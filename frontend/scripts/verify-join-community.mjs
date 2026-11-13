// 真实后台上传/保存组件与主站加入页；接口用隔离内存数据，不修改实际站点或上传文件。
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { mkdir, readFile, readdir } from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import { createPresentationSection } from '../../shared/presentation-contract.js'

const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const adminRoot = path.resolve(root, '../admin')
let playwright
for (const candidate of [process.env.PLAYWRIGHT_CORE, 'playwright-core',
  'D:/nodejs/node_global/node_modules/@executeautomation/playwright-mcp-server/node_modules/playwright-core'].filter(Boolean)) {
  try { playwright = require(candidate); break } catch { /* next */ }
}
assert(playwright, '未找到 playwright-core')
const browserCache = path.join(os.homedir(), 'AppData/Local/ms-playwright')
const chrome = process.env.CHROME_PATH || (await readdir(browserCache)).filter(name => name.startsWith('chromium-')).sort().reverse()
  .map(name => path.join(browserCache, name, 'chrome-win64/chrome.exe')).find(existsSync)
assert(chrome, '未找到 Chromium')
const fixture = await readFile(path.join(root, 'src/image/community-qr.jpg'))
let saved = { eyebrow: '', title: '加入我们', subtitle: '社群测试', paragraphs: ['保持原有社群入口。'], qrImage: '', qrCaption: '测试社群二维码' }
let extra = { ...createPresentationSection('join'), invitationText: '一起交流作品与技术。' }
let uploads = 0, writes = 0, joinReads = 0, failSave = false

const publicHtml = `<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body><div id="app"></div><script type="module">
import {createApp,h} from 'vue';import {createPinia} from 'pinia';
import {createRouter,createMemoryHistory,RouterView} from 'vue-router';import Join from '/src/pages/JoinPage.vue';import '/src/assets/main.css';
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/join',component:Join},{path:'/other',component:{template:'<p>其他页面</p>'}}]});
const app=createApp({render:()=>h(RouterView)});app.use(createPinia());app.use(router);await router.push('/join');await router.isReady();app.mount('#app');window.__router=router;
</script></body></html>`

function reply(res, data, code = 200, message = 'success') {
  res.statusCode = code; res.setHeader('Content-Type', 'application/json; charset=UTF-8')
  res.end(JSON.stringify({ code, message, data }))
}
async function body(req) { const parts = []; for await (const part of req) parts.push(part); return Buffer.concat(parts) }

const publicServer = await createServer({ root, configFile: path.join(root, 'vite.config.js'),
  server: { host: '127.0.0.1', port: 0, open: false }, plugins: [{ name: 'join-public-test', configureServer(vite) {
    vite.middlewares.use(async (req, res, next) => {
      const pathname = new URL(req.url, 'http://localhost').pathname
      if (pathname === '/__join') {
        res.setHeader('Content-Type', 'text/html; charset=UTF-8'); res.end(await vite.transformIndexHtml(pathname, publicHtml)); return
      }
      if (pathname === '/preview') { res.setHeader('Content-Type', 'text/html'); res.end('<p>Isolated preview frame</p>'); return }
      if (pathname.startsWith('/uploads/')) {
        if (pathname.includes('missing')) { res.statusCode = 404; res.end(); return }
        res.setHeader('Content-Type', 'image/jpeg'); res.end(fixture); return
      }
      if (pathname === '/api/join') { joinReads++; return reply(res, saved) }
      if (pathname === '/api/site/presentation') return reply(res, { join: extra })
      if (pathname.startsWith('/api/')) return reply(res, {})
      next()
    })
  } }] })

let adminServer, browser
try {
  await publicServer.listen()
  const publicOrigin = `http://127.0.0.1:${publicServer.httpServer.address().port}`
  const adminHtml = `<!doctype html><html lang="zh-CN"><body><div id="app"></div><script type="module">
import {createApp,h} from 'vue';import {createPinia} from 'pinia';import {createRouter,createMemoryHistory,RouterView} from 'vue-router';
import Join from '/admin/src/views/join/JoinView.vue';import {useAuthStore} from '/admin/src/stores/auth.ts';
import permission from '/admin/src/directives/permission.ts';import 'element-plus/dist/index.css';
const app=createApp({render:()=>h(RouterView)}),pinia=createPinia();app.use(pinia);app.directive('permission',permission);
const auth=useAuthStore(pinia);auth.token='join-test-token';auth.permissions=['join:view','join:edit'];
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Join}]});app.use(router);await router.push('/');await router.isReady();app.mount('#app');
</script></body></html>`
  adminServer = await createServer({ root: adminRoot, configFile: path.join(adminRoot, 'vite.config.ts'),
    define: { 'import.meta.env.VITE_PUBLIC_SITE_URL': JSON.stringify(publicOrigin) },
    server: { host: '127.0.0.1', port: 0, open: false }, plugins: [{ name: 'join-admin-test', configureServer(vite) {
      vite.middlewares.use(async (req, res, next) => {
        const pathname = new URL(req.url, 'http://localhost').pathname
        if (pathname === '/admin/__join') {
          res.setHeader('Content-Type', 'text/html; charset=UTF-8'); res.end(await vite.transformIndexHtml(pathname, adminHtml)); return
        }
        if (pathname.startsWith('/uploads/')) { res.setHeader('Content-Type', 'image/jpeg'); res.end(fixture); return }
        if (pathname === '/api/admin/files/upload') {
          assert((await body(req)).includes(Buffer.from('new-community.jpg')))
          uploads++; return reply(res, { url: `/uploads/community-test-${uploads}.jpg`, name: 'new-community.jpg', size: fixture.length })
        }
        if (pathname === '/api/admin/join') {
          if (req.method === 'PUT') {
            if (failSave) return reply(res, null, 503, '模拟保存失败')
            saved = JSON.parse((await body(req)).toString()); writes++
          }
          return reply(res, saved)
        }
        if (pathname === '/api/admin/presentation/join') {
          if (req.method === 'PUT') extra = JSON.parse((await body(req)).toString())
          return reply(res, extra)
        }
        if (pathname.startsWith('/api/')) return reply(res, {})
        next()
      })
    } }] })
  await adminServer.listen()
  const adminOrigin = `http://127.0.0.1:${adminServer.httpServer.address().port}`
  browser = await playwright.chromium.launch({ executablePath: chrome, headless: true })
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, reducedMotion: 'reduce' })
  await context.route('**/*', route => [publicOrigin, adminOrigin].includes(new URL(route.request().url()).origin)
    ? route.continue() : route.abort())
  const page = await context.newPage(), admin = await context.newPage(), errors = []
  for (const tab of [page, admin]) tab.on('pageerror', error => errors.push(error.message))
  await page.goto(publicOrigin + '/__join')
  const qr = page.getByAltText('独角兽工作室社群二维码')
  await page.getByText('1107213369', { exact: true }).waitFor()
  await page.waitForFunction(() => document.querySelector('.join-qr-art img')?.naturalWidth > 0)
  const defaultSrc = await qr.getAttribute('src')
  await admin.goto(adminOrigin + '/admin/__join')
  const save = admin.getByRole('button', { name: '保存并更新主站', exact: true })
  await save.waitFor(); await save.isEnabled()
  const upload = admin.locator('.image-upload input[type=file]')
  await upload.setInputFiles({ name: 'new-community.jpg', mimeType: 'image/jpeg', buffer: fixture })
  await admin.waitForFunction(() => document.querySelector('.qr-preview img')?.getAttribute('src')?.includes('community-test-1'))
  assert.equal(saved.qrImage, '', '仅上传不能更改线上配置')
  await save.click()
  await admin.getByText('加入页配置已保存', { exact: true }).waitFor()
  assert.equal(writes, 1); assert.equal(saved.qrImage, '/uploads/community-test-1.jpg')
  await page.bringToFront(); await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await page.waitForFunction(() => document.querySelector('.join-qr-art img')?.getAttribute('src') === '/uploads/community-test-1.jpg')
  assert.equal(await page.getByText('1107213369', { exact: true }).count(), 0, '自定义社群不得显示旧群号')
  assert.equal(await page.locator('.qr-save-link[download]').getAttribute('href'), '/uploads/community-test-1.jpg')
  await page.reload(); await page.waitForFunction(() => document.querySelector('.join-qr-art img')?.getAttribute('src') === '/uploads/community-test-1.jpg')

  failSave = true
  await admin.bringToFront()
  await upload.setInputFiles({ name: 'new-community.jpg', mimeType: 'image/jpeg', buffer: fixture })
  await admin.waitForFunction(() => document.querySelector('.qr-preview img')?.getAttribute('src')?.includes('community-test-2'))
  const rejectedSave = admin.waitForResponse(response => response.url().endsWith('/api/admin/join') && response.status() === 503)
  await save.click(); await rejectedSave; await admin.locator('.el-alert--warning').waitFor()
  assert.equal(saved.qrImage, '/uploads/community-test-1.jpg', '保存失败不得覆盖已保存二维码')
  failSave = false

  await admin.locator('.image-upload .preview').hover()
  await admin.locator('.image-upload').getByRole('button', { name: '移除', exact: true }).click()
  const restoredSave = admin.waitForResponse(response => response.url().endsWith('/api/admin/join') && response.request().method() === 'PUT' && response.status() === 200)
  await save.click(); await restoredSave
  await page.bringToFront(); await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await page.waitForFunction(() => document.querySelector('.join-qr-art img')?.getAttribute('src')?.includes('community-qr.jpg'))
  assert.equal(await qr.getAttribute('src'), defaultSrc)
  await page.getByText('1107213369', { exact: true }).waitFor()

  saved.qrImage = '/uploads/missing-community.jpg'
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await page.getByText('二维码暂时无法显示。请使用下方加入入口或页面中的联系方式。', { exact: true }).waitFor()
  assert.equal(await qr.count(), 0); assert.equal(await page.getByText('1107213369', { exact: true }).count(), 0)
  const artifacts = path.resolve(root, '../backend/target/join-community')
  await mkdir(artifacts, { recursive: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: path.join(artifacts, 'unavailable-phone.png'), fullPage: true })
  await page.evaluate(() => window.__router.push('/other'))
  const readsAfterLeave = joinReads
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await page.waitForTimeout(50)
  assert.equal(joinReads, readsAfterLeave, '路由离开后须移除二维码刷新监听')
  assert.deepEqual(errors, [])
  console.log('Join community regression passed: real admin upload/save UI, public QR override, refresh/focus update, save failure preservation, default restoration, unavailable image handling, old group isolation, listener cleanup; no real site data changed.')
} finally {
  await browser?.close(); await adminServer?.close(); await publicServer.close()
}
