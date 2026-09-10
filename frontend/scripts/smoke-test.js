import assert from 'node:assert/strict'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..')

function getAvailablePort() {
  return new Promise((resolve, reject) => {
    const probe = net.createServer()
    probe.once('error', reject)
    probe.listen(0, '127.0.0.1', () => {
      const address = probe.address()
      probe.close(() => resolve(address.port))
    })
  })
}

function waitForServer(child) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error('Server did not start within 10 seconds'))
    }, 10_000)

    child.stdout.on('data', (chunk) => {
      if (chunk.toString().includes('server is running')) {
        clearTimeout(timeout)
        resolve()
      }
    })

    child.once('exit', (code) => {
      clearTimeout(timeout)
      reject(new Error('Server exited before startup with code ' + code))
    })
  })
}

function stopServer(child) {
  return new Promise((resolve) => {
    if (child.exitCode !== null) {
      resolve()
      return
    }

    const timeout = setTimeout(() => {
      child.kill('SIGKILL')
    }, 5_000)

    child.once('exit', () => {
      clearTimeout(timeout)
      resolve()
    })
    child.kill('SIGTERM')
  })
}

async function request(baseUrl, pathname, options = {}) {
  return fetch(baseUrl + pathname, {
    redirect: 'manual',
    ...options,
  })
}

async function run() {
  const port = await getAvailablePort()
  const baseUrl = 'http://127.0.0.1:' + port
  const child = spawn(process.execPath, ['server/index.js'], {
    cwd: rootDir,
    env: {
      ...process.env,
      NODE_ENV: 'production',
      HOST: '127.0.0.1',
      PORT: String(port),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })

  let stderr = ''
  child.stderr.on('data', (chunk) => {
    stderr += chunk.toString()
  })

  try {
    await waitForServer(child)

    const health = await request(baseUrl, '/api/health')
    assert.equal(health.status, 200)
    assert.deepEqual(await health.json(), { ok: true })

    // 静态资源：robots/sitemap 由 public/ 随构建产出；favicon 引用被 Vite 重写为哈希路径，
    // 从产物 index.html 动态解析后验证（防止再次静默丢失，优化方案 F-01）
    for (const staticPath of ['/robots.txt', '/sitemap.xml']) {
      const staticRes = await request(baseUrl, staticPath)
      assert.equal(staticRes.status, 200, staticPath + ' 应存在（frontend/public/）')
    }

    const homePage = await request(baseUrl, '/')
    assert.equal(homePage.status, 200)
    const iconHref = (await homePage.text()).match(/<link rel="icon"[^>]*href="([^"]+)"/)?.[1]
    assert.ok(iconHref, 'index.html 应包含 favicon 引用')
    const iconRes = await request(baseUrl, iconHref)
    assert.equal(iconRes.status, 200, 'favicon ' + iconHref + ' 应可访问')

    const resources = await request(baseUrl, '/api/learning/resources')
    assert.equal(resources.status, 200)
    const resourcesPayload = await resources.json()
    // 兼容后端统一信封 {data:{resources}} 与旧平铺 {resources}
    const resourcesList = resourcesPayload.data?.resources || resourcesPayload.resources
    assert.ok(Array.isArray(resourcesList), '学习资料应来自后端数据库（信封 data.resources）')
    assert.ok(resourcesList.length > 0)

    // 能力培养：来自数据库 /api/services
    const services = await request(baseUrl, '/api/services')
    assert.equal(services.status, 200)
    const servicesPayload = await services.json()
    assert.ok(Array.isArray(servicesPayload.data?.list), '能力条目接口应返回 data.list 数组')
    assert.ok(servicesPayload.data.list.length > 0, '能力条目种子数据应存在')

    // 作品：来自数据库 /api/works
    const works = await request(baseUrl, '/api/works')
    assert.equal(works.status, 200)
    const worksPayload = await works.json()
    assert.ok(Array.isArray(worksPayload.data?.list), '作品接口应返回 data.list 数组')
    assert.ok(worksPayload.data.list.length > 0, '作品种子数据应存在')

    const directRoute = await request(baseUrl, '/learning')
    assert.equal(directRoute.status, 200)
    assert.match(await directRoute.text(), /<div id="app"><\/div>/)

    const unknownAsset = await request(baseUrl, '/assets/missing.js')
    assert.equal(unknownAsset.status, 404)

    const videoFile = fs.readdirSync(path.join(rootDir, 'frontend', 'assets'))
      .find((fileName) => fileName.endsWith('.mp4'))
    assert.ok(videoFile)

    const rangeResponse = await request(baseUrl, '/assets/' + videoFile, {
      headers: { Range: 'bytes=0-1023' },
    })
    assert.equal(rangeResponse.status, 206)
    assert.equal(rangeResponse.headers.get('content-length'), '1024')
    assert.match(rangeResponse.headers.get('content-range'), /^bytes 0-1023\//)
    assert.equal((await rangeResponse.arrayBuffer()).byteLength, 1024)

    console.log('Smoke tests passed.')
  } finally {
    await stopServer(child)
  }

  assert.equal(stderr, '')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
