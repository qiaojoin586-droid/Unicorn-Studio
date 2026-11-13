// 页脚备案号回归：使用模拟公开配置，不访问备案系统或生产数据库。
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { readdir, mkdir } from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const { chromium } = require(process.env.PLAYWRIGHT_CORE || 'D:/nodejs/node_global/node_modules/@executeautomation/playwright-mcp-server/node_modules/playwright-core')
const cache = path.join(os.homedir(), 'AppData/Local/ms-playwright')
const chrome = process.env.CHROME_PATH || (await readdir(cache)).filter(name => name.startsWith('chromium-')).sort().reverse()
  .map(name => path.join(cache, name, 'chrome-win64/chrome.exe')).find(existsSync)
assert(chrome, '未找到 Chromium')
let scenario = 'default'
const html = `<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><main id="main-content"></main><div id="app"></div><script type="module">
import {createApp} from 'vue';import {createRouter,createMemoryHistory} from 'vue-router';import Footer from '/src/components/AppFooter.vue';import '/src/assets/main.css';
const app=createApp(Footer);app.use(createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]}));app.mount('#app');
</script></body></html>`
const server = await createServer({ root, configFile: path.join(root, 'vite.config.js'), server: { host: '127.0.0.1', port: 0, open: false },
  plugins: [{ name: 'icp-footer-harness', configureServer(vite) { vite.middlewares.use(async (req, res, next) => {
    const pathname = new URL(req.url, 'http://localhost').pathname
    if (pathname === '/__icp') { res.setHeader('Content-Type', 'text/html; charset=UTF-8'); res.end(await vite.transformIndexHtml(pathname, html)); return }
    if (!pathname.startsWith('/api/')) return next()
    let data = {}
    if (pathname === '/api/site/settings') {
      if (scenario === 'unavailable') res.statusCode = 503
      data = { basic: { 'basic.icp': scenario === 'custom' ? '  测试备案字段  ' : '' } }
    }
    res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ code: res.statusCode, data }))
  }) } }],
})
let browser
try {
  await server.listen()
  browser = await chromium.launch({ executablePath: chrome, headless: true })
  const errors = []
  for (const mode of ['default', 'custom', 'unavailable']) {
    scenario = mode
    const page = await browser.newPage({ viewport: mode === 'custom' ? { width: 390, height: 844 } : { width: 1280, height: 900 }, reducedMotion: 'reduce' })
    page.on('pageerror', error => errors.push(error.message))
    await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/__icp`)
    const link = page.locator('.footer-registration a')
    await page.waitForFunction(expected => document.querySelector('.footer-registration a')?.textContent.startsWith(expected),
      mode === 'custom' ? '测试备案字段' : '豫ICP备2026050572号-1')
    assert.equal(await link.getAttribute('href'), 'https://beian.miit.gov.cn/')
    assert.equal(await link.getAttribute('target'), '_blank')
    assert.match(await link.getAttribute('rel'), /noopener/)
    assert(await page.evaluate(() => document.querySelector('.site-footer .page-shell').lastElementChild.classList.contains('footer-registration')))
    assert.equal(await page.getByRole('link', { name: /回到顶部/ }).count(), 1)
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    if (mode === 'default') {
      const artifacts = path.resolve(root, '../backend/target/icp-footer')
      await mkdir(artifacts, { recursive: true })
      await page.locator('.site-footer').screenshot({ path: path.join(artifacts, 'desktop.png') })
    }
    await page.close()
  }
  assert.deepEqual(errors, [])
  console.log('ICP footer regression passed: default number, admin setting override/trim, API failure fallback, official link, bottom placement, mobile layout and existing footer controls.')
} finally { await browser?.close(); await server.close() }
