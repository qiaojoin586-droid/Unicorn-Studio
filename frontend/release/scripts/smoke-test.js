import assert from 'node:assert/strict'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..')

const backendTarget = process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080'

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

async function backendReachable() {
  try {
    const res = await fetch(backendTarget + '/api/health', { signal: AbortSignal.timeout(2000) })
    return res.ok
  } catch {
    return false
  }
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

    // ===== 不依赖后端的用例：健康检查与静态服务 =====
    const health = await request(baseUrl, '/api/health')
    assert.equal(health.status, 200)
    assert.deepEqual(await health.json(), { ok: true })

    const directRoute = await request(baseUrl, '/learning')
    assert.equal(directRoute.status, 200)
    assert.match(await directRoute.text(), /<div id="app"><\/div>/)

    const unknownAsset = await request(baseUrl, '/assets/missing.js')
    assert.equal(unknownAsset.status, 404)

    const videoFile = fs.readdirSync(path.join(rootDir, 'dist', 'assets'))
      .find((fileName) => fileName.endsWith('.mp4'))
    assert.ok(videoFile)

    const rangeResponse = await request(baseUrl, '/assets/' + videoFile, {
      headers: { Range: 'bytes=0-1023' },
    })
    assert.equal(rangeResponse.status, 206)
    assert.equal(rangeResponse.headers.get('content-length'), '1024')
    assert.match(rangeResponse.headers.get('content-range'), /^bytes 0-1023\//)
    assert.equal((await rangeResponse.arrayBuffer()).byteLength, 1024)

    // ===== API 代理与后端联调 =====
    const backendUp = await backendReachable()
    const resources = await request(baseUrl, '/api/learning/resources')

    if (backendUp) {
      // 后端可达：学习资料来自后端（统一信封 data.resources）
      assert.equal(resources.status, 200)
      const payload = await resources.json()
      assert.ok(Array.isArray(payload.data?.resources), '后端资源接口应返回 data.resources 数组')

      // 作品列表（统一信封 data.list）
      const works = await request(baseUrl, '/api/works')
      assert.equal(works.status, 200)
      const worksPayload = await works.json()
      assert.ok(Array.isArray(worksPayload.data?.list), '作品接口应返回 data.list 数组')
      if (worksPayload.data.list.length > 0) {
        const first = worksPayload.data.list[0]
        const detail = await request(baseUrl, '/api/works/' + first.slug)
        assert.equal(detail.status, 200)
        assert.ok(detail.bodyUsed === false || detail.status === 200)
      }

      // 留言提交（连续测试可能触发限流，200/429 均证明链路接通）
      const message = await request(baseUrl, '/api/messages', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'smoke-test', contact: '', content: 'smoke 测试留言', source: 'contact_form' }),
      })
      assert.ok([200, 429].includes(message.status), '留言接口应返回 200 或 429（限流）')

      // AI 聊天链路：流式 data 帧 / error 事件 / 503 未配置，三者任一即证明链路接通
      const chat = await request(baseUrl, '/api/ai/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: '你好' }),
        signal: AbortSignal.timeout(15_000),
      })
      if (chat.status === 200) {
        assert.match(chat.headers.get('content-type') || '', /text\/event-stream/)
        const body = await chat.text()
        assert.ok(body.includes('data:'), '应收到 SSE data 帧')
        assert.ok(
          body.includes('[DONE]') || body.includes('event: error'),
          '流应以 [DONE] 或 error 事件结束',
        )
      } else if (chat.status === 503) {
        // 503 响应体可能为 Spring 默认格式（无 message 字段），按文本宽容断言
        const body = await chat.text()
        assert.ok(
          body.includes('未配置') || body.includes('AI 服务'),
          '503 响应应提示 AI 服务未配置',
        )
      } else if (chat.status === 429) {
        // 连续测试可能触发限流——429 同样证明后端在线、链路接通
        const body = await chat.text()
        assert.ok(body.includes('429'), '429 响应应为限流提示')
      } else {
        assert.fail('AI 聊天接口返回异常状态: ' + chat.status)
      }
    } else {
      // 后端不可达：代理应返回 502 而非 404（证明 /api/* 已走反向代理）
      assert.equal(resources.status, 502)
      const chat = await request(baseUrl, '/api/ai/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: '你好' }),
      })
      assert.equal(chat.status, 502)
    }

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
