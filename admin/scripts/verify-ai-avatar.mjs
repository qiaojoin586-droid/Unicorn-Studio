// 运行管理端实际构建产物，接口使用内存数据，验证头像和连通性测试反馈。
import assert from 'node:assert/strict'
import http from 'node:http'
import { createRequire } from 'node:module'
import { readFile, readdir, mkdir } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const dist = path.join(root, 'dist')
const artifactDir = path.resolve(root, '../backend/target/ai-avatar')
await mkdir(artifactDir, { recursive: true })
const { chromium } = require(process.env.PLAYWRIGHT_CORE || 'D:/nodejs/node_global/node_modules/@executeautomation/playwright-mcp-server/node_modules/playwright-core')
const cache = path.join(os.homedir(), 'AppData/Local/ms-playwright')
const chrome = process.env.CHROME_PATH || (await readdir(cache)).filter(name => name.startsWith('chromium-')).sort().reverse()
  .map(name => path.join(cache, name, 'chrome-win64/chrome.exe')).find(existsSync)
assert(chrome, '未找到 Chromium')
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=', 'base64')
let config = { apiKeyMasked: 'test••••key', apiKeySet: true, apiUrl: 'https://example.com/v1', model: 'test-model',
  systemPrompt: '测试人设', maxTokens: 1024, temperature: 0.7, enabled: true, usable: true, cryptoEnabled: true,
  messageAvatarMode: 'default', messageAvatarUrl: '' }
let uploads = 0
let saves = []
let failUpload = false
let probeOutcome = 'success'
const probes = []
const readBody = req => new Promise((resolve, reject) => {
  const chunks = []; req.on('data', chunk => chunks.push(chunk)); req.on('end', () => resolve(Buffer.concat(chunks))); req.on('error', reject)
})
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' }
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost')
  const json = (data, status = 200, message = status === 200 ? 'success' : 'upload failed') => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ code: status, message, data })) }
  try {
    if (url.pathname === '/uploads/avatar-test.png') { res.writeHead(200, { 'Content-Type': 'image/png' }); res.end(png); return }
    if (url.pathname.startsWith('/api/')) {
      if (url.pathname === '/api/admin/auth/me') return json({ id: 1, username: 'avatar-test', realName: '头像测试', mustChangePassword: 0,
        permissions: ['ai:view', 'ai:edit', 'ai:test'], roles: [] })
      if (url.pathname === '/api/admin/ai-config/test') {
        assert.equal(req.headers.authorization, 'Bearer avatar-test-token')
        const body = JSON.parse((await readBody(req)).toString())
        probes.push(body)
        await new Promise(resolve => setTimeout(resolve, 350))
        if (probeOutcome === 'unavailable') return json(null, 503, '测试服务暂不可用')
        const messages = { connect: '连接超时：10 秒内未完成 TCP/TLS 连接',
          total: '测试总耗时超过 25 秒，已取消', busy: '已有 2 个连通性测试正在执行，请等待完成后再试' }
        return json({ success: probeOutcome === 'success', message: messages[probeOutcome] || '连通正常，模型已返回响应',
          model: body.model, apiUrl: body.apiUrl, latencyMs: probeOutcome === 'total' ? 25000 : 350,
          reply: probeOutcome === 'success' ? '连通' : null })
      }
      if (url.pathname === '/api/admin/ai-config/message-avatar') {
        uploads++
        assert.equal(req.headers.authorization, 'Bearer avatar-test-token')
        assert.match(req.headers['content-type'], /multipart\/form-data; boundary=/)
        const body = await readBody(req)
        assert(body.includes(Buffer.from('filename="avatar.png"')))
        await new Promise(resolve => setTimeout(resolve, 200))
        return json({ url: '/uploads/avatar-test.png', name: 'avatar.png', size: png.length }, failUpload ? 503 : 200)
      }
      if (url.pathname === '/api/admin/ai-config' && req.method === 'PUT') {
        const body = JSON.parse((await readBody(req)).toString())
        saves.push(body); config = { ...config, ...body }; return json(null)
      }
      if (url.pathname === '/api/admin/ai-config') return json(config)
      if (url.pathname === '/api/admin/ai-config/tools') return json({ registered: ['current_time'], count: 1 })
      return json({})
    }
    const relative = decodeURIComponent(url.pathname.replace(/^\/admin\/?/, ''))
    let file = path.resolve(dist, relative || 'index.html')
    if (!file.startsWith(dist + path.sep) || !existsSync(file)) file = path.join(dist, 'index.html')
    res.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream' })
    res.end(await readFile(file))
  } catch (error) { res.writeHead(500); res.end(String(error)) }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
let browser
try {
  browser = await chromium.launch({ executablePath: chrome, headless: true })
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {
    localStorage.setItem('admin_token', 'avatar-test-token')
    localStorage.setItem('admin_permissions', JSON.stringify(['ai:view', 'ai:edit', 'ai:test']))
    localStorage.setItem('admin_user', JSON.stringify({ id: 1, username: 'avatar-test', mustChangePassword: 0 }))
  })
  await page.goto(`http://127.0.0.1:${server.address().port}/admin/ai-config`)
  const card = page.locator('.el-card').filter({ has: page.getByText('助手消息头像', { exact: true }) })
  await card.waitFor()
  const choose = mode => card.locator('.el-radio').filter({ hasText: mode === 'custom' ? '自定义头像' : '内置默认头像' }).click()
  const preview = () => card.locator('[data-message-avatar-preview] img')
  const save = async () => {
    const response = page.waitForResponse(res => res.url().endsWith('/api/admin/ai-config') && res.request().method() === 'PUT')
    await page.getByRole('button', { name: '保存配置', exact: true }).click()
    assert.equal((await response).status(), 200)
    await page.waitForFunction(() => {
      const button = [...document.querySelectorAll('button')].find(button => button.textContent.trim() === '保存配置')
      return button && !button.disabled && !button.classList.contains('is-loading')
    })
  }
  await choose('custom')
  await card.locator('input[type=file]').setInputFiles({ name: 'avatar.png', mimeType: 'image/png', buffer: png })
  await page.waitForFunction(() => [...document.querySelectorAll('button')].find(button => button.textContent.trim() === '保存配置')?.disabled)
  await card.locator('button').filter({ hasText: '更换图片' }).waitFor()
  await save()
  assert.equal(config.messageAvatarMode, 'custom'); assert.equal(config.messageAvatarUrl, '/uploads/avatar-test.png')
  assert.equal(config.model, 'test-model'); assert(saves.every(body => !('apiKey' in body)))
  await page.reload(); await card.waitFor()
  await page.waitForFunction(() => document.querySelector('.avatar-preview img')?.getAttribute('src') === '/uploads/avatar-test.png')
  assert.equal(await preview().getAttribute('src'), '/uploads/avatar-test.png')
  await choose('default'); await save()
  assert.equal(config.messageAvatarMode, 'default'); assert.equal(config.messageAvatarUrl, '/uploads/avatar-test.png')
  await choose('custom')
  assert.equal(await preview().getAttribute('src'), '/uploads/avatar-test.png')
  await save()
  await card.locator('input[type=file]').setInputFiles({ name: 'bad.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg />') })
  await card.getByRole('alert').waitFor(); assert.equal(uploads, 1)
  await card.locator('input[type=file]').setInputFiles([])
  failUpload = true
  await card.locator('input[type=file]').setInputFiles({ name: 'avatar.png', mimeType: 'image/png', buffer: png })
  await page.waitForFunction(() => document.querySelector('.avatar-error')?.textContent.includes('原头像仍然保留'))
  assert.equal(await preview().getAttribute('src'), '/uploads/avatar-test.png')
  const testButton = () => page.getByRole('button', { name: '测试连通性', exact: true })
  const statusCard = page.locator('.el-card').filter({ has: page.locator('.card-title').filter({ hasText: /^状态$/ }) })
  const unsavedUrl = 'https://open.bigmodel.cn/api/paas/v4'
  await page.getByPlaceholder('https://open.bigmodel.cn/api/paas/v4', { exact: true }).fill(unsavedUrl)
  for (const [outcome, message] of [['success', '连通正常'], ['connect', '10 秒内未完成'],
    ['busy', '已有 2 个'], ['total', '25 秒，已取消'], ['unavailable', '测试服务暂不可用']]) {
    probeOutcome = outcome
    const count = probes.length
    await testButton().click()
    const pending = page.getByRole('button', { name: '测试中，最多等待 25 秒', exact: true })
    await pending.waitFor()
    assert.equal(await pending.isDisabled(), true)
    await pending.evaluate(button => button.click()) // 等待中不能重复提交。
    await testButton().waitFor()
    await statusCard.getByRole('alert').filter({ hasText: message }).waitFor()
    assert.equal(probes.length, count + 1)
    assert.equal(probes.at(-1).apiUrl, unsavedUrl)
    assert(!('apiKey' in probes.at(-1)))
    assert((await statusCard.innerText()).includes(unsavedUrl))
  }
  assert.deepEqual(errors, [])
  await page.screenshot({ path: path.join(artifactDir, 'admin.png'), fullPage: true })
  console.log('管理端头像维护、测试等待提示、防重复提交、成功/连接超时/繁忙/总超时/后端异常反馈通过')
} finally {
  await browser?.close()
  await new Promise(resolve => server.close(resolve))
}
