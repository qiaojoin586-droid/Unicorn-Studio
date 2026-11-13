// 真实 Vue 注册弹窗 + 模拟 HTTP 接口，不调用后端、不发送真实短信。
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { mkdir, readdir } from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(import.meta.url)
let playwright
for (const candidate of [process.env.PLAYWRIGHT_CORE, 'playwright-core',
  'D:/nodejs/node_global/node_modules/@executeautomation/playwright-mcp-server/node_modules/playwright-core'].filter(Boolean)) {
  try { playwright = require(candidate); break } catch { /* try next */ }
}
assert(playwright, '未找到 playwright-core')
const cache = path.join(os.homedir(), 'AppData/Local/ms-playwright')
const versions = (await readdir(cache)).filter(name => name.startsWith('chromium-')).sort().reverse()
const executablePath = process.env.CHROME_PATH || versions
  .map(version => path.join(cache, version, 'chrome-win64/chrome.exe')).find(existsSync)
assert(executablePath, '未找到 Chromium')

let mode = 'normal'
let sent = 0
const html = `<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body><div id="app"></div><script type="module">
import { createApp } from 'vue'; import { createPinia } from 'pinia';
import AiLoginPrompt from '/src/components/AiLoginPrompt.vue';
import { useAiChatStore } from '/src/stores/aiChat.js';
import '/src/assets/main.css';
const pinia = createPinia(); const app = createApp(AiLoginPrompt); app.use(pinia); app.mount('#app');
window.__store = useAiChatStore(pinia); window.__store.showLoginPrompt = true;
window.__offset = 0; const realNow = Date.now; Date.now = () => realNow() + window.__offset;
</script></body></html>`

const server = await createServer({ root, configFile: path.join(root, 'vite.config.js'),
  server: { host: '127.0.0.1', port: 0, open: false },
  plugins: [{ name: 'registration-code-harness', configureServer(vite) {
    vite.middlewares.use(async (req, res, next) => {
      const pathname = new URL(req.url, 'http://localhost').pathname
      if (pathname === '/__registration_test') {
        res.setHeader('Content-Type', 'text/html; charset=UTF-8')
        res.end(await vite.transformIndexHtml(pathname, html)); return
      }
      if (!pathname.startsWith('/api/')) return next()
      res.setHeader('Content-Type', 'application/json; charset=UTF-8')
      if (pathname !== '/api/auth/register/code') {
        res.end(JSON.stringify({ code: 200, data: {} })); return
      }
      sent++
      const limited = mode === 'quota' || mode === 'cooldown'
      const retryAfterSeconds = mode === 'quota' ? 2400 : mode === 'cooldown' ? 24 : mode === 'tenth' ? 1800 : 60
      const message = mode === 'quota'
        ? '该手机号每小时最多发送10条验证码，当前额度已用完，请在40分钟后重试'
        : mode === 'cooldown' ? '验证码已发送，请在24秒后重新获取' : 'success'
      res.statusCode = limited ? 429 : 200
      if (limited) res.setHeader('Retry-After', String(retryAfterSeconds))
      res.end(JSON.stringify({ code: res.statusCode, message,
        data: { retryAfterSeconds, remainingHourlySends: mode === 'tenth' ? 0 : 9 } }))
    })
  } }],
})

let browser
try {
  await server.listen()
  browser = await playwright.chromium.launch({ executablePath, headless: true })
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, reducedMotion: 'reduce' })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/__registration_test`)
  await page.getByRole('button', { name: '立即注册', exact: true }).click()
  const phone = page.getByPlaceholder('11 位手机号')
  const button = page.getByRole('button', { name: /获取验证码|后重试|获取中/ })
  await phone.fill('13900000000')
  await button.click()
  await page.getByText('请填写获取到的 6 位验证码，5 分钟内有效', { exact: true }).waitFor()
  assert(await button.isDisabled())
  assert.equal(sent, 1)

  await page.evaluate(() => { window.__offset += 61_000 })
  await page.waitForFunction(() => [...document.querySelectorAll('button')]
    .some(node => node.textContent.trim() === '获取验证码' && !node.disabled))
  await button.click()
  await page.waitForFunction(() => [...document.querySelectorAll('button')]
    .some(node => node.textContent.includes('秒后重试') && node.disabled))
  assert.equal(sent, 2, '一分钟后必须可以发送第二条')

  mode = 'tenth'
  await page.evaluate(() => { window.__offset += 61_000 })
  await page.waitForFunction(() => [...document.querySelectorAll('button')]
    .some(node => node.textContent.trim() === '获取验证码' && !node.disabled))
  await button.click()
  await page.getByText('验证码已发送，5 分钟内有效。该手机号每小时最多发送10条验证码，当前额度已用完', { exact: true }).waitFor()
  assert.match(await button.innerText(), /30 分钟后重试/)
  assert(await button.isDisabled())

  mode = 'quota'
  await phone.fill('13800000000')
  assert(await button.isEnabled(), '更换手机号不能共享上一号码的冷却')
  await button.click()
  await page.getByText('该手机号每小时最多发送10条验证码，当前额度已用完，请在40分钟后重试', { exact: true }).waitFor()
  assert.match(await button.innerText(), /40 分钟后重试/)
  const requestsAtLimit = sent
  await button.dispatchEvent('click')
  assert.equal(sent, requestsAtLimit, '限流提示后不重复请求')

  const artifacts = path.resolve(root, '../backend/target/registration-code')
  await mkdir(artifacts, { recursive: true })
  await page.screenshot({ path: path.join(artifacts, 'quota-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: path.join(artifacts, 'quota-phone.png') })

  mode = 'cooldown'
  await phone.fill('13700000000')
  await button.click()
  await page.getByText('验证码已发送，请在24秒后重新获取', { exact: true }).waitFor()
  assert.match(await button.innerText(), /24 秒后重试/)
  assert(await button.isDisabled())
  await page.getByRole('button', { name: '关闭登录弹窗', exact: true }).click()
  await page.evaluate(() => window.__store.showLoginPrompt = true)
  assert.deepEqual(errors, [])
  console.log('Registration SMS UI regression passed: resend after 60s, tenth-message notice, hourly quota, 429 countdown, independent phone cooldown, desktop/mobile render; no real SMS sent.')
} finally {
  await browser?.close()
  await server.close()
}
