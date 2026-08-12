import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..')
const frontendDir = path.join(rootDir, 'frontend')

const dirsToCopy = ['server', 'scripts', 'src']
const filesToCopy = [
  'package.json',
  'vite.config.js',
  'tailwind.config.js',
  'postcss.config.js',
  'index.html',
  '.env.example',
]

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

for (const dir of dirsToCopy) {
  const src = path.join(rootDir, dir)
  const dest = path.join(frontendDir, dir)
  if (fs.existsSync(src)) {
    copyRecursive(src, dest)
    console.log('  copied ' + dir + '/')
  }
}

for (const file of filesToCopy) {
  const src = path.join(rootDir, file)
  const dest = path.join(frontendDir, file)
  if (fs.existsSync(src)) {
    fs.copyFileSync(src, dest)
    console.log('  copied ' + file)
  }
}

console.log('Frontend deployment bundle ready at frontend/')
