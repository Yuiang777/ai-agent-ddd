/** Local-only acceptance harness. Never expose these test credentials beyond loopback. */
import http from 'node:http'
import net from 'node:net'
import { spawn } from 'node:child_process'
import { randomBytes, createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import fs from 'node:fs/promises'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const args = Object.fromEntries(process.argv.slice(2).map(value => value.replace(/^--/, '').split('=')))
const port = Number(args.port || 4173)
const backendPort = Number(args['backend-port'] || 18091)
const role = args.role || 'LAWYER'
if (!['LAWYER', 'PARALEGAL'].includes(role)) throw new Error('Acceptance role must be LAWYER or PARALEGAL')
if (![port, backendPort].every(value => Number.isInteger(value) && value > 1024 && value <= 65535) || port === backendPort) throw new Error('Choose two different ports between 1025 and 65535')
const dist = path.join(root, 'front', 'dist')
const jar = path.join(root, 'ddd-agent-sheep-app', 'target', 'ai-agent-station-study-app.jar')
await Promise.all([fs.access(path.join(dist, 'index.html')), fs.access(jar)])
for (const candidate of [port, backendPort]) {
  const probe = net.createServer()
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(candidate, '127.0.0.1', resolve) })
  await new Promise(resolve => probe.close(resolve))
}
const session = randomBytes(8).toString('hex')
const token = randomBytes(32).toString('hex')
const runtime = path.join(root, 'data', 'acceptance', session)
await fs.mkdir(runtime, { recursive: true })
const runtimeJar = path.join(runtime, 'acceptance-app.jar')
await fs.copyFile(jar, runtimeJar)
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
const backendArgs = [
  `-Dacceptance.session=${session}`, '-jar', runtimeJar,
  '--spring.profiles.active=offline', '--server.address=127.0.0.1', `--server.port=${backendPort}`,
  '--app.infrastructure.enabled=false', '--app.model-runtime.enabled=false', '--app.legal.matter-store=memory',
  '--app.admin-api.enabled=true', `--app.admin-api.token=${token}`,
  '--app.enterprise.security-context-enabled=true', '--app.enterprise.trusted-context-header-enabled=true',
  `--app.legal.knowledge.storage-root=${path.join(runtime, 'knowledge')}`,
  `--app.legal.object-storage.filesystem-root=${path.join(runtime, 'objects')}`,
  `--workflow.storage-root=${path.join(runtime, 'workflows')}`
]
const backend = spawn(java, backendArgs, { cwd: root, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
const log = await fs.open(path.join(runtime, 'backend.log'), 'a')
backend.stdout.on('data', data => log.write(data).catch(() => {}))
backend.stderr.on('data', data => log.write(data).catch(() => {}))
let stopped = false
let backendExited = false
let server
async function stop(code = 0) {
  if (stopped) return
  stopped = true
  server?.close()
  if (!backendExited) backend.kill()
  await fs.writeFile(path.join(runtime, 'stopped.txt'), new Date().toISOString()).catch(() => {})
  process.exitCode = code
}
backend.once('error', error => { console.error(`Backend launch failed: ${error.message}`); backendExited = true; stop(1) })
backend.once('exit', code => { backendExited = true; if (!stopped) { console.error(`Backend exited (${code}); inspect ${path.join(runtime, 'backend.log')}`); stop(1) } })
process.once('SIGINT', () => stop())
process.once('SIGTERM', () => stop())
process.once('exit', () => { if (!backendExited) backend.kill() })
const authHeaders = {
  'X-Admin-Token': token,
  'X-Authenticated-Tenant-Id': 'acceptance-tenant',
  'X-Authenticated-Actor-Id': role === 'LAWYER' ? 'acceptance-lawyer' : 'acceptance-paralegal',
  'X-Authenticated-Actor-Role': role
}
let ready = false
for (let attempt = 0; attempt < 120 && !backendExited; attempt++) {
  try {
    const response = await fetch(`http://127.0.0.1:${backendPort}/api/legal/matters/context`, { headers: authHeaders, signal: AbortSignal.timeout(1000) })
    const body = await response.json()
    if (body.success && body.data?.actorId === authHeaders['X-Authenticated-Actor-Id']) { ready = true; break }
  } catch { }
  await new Promise(resolve => setTimeout(resolve, 500))
}
if (!ready) { await stop(1); throw new Error(`Backend not ready; inspect ${path.join(runtime, 'backend.log')}`) }
async function fingerprintDirectory(directory) {
  const entries = (await fs.readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))
  const hash = createHash('sha256')
  for (const entry of entries) {
    const target = path.join(directory, entry.name)
    hash.update(entry.name + '\0')
    hash.update(entry.isDirectory() ? await fingerprintDirectory(target) : createHash('sha256').update(await fs.readFile(target)).digest('hex'))
  }
  return hash.digest('hex')
}
const buildIdentity = {
  jarSha256: createHash('sha256').update(await fs.readFile(runtimeJar)).digest('hex'),
  frontendSha256: await fingerprintDirectory(dist),
  harnessSha256: createHash('sha256').update(await fs.readFile(fileURLToPath(import.meta.url))).digest('hex')
}
const origin = `http://127.0.0.1:${port}`
const types = { '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.html': 'text/html; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2' }
server = http.createServer(async (request, response) => {
  // A remote browser origin must never use this local test identity.
  if (request.headers.host !== `127.0.0.1:${port}` || (request.headers.origin && request.headers.origin !== origin) || request.headers['sec-fetch-site'] === 'cross-site') {
    response.writeHead(403); response.end('Local acceptance origin required'); return
  }
  response.setHeader('X-Content-Type-Options', 'nosniff')
  response.setHeader('Referrer-Policy', 'same-origin')
  response.setHeader('Cache-Control', 'no-store')
  if (request.url === '/__acceptance/build' && request.method === 'GET') {
    try {
      const currentFrontend = await fingerprintDirectory(dist)
      if (currentFrontend !== buildIdentity.frontendSha256) {
        response.writeHead(409, { 'Content-Type': 'application/json' })
        response.end(JSON.stringify({ error: 'ACCEPTANCE_BUILD_CHANGED_RESTART_REQUIRED' })); return
      }
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify(buildIdentity)); return
    } catch {
      response.writeHead(409, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ error: 'ACCEPTANCE_BUILD_UNREADABLE_RESTART_REQUIRED' })); return
    }
  }
  const identities = {
    author: { actorId: 'acceptance-lawyer', role: 'LAWYER' },
    reviewer: { actorId: 'acceptance-reviewer', role: 'LAWYER' },
    signer: { actorId: 'acceptance-signer', role: 'LAWYER' }
  }
  const identityName = request.headers.cookie?.split(';').map(value => value.trim()).find(value => value.startsWith('acceptance-identity='))?.split('=')[1]
  const identity = Object.hasOwn(identities, identityName) ? identities[identityName] : undefined
  if (identityName && !identity) { response.writeHead(400); response.end('Unknown acceptance identity'); return }
  if (request.url === '/__acceptance/switch' && request.method === 'POST') {
    let body = ''
    for await (const chunk of request) { body += chunk.toString(); if (body.length > 128) { response.writeHead(413); response.end(); return } }
    const chosen = new URLSearchParams(body).get('actor')
    if (!Object.hasOwn(identities, chosen)) { response.writeHead(400); response.end('Unknown acceptance identity'); return }
    response.writeHead(303, { 'Set-Cookie': 'acceptance-identity=' + chosen + '; Path=/; SameSite=Strict; HttpOnly', Location: '/matters' })
    response.end(); return
  }
  if (request.url === '/__acceptance/identity' && request.method === 'POST') {
    const chosen = request.headers['x-acceptance-identity']
    if (!Object.hasOwn(identities, chosen)) { response.writeHead(400); response.end('Unknown acceptance identity'); return }
    response.writeHead(200, { 'Set-Cookie': 'acceptance-identity=' + chosen + '; Path=/; SameSite=Strict; HttpOnly', 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ success: true })); return
  }
  if (request.url.startsWith('/api/')) {
    const headers = { ...request.headers }
    for (const name of Object.keys(headers)) {
      if (name.startsWith('x-authenticated-') || name === 'x-admin-token' || name === 'authorization' || name === 'cookie' || name === 'host' || name === 'x-acceptance-identity') delete headers[name]
    }
    Object.assign(headers, authHeaders)
    if (identity) { headers['X-Authenticated-Actor-Id'] = identity.actorId; headers['X-Authenticated-Actor-Role'] = identity.role }
    headers.host = `127.0.0.1:${backendPort}`
    const upstream = http.request({ hostname: '127.0.0.1', port: backendPort, path: request.url, method: request.method, headers }, incoming => {
      response.writeHead(incoming.statusCode || 502, { ...incoming.headers, 'cache-control': 'no-store' })
      incoming.pipe(response)
    })
    upstream.on('error', () => { if (!response.headersSent) response.writeHead(502, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ success: false, message: '本地验收后端连接失败，请查看启动日志' })) })
    upstream.setTimeout(125000, () => upstream.destroy())
    response.on('close', () => { if (!response.writableEnded) upstream.destroy() })
    request.pipe(upstream)
    return
  }
  if (!['GET', 'HEAD'].includes(request.method)) { response.writeHead(405); response.end(); return }
  try {
    const pathname = decodeURIComponent(new URL(request.url, origin).pathname)
    let file = path.resolve(dist, '.' + pathname)
    if (file !== dist && !file.startsWith(dist + path.sep)) { response.writeHead(403); response.end(); return }
    let content
    try { content = await fs.readFile(file) }
    catch {
      if (path.extname(pathname) && path.extname(pathname) !== '.html') { response.writeHead(404); response.end(); return }
      file = path.join(dist, 'index.html'); content = await fs.readFile(file)
    }
    if (path.extname(file) === '.html') {
      const activeIdentity = identityName || (role === 'LAWYER' ? 'author' : '')
      const selector = '<form method="post" action="/__acceptance/switch" style="display:inline-flex;gap:8px;margin-left:12px"><label>测试处理人 <select name="actor" aria-label="测试处理人">' + Object.entries(identities).map(([key, value]) => '<option value="' + key + '"' + (key === activeIdentity ? ' selected' : '') + '>' + { author: '作者', reviewer: '复核人', signer: '签署律师' }[key] + ' (' + value.actorId + ')</option>').join('') + '</select></label><button type="submit">切换身份</button></form>'
      const banner = '<div role="status" style="padding:8px 16px;background:#fff3cd;color:#664d03;font:13px/1.5 system-ui;text-align:center">本地验收环境 · 测试' + ((identity?.role || role) === 'LAWYER' ? '律师' : '法律助理') + '身份 · 内存数据重启丢失 · 外部模型与文件上传未启用' + selector + '</div>'
      content = Buffer.from(content.toString('utf8').replace('<body>', '<body>' + banner))
    }
    response.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream')
    response.end(request.method === 'HEAD' ? undefined : content)
  } catch { response.writeHead(400); response.end('Invalid request') }
})
server.on('error', error => { console.error(error.message); stop(1) })
await new Promise(resolve => server.listen(port, '127.0.0.1', resolve))
await fs.writeFile(path.join(root, 'data', 'acceptance', 'current-session.json'), JSON.stringify({
  session, role, origin, backendPort, proxyPid: process.pid, backendPid: backend.pid, runtime, startedAt: new Date().toISOString()
}, null, 2))
console.log(`Local acceptance ready: ${origin}/matters`)
console.log(`Settings: ${origin}/settings`)
console.log(`Role: ${role}; loopback only; data is in memory; Ctrl+C stops this session.`)
console.log(`Session log: ${path.join(runtime, 'backend.log')}`)
