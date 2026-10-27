import fs from 'node:fs'
import path from 'node:path'
import http from 'node:http'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'

// Tests compiled apps against deterministic API fixtures. No production writes.
const require = createRequire(import.meta.url)
const { chromium, firefox, webkit } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const out = process.env.COORDINATION_QA_OUT || path.join(project, 'artifacts', 'site-coordination')
fs.mkdirSync(out, { recursive: true })
const defaults = JSON.parse(fs.readFileSync(path.join(project, 'shared/presentation-defaults.json'), 'utf8'))
let presentation = structuredClone(defaults)
let brandName = '共同品牌'
let failAboutOnce = false
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.jpg': 'image/jpeg', '.png': 'image/png' }
let productionServer = null
let productionOrigin = ''
const server = http.createServer((req, res) => {
  const pathname = new URL(req.url, 'http://localhost').pathname
  const admin = pathname.startsWith('/admin/')
  if (!admin && productionOrigin) {
    const upstream = http.get(productionOrigin + req.url, incoming => { res.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(res) })
    upstream.on('error', () => { res.writeHead(502).end() })
    return
  }
  const root = path.join(project, admin ? 'admin/dist' : 'frontend/frontend')
  let file = path.resolve(root, '.' + (admin ? pathname.slice(6) : pathname))
  if (!file.startsWith(root + path.sep) && file !== root) { res.writeHead(403).end(); return }
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(root, 'index.html')
  res.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream' })
  fs.createReadStream(file).pipe(res)
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const base = `http://127.0.0.1:${server.address().port}`
if (process.argv.includes('--production')) {
  const reservation = http.createServer()
  await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
  const port = reservation.address().port
  await new Promise(resolve => reservation.close(resolve))
  productionOrigin = `http://127.0.0.1:${port}`
  productionServer = spawn(process.execPath, ['server/index.js'], { cwd: path.join(project, 'frontend'), env: { ...process.env, NODE_ENV: 'production', HOST: '127.0.0.1', PORT: String(port), VITE_ADMIN_SITE_URL: base + '/admin/' }, windowsHide: true, stdio: 'ignore' })
  let started = false
  for (let attempt = 0; attempt < 50; attempt++) {
    try { const response = await fetch(productionOrigin + '/api/health'); if (response.ok) { started = true; break } } catch { /* Wait for the local production process. */ }
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  if (!started) { productionServer.kill(); server.close(); throw new Error('Local production server did not start') }
}
const engine = process.env.QA_BROWSER === 'firefox' ? firefox : process.env.QA_BROWSER === 'webkit' ? webkit : chromium
const browser = await engine.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' })
const errors = [], results = [], writes = [], previewMessages = [], publicAuth = []
const check = (name, passed, detail = '') => { results.push({ name, passed, detail }); console.log(`${passed ? 'PASS' : 'FAIL'} ${name}${detail ? ' ' + detail : ''}`) }
const work = { id: 1, slug: 'fixture-work', title: '协同验收样本作品', summary: '这是未保存预览应该完整展示的摘要。', coverUrl: '', content: '<h2>样本正文</h2><p>仅用于本地验收。</p>', tagType: 'project', authors: '验收样本', dateLabel: '2026', category: '项目', status: 'published', featured: 1, sortOrder: 0, clientName: 'PRIVATE_CLIENT_MARKER' }
work.content += '<p>' + '长文验收'.repeat(28000) + '完整正文结束标记</p>'
const about = { eyebrow: '', title: '样本工作室', subtitle: '', content: '样本介绍', imageUrl: '', stats: [] }
const join = { eyebrow: '', title: '一起来创造', subtitle: '', paragraphs: [], qrImage: '', qrCaption: '' }
const footer = { brandName: '原有品牌', brandIntro: '', showParticles: true, linksTitle: '', links: [{ label: '旧版相对链接', url: 'works', newTab: false }], socialTitle: '', socials: [], email: 'old@example.com', emailLabel: '', copyrightLeft: '', copyrightRight: '' }
const permissions = ['dashboard:view', 'work:list', 'work:edit', 'work:create', 'carousel:list', 'carousel:edit', 'about:view', 'about:edit', 'join:view', 'join:edit', 'footer:view', 'footer:edit', 'capability:list', 'capability:edit', 'service:list', 'service:edit', 'learning:list', 'learning:create', 'learning:edit', 'system:settings']
await context.addInitScript(() => { if (location.pathname.startsWith('/admin/')) localStorage.setItem('admin_token', 'LOCAL_QA_TOKEN') })
await context.route('**/api/**', async route => {
  const request = route.request(), p = new URL(request.url()).pathname, method = request.method()
  if (!p.startsWith('/api/admin/') && request.headers().authorization) publicAuth.push(p)
  let data = {}
  if (p === '/api/admin/auth/me') data = { id: 1, username: 'qa', nickname: '本地验收', roles: [], permissions }
  else if (p === '/api/admin/presentation/preview-html') data = { html: request.postDataJSON()?.html || '' }
  else if (p.startsWith('/api/admin/presentation/')) {
    const key = p.split('/').pop()
    if (method === 'PUT') {
      writes.push({ p, body: request.postDataJSON() })
      if (key === 'about' && failAboutOnce) { failAboutOnce = false; return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: '样本：扩展配置暂未保存' }) }) }
      presentation[key] = request.postDataJSON()
    }
    data = presentation[key]
  } else if (p === '/api/site/presentation') data = presentation
  else if (p === '/api/site/settings') data = { basic: { 'basic.site_name': brandName, 'basic.slogan': '让好奇心与真实项目一起成长' }, contact: { 'contact.email': 'team@example.com' }, seo: { 'seo.site_title': '全站 SEO 标题', 'seo.site_description': '全站 SEO 描述', 'seo.site_keywords': '独角兽,技术,作品' } }
  else if (p === '/api/admin/settings') data = { basic: { 'basic.site_name': brandName }, content: { 'content.presentation.home': JSON.stringify(defaults.home), 'content.comment_enabled': '1' } }
  else if (p === '/api/works') { const { content, clientName, ...publicCard } = work; data = { list: Array.from({ length: 6 }, (_, i) => ({ ...publicCard, id: i + 1, slug: work.slug + '-' + i })) } }
  else if (p.startsWith('/api/admin/works/')) data = { work, seo: {} }
  else if (p === '/api/admin/works') data = { records: [work], total: 1, list: [work] }
  else if (p === '/api/carousel') data = { list: [1, 2].map(i => ({ id: i, title: `轮播标题 ${i}`, subtitle: `轮播说明 ${i}`, imageUrl: '/unicorn.svg', linkUrl: '/join' })) }
  else if (p === '/api/capabilities') data = { list: [{ id: 1, title: '能力样本', description: '配置的图标应呈现', icon: 'code' }] }
  else if (p === '/api/services') data = { list: [{ id: 1, title: '方向样本', description: '配置的图标应呈现', icon: 'research' }] }
  else if (p === '/api/about' || p === '/api/admin/about') { if (method === 'PUT') Object.assign(about, request.postDataJSON()); data = about }
  else if (p === '/api/join' || p === '/api/admin/join') { if (method === 'PUT') Object.assign(join, request.postDataJSON()); data = join }
  else if (p === '/api/footer' || p === '/api/admin/footer') { if (method === 'PUT') Object.assign(footer, request.postDataJSON()); data = footer }
  else if (p === '/api/admin/learning/categories') data = []
  else if (p.startsWith('/api/admin/carousel') || p.startsWith('/api/admin/capabilities') || p.startsWith('/api/admin/services') || p.startsWith('/api/admin/learning')) data = { records: [], total: 0, list: [] }
  else if (p.startsWith('/api/learning')) data = { resources: [] }
  else if (p.startsWith('/api/page-background/')) data = { enabled: false }
  if (method !== 'GET' && p.startsWith('/api/admin/') && !p.includes('/presentation/')) writes.push({ p, body: request.postDataJSON() })
  await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
})
const page = await context.newPage()
page.on('pageerror', e => errors.push(String(e)))
await page.exposeFunction('capturePreview', value => previewMessages.push(value))
await page.addInitScript(() => window.addEventListener('message', e => { if (e.data?.type === 'unicorn:preview:update') window.capturePreview(e.data) }))
try {
  await page.goto(base, { waitUntil: 'networkidle' })
  check('global brand and SEO consumed', await page.title() === '全站 SEO 标题' && await page.locator('.brand-type strong').textContent() === '共同品牌')
  await page.getByRole('button', { name: '下一张精选内容' }).click()
  check('carousel caption and link reflect active slide', await page.locator('.media-caption p').textContent() === '轮播标题 2' && await page.locator('.media-link').getAttribute('href') === '/join')
  check('configured icons rendered', await page.locator('.capability__number svg').count() === 1 && await page.locator('.direction-row__number svg').count() === 1)
  check('legacy relative footer links resolve to site root', await page.getByRole('link', { name: '旧版相对链接', exact: true }).getAttribute('href') === '/works')
  check('global slogan and description consumed', (await page.locator('.brand-type > span').textContent()).includes('让好奇心') && await page.locator('meta[name=description]').getAttribute('content') === '全站 SEO 描述')
  presentation.home.title = '这是一个比默认文案更长的主标题，用来检验内容变化后页面是否仍然清楚而完整。'
  brandName = '这是一个很长的工作室品牌名称用于窄屏验收'
  for (const width of [320, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto(base, { waitUntil: 'networkidle' })
    check('long configured content ' + width + ' no page overflow', await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
  }
  presentation.footer.inheritBrand = true
  presentation.footer.inheritContact = true
  await page.goto(base + '/join', { waitUntil: 'networkidle' })
  check('explicit inheritance connects global brand and email', await page.locator('.footer-brand strong').textContent() === brandName && await page.locator('.contact-email a').textContent() === 'team@example.com')
  join.qrImage = '/missing-qr.png'
  await page.reload({ waitUntil: 'networkidle' })
  check('custom broken QR never inherits old group number', await page.locator('.qr-unavailable').count() === 1 && await page.locator('.join-group').count() === 0)
  join.qrImage = ''
  presentation = structuredClone(defaults)
  brandName = '共同品牌'
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(base + '/preview/home', { waitUntil: 'networkidle' })
  check('standalone preview rejects missing editor', await page.getByRole('heading', { name: '无法连接编辑预览' }).isVisible())
  if (!process.argv.includes('--public-only')) {
  await page.goto(base + '/admin/home', { waitUntil: 'networkidle' })
  await page.getByLabel('主标题', { exact: true }).waitFor()
  const iframe = page.frameLocator('.site-preview iframe')
  await iframe.locator('#home-title').waitFor()
  await page.getByLabel('主标题', { exact: true }).fill('未保存标题\n让两端保持联系。')
  await iframe.getByRole('heading', { name: '未保存标题 让两端保持联系。' }).waitFor()
  check('unsaved form renders with real main template', await iframe.locator('.hero').count() === 1 && writes.length === 0)
  await page.evaluate(() => {
    const frame = document.querySelector('.site-preview iframe')
    frame.contentWindow.postMessage({ type: 'unicorn:preview:update', kind: 'home', nonce: 'invalid-session-nonce', payload: { presentation: { home: { title: '伪造的消息' } } } }, location.origin)
  })
  await page.waitForTimeout(200)
  check('preview ignores messages from a different session', (await iframe.locator('#home-title').textContent()).includes('未保存标题'))
  check('preview skips analytics and AI launcher', await iframe.locator('[data-ai-trigger]').count() === 0)
  await page.getByLabel('首屏说明', { exact: true }).fill('')
  await page.waitForTimeout(400)
  check('explicit empty field hides in preview', await iframe.locator('.hero__description').count() === 0)
  const popupPromise = page.waitForEvent('popup')
  await page.getByRole('button', { name: '放大预览', exact: false }).click()
  const popup = await popupPromise
  await popup.getByRole('heading', { name: '未保存标题 让两端保持联系。' }).waitFor()
  check('expanded preview retains editor connection under production policy', await popup.evaluate(() => Boolean(window.opener)))
  await popup.close()
  await page.getByLabel('首页精选展示数量').fill('6')
  await page.waitForTimeout(400)
  check('featured limit accepts six and matches preview', await iframe.locator('.work-gallery .work-card').count() === 6)
  await page.getByRole('button', { name: '保存并更新主站' }).click()
  await page.getByText('首页配置已保存并更新主站，请刷新线上页面核验。').waitFor()
  check('save persists current draft and empty string', writes.some(w => w.p.endsWith('/home') && w.body.title.startsWith('未保存标题') && w.body.description === '' && w.body.featuredLimit === 6))
  await page.screenshot({ path: path.join(out, 'admin-home-desktop.png'), fullPage: false })
  await page.goto(base, { waitUntil: 'networkidle' })
  check('saved configuration reaches public renderer', (await page.locator('#home-title').textContent()).includes('未保存标题') && await page.locator('.hero__description').count() === 0 && await page.locator('.work-gallery .work-card').count() === 6)
  await page.goto(base + '/admin/about', { waitUntil: 'networkidle' })
  await page.getByLabel('主标题', { exact: true }).fill('部分保存验收标题')
  await page.getByLabel('理念章节标题', { exact: true }).fill('部分保存验收理念')
  const legacyWritesBefore = writes.filter(w => w.p === '/api/admin/about').length
  failAboutOnce = true
  await page.getByRole('button', { name: '保存并更新主站', exact: true }).click()
  await page.getByText('基础介绍已保存；理念与邀请未保存。两部分独立生效，请重试未完成配置。', { exact: true }).waitFor()
  check('partial save reports both results and retains draft', (await page.locator('.config-actions').textContent()).includes('未保存修改'))
  await page.getByRole('button', { name: '保存并更新主站', exact: true }).click()
  await page.getByText('全部配置已保存并更新主站，请刷新线上页面核验。', { exact: true }).waitFor()
  check('retry saves failed section without replaying successful section', writes.filter(w => w.p === '/api/admin/about').length === legacyWritesBefore + 1 && presentation.about.principlesTitle === '部分保存验收理念')
  for (const route of ['/admin/home', '/admin/about', '/admin/join', '/admin/footer', '/admin/works/edit/1']) {
    await page.goto(base + route, { waitUntil: 'networkidle' })
    await page.locator('.site-preview iframe').waitFor()
    await page.waitForTimeout(500)
    for (const width of [390, 1440]) {
      await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 })
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)
      check(route + ' ' + width + ' no page overflow', !overflow)
      await page.screenshot({ path: path.join(out, route.split('/').filter(Boolean).join('-') + '-' + width + '.png'), fullPage: false })
    }
    check(route + ' preview connected', await page.locator('.preview-status').textContent().then(t => t.includes('已连接')))
    if (route === '/admin/footer') check('footer preview preserves legacy relative links', await page.frameLocator('.site-preview iframe').getByRole('link', { name: '旧版相对链接', exact: true }).getAttribute('href') === '/works')
    if (route.includes('/works/')) {
      const workFrame = page.frameLocator('.site-preview iframe')
      check('work card preview includes summary and date', await workFrame.locator('.work-card__description').textContent() === work.summary && (await workFrame.locator('.work-card__meta').textContent()).includes('2026'))
      await page.getByRole('button', { name: '详情', exact: true }).click()
      await workFrame.getByRole('heading', { name: work.title, exact: true }).waitFor()
      await workFrame.getByRole('heading', { name: '样本正文', exact: true }).waitFor()
      check('work detail preview renders current rich text', await workFrame.getByRole('heading', { name: '样本正文', exact: true }).count() === 1)
      check('legal long rich text is not silently truncated', (await workFrame.locator('.detail-article').textContent()).includes('完整正文结束标记'))
      check('work detail retains public category', await workFrame.getByText('项目', { exact: true }).count() > 0)
      check('rich text uses backend save sanitizer', (await page.locator('.preview-status').textContent()).includes('服务端保存规则'))
    }
  }
  await page.goto(base + '/admin/settings', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '保存设置', exact: true }).click()
  await page.getByText('设置已保存', { exact: true }).waitFor()
  const settingsWrite = writes.findLast(item => item.p === '/api/admin/settings')
  check('global settings cannot replay dedicated content snapshots', Array.isArray(settingsWrite?.body) && settingsWrite.body.every(item => !item.key.startsWith('content.presentation.')))
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '打开管理导航', exact: true }).click()
  check('mobile admin menu opens with dialog semantics', await page.locator('#admin-sidebar').getAttribute('aria-modal') === 'true')
  await page.keyboard.press('Escape')
  check('mobile admin Escape restores trigger focus', await page.getByRole('button', { name: '打开管理导航', exact: true }).evaluate(el => el === document.activeElement))
  await page.getByRole('button', { name: '打开管理导航', exact: true }).click()
  await page.getByRole('menuitem', { name: '首页配置', exact: true }).click()
  await page.waitForURL('**/admin/home')
  check('mobile admin navigation returns focus to workspace', await page.locator('#admin-main').evaluate(el => el === document.activeElement && el.scrollTop === 0))
  await page.goto(base + '/admin/learning', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '新增资料', exact: false }).click()
  const dialog = page.getByRole('dialog', { name: '新增资料', exact: true })
  await dialog.getByLabel('标题', { exact: true }).fill('学习资料预览样本')
  await dialog.getByLabel('描述', { exact: true }).fill('学习资料未保存说明')
  const learningBody = 'Learning preview body.'
  const editor = dialog.locator('.w-e-text-container [contenteditable=true]')
  await editor.click()
  await editor.pressSequentially(learningBody, { delay: 15 })
  check('lazy rich editor preserves title input', await dialog.getByLabel('标题', { exact: true }).inputValue() === '学习资料预览样本')
  const learningFrame = page.frameLocator('.site-preview iframe')
  await learningFrame.getByRole('heading', { name: '学习资料预览样本', exact: true }).waitFor()
  await learningFrame.getByText(learningBody, { exact: true }).waitFor()
  check('learning editor renders real unsaved reading template', (await learningFrame.locator('.reading-article').textContent()).includes(learningBody))
  check('learning dialog fits mobile viewport', await dialog.locator('.el-dialog').evaluate(el => el.getBoundingClientRect().right <= innerWidth + 1 && el.scrollWidth <= el.clientWidth + 1))
  await dialog.locator('.site-preview').scrollIntoViewIfNeeded()
  await page.screenshot({ path: path.join(out, 'learning-preview-390.png'), fullPage: false })
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  check('private fields never cross preview boundary', !JSON.stringify(previewMessages).includes('PRIVATE_CLIENT_MARKER') && !JSON.stringify(previewMessages).includes('LOCAL_QA_TOKEN'))
  }
  check('public preview requests contain no admin authorization', publicAuth.length === 0)
  check('no runtime JavaScript errors', errors.length === 0, JSON.stringify(errors))
} catch (error) { check('execution', false, String(error)); console.error(error.stack); try { await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: false }) } catch {} }
finally {
  fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ date: new Date().toISOString(), browser: browser.version(), productionNodeServer: Boolean(productionServer), fixtureNote: 'Deterministic API fixtures, not live backend or real devices.', results, errors }, null, 2))
  await browser.close()
  productionServer?.kill()
  await new Promise(resolve => server.close(resolve))
}
if (results.some(r => !r.passed)) process.exitCode = 1
