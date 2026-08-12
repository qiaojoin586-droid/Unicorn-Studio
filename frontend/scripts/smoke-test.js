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

    const resources = await request(baseUrl, '/api/learning/resources')
    assert.equal(resources.status, 200)
    const resourcesPayload = await resources.json()
    assert.ok(Array.isArray(resourcesPayload.resources))
    assert.ok(resourcesPayload.resources.length > 0)

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
