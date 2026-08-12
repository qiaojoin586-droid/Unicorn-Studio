import net from 'node:net'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadEnvFile } from '../server/env.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..')

loadEnvFile(path.join(rootDir, '.env'))

const requestedApiPort = Number(process.env.PORT || 3000)
if (!Number.isSafeInteger(requestedApiPort) || requestedApiPort < 1 || requestedApiPort > 65535) {
  throw new Error('PORT must be an integer between 1 and 65535')
}

const apiPort = await findAvailablePort(requestedApiPort)
if (apiPort !== requestedApiPort) {
  console.warn(
    'Port ' + requestedApiPort + ' is already in use. Development API will use port ' + apiPort + '.',
  )
}

const env = {
  ...process.env,
  NODE_ENV: 'development',
  HOST: '127.0.0.1',
  PORT: String(apiPort),
  VITE_API_PROXY_TARGET: process.env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:' + apiPort,
}

const children = [
  spawn(process.execPath, ['server/index.js'], {
    cwd: rootDir,
    env,
    stdio: 'inherit',
  }),
  spawn(process.execPath, ['node_modules/vite/bin/vite.js'], {
    cwd: rootDir,
    env,
    stdio: 'inherit',
  }),
]

let shuttingDown = false

function canListen(port) {
  return new Promise((resolve) => {
    const probe = net.createServer()

    probe.once('error', () => resolve(false))
    probe.listen(port, '127.0.0.1', () => {
      probe.close(() => resolve(true))
    })
  })
}

async function findAvailablePort(startPort) {
  for (let port = startPort; port < Math.min(startPort + 100, 65536); port += 1) {
    if (await canListen(port)) {
      return port
    }
  }

  throw new Error('No available development API port was found')
}

async function shutdown(exitCode = 0) {
  if (shuttingDown) {
    return
  }

  shuttingDown = true
  const runningChildren = children.filter((child) => child.exitCode === null)

  await Promise.all(runningChildren.map((child) => new Promise((resolve) => {
    child.once('exit', resolve)
    child.kill()
    setTimeout(resolve, 2000).unref()
  })))

  process.exit(exitCode)
}

for (const child of children) {
  child.on('error', (error) => {
    console.error('Failed to start development process:', error)
    void shutdown(1)
  })

  child.on('exit', (code) => {
    if (!shuttingDown && code !== 0) {
      void shutdown(code || 1)
    }
  })
}

process.on('SIGINT', () => {
  void shutdown(0)
})
process.on('SIGTERM', () => {
  void shutdown(0)
})