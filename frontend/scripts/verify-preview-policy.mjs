import assert from 'node:assert/strict'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import { embeddingPolicy, trustedAdminOrigin } from '../server/preview-policy.js'

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
let assertions = 0
function equal(actual, expected) { assert.equal(actual, expected); assertions++ }

for (const invalid of ['', '//evil.example', '/admin/', 'javascript:alert(1)', 'data:text/html,test', 'https://user:pass@admin.example', 'https://admin.example/?x=1', 'https://admin.example/#x', 'https://admin.example\n', "https://admin.example/; frame-ancestors *", 'https:\\evil.example']) {
  equal(trustedAdminOrigin(invalid), null)
}
equal(trustedAdminOrigin('https://admin.example/admin/'), 'https://admin.example')
equal(trustedAdminOrigin('http://127.0.0.1:5174/admin/'), 'http://127.0.0.1:5174')
equal(embeddingPolicy('/preview/home', false, 'https://admin.example').frameOptions, 'DENY')

async function availablePort() {
  const probe = net.createServer()
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve) })
  const port = probe.address().port
  await new Promise(resolve => probe.close(resolve))
  return port
}

async function startServer(adminUrl) {
  const port = await availablePort()
  const child = spawn(process.execPath, ['server/index.js'], {
    cwd: rootDir, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, NODE_ENV: 'production', HOST: '127.0.0.1', PORT: String(port), VITE_ADMIN_SITE_URL: adminUrl },
  })
  let stderr = ''
  child.stderr.on('data', chunk => { stderr += chunk })
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Production server startup timed out: ' + stderr)), 10000)
    child.stdout.on('data', chunk => { if (String(chunk).includes('server is running')) { clearTimeout(timer); resolve() } })
    child.once('error', error => { clearTimeout(timer); reject(error) })
    child.once('exit', code => { clearTimeout(timer); reject(new Error('Production server exited: ' + code + ' ' + stderr)) })
  }).catch(error => { child.kill(); throw error })
  return { child, base: `http://127.0.0.1:${port}` }
}

async function stopServer(child) {
  if (child.exitCode !== null) return
  await new Promise(resolve => {
    const timer = setTimeout(() => child.kill('SIGKILL'), 3000)
    child.once('exit', () => { clearTimeout(timer); resolve() })
    child.kill('SIGTERM')
  })
}

async function verifyDeployment(adminUrl, expectedOrigin) {
  const { child, base } = await startServer(adminUrl)
  try {
    for (const pathname of ['/', '/about', '/preview/unknown', '/preview/home/extra', '/api/health']) {
      const response = await fetch(base + pathname)
      equal(response.status, 200)
      equal(response.headers.get('x-frame-options'), 'DENY')
      equal(response.headers.get('cross-origin-opener-policy'), 'same-origin')
      equal(response.headers.get('content-security-policy').includes("frame-ancestors 'none';"), true)
      await response.arrayBuffer()
    }
    for (const kind of ['home', 'work', 'about', 'join', 'footer', 'learning']) {
      const response = await fetch(base + '/preview/' + kind + '?parentOrigin=https://evil.example', { method: kind === 'footer' ? 'HEAD' : 'GET' })
      equal(response.status, 200)
      equal(response.headers.get('content-type').startsWith('text/html'), true)
      equal(response.headers.get('x-frame-options'), expectedOrigin ? null : 'SAMEORIGIN')
      equal(response.headers.get('cross-origin-opener-policy'), 'unsafe-none')
      const csp = response.headers.get('content-security-policy')
      equal(csp.includes("frame-ancestors 'self'" + (expectedOrigin ? ' ' + expectedOrigin : '') + ';'), true)
      equal(csp.includes('evil.example'), false)
      equal(csp.includes("script-src 'self';"), true)
      equal(response.headers.get('x-content-type-options'), 'nosniff')
      await response.arrayBuffer()
    }
    // The HTML exception must not relax errors or write requests on the same path.
    const rejected = await fetch(base + '/preview/home', { method: 'POST' })
    equal(rejected.status, 405)
    equal(rejected.headers.get('x-frame-options'), 'DENY')
    await rejected.arrayBuffer()
  } finally { await stopServer(child) }
}

await verifyDeployment('', null)
await verifyDeployment('https://admin.example/admin/', 'https://admin.example')
await verifyDeployment('javascript:alert(1)', null)
console.log(`Preview embedding policy: ${assertions} assertions passed against the production Node server.`)
