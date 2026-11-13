import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, realpathSync, rmSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const tempBase = path.join(root, 'node_modules', '.cache', 'admin-build')
mkdirSync(tempBase, { recursive: true })
const tempDirectory = mkdtempSync(path.join(tempBase, 'run-'))
// esbuild exchanges large transforms through temporary files. Give each build its
// own writable directory instead of sharing the Windows profile's Temp directory.
const environment = { ...process.env, TMPDIR: tempDirectory, TMP: tempDirectory, TEMP: tempDirectory }
const steps = [
  ['TypeScript', 'node_modules/vue-tsc/bin/vue-tsc.js', '-b'],
  ['Vite', 'node_modules/vite/bin/vite.js', 'build'],
  ['Artifact integrity', 'scripts/verify-dist.js'],
  ['First screen', 'scripts/verify-first-screen.mjs'],
  ['Icons', 'scripts/verify-icons.mjs'],
  ['Charts', 'scripts/verify-echarts.mjs'],
]
let exitCode = 0
try {
  console.log(`[build] Temporary directory: ${path.relative(root, tempDirectory)}`)
  for (const [label, script, ...args] of steps) {
    const scriptPath = path.join(root, script)
    if (!existsSync(scriptPath)) throw new Error(`Missing build tool: ${script}. Run npm ci in admin first.`)
    console.log(`[build] ${label}`)
    const result = spawnSync(process.execPath, [scriptPath, ...args], { cwd: root, env: environment, stdio: 'inherit' })
    if (result.error) throw result.error
    if (result.status !== 0) {
      exitCode = result.status ?? 1
      console.error(`[build] ${label} failed. Remaining checks were not run.`)
      break
    }
  }
} catch (error) {
  exitCode = 1
  console.error(`[build] ${error.message}`)
} finally {
  // Only remove the unique directory created above, after verifying its boundary.
  try {
    const resolvedDirectory = realpathSync(tempDirectory)
    const inside = parent => {
      const relative = path.relative(realpathSync(parent), resolvedDirectory)
      return relative && !relative.startsWith('..') && !path.isAbsolute(relative)
    }
    if (inside(root) && inside(tempBase)) {
      rmSync(resolvedDirectory, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
    } else {
      console.warn('[build] Temporary cleanup skipped: directory is outside the admin workspace.')
    }
  } catch (error) {
    if (error.code !== 'ENOENT') console.warn(`[build] Temporary directory retained (${error.code}): ${path.relative(root, tempDirectory)}`)
  }
}
process.exitCode = exitCode
