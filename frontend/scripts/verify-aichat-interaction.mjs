// AI 助手悬浮组件（图标 + 对话框）交互回归守卫
//
// 覆盖三件事（对应主站对话框优化需求）：
//   1) 整体拖动：拖「对话框表头」或拖「对话图标」，两者始终成刚性整体一起走（图标即锚点）
//   2) 拉伸与缩小：8 个方向（4 边 + 4 角）都能改尺寸，且能缩到最小尺寸
//   3) 位置同步：图标移动时对话框位移完全一致
//
// 同时守护改造前已有的行为，避免"优化时破坏其他逻辑"：
//   单击图标开合对话 / 拖动阈值（超过 3px 才算拖动）/ 视口边界钳制 /
//   localStorage 持久化 / 旧存档（只有 width/height）兼容 / 双击角把手切换默认↔放大 / 运行期零真实报错
//
// 用法：
//   node scripts/verify-aichat-interaction.mjs                 # 默认测生产产物 frontend/frontend
//   TEST_ROOT=... node scripts/verify-aichat-interaction.mjs   # 指定其它产物目录（如 .verify-dist）
//
// 依赖 playwright-core + 本机 chromium。任一项找不到时**跳过**（退出码 0），不会阻塞构建。
import http from 'node:http'
import { readFile, readdir } from 'node:fs/promises'
import { existsSync, statSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const projectRoot = path.resolve(__dirname, '..')

// ---- 依赖定位（找不到就跳过，别让守卫本身成为构建的绊脚石）------------------
const require = createRequire(import.meta.url)

function loadPlaywrightCore() {
  const candidates = [
    process.env.PLAYWRIGHT_CORE,
    'playwright-core',
    'playwright',
    // 本机 playwright-mcp-server 自带的副本（无全局安装时的兜底）
    'D:/nodejs/node_global/node_modules/@executeautomation/playwright-mcp-server/node_modules/playwright-core',
    path.join(process.env.APPDATA || '', 'npm/node_modules/playwright-core'),
    path.join(process.env.APPDATA || '', 'npm/node_modules/playwright/node_modules/playwright-core'),
  ].filter(Boolean)
  for (const c of candidates) {
    try {
      return require(c)
    } catch {
      /* 继续尝试下一个 */
    }
  }
  return null
}

async function findChromium() {
  if (process.env.CHROME_PATH && existsSync(process.env.CHROME_PATH)) return process.env.CHROME_PATH
  const base = path.join(os.homedir(), 'AppData/Local/ms-playwright')
  try {
    const dirs = (await readdir(base)).filter((d) => d.startsWith('chromium-')).sort()
    for (const d of dirs.reverse()) {
      for (const rel of ['chrome-win64/chrome.exe', 'chrome-win/chrome.exe', 'chrome-linux/chrome']) {
        const p = path.join(base, d, rel)
        if (existsSync(p)) return p
      }
    }
  } catch {
    /* 没有 playwright 缓存目录 */
  }
  return null
}

const pw = loadPlaywrightCore()
if (!pw || !pw.chromium) {
  console.log('⏭  跳过 AI 对话框交互回归：未找到 playwright-core（设 PLAYWRIGHT_CORE 指定路径）')
  process.exit(0)
}
const CHROME = await findChromium()
if (!CHROME) {
  console.log('⏭  跳过 AI 对话框交互回归：未找到本机 chromium（设 CHROME_PATH 指定可执行文件）')
  process.exit(0)
}

const ROOT = path.resolve(process.env.TEST_ROOT || path.join(projectRoot, 'frontend'))
if (!existsSync(path.join(ROOT, 'index.html'))) {
  console.log(`⏭  跳过 AI 对话框交互回归：产物目录没有 index.html -> ${ROOT}（先 build，或用 TEST_ROOT 指定）`)
  process.exit(0)
}

const VW = 1280
const VH = 800

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.mp4': 'video/mp4',
  '.woff2': 'font/woff2',
  '.ico': 'image/x-icon',
}

// 监听 0 端口让系统分配，彻底避开端口占用
function startServer() {
  const server = http.createServer(async (req, res) => {
    const url = decodeURIComponent((req.url || '/').split('?')[0])
    let file = path.join(ROOT, url)
    if (!existsSync(file) || statSync(file).isDirectory()) file = path.join(ROOT, 'index.html')
    try {
      const buf = await readFile(file)
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream' })
      res.end(buf)
    } catch {
      res.writeHead(404).end('not found')
    }
  })
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)))
}

// ---- 断言工具 -------------------------------------------------------------
let pass = 0
let fail = 0
const round = (v) => Math.round(v * 100) / 100
const near = (a, b, eps = 2) => Math.abs(a - b) <= eps

function ok(name, cond, detail = '') {
  if (cond) {
    pass++
    console.log(`  ✓ ${name}${detail ? '  ' + detail : ''}`)
  } else {
    fail++
    console.log(`  ✘ ${name}  ${detail}`)
  }
}
function eq(name, actual, expected, eps = 2) {
  const keys = Object.keys(expected)
  const bad = keys.filter((k) => !near(actual[k], expected[k], eps))
  ok(
    name,
    bad.length === 0,
    bad.length
      ? `差异: ${bad.map((k) => `${k} 实际${round(actual[k])}/期望${round(expected[k])}`).join(', ')}`
      : `${keys.map((k) => `${k}=${round(actual[k])}`).join(' ')}`,
  )
}

async function box(page, sel) {
  const b = await page.locator(sel).boundingBox()
  if (!b) throw new Error(`找不到 ${sel}`)
  return b
}

async function drag(page, x, y, dx, dy, steps = 14) {
  await page.mouse.move(x, y)
  await page.mouse.down()
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(x + (dx * i) / steps, y + (dy * i) / steps)
  }
  await page.mouse.up()
}

// ---- 主流程 ---------------------------------------------------------------
console.log(`AI 对话框交互回归 · 产物目录 ${ROOT}`)
const server = await startServer()
const PORT = server.address().port
const browser = await pw.chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: VW, height: VH } })
// Hero 背景视频有 4MB，布局测试用不到，拦掉以免拖慢加载
await page.route('**/*.mp4', (r) => r.abort())

const errors = []
page.on('pageerror', (e) => errors.push(String(e)))
page.on('console', (m) => {
  if (m.type() === 'error') errors.push('console: ' + m.text())
})

try {
  await page.goto(`http://127.0.0.1:${PORT}/`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-ai-icon]', { timeout: 20000 })
  await page.evaluate(() => localStorage.removeItem('unicorn.aiChat.panelSize'))
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-ai-icon]', { timeout: 20000 })

  console.log('\n【1】默认布局（应与改造前 bottom-8 right-8 / bottom-28 right-8 一致）')
  let icon = await box(page, '[data-ai-icon]')
  eq('图标尺寸 64×64', icon, { width: 64, height: 64 })
  eq('图标默认位置 32px 内缩', icon, { x: VW - 32 - 64, y: VH - 32 - 64 })

  await page.mouse.click(icon.x + 32, icon.y + 32)
  await page.waitForSelector('[data-ai-panel]', { timeout: 5000 })
  await page.waitForTimeout(600) // 等入场动画(scale-95)结束再量
  let panel = await box(page, '[data-ai-panel]')
  eq('对话框默认尺寸 384×500', panel, { width: 384, height: 500 })
  eq('对话框默认位置（右对齐图标、底边在图标上方16px）', panel, {
    x: VW - 32 - 384,
    y: VH - 32 - 64 - 16 - 500,
  })
  ok('右边缘与图标对齐', near(panel.x + panel.width, icon.x + icon.width))
  ok('底边在图标上方 16px', near(panel.y + panel.height, icon.y - 16))

  console.log('\n【2】拖动图标 → 对话框同步位移（需求 3）')
  let before = { icon: await box(page, '[data-ai-icon]'), panel: await box(page, '[data-ai-panel]') }
  await drag(page, before.icon.x + 32, before.icon.y + 32, -200, -150)
  icon = await box(page, '[data-ai-icon]')
  panel = await box(page, '[data-ai-panel]')
  eq('图标位移 = (-200,-150)', icon, { x: before.icon.x - 200, y: before.icon.y - 150 })
  eq('对话框位移与图标相同', panel, {
    x: before.panel.x - 200,
    y: before.panel.y - 150,
    width: before.panel.width,
    height: before.panel.height,
  })
  ok('拖动后对话仍保持打开', (await page.locator('[data-ai-panel]').count()) === 1)

  console.log('\n【3】拖动对话框表头 → 图标同步位移（需求 1）')
  before = { icon: await box(page, '[data-ai-icon]'), panel: await box(page, '[data-ai-panel]') }
  await drag(page, before.panel.x + 120, before.panel.y + 24, -100, 80)
  icon = await box(page, '[data-ai-icon]')
  panel = await box(page, '[data-ai-panel]')
  eq('对话框位移 = (-100,+80)', panel, { x: before.panel.x - 100, y: before.panel.y + 80 })
  eq('图标位移与对话框相同', icon, { x: before.icon.x - 100, y: before.icon.y + 80 })
  ok('相对关系保持不变', near(panel.x + panel.width, icon.x + icon.width) && near(panel.y + panel.height, icon.y - 16))

  console.log('\n【4】四角拉伸（需求 2）')
  // 右下角（se）：位置不动，宽高变大
  before = await box(page, '[data-ai-panel]')
  let h = await box(page, '[data-resize="se"]')
  await drag(page, h.x + h.width / 2, h.y + h.height / 2, 60, 40)
  panel = await box(page, '[data-ai-panel]')
  eq('se 角：宽高 +60/+40，位置不变', panel, {
    x: before.x,
    y: before.y,
    width: before.width + 60,
    height: before.height + 40,
  })

  // 左上角（nw）：位置跟着走，宽高反向增加
  before = await box(page, '[data-ai-panel]')
  h = await box(page, '[data-resize="nw"]')
  await drag(page, h.x + h.width / 2, h.y + h.height / 2, -40, -30)
  panel = await box(page, '[data-ai-panel]')
  eq('nw 角：位置 -40/-30 且宽高 +40/+30', panel, {
    x: before.x - 40,
    y: before.y - 30,
    width: before.width + 40,
    height: before.height + 30,
  })

  // 右上角（ne）：右上角被拖动，左/下边不动
  before = await box(page, '[data-ai-panel]')
  h = await box(page, '[data-resize="ne"]')
  await drag(page, h.x + h.width / 2, h.y + h.height / 2, 30, -20)
  panel = await box(page, '[data-ai-panel]')
  eq('ne 角：x 不变、宽 +30、y -20、高 +20', panel, {
    x: before.x,
    y: before.y - 20,
    width: before.width + 30,
    height: before.height + 20,
  })

  // 左下角（sw）
  before = await box(page, '[data-ai-panel]')
  h = await box(page, '[data-resize="sw"]')
  await drag(page, h.x + h.width / 2, h.y + h.height / 2, -25, 35)
  panel = await box(page, '[data-ai-panel]')
  eq('sw 角：x -25、宽 +25、y 不变、高 +35', panel, {
    x: before.x - 25,
    y: before.y,
    width: before.width + 25,
    height: before.height + 35,
  })

  console.log('\n【5】边拉伸 + 缩小（用户明确要求「拉伸与缩小」）')
  before = await box(page, '[data-ai-panel]')
  h = await box(page, '[data-resize="e"]')
  await drag(page, h.x + h.width / 2, h.y + h.height / 2, 50, 0)
  panel = await box(page, '[data-ai-panel]')
  eq('e 边：宽 +50，位置不变', panel, { x: before.x, y: before.y, width: before.width + 50 })

  before = await box(page, '[data-ai-panel]')
  h = await box(page, '[data-resize="se"]')
  await drag(page, h.x + h.width / 2, h.y + h.height / 2, -400, -400)
  panel = await box(page, '[data-ai-panel]')
  eq('se 反向拖到底：收窄到最小尺寸 320×320', panel, { width: 320, height: 320 })

  console.log('\n【6】边界钳制：把组合拖到左上极限，两者都必须留在视口内')
  // 注意：拉伸会改变两者相对偏移（resize 只动面板，不动图标），
  // 所以这里断言的是「拖动前后偏移不变」，而不是默认布局那种右对齐关系。
  icon = await box(page, '[data-ai-icon]')
  panel = await box(page, '[data-ai-panel]')
  const offBefore = { dx: panel.x - icon.x, dy: panel.y - icon.y }
  await drag(page, icon.x + 32, icon.y + 32, -5000, -5000)
  icon = await box(page, '[data-ai-icon]')
  panel = await box(page, '[data-ai-panel]')
  ok('图标未越界', icon.x >= 7 && icon.y >= 7, `icon=(${round(icon.x)},${round(icon.y)})`)
  ok('对话框未越界', panel.x >= 7 && panel.y >= 7, `panel=(${round(panel.x)},${round(panel.y)})`)
  ok(
    '拖动极限位置后两者相对偏移不变',
    near(panel.x - icon.x, offBefore.dx) && near(panel.y - icon.y, offBefore.dy),
    `偏移 ${round(offBefore.dx)},${round(offBefore.dy)} → ${round(panel.x - icon.x)},${round(panel.y - icon.y)}`,
  )
  ok(
    '被挡住的确实是「面板先到边界」而非图标（说明钳制取的是两者交集）',
    panel.x <= 9 && panel.y <= 9 && icon.x > 9,
    `panel=(${round(panel.x)},${round(panel.y)}) icon=(${round(icon.x)},${round(icon.y)})`,
  )

  console.log('\n【7】点击语义未被破坏（拖动阈值仍生效）')
  // 此刻对话框是打开的 → 点一下图标应该「关闭」
  icon = await box(page, '[data-ai-icon]')
  await page.mouse.click(icon.x + 32, icon.y + 32)
  await page.waitForTimeout(400)
  ok('单击图标可关闭对话（未被拖动逻辑吃掉）', (await page.locator('[data-ai-panel]').count()) === 0)
  await page.mouse.click(icon.x + 32, icon.y + 32)
  await page.waitForTimeout(600)
  ok('再次单击可打开对话', (await page.locator('[data-ai-panel]').count()) === 1)

  console.log('\n【8】布局持久化：刷新后恢复')
  const savedBefore = await page.evaluate(() => localStorage.getItem('unicorn.aiChat.panelSize'))
  const p1 = await box(page, '[data-ai-panel]')
  const i1 = await box(page, '[data-ai-icon]')
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-ai-icon]', { timeout: 20000 })
  await page.waitForTimeout(300)
  const i2 = await box(page, '[data-ai-icon]')
  eq('刷新后图标位置保持', i2, { x: i1.x, y: i1.y })
  await page.mouse.click(i2.x + 32, i2.y + 32)
  await page.waitForSelector('[data-ai-panel]', { timeout: 5000 })
  await page.waitForTimeout(600)
  const p2 = await box(page, '[data-ai-panel]')
  eq('刷新后对话框位置与尺寸保持', p2, {
    x: p1.x,
    y: p1.y,
    width: p1.width,
    height: p1.height,
  })
  ok('localStorage 已写入坐标', /iconX|panelX/.test(savedBefore || ''), (savedBefore || '').slice(0, 90))

  console.log('\n【9】兼容旧存档：只有 width/height 时不炸，按默认位置摆放')
  await page.evaluate(() =>
    localStorage.setItem('unicorn.aiChat.panelSize', JSON.stringify({ width: 420, height: 360 })),
  )
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-ai-icon]', { timeout: 20000 })
  await page.waitForTimeout(300)
  const i3 = await box(page, '[data-ai-icon]')
  eq('旧存档：图标回到默认位置', i3, { x: VW - 96, y: VH - 96 })
  await page.mouse.click(i3.x + 32, i3.y + 32)
  await page.waitForSelector('[data-ai-panel]', { timeout: 5000 })
  await page.waitForTimeout(600)
  const p3 = await box(page, '[data-ai-panel]')
  eq('旧存档：沿用保存的尺寸、位置按默认推算', p3, {
    x: VW - 32 - 420,
    y: VH - 96 - 16 - 360,
    width: 420,
    height: 360,
  })

  console.log('\n【10】双击角把手：尺寸在「默认」与「放大」两态间来回切换（改造前已有能力，不能被破坏）')
  const DW = 384
  const DH = 500
  const EW = DW * 1.6 // bounds.maxW=900 未触及，故放大态为精确 1.6 倍
  const EH = DH * 1.4 // bounds.maxH=752 未触及，故放大态为精确 1.4 倍
  panel = await box(page, '[data-ai-panel]')
  const beforeDbl = { w: panel.width, h: panel.height }

  // 尺寸一变，右下角把手的坐标就跟着变 —— 每次双击前必须重新测量，否则会打在空处
  const dblSE = async () => {
    const b = await box(page, '[data-resize="se"]')
    await page.mouse.dblclick(b.x + b.width / 2, b.y + b.height / 2)
    await page.waitForTimeout(250)
    return await box(page, '[data-ai-panel]')
  }
  const inView = (p) => p.x >= 7 && p.y >= 7 && p.x + p.width <= VW - 7 && p.y + p.height <= VH - 7

  panel = await dblSE()
  ok(
    '第一次双击（自定义尺寸）→ 默认尺寸',
    near(panel.width, DW, 3) && near(panel.height, DH, 3),
    `${round(beforeDbl.w)}×${round(beforeDbl.h)} → ${round(panel.width)}×${round(panel.height)}（期望 ${DW}×${DH}）`,
  )
  ok(
    '双击后仍留在视口内',
    inView(panel),
    `${round(panel.x)},${round(panel.y)} ${round(panel.width)}×${round(panel.height)}`,
  )

  panel = await dblSE()
  ok(
    '第二次双击（默认）→ 放大尺寸',
    near(panel.width, EW, 3) && near(panel.height, EH, 3),
    `${round(panel.width)}×${round(panel.height)}（期望 ${round(EW)}×${round(EH)}）`,
  )
  ok(
    '放大后仍留在视口内',
    inView(panel),
    `${round(panel.x)},${round(panel.y)} ${round(panel.width)}×${round(panel.height)}`,
  )

  panel = await dblSE()
  ok(
    '第三次双击（放大）→ 回到默认尺寸（来回可切，未卡死在单态）',
    near(panel.width, DW, 3) && near(panel.height, DH, 3),
    `${round(panel.width)}×${round(panel.height)}（期望 ${DW}×${DH}）`,
  )

  console.log('\n【11】页面运行期无真实 JS 报错')
  // 过滤掉测试自身造成的资源失败（拦掉 mp4）与后端未启动导致的 /api 404
  const real = errors.filter((e) => !/ERR_FAILED|Failed to load resource|net::ERR/.test(e))
  ok('无 pageerror / 真实 console.error', real.length === 0, real.slice(0, 3).join(' | '))
} catch (e) {
  fail++
  console.log('\n✘ 异常中止:', e.message)
} finally {
  await browser.close()
  server.close()
}

console.log(`\n${'='.repeat(60)}\n通过 ${pass} / 失败 ${fail}\n${'='.repeat(60)}`)
process.exit(fail === 0 ? 0 : 1)
