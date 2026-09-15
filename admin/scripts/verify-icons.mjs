#!/usr/bin/env node
/**
 * A-02 配套守卫：Element Plus 图标白名单完整性
 * ------------------------------------------------------------------
 * 背景：A-02 之前 `main.ts` 全量注册 293 个图标（gzip 约 209 KB，其中 86% 是死重），
 * 之后改为只注册实际用到的白名单（`src/constants/icons.ts`）。这带来一个**静默故障面**：
 *
 *   · `<el-icon><Plus /></el-icon>` 这类**静态标签**：新图标没登记时 Vue 会告警
 *     "Failed to resolve component"，图标不显示；
 *   · `<component :is="item.meta.icon" />` 这类**动态字符串**（`AdminLayout.vue:24`、
 *     `components/StatCard.vue:5`、`views/dashboard/components/QuickActions.vue:23`）：
 *     编译期完全无法检查，只会在运行时渲染为空 —— **最难发现的一类**。
 *
 * 本脚本把"白名单是否覆盖全部用法"变成可执行断言：
 *   1) 从 `@element-plus/icons-vue` 取出全部导出图标名；
 *   2) 扫描 `src/` 下所有模板标签与字符串字面量，求"实际被引用"的集合；
 *   3) 断言该集合 ⊆ 白名单，否则退出码 1 并打印缺哪个、在哪个文件。
 *
 * 用法：node scripts/verify-icons.mjs
 *   （已挂在 `npm run build` 末尾）
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const srcDir = path.join(rootDir, 'src')
const iconTypes = path.join(
  rootDir,
  'node_modules/@element-plus/icons-vue/dist/types/components/index.d.ts'
)

function die(msg) {
  console.error(`\n[verify-icons] FAIL  ${msg}\n`)
  process.exit(1)
}

if (!fs.existsSync(iconTypes)) die(`找不到图标类型声明：${iconTypes}`)
if (!fs.existsSync(srcDir)) die('找不到 src/ 目录')

const available = new Set(
  [...fs.readFileSync(iconTypes, 'utf8').matchAll(/default as ([A-Za-z0-9_]+)/g)].map((m) => m[1])
)
if (available.size === 0) die('未能从图标类型声明中解析出任何图标名')

// ---- 1. 读取白名单（src/constants/icons.ts 的对象字面量键）----
const iconsFile = path.join(srcDir, 'constants/icons.ts')
if (!fs.existsSync(iconsFile)) die('找不到 src/constants/icons.ts')
const registry = new Set(
  [...fs.readFileSync(iconsFile, 'utf8').matchAll(/^\s{2}([A-Z][A-Za-z0-9]*),$/gm)].map((m) => m[1])
)

// ---- 2. 扫描实际用法 ----
const walk = (dir, exts) => {
  const out = []
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) out.push(...walk(p, exts))
    else if (exts.some((x) => e.name.endsWith(x))) out.push(p)
  }
  return out
}

const usedByTag = new Map() // 图标名 -> [文件]
const usedByString = new Map() // 图标名 -> [文件]

for (const p of walk(srcDir, ['.vue'])) {
  const txt = fs.readFileSync(p, 'utf8')
  // 注意：不要用 <template>([\s\S]*?)</template> 截模板段 —— 文件里常有嵌套
  // <template #slot>，非贪婪正则会提前截断并**漏报**后半段图标（曾因此漏掉
  // AdminLayout 的 Expand/Fold/ArrowDown/SwitchButton）。整文件扫描 + 与图标名取交集
  // 才是安全方向：多报无害，漏报才会导致线上图标缺失。
  for (const [, tag] of txt.matchAll(/<([A-Z][A-Za-z0-9]*)/g)) {
    if (tag.startsWith('El') || !available.has(tag)) continue
    if (!usedByTag.has(tag)) usedByTag.set(tag, [])
    const rel = path.relative(rootDir, p)
    if (!usedByTag.get(tag).includes(rel)) usedByTag.get(tag).push(rel)
  }
}

for (const p of walk(srcDir, ['.vue', '.ts'])) {
  const txt = fs.readFileSync(p, 'utf8')
  for (const name of available) {
    if (!new RegExp(`["'\`]${name}["'\`]`).test(txt)) continue
    if (!usedByString.has(name)) usedByString.set(name, [])
    const rel = path.relative(rootDir, p)
    if (!usedByString.get(name).includes(rel)) usedByString.get(name).push(rel)
  }
}

const used = new Set([...usedByTag.keys(), ...usedByString.keys()])

// ---- 3. 断言 ----
const missing = [...used].filter((n) => !registry.has(n)).sort()
const unused = [...registry].filter((n) => !used.has(n)).sort()

console.log('\n[verify-icons] Element Plus 图标白名单')
console.log(`  可选图标 ${available.size} 个 / 白名单登记 ${registry.size} 个 / 实际引用 ${used.size} 个`)
console.log(`  （静态标签 ${usedByTag.size} 个，字符串用法 ${usedByString.size} 个）`)

if (unused.length > 0) {
  console.log(`  NOTE 白名单中未被引用（无害，仅占极小体积）：${unused.join(', ')}`)
}

if (missing.length > 0) {
  console.error(`\n[verify-icons] 发现 ${missing.length} 个「被使用但未登记」的图标：`)
  for (const n of missing) {
    const where = [...(usedByTag.get(n) || []), ...(usedByString.get(n) || [])]
    console.error(`  MISSING  ${n}   <- ${where.join(', ')}`)
  }
  die('请把上述图标补进 src/constants/icons.ts，否则动态 :is 用法会静默渲染为空')
}

console.log('  OK  白名单已覆盖全部引用\n')
