import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..')
const distDir = path.join(rootDir, 'dist')
const releaseDir = path.join(rootDir, 'release')

const dirsToCopy = ['server', 'scripts']
const filesToCopy = ['package.json', '.env.example']

function copyRecursive(src, dest) {
  const stat = fs.statSync(src)
  if (stat.isDirectory()) {
    fs.mkdirSync(dest, { recursive: true })
    for (const entry of fs.readdirSync(src)) {
      copyRecursive(path.join(src, entry), path.join(dest, entry))
    }
  } else {
    fs.copyFileSync(src, dest)
  }
}

console.log('Packaging frontend deployment bundle...')

if (!fs.existsSync(path.join(distDir, 'index.html'))) {
  console.error('dist/index.html not found; run vite build first')
  process.exit(1)
}

// 重建 release/，避免残留旧文件
fs.rmSync(releaseDir, { recursive: true, force: true })
fs.mkdirSync(releaseDir, { recursive: true })

// 1. 静态资源
copyRecursive(distDir, path.join(releaseDir, 'dist'))
console.log('  copied dist/')

// 2. 运行时（server + 脚本 + 依赖清单）
for (const dir of dirsToCopy) {
  const src = path.join(rootDir, dir)
  const dest = path.join(releaseDir, dir)
  if (fs.existsSync(src)) {
    copyRecursive(src, dest)
    console.log('  copied ' + dir + '/')
  }
}

for (const file of filesToCopy) {
  const src = path.join(rootDir, file)
  const dest = path.join(releaseDir, file)
  if (fs.existsSync(src)) {
    fs.copyFileSync(src, dest)
    console.log('  copied ' + file)
  }
}

console.log('Frontend deployment bundle ready at release/')
console.log('Deploy: copy release/ to server, then run: npm install --omit=dev && npm start')
