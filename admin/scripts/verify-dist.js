#!/usr/bin/env node
/**
 * A-01 构建产物完整性自检（跨平台 Node 版，无外部依赖）
 * ------------------------------------------------------------------
 * 背景：`admin/dist/` 曾被构建中断/部分清理，产出一份**残缺** dist ——
 * entry chunk 的 `__vite__mapDeps` 引用了 70 个 chunk，磁盘上只有 30 个，
 * 缺失清单里包含 `LoginView-*.js`（登录页路由 chunk）。这份 dist 若被部署，
 * 未登录访问会「守卫跳 /login → 动态 import 404 → 白屏，且无法登录」。
 *
 * 本脚本把「产物完整性」变成可执行断言：扫描 index.html 与 dist/assets 下所有
 * JS chunk，抽出它们引用的 assets/*.js|css，逐个确认磁盘上存在；有任何缺失即
 * 以非 0 退出码失败，从而让 CI / 发布流程拦住残缺产物。
 *
 * 用法：node scripts/verify-dist.js
 *   （已挂在 `npm run build` 末尾，构建后自动执行）
 *
 * 附带输出当前体积基线（JS/CSS 原始 + gzip），供优化前后对比。
 * ⚠️ 单位口径：**KiB**（字节 ÷ 1024），gzip 为逐文件 zlib level 9 后求和。
 *    该口径是 docs/前端优化方案.md §8/§10.2 的唯一标准 —— 早先误标 "KB" 曾导致
 *    文档里 KiB/KB 混用、凭空造出几十 KB 的"收益"，故此处显式标注单位。
 */
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import { fileURLToPath } from 'node:url'

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const distDir = path.join(rootDir, 'dist')
const assetsDir = path.join(distDir, 'assets')
const indexHtml = path.join(distDir, 'index.html')

function die(message) {
  console.error(`\n[verify-dist] FAIL  ${message}\n`)
  process.exit(1)
}

if (!fs.existsSync(indexHtml)) die('dist/index.html 不存在，请先执行 vite build')
if (!fs.existsSync(assetsDir)) die('dist/assets 不存在，请先执行 vite build')

const entries = fs.readdirSync(assetsDir)
const hashes = new Set(entries) // 磁盘上实际存在的文件名

// Vite 产物命名：assets/[name]-[hash].js|css（hash 为 base64url 的 8 位，可能含 - 与 _）。
// 限定这个形状可避免把第三方库里的无关字符串误报成缺失资源。
const CHUNK_REF_RE =
  /assets\/([A-Za-z0-9_.-]+-[A-Za-z0-9_-]{8}\.(?:js|css))/g
const RELATIVE_REF_RE =
  /["'`]\.\.?\/([A-Za-z0-9_.-]+-[A-Za-z0-9_-]{8}\.(?:js|css))["'`]/g

const referenced = new Map() // 文件名 -> 引用它的文件（便于定位）

function collect(filePath, label) {
  const text = fs.readFileSync(filePath, 'utf8')
  for (const re of [CHUNK_REF_RE, RELATIVE_REF_RE]) {
    re.lastIndex = 0
    let m
    while ((m = re.exec(text)) !== null) {
      if (!referenced.has(m[1])) referenced.set(m[1], label)
    }
  }
}

collect(indexHtml, 'index.html')
for (const name of entries) {
  if (name.endsWith('.js')) collect(path.join(assetsDir, name), name)
}

const missing = [...referenced.keys()].filter((name) => !hashes.has(name)).sort()

// 体积基线
function statOf(names, ext) {
  let raw = 0
  let gz = 0
  let count = 0
  for (const name of names) {
    if (!name.endsWith(ext)) continue
    const buf = fs.readFileSync(path.join(assetsDir, name))
    raw += buf.length
    // 与 gzip -9 接近的静态压缩估算
    gz += zlib.gzipSync(buf, { level: 9 }).length
    count += 1
  }
  return { count, raw, gz }
}

const js = statOf(entries, '.js')
const css = statOf(entries, '.css')
const kb = (n) => (n / 1024).toFixed(1)

console.log('\n[verify-dist] 产物体积基线（单位 KiB = 字节/1024）')
console.log(`  JS   ${String(js.count).padStart(3)} 个文件   ${kb(js.raw).padStart(8)} KiB   gzip ${kb(js.gz).padStart(8)} KiB`)
console.log(`  CSS  ${String(css.count).padStart(3)} 个文件   ${kb(css.raw).padStart(8)} KiB   gzip ${kb(css.gz).padStart(8)} KiB`)
console.log(`  合计 ${String(js.count + css.count).padStart(3)} 个文件   ${kb(js.raw + css.raw).padStart(8)} KiB   gzip ${kb(js.gz + css.gz).padStart(8)} KiB`)
console.log(`  引用 chunk ${referenced.size} 个 / 磁盘 assets 文件 ${entries.length} 个`)

if (missing.length > 0) {
  console.error(`\n[verify-dist] 发现 ${missing.length} 个被引用但缺失的产物（部署会白屏）：`)
  for (const name of missing) {
    console.error(`  MISSING  ${name}   <- 被 ${referenced.get(name)} 引用`)
  }
  die('产物不完整，拒绝发布。请执行 `rm -rf dist && npm run build` 全量重建')
}

console.log('\n[verify-dist] OK  产物完整，entry 引用的 chunk 全部落盘\n')
