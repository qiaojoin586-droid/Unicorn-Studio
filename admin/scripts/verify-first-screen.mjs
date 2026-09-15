#!/usr/bin/env node
/**
 * A-02 / A-26 配套守卫：首屏构成 + Element Plus 按需是否真的生效
 * ------------------------------------------------------------------
 * 与 `verify-dist.js` 的分工：
 *   - verify-dist.js  管「完整性」—— entry 引用的 chunk 是否全部落盘（防残缺 dist 白屏）。
 *   - 本脚本          管「构成」  —— 首次加载到底拉了哪些文件、有多大、按需是否白做了。
 *
 * 为什么需要它：A-02（Element Plus 按需 + 图标白名单）与 A-26（manualChunks）的收益
 * **只能从产物构成上验证**，源码层面看不出来 —— 一旦有人把 `import ElementPlus from
 * 'element-plus'` 或 `import 'element-plus/dist/index.css'` 加回来，构建照样成功、
 * 单测照样过，但首屏会悄悄涨回近 900 KiB，且没有任何报错。
 *
 * 四组断言：
 *   [1] 按需 CSS 生效   —— entry CSS 必须含 Element Plus 基础变量（base.css），
 *                          否则主题色/尺寸全失真；同时全量样式里本项目没用的组件类
 *                          应当已被剥离（这是"按需真的生效"的正面证据）。
 *   [2] 交互反馈样式齐全 —— ElMessage / ElMessageBox / v-loading 的样式随各自 chunk
 *                          分发，容易被"按需"误伤成静默无样式，逐个确认落盘。
 *   [3] 首屏体积预算     —— 按 index.html 实际引用（entry + modulepreload）算真实首屏，
 *                          超出预算即失败（预算见下方常量，对齐 docs/前端优化方案.md）。
 *   [4] 重量级依赖出首屏 —— ECharts / wangEditor 必须只在懒加载 chunk 里出现。
 *
 * 用法：node scripts/verify-first-screen.mjs
 *   （已挂在 `npm run build` 末尾）
 */
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import { fileURLToPath } from 'node:url'

// ---- 首屏预算（原始 / gzip），对齐 docs/前端优化方案.md 的阶段一目标 ----
const BUDGET_RAW_KIB = 1000
const BUDGET_GZIP_KIB = 300

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const distDir = path.join(rootDir, 'dist')
const assetsDir = path.join(distDir, 'assets')
const indexHtmlPath = path.join(distDir, 'index.html')

function die(msg) {
  console.error(`\n[verify-first-screen] FAIL  ${msg}\n`)
  process.exit(1)
}

if (!fs.existsSync(indexHtmlPath)) die('dist/index.html 不存在，请先执行 vite build')

const indexHtml = fs.readFileSync(indexHtmlPath, 'utf8')

// ---- 解析 index.html 引用的资源 ----
const uniq = (a) => [...new Set(a)]
const entryJs = uniq([...indexHtml.matchAll(/<script[^>]+src="[^"]*assets\/([^"]+\.js)"/g)].map((m) => m[1]))
const entryCss = uniq([...indexHtml.matchAll(/<link[^>]+rel="stylesheet"[^>]*href="[^"]*assets\/([^"]+\.css)"/g)].map((m) => m[1]))
const preloadJs = uniq([...indexHtml.matchAll(/<link[^>]+rel="modulepreload"[^>]*href="[^"]*assets\/([^"]+\.js)"/g)].map((m) => m[1]))

if (entryJs.length === 0) die('index.html 里没解析到 entry JS —— 产物结构变了？')

// modulepreload 集合 = entry 的静态 import 图，即真正的"首屏"
const firstScreen = uniq([...entryJs, ...entryCss, ...preloadJs])

let ok = true
const fail = (m) => {
  ok = false
  console.log('  FAIL ' + m)
}

const readAsset = (n) => fs.readFileSync(path.join(assetsDir, n), 'utf8')
const sizeOf = (n) => {
  const buf = fs.readFileSync(path.join(assetsDir, n))
  return { raw: buf.length, gz: zlib.gzipSync(buf, { level: 9 }).length }
}

console.log('\n[verify-first-screen] 首屏构成 + 按需生效校验')
console.log(`  index.html → entry JS ${entryJs.join(', ')}`)
console.log(`                entry CSS ${entryCss.join(', ')}`)
console.log(`                modulepreload ${preloadJs.length} 个`)

// ============ [1] 按需 CSS 生效 ============
console.log('\n--- [1] Element Plus 按需 CSS ---')
const entryCssText = entryCss.map(readAsset).join('')

// base.css 提供全部 --el-* 变量，必须在首屏，否则所有组件的主题色/圆角/间距失真
if (entryCssText.includes('--el-color-primary')) {
  console.log('  OK   entry CSS 含 CSS 变量（base.css 未被按需优化误伤）')
} else {
  fail('entry CSS 缺少 --el-color-primary —— 首屏组件主题会全面失真')
}

const cssFiles = fs.readdirSync(assetsDir).filter((f) => f.endsWith('.css'))
const cssTextOf = new Map(cssFiles.map((f) => [f, readAsset(f)]))
const allCss = [...cssTextOf.values()].join('')

// 「按需生效」的正面证据：全量样式里有、而本项目根本没用到的组件类应当已消失
const UNUSED_COMPONENT_CLASSES = [
  'el-transfer', 'el-cascader', 'el-color-picker', 'el-calendar', 'el-carousel',
  'el-timeline', 'el-steps', 'el-rate', 'el-backtop', 'el-affix', 'el-anchor',
]
const stillPresent = UNUSED_COMPONENT_CLASSES.filter((c) => allCss.includes('.' + c))
if (stillPresent.length === 0) {
  console.log(`  OK   未使用组件样式已剥离（探测 ${UNUSED_COMPONENT_CLASSES.length} 个组件类，全部缺席）`)
} else {
  fail(`仍存在未使用组件样式 ${stillPresent.join(', ')} —— 全量 index.css 疑似被引回`)
}

// ============ [2] 交互反馈样式齐全 ============
console.log('\n--- [2] 交互反馈样式（易被按需误伤） ---')
for (const [probe, desc] of [
  ['.el-message-box', 'ElMessageBox 样式'],
  ['.el-overlay', 'ElMessageBox 遮罩样式'],
  ['.el-message', 'ElMessage 样式'],
  ['.el-loading-mask', 'v-loading 遮罩样式'],
]) {
  const where = [...cssTextOf.entries()].filter(([, t]) => t.includes(probe)).map(([f]) => f)
  if (where.length > 0) {
    console.log(`  OK   含 ${desc}（${where.slice(0, 3).join(', ')}）`)
  } else {
    fail(`产物缺少 ${desc}（探测 "${probe}"）—— 运行时该反馈会无样式`)
  }
}

// ============ [3] 首屏体积预算 ============
console.log('\n--- [3] 首屏体积（entry + modulepreload） ---')
let raw = 0
let gz = 0
const sorted = firstScreen
  .map((n) => ({ n, ...sizeOf(n) }))
  .sort((a, b) => b.raw - a.raw)
for (const { n, raw: r, gz: g } of sorted) {
  raw += r
  gz += g
  console.log(`  ${n.padEnd(44)} ${(r / 1024).toFixed(2).padStart(9)} KiB  gzip ${(g / 1024).toFixed(2).padStart(8)} KiB`)
}
const rawKib = raw / 1024
const gzKib = gz / 1024
console.log('  ' + '-'.repeat(72))
console.log(`  首屏合计 ${firstScreen.length} 个文件   原始 ${rawKib.toFixed(2)} KiB   gzip ${gzKib.toFixed(2)} KiB`)
console.log(`  预算 ${BUDGET_RAW_KIB} KiB / gzip ${BUDGET_GZIP_KIB} KiB`)

if (rawKib > BUDGET_RAW_KIB || gzKib > BUDGET_GZIP_KIB) {
  fail(
    `首屏超出预算（原始 ${rawKib.toFixed(2)}/${BUDGET_RAW_KIB} KiB，gzip ${gzKib.toFixed(2)}/${BUDGET_GZIP_KIB} KiB）。` +
      ' 常见原因：重量级依赖被静态引入首屏，或 Element Plus 全量样式被引回。'
  )
} else {
  console.log('  OK   首屏在预算内')
}

// ============ [4] 重量级依赖必须出首屏 ============
console.log('\n--- [4] 重量级依赖是否已移出首屏 ---')
const firstSet = new Set(firstScreen)
let heavySeen = false
for (const prefix of ['vendor-echarts', 'vendor-editor']) {
  const hits = fs.readdirSync(assetsDir).filter((f) => f.startsWith(prefix) && f.endsWith('.js'))
  if (hits.length === 0) {
    console.log(`  NOTE 未发现 ${prefix}-*.js（manualChunks 规则变了？）`)
    continue
  }
  heavySeen = true
  for (const h of hits) {
    const { raw: r, gz: g } = sizeOf(h)
    const inFirst = firstSet.has(h)
    console.log(`  ${h.padEnd(44)} ${(r / 1024).toFixed(2).padStart(9)} KiB  gzip ${(g / 1024).toFixed(2).padStart(8)} KiB  首屏内=${inFirst}`)
    if (inFirst) fail(`${h} 出现在首屏集合中，应为懒加载`)
  }
}
if (heavySeen) console.log('  OK   重量级依赖均不在首屏')

console.log(`\n[verify-first-screen] ${ok ? 'OK  首屏构成与按需生效均符合预期' : 'FAIL'}\n`)
process.exit(ok ? 0 : 1)
