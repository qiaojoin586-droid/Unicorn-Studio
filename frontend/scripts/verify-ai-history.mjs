// 真实 Vue 组件 + 浏览器交互；接口使用本地测试数据，不操作用户历史或外部模型。
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { mkdir, readdir } from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const artifactDir = path.resolve(root, '../backend/target/ai-history')
await mkdir(artifactDir, { recursive: true })
let playwright
for (const candidate of [process.env.PLAYWRIGHT_CORE, 'playwright-core',
  'D:/nodejs/node_global/node_modules/@executeautomation/playwright-mcp-server/node_modules/playwright-core'].filter(Boolean)) {
  try { playwright = require(candidate); break } catch { /* try next */ }
}
assert(playwright, '未找到 playwright-core')
const browserCache = path.join(os.homedir(), 'AppData/Local/ms-playwright')
const versions = (await readdir(browserCache)).filter(name => name.startsWith('chromium-')).sort().reverse()
const chrome = process.env.CHROME_PATH || versions.map(version => path.join(browserCache, version, 'chrome-win64/chrome.exe'))
  .find(existsSync)
assert(chrome, '未找到 Chromium')

const sessions = () => ['A', 'B'].map(id => ({ id, title: `会话${id}`, messageCount: 2, lastActiveAt: new Date().toISOString() }))
let rows = sessions()
let mode = 'ok'
let deletions = []
let streamClosed = false
let chatRequests = 0
let messageAvatarUrl = ''
const html = `<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body><div id="app"></div><script type="module">
import { createApp } from 'vue'; import { createPinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import AiAssistant from '/src/components/AiAssistant.vue';
import { useAiChatStore } from '/src/stores/aiChat.js';
import { useUserStore } from '/src/stores/user.js';
import { useAiChat } from '/src/composables/useAiChat.js';
import * as markdown from '/src/composables/useMarkdown.js';
import '/src/assets/main.css';
const pinia = createPinia(); const app = createApp(AiAssistant); app.use(pinia);
app.use(createRouter({ history: createMemoryHistory(), routes: [{path:'/', component:{template:'<div />'}}] }));
useUserStore(pinia).setSession('history-test-token', {id:1,nickname:'测试用户'});
app.mount('#app'); window.__ai = useAiChatStore(pinia); window.__chat = useAiChat();
window.__markdown = markdown;
window.__ai.isOpen = true; window.__ai.drawerOpen = true;
</script></body></html>`

const server = await createServer({ root, configFile: path.join(root, 'vite.config.js'),
  server: { host: '127.0.0.1', port: 0, open: false },
  plugins: [{ name: 'history-regression-harness', configureServer(vite) {
    vite.middlewares.use(async (req, res, next) => {
      const url = new URL(req.url, 'http://localhost')
      if (url.pathname === '/__history_test') {
        res.setHeader('Content-Type', 'text/html; charset=UTF-8')
        res.end(await vite.transformIndexHtml(url.pathname, html)); return
      }
      if (!url.pathname.startsWith('/api/')) return next()
      res.setHeader('Content-Type', 'application/json; charset=UTF-8')
      if (url.pathname === '/api/ai/chat') {
        chatRequests++
        streamClosed = false
        res.setHeader('Content-Type', 'text/event-stream; charset=UTF-8')
        res.write('data: 正在生成的片段\n\n')
        res.on('close', () => { streamClosed = true })
        return
      }
      if (req.method === 'DELETE') {
        const id = url.pathname.split('/').at(-1)
        deletions.push({ id, authorization: req.headers.authorization })
        if (mode === 'network') { res.destroy(); return }
        await new Promise(resolve => setTimeout(resolve, 100))
        res.statusCode = mode === 'fail' ? 503 : mode === 'missing' ? 404 : 200
        if (res.statusCode === 200) rows = rows.filter(row => row.id !== id)
        res.end(JSON.stringify({ code: res.statusCode, data: null })); return
      }
      const data = url.pathname === '/api/ai/sessions' ? { list: rows, total: rows.length }
        : url.pathname.endsWith('/messages') ? { items: [], hasMore: false }
          : { enabled: true, tools: ['current_time'], messageAvatarUrl }
      res.end(JSON.stringify({ code: 200, data }))
    })
  } }],
})
await server.listen()
const base = `http://127.0.0.1:${server.httpServer.address().port}`
let browser
try {
  browser = await playwright.chromium.launch({ executablePath: chrome, headless: true })
  for (const [name, viewport, colorScheme] of [
    ['desktop', { width: 1280, height: 800 }, 'light'],
    ['phone', { width: 375, height: 812 }, 'dark'],
  ]) {
    rows = sessions(); deletions = []; mode = 'ok'; chatRequests = 0; messageAvatarUrl = ''
    const context = await browser.newContext({ viewport, colorScheme, reducedMotion: 'reduce', hasTouch: name === 'phone' })
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    const row = id => page.locator('.group').filter({ has: page.locator(`p[title="会话${id}"]`) })
    const trash = id => row(id).getByRole('button', { name: '清空对话', exact: true })
    const confirm = () => page.getByRole('button', { name: '确认清空', exact: true })
    const seed = async () => {
      rows = sessions()
      await page.evaluate(list => {
        window.__ai.setSessions(list, false); window.__ai.setSessionId('A')
        window.__ai.replaceMessages([{ role: 'user', content: '需要保留的消息' }])
      }, rows)
    }
    await page.goto(`${base}/__history_test`)
    await trash('A').waitFor({ state: 'visible' })
    await seed()
    assert.equal(await trash('A').evaluate(el => getComputedStyle(el).opacity), '1')
    assert((await trash('A').boundingBox()).width >= 44)
    await trash('A').click()
    await page.getByRole('alertdialog').waitFor()
    await page.getByRole('button', { name: '取消', exact: true }).click()
    assert.equal(deletions.length, 0)
    await trash('A').click(); await page.keyboard.press('Escape')
    await page.getByRole('alertdialog').waitFor({ state: 'hidden' })
    assert(await page.locator('[data-ai-panel]').isVisible())

    mode = 'fail'; await trash('A').click(); await confirm().click()
    await page.getByRole('alert').waitFor()
    assert(await page.getByRole('alertdialog').isVisible())
    assert.equal(await page.evaluate(() => window.__ai.sessionId), 'A')
    mode = 'ok'
    await confirm().evaluate(button => {
      button.click(); button.click()
      window.__chat.streamChat('删除期间不得发起新的对话')
    })
    await page.getByRole('alertdialog').waitFor({ state: 'hidden' })
    assert.equal(deletions.length, 2, '失败一次、重试一次，重复点击不得重复删除')
    assert.equal(chatRequests, 0, '删除期间不得向旧会话发送新消息')
    assert.equal(await row('A').count(), 0)
    assert.equal(await row('B').count(), 1, '删除当前会话必须保留其他历史')
    assert.equal(await page.evaluate(() => window.__ai.sessionId), '')

    await seed(); mode = 'network'; await trash('B').click(); await confirm().click()
    await page.getByRole('alert').waitFor()
    assert.match(await page.getByRole('alert').textContent(), /网络连接失败/)
    mode = 'ok'; await confirm().click()
    await page.getByRole('alertdialog').waitFor({ state: 'hidden' })
    assert.equal(await page.evaluate(() => window.__ai.sessionId), 'A')
    assert.equal(await page.evaluate(() => window.__ai.messages[0].content), '需要保留的消息')

    await seed(); mode = 'missing'; await trash('B').click(); await confirm().click()
    await page.getByRole('alertdialog').waitFor({ state: 'hidden' })
    assert.equal(await row('B').count(), 0, '已经不存在的会话也应从历史列表移除')

    await seed(); mode = 'ok'
    await page.evaluate(() => { window.__chat.streamChat('继续生成') })
    await page.waitForFunction(() => window.__ai.streamingText.includes('正在生成'))
    await trash('A').click(); await confirm().click()
    await page.getByRole('alertdialog').waitFor({ state: 'hidden' })
    assert(streamClosed, '删除当前会话前应关闭正在生成的响应')
    assert.equal(await page.evaluate(() => window.__ai.messages.length), 0)
    assert.equal(await page.evaluate(() => window.__ai.isLoading), false)
    assert(deletions.every(item => item.authorization === 'Bearer history-test-token'))
    await page.evaluate(() => {
      window.__ai.drawerOpen = false
      window.__ai.replaceMessages([{ role: 'assistant', content: '[作品详情](/works/demo)' }])
    })
    const avatar = page.locator('[data-ai-message-avatar]').first()
    await avatar.locator('svg').waitFor()
    const headerMark = await page.locator('.ai-panel-heading .brand-mark').innerHTML()
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=', 'base64')
    await context.route('**/uploads/appearance-test.png', route => route.fulfill({ contentType: 'image/png', body: png }))
    messageAvatarUrl = '/uploads/appearance-test.png'
    await page.evaluate(() => window.__chat.loadAssistantAppearance())
    await avatar.locator('img').waitFor()
    await page.waitForFunction(() => document.querySelector('[data-ai-message-avatar] img')?.naturalWidth > 0)
    assert.equal(await page.locator('[data-ai-icon] img').count(), 0)
    assert.equal(await page.locator('.ai-panel-heading .brand-mark').innerHTML(), headerMark)
    await context.route('**/uploads/missing-avatar.png', route => route.fulfill({ status: 404, body: '' }))
    messageAvatarUrl = '/uploads/missing-avatar.png'
    await page.evaluate(() => window.__chat.loadAssistantAppearance())
    await avatar.locator('svg').waitFor()
    messageAvatarUrl = ''
    await page.evaluate(() => window.__chat.loadAssistantAppearance())
    await avatar.locator('svg').waitFor()

    const cases = [
      ['[作品详情](/works/demo)', ['/works/demo']],
      ['作品：/works/demo。', ['/works/demo']],
      ['[关于我们](/about?source=ai&lang=zh)', ['/about?source=ai&lang=zh']],
      ['网址：https://example.com/works/demo。', ['https://example.com/works/demo']],
      ['[外部链接](https://example.com/project)', ['https://example.com/project']],
      ['`/works/demo`', []],
      ['```text\nhttps://example.com/works/demo\n/works/demo\n```', []],
      ['[危险](javascript:alert(1))', []],
      ['<a href="//evil.example/path">不安全链接</a><img src=x onerror=alert(1)><script>alert(1)</script>', []],
    ]
    for (const [text, expected] of cases) {
      const result = await page.evaluate(text => {
        const container = document.createElement('div')
        container.innerHTML = window.__markdown.renderMarkdown(text)
        return { links: [...container.querySelectorAll('a[href]')].map(a => ({ href: a.getAttribute('href'), target: a.target, rel: a.rel })),
          unsafe: container.querySelectorAll('script,iframe,[onerror],[onclick]').length }
      }, text)
      assert.deepEqual(result.links.map(link => link.href), expected, text)
      assert.equal(result.unsafe, 0, text)
      assert(result.links.every(link => link.target === '_blank' && link.rel.includes('noopener') && link.rel.includes('noreferrer')))
    }
    await context.route('**/works/demo', route => route.fulfill({ contentType: 'text/html', body: '<h1>作品详情测试页</h1>' }))
    const originalPage = page.url()
    const popupEvent = page.waitForEvent('popup')
    await page.getByRole('link', { name: '作品详情', exact: true }).click()
    const popup = await popupEvent
    await popup.waitForURL('**/works/demo')
    await popup.waitForLoadState('domcontentloaded')
    assert(popup.url().endsWith('/works/demo'))
    assert.equal(page.url(), originalPage)
    await popup.close()
    await page.evaluate(() => window.__ai.setStreamingText('详细介绍：[工作室介绍](/about)'))
    await page.getByRole('link', { name: '工作室介绍', exact: true }).waitFor()
    assert.equal(await page.getByRole('link', { name: '工作室介绍', exact: true }).getAttribute('href'), '/about')
    assert.deepEqual(errors, [], '不应出现未捕获的页面异常')
    await page.screenshot({ path: path.join(artifactDir, `${name}.png`) })
    console.log(`${name}: 历史删除、消息头像切换/失效回退、消息和流式链接、安全过滤和新标签打开均通过`)
    await context.close()
  }
} finally {
  await browser?.close()
  await server.close()
}
