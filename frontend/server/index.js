import crypto from 'node:crypto'
import fs from 'node:fs'
import http from 'node:http'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadEnvFile } from './env.js'
import { learningResources } from './learningResources.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..')
const distDir = path.join(rootDir, 'frontend')

loadEnvFile(path.join(rootDir, '.env'))

const isProduction = process.env.NODE_ENV === 'production'
const host = process.env.HOST || '127.0.0.1'
const port = readIntegerEnv('PORT', 3000, 1, 65535)
const learningPassword = process.env.LEARNING_PASSWORD || ''
const sessionSecret = process.env.SESSION_SECRET || crypto.randomBytes(32).toString('hex')
const sessionTtlMs = readIntegerEnv('SESSION_TTL_MS', 1000 * 60 * 60 * 8, 60_000)
const maxLoginAttempts = readIntegerEnv('LOGIN_RATE_LIMIT_MAX', 10, 1, 1000)
const loginWindowMs = readIntegerEnv('LOGIN_RATE_LIMIT_WINDOW_MS', 1000 * 60 * 10, 1000)
const maxTrackedLoginIps = readIntegerEnv('LOGIN_RATE_LIMIT_MAX_IPS', 10_000, 100, 1_000_000)
const cookieName = 'learning_session'
const secureCookie = isProduction || process.env.COOKIE_SECURE === 'true'
const trustProxy = process.env.TRUST_PROXY === 'true'
const loginAttempts = new Map()

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

class RequestBodyTooLargeError extends Error {}

function readIntegerEnv(name, fallback, min, max = Number.MAX_SAFE_INTEGER) {
  const value = Number(process.env[name] ?? fallback)

  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new Error(name + ' must be an integer between ' + min + ' and ' + max)
  }

  return value
}

function validateConfiguration() {
  const errors = []
  const placeholderPasswords = new Set([
    'change-this-password',
    'change-this-to-at-least-12-characters',
  ])
  const placeholderSecrets = new Set([
    'replace-with-a-long-random-secret',
    'replace-with-at-least-32-random-characters',
    'local-development-session-secret-change-me',
  ])

  if (!learningPassword) {
    errors.push('LEARNING_PASSWORD is required')
  }

  if (isProduction && (learningPassword.length < 12 || placeholderPasswords.has(learningPassword))) {
    errors.push('LEARNING_PASSWORD must be non-placeholder and contain at least 12 characters')
  }

  if (isProduction && (sessionSecret.length < 32 || placeholderSecrets.has(sessionSecret))) {
    errors.push('SESSION_SECRET must be a non-placeholder value with at least 32 characters')
  }

  if (isProduction && !fs.existsSync(path.join(distDir, 'index.html'))) {
    errors.push('frontend/index.html is missing; run npm run build before starting production')
  }

  if (errors.length > 0) {
    throw new Error('Invalid server configuration:\n- ' + errors.join('\n- '))
  }

  if (!process.env.SESSION_SECRET) {
    console.warn('SESSION_SECRET is not set. Sessions will be invalidated when the server restarts.')
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

function readBody(req, maxBytes = 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    let tooLarge = false

    req.on('data', (chunk) => {
      size += chunk.length
      if (size > maxBytes) {
        tooLarge = true
        return
      }

      chunks.push(chunk)
    })

    req.on('end', () => {
      if (tooLarge) {
        reject(new RequestBodyTooLargeError('Request body is too large'))
        return
      }

      resolve(Buffer.concat(chunks).toString('utf8'))
    })
    req.on('error', reject)
  })
}

function parseCookies(cookieHeader = '') {
  const cookies = {}

  for (const cookie of cookieHeader.split(';')) {
    const trimmed = cookie.trim()
    const separatorIndex = trimmed.indexOf('=')
    if (separatorIndex <= 0) {
      continue
    }

    try {
      const name = decodeURIComponent(trimmed.slice(0, separatorIndex))
      cookies[name] = decodeURIComponent(trimmed.slice(separatorIndex + 1))
    } catch {
      // Ignore malformed cookies instead of failing the whole request.
    }
  }

  return cookies
}

function sign(value) {
  return crypto.createHmac('sha256', sessionSecret).update(value).digest('base64url')
}

function createSessionCookie() {
  const payload = Buffer.from(JSON.stringify({ exp: Date.now() + sessionTtlMs })).toString('base64url')
  const token = payload + '.' + sign(payload)
  const secureFlag = secureCookie ? '; Secure' : ''

  return cookieName + '=' + encodeURIComponent(token) + '; Max-Age=' +
    Math.floor(sessionTtlMs / 1000) + '; Path=/; HttpOnly; SameSite=Lax' + secureFlag
}

function clearSessionCookie() {
  const secureFlag = secureCookie ? '; Secure' : ''
  return cookieName + '=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax' + secureFlag
}

function hasValidSession(req) {
  const token = parseCookies(req.headers.cookie)[cookieName]
  if (!token) {
    return false
  }

  const [payload, signature] = token.split('.')
  if (!payload || !signature || !timingSafeEqualText(sign(payload), signature)) {
    return false
  }

  try {
    const session = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'))
    return Number(session.exp) > Date.now()
  } catch {
    return false
  }
}

function timingSafeEqualText(left, right) {
  const leftBuffer = Buffer.from(left)
  const rightBuffer = Buffer.from(right)

  if (leftBuffer.length !== rightBuffer.length) {
    return false
  }

  return crypto.timingSafeEqual(leftBuffer, rightBuffer)
}

function getClientIp(req) {
  if (trustProxy && req.headers['x-forwarded-for']) {
    const forwardedIp = String(req.headers['x-forwarded-for']).split(',')[0].trim()
    if (net.isIP(forwardedIp)) {
      return forwardedIp
    }
  }

  return req.socket.remoteAddress || 'unknown'
}

function pruneLoginAttempts(now = Date.now()) {
  for (const [ip, entry] of loginAttempts) {
    if (now - entry.firstAttemptAt > loginWindowMs) {
      loginAttempts.delete(ip)
    }
  }
}

function getRateLimitState(ip) {
  const now = Date.now()
  let current = loginAttempts.get(ip)

  if (current && now - current.firstAttemptAt > loginWindowMs) {
    loginAttempts.delete(ip)
    current = undefined
  }

  if (!current && loginAttempts.size >= maxTrackedLoginIps) {
    pruneLoginAttempts(now)
    if (loginAttempts.size >= maxTrackedLoginIps) {
      return { limited: true, retryAfterSeconds: Math.ceil(loginWindowMs / 1000) }
    }
  }

  if (!current || current.count < maxLoginAttempts) {
    return { limited: false, retryAfterSeconds: 0 }
  }

  const retryAfterMs = loginWindowMs - (now - current.firstAttemptAt)
  return {
    limited: true,
    retryAfterSeconds: Math.max(1, Math.ceil(retryAfterMs / 1000)),
  }
}

function recordFailedLogin(ip) {
  const now = Date.now()
  const current = loginAttempts.get(ip)

  if (!current || now - current.firstAttemptAt > loginWindowMs) {
    loginAttempts.set(ip, { count: 1, firstAttemptAt: now })
    return
  }

  current.count += 1
}

function isCrossSiteRequest(req) {
  if (req.headers['sec-fetch-site'] === 'cross-site') {
    return true
  }

  const origin = req.headers.origin
  if (!origin) {
    return false
  }

  try {
    return new URL(origin).host !== req.headers.host
  } catch {
    return true
  }
}

async function handleApi(req, res, pathname) {
  if (pathname === '/api/health' && req.method === 'GET') {
    return sendJson(res, 200, { ok: true })
  }

  if (pathname === '/api/learning/resources' && req.method === 'GET') {
    if (!hasValidSession(req)) {
      return sendJson(res, 401, { message: '请先验证访问密码' })
    }

    return sendJson(res, 200, { resources: learningResources })
  }

  if (pathname === '/api/learning/login' && req.method === 'POST') {
    if (isCrossSiteRequest(req)) {
      return sendJson(res, 403, { message: '拒绝跨站请求' })
    }

    if (!String(req.headers['content-type'] || '').toLowerCase().startsWith('application/json')) {
      return sendJson(res, 415, { message: '仅支持 JSON 请求' })
    }

    const clientIp = getClientIp(req)
    const rateLimitState = getRateLimitState(clientIp)

    if (rateLimitState.limited) {
      return sendJson(
        res,
        429,
        { message: '尝试次数过多，请稍后再试' },
        { 'Retry-After': rateLimitState.retryAfterSeconds },
      )
    }

    try {
      const body = await readBody(req)
      const data = JSON.parse(body || '{}')

      if (!timingSafeEqualText(String(data.password || ''), learningPassword)) {
        recordFailedLogin(clientIp)
        return sendJson(res, 401, { message: '密码错误，请重试' })
      }

      loginAttempts.delete(clientIp)
      res.setHeader('Set-Cookie', createSessionCookie())
      return sendJson(res, 200, { resources: learningResources })
    } catch (error) {
      recordFailedLogin(clientIp)
      const statusCode = error instanceof RequestBodyTooLargeError ? 413 : 400
      return sendJson(res, statusCode, { message: '请求格式不正确' })
    }
  }

  if (pathname === '/api/learning/logout' && req.method === 'POST') {
    if (isCrossSiteRequest(req)) {
      return sendJson(res, 403, { message: '拒绝跨站请求' })
    }

    res.setHeader('Set-Cookie', clearSessionCookie())
    return sendJson(res, 200, { ok: true })
  }

  return sendJson(res, 404, { message: '接口不存在' })
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
    const { pathname } = new URL(req.url || '/', 'http://localhost')

    if (pathname.startsWith('/api/')) {
      await handleApi(req, res, pathname)
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