import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadEnvFile } from './env.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..')
const distDir = path.join(rootDir, 'frontend')

loadEnvFile(path.join(rootDir, '.env'))

const isProduction = process.env.NODE_ENV === 'production'
// Java 后端地址：Node 将 /api/* 反向代理到此处（学习资料/作品/能力等业务数据统一来自数据库）
const apiProxyTarget = process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080'
const host = process.env.HOST || '127.0.0.1'
const port = readIntegerEnv('PORT', 3000, 1, 65535)

validateConfiguration()

const contentTypes = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.ico': 'image/x-icon',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.mp4': 'video/mp4',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.txt': 'text/plain; charset=utf-8',
  '.webp': 'image/webp',
}

function readIntegerEnv(name, fallback, min, max = Number.MAX_SAFE_INTEGER) {
  const value = Number(process.env[name] ?? fallback)

  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new Error(name + ' must be an integer between ' + min + ' and ' + max)
  }

  return value
}

function validateConfiguration() {
  const errors = []

  if (isProduction && !fs.existsSync(path.join(distDir, 'index.html'))) {
    errors.push('frontend/index.html is missing; run npm run build before starting production')
  }

  if (errors.length > 0) {
    throw new Error('Invalid server configuration:\n- ' + errors.join('\n- '))
  }
}

function setSecurityHeaders(res) {
  res.setHeader('X-Content-Type-Options', 'nosniff')
  res.setHeader('X-Frame-Options', 'DENY')
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin')
  res.setHeader('Cross-Origin-Opener-Policy', 'same-origin')
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()')
  res.setHeader(
    'Content-Security-Policy',
    "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; img-src 'self' data:; media-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; form-action 'self'",
  )

  if (isProduction) {
    res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains')
  }
}

function sendJson(res, statusCode, data, extraHeaders = {}) {
  const body = JSON.stringify(data)
  setSecurityHeaders(res)
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    ...extraHeaders,
  })
  res.end(body)
}

function sendText(res, statusCode, body, extraHeaders = {}) {
  setSecurityHeaders(res)
  res.writeHead(statusCode, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    ...extraHeaders,
  })
  res.end(body)
}

function handleApi(req, res, pathname, search) {
  if (pathname === '/api/health' && req.method === 'GET') {
    // Node 进程自身存活检查；业务健康检查见后端 /actuator/health
    return sendJson(res, 200, { ok: true })
  }

  // 其余 /api/* 与 /uploads/*（后台上传的图片，如轮播图）一律反向代理到 Java 后端——
  // 业务数据（学习资料/作品/能力等）统一来自数据库，禁止在 Node 层 mock，避免"集成断裂不可见"
  return proxyToBackend(req, res, pathname + search)
}

function proxyToBackend(req, res, pathnameWithSearch) {
  const targetUrl = new URL(apiProxyTarget)
  const headers = { ...req.headers, host: targetUrl.host }
  // 注意：透传时不重算 Content-Length，直接复用原始 header；若缺失则删除该头，由 Node 自行处理
  if (headers['content-length'] === undefined) {
    delete headers['content-length']
  }

  // X-Forwarded-For 安全（S-7）：必须【追加】本机看到的对端地址，而不是原样透传。
  //
  // 此前 headers 直接展开 req.headers，客户端自带的 X-Forwarded-For 会原封不动到达后端 ——
  // 而后端当时取的是该头的【第一个】值，即完全由客户端控制，可据此绕过全部 IP 维度限流
  // （登录/注册/评论/留言/PV/AI 对话），审计日志里的客户端 IP 同样可被伪造。
  //
  // 这里与 nginx 的 $proxy_add_x_forwarded_for 保持【同一语义】（追加），后端配套地
  // 「从右往左取第 N 跳」（N = app.security.trusted-proxy-count，见 IpUtils）：
  //   只有本代理一层时   → "<客户端伪造值>, <真实客户端IP>"，取最右 = 真实 IP ✅
  //   nginx → 本代理两层 → "<...>, <真实客户端IP>, <nginx IP>"，取最右 2 跳 = 真实 IP ✅
  // 若此处改为覆写（= remoteAddress），两层代理的拓扑下会把 nginx 的 IP 当成客户端，故用追加。
  const peerAddress = req.socket?.remoteAddress
  if (peerAddress) {
    const priorXff = req.headers['x-forwarded-for']
    headers['x-forwarded-for'] = priorXff ? priorXff + ', ' + peerAddress : peerAddress
  } else {
    delete headers['x-forwarded-for']
  }

  const upstream = http.request(targetUrl.origin + pathnameWithSearch, {
    method: req.method,
    headers,
  }, (upRes) => {
    res.writeHead(upRes.statusCode, {
      ...upRes.headers,
      // 缓存分流（优化方案 F-10）：/api/*（含 SSE）不缓存；/uploads/* 上传图片走协商缓存，
      // 否则轮播图等每次访问全量重下
      'Cache-Control': pathnameWithSearch.startsWith('/api/')
        ? 'no-store'
        : 'public, max-age=3600',
    })
    upRes.pipe(res) // 关键：流式 pipe，不做任何聚合缓冲
  })

  upstream.on('error', () => {
    if (!res.headersSent) {
      sendJson(res, 502, { message: '后端服务暂不可用，请稍后重试' })
    } else {
      res.destroy()
    }
  })

  req.pipe(upstream) // 请求体透传（POST 聊天消息）
}

function resolveStaticPath(pathname) {
  let decodedPath

  try {
    decodedPath = decodeURIComponent(pathname)
  } catch {
    return { error: 400 }
  }

  const requestedPath = decodedPath === '/' ? '/index.html' : decodedPath
  const filePath = path.resolve(distDir, '.' + requestedPath)

  if (filePath !== distDir && !filePath.startsWith(distDir + path.sep)) {
    return { error: 403 }
  }

  if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
    return { filePath }
  }

  if (path.extname(decodedPath)) {
    return { error: 404 }
  }

  return { filePath: path.join(distDir, 'index.html') }
}

function parseRange(rangeHeader, fileSize) {
  const match = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader || '')
  if (!match) {
    return null
  }

  let start
  let end

  if (!match[1]) {
    const suffixLength = Number(match[2])
    if (!Number.isSafeInteger(suffixLength) || suffixLength <= 0) {
      return null
    }
    start = Math.max(fileSize - suffixLength, 0)
    end = fileSize - 1
  } else {
    start = Number(match[1])
    end = match[2] ? Number(match[2]) : fileSize - 1
  }

  if (
    !Number.isSafeInteger(start) ||
    !Number.isSafeInteger(end) ||
    start < 0 ||
    end < start ||
    start >= fileSize
  ) {
    return null
  }

  return { start, end: Math.min(end, fileSize - 1) }
}

function sendStaticFile(req, res, pathname) {
  const resolved = resolveStaticPath(pathname)
  if (resolved.error) {
    return sendText(res, resolved.error, http.STATUS_CODES[resolved.error] || 'Request failed')
  }

  let stat
  try {
    stat = fs.statSync(resolved.filePath)
  } catch {
    return sendText(res, 404, 'Not Found')
  }

  const extension = path.extname(resolved.filePath).toLowerCase()
  const isHashedAsset = resolved.filePath.includes(path.sep + 'assets' + path.sep)
  const headers = {
    'Content-Type': contentTypes[extension] || 'application/octet-stream',
    'Cache-Control': isHashedAsset
      ? 'public, max-age=31536000, immutable'
      : 'no-cache',
    'Accept-Ranges': 'bytes',
  }
  let statusCode = 200
  let streamOptions

  if (req.headers.range) {
    const range = parseRange(req.headers.range, stat.size)
    if (!range) {
      return sendText(res, 416, 'Range Not Satisfiable', {
        'Content-Range': 'bytes */' + stat.size,
      })
    }

    statusCode = 206
    streamOptions = range
    headers['Content-Range'] =
      'bytes ' + range.start + '-' + range.end + '/' + stat.size
    headers['Content-Length'] = range.end - range.start + 1
  } else {
    headers['Content-Length'] = stat.size
  }

  setSecurityHeaders(res)
  res.writeHead(statusCode, headers)

  if (req.method === 'HEAD') {
    res.end()
    return
  }

  fs.createReadStream(resolved.filePath, streamOptions)
    .on('error', () => res.destroy())
    .pipe(res)
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', 'http://localhost')
    const { pathname, search } = url

    if (pathname.startsWith('/api/') || pathname.startsWith('/uploads/')) {
      await handleApi(req, res, pathname, search)
      return
    }

    if (req.method !== 'GET' && req.method !== 'HEAD') {
      return sendText(res, 405, 'Method Not Allowed', { Allow: 'GET, HEAD' })
    }

    sendStaticFile(req, res, pathname)
  } catch (error) {
    console.error('Request failed:', error)
    if (!res.headersSent) {
      sendJson(res, 500, { message: '服务器内部错误' })
    } else {
      res.destroy()
    }
  }
})

server.headersTimeout = 10_000
server.requestTimeout = 15_000
server.keepAliveTimeout = 5_000
server.maxHeadersCount = 100

server.on('clientError', (_error, socket) => {
  if (socket.writable) {
    socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n')
  }
})

server.listen(port, host, () => {
  console.log('Unicorn Studio server is running at http://' + host + ':' + port)
})

function shutdown(signal) {
  console.log(signal + ' received, shutting down...')
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(1), 10_000).unref()
}

process.once('SIGINT', () => shutdown('SIGINT'))
process.once('SIGTERM', () => shutdown('SIGTERM'))
