/**
 * 小鱼 · 手机服务（便携版桥接）
 *
 *   手机 App ──HTTP──> 本服务 ──headless──> DSH agent（本机模型 + 本包记忆）
 *
 * 顺带把「设置页」也由它托管：浏览器打开 http://127.0.0.1:<端口>/ 就能改 API Key、
 * 换模型、看余额、测连通，不用装别的东西。
 *
 * 接口：
 *   GET  /                     设置页（仅本机）
 *   GET  /api/status           运行状态（本机）
 *   GET  /api/config           读配置（Key 打码，本机）
 *   POST /api/config           改配置（本机）
 *   GET  /api/balance          查 DeepSeek 余额（本机）
 *   POST /api/test             跑一句话验证配置（本机）
 *   GET  /ping                 探活（App，需口令）
 *   POST /chat                 { sessionId?, text } → { reply, sessionId, parts }（App，需口令）
 *   POST /upload               { name, data(base64) } → { path }（App，需口令）
 *   GET  /media?f=<文件名>     表情包预览（本机）
 */
import { spawn } from 'node:child_process'
import { appendFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { createHmac, timingSafeEqual } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { createServer } from 'node:http'
import { basename, extname, join } from 'node:path'
import { PATHS, loadConfig, saveConfig } from './config.mjs'
import { appendTurn, listDays, countTurns } from './memory.mjs'

const MAX_TEXT = 8000
const busy = new Set()

function log(...args) {
  const t = new Date().toTimeString().slice(0, 8)
  console.log('[' + t + ']', ...args)
}

/* ────────────────────────── 共享会话日志 ────────────────────────── */

/** 一轮对话写进「记忆/对话/YYYY/MM/DD/对话.md」—— 一天一个文件，硬盘多大存多少。 */
function appendSharedLog(who, text) {
  const ok = appendTurn(who, text)
  if (!ok) log('! 写对话记录失败')
}
/* ────────────────────────── 附件标记 ────────────────────────── */

let stickerCache = null
function stickers() {
  if (stickerCache) return stickerCache
  try {
    stickerCache = JSON.parse(readFileSync(PATHS.stickerIndex, 'utf8'))
  } catch {
    stickerCache = []
  }
  return stickerCache
}

const norm = (s) => String(s).replace(/[\s，,。、！!？?….]/g, '')

/* ────────────────────────── 文件下载签名 ──────────────────────────
 * 手机要拿到电脑上的文件，又不能把任意路径暴露出去：
 * 只对「她这次真的发出来的那个路径」签发一个带过期时间和签名的链接。
 * 签名密钥就是 App 用的口令，所以只有拿得到口令的人能下。
 */
const FILE_TTL_MS = 30 * 60 * 1000

function signFilePath(absPath, cfg) {
  const exp = Date.now() + FILE_TTL_MS
  const payload = absPath + '|' + exp
  const sig = createHmac('sha256', cfg.token || 'x').update(payload).digest('hex').slice(0, 32)
  const p = Buffer.from(absPath, 'utf8').toString('base64url')
  return '/file?p=' + p + '&e=' + exp + '&s=' + sig
}

function verifyFileSig(p, e, s, cfg) {
  const abs = Buffer.from(String(p || ''), 'base64url').toString('utf8')
  const exp = Number(e)
  if (!abs || !exp || !s) return null
  if (Date.now() > exp) return null
  const want = createHmac('sha256', cfg.token || 'x').update(abs + '|' + exp).digest('hex').slice(0, 32)
  try {
    if (!timingSafeEqual(Buffer.from(want), Buffer.from(String(s)))) return null
  } catch {
    return null
  }
  if (!existsSync(abs)) return null
  return abs
}
function resolvePart(kind, value, cfg) {
  try {
    if (kind === 'sticker') {
      const want = norm(value)
      const list = stickers()
      const hit =
        list.find((f) => norm(f.summary) === want) ||
        list.find((f) => norm(f.summary).includes(want) || want.includes(norm(f.summary)))
      if (!hit) return null
      const p = join(PATHS.stickers, hit.file)
      if (!existsSync(p)) return null
      const buf = readFileSync(p)
      if (buf.length > 3 * 1024 * 1024) return null
      return { kind: 'sticker', name: hit.summary, b64: buf.toString('base64') }
    }
    const p = value.replace(/^["'`]|["'`]$/g, '')
    if (!existsSync(p)) {
      log('! 标记里的路径不存在', p)
      return null
    }
    const buf = readFileSync(p)
    const name = basename(p)
    if (kind === 'file' || buf.length > 3 * 1024 * 1024) {
      // 文件（或超大图）：不发内容，发一个带签名的下载链接，App 点一下就能存到手机
      const st = statSync(p)
      return {
        kind: 'file',
        name,
        size: st.size,
        url: signFilePath(p, cfg),
      }
    }
    return { kind: 'image', name, b64: buf.toString('base64') }
  } catch (e) {
    log('! 解析附件失败', String(e.message))
    return null
  }
}

function splitParts(text, cfg) {
  const parts = []
  let rest = String(text ?? '')
  const patterns = [
    { re: /\[\[表情[:：]([^\]]{1,80})\]\]/g, kind: 'sticker' },
    { re: /\[\[图片[:：]([^\]]{1,400})\]\]/g, kind: 'image' },
    { re: /\[\[文件[:：]([^\]]{1,400})\]\]/g, kind: 'file' },
  ]
  for (const { re, kind } of patterns) {
    rest = rest.replace(re, (_m, arg) => {
      const part = resolvePart(kind, String(arg).trim(), cfg)
      if (part) parts.push(part)
      return '\n'
    })
  }
  // 去重：同一张表情被写了两遍只发一次
  const seen = new Set()
  const unique = []
  for (const p of parts) {
    const k = p.kind + '|' + (p.name || '')
    if (seen.has(k)) continue
    seen.add(k)
    unique.push(p)
  }
  return { text: rest.replace(/\n{3,}/g, '\n\n').trim(), parts: unique }
}

/* ────────────────────────── 跑一次 headless ────────────────────────── */

function runOnce(cfg, sessionId, text) {
  return new Promise((resolve, reject) => {
    const args = ['--profile', cfg.profileName || 'phone', '--json']
    if (sessionId) args.push('--session-id', sessionId)
    args.push(text)

    const env = {
      ...process.env,
      ELECTRON_RUN_AS_NODE: '1',
      DSH_PERMISSION_MODE: cfg.permissionMode || 'danger-full-access',
    }
    if (cfg.apiKey) env.DEEPSEEK_API_KEY = cfg.apiKey
    if (cfg.provider === 'ollama') env.OLLAMA_API_KEY = 'ollama-local'

    const child = spawn(cfg.dshExe, ['--expose-internals', cfg.dshEntry, ...args], {
      cwd: PATHS.root,
      env,
      windowsHide: true,
    })

    let buf = ''
    let err = ''
    let sid = sessionId || ''
    let final = ''
    let streamed = ''

    const timer = setTimeout(() => {
      try {
        child.kill()
      } catch {}
      reject(new Error('超时（' + Math.round((cfg.timeoutMs || 300000) / 1000) + ' 秒）'))
    }, cfg.timeoutMs || 300000)

    const eat = (chunk) => {
      buf += chunk
      let nl
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl).trim()
        buf = buf.slice(nl + 1)
        if (!line.startsWith('{')) continue
        let ev
        try {
          ev = JSON.parse(line)
        } catch {
          continue
        }
        if (ev.type === 'session' && ev.sessionId) sid = String(ev.sessionId)
        else if (ev.type === 'final' && typeof ev.text === 'string') final = ev.text
        else if (ev.type === 'text' && typeof ev.text === 'string') streamed += ev.text
      }
    }

    child.stdout.on('data', (d) => eat(d.toString('utf8')))
    child.stderr.on('data', (d) => {
      err += d.toString('utf8')
    })
    child.on('error', (e) => {
      clearTimeout(timer)
      reject(e)
    })
    child.on('close', () => {
      clearTimeout(timer)
      eat('\n')
      const reply = String(final || streamed || '').trim()
      if (reply) return resolve({ reply, sessionId: sid })
      reject(new Error(err.trim().split('\n').slice(-6).join('\n') || 'dsh 没有输出'))
    })
  })
}

async function runChat(cfg, sessionId, text) {
  try {
    return await runOnce(cfg, sessionId, text)
  } catch (e) {
    // 会话不存在 → 新建；
    // 会话是别的目录下建的（换了 cwd / 换了电脑）→ 也新建，否则 DSH 会拒绝续用
    if (sessionId && /does not exist|was recorded in/i.test(String(e.message))) {
      log('· 会话不可续用，新建一个')
      return runOnce(cfg, '', text)
    }
    throw e
  }
}

/* ────────────────────────── HTTP ────────────────────────── */

function json(res, code, obj) {
  const body = JSON.stringify(obj)
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'access-control-allow-origin': '*',
    'access-control-allow-headers': 'authorization, content-type',
  })
  res.end(body)
}

function readBody(req, limit = 64 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (c) => {
      size += c.length
      if (size > limit) {
        reject(new Error('太大了'))
        req.destroy()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

/**
 * 只有「真的从本机浏览器直连」才算本机。
 *
 * 坑：走隧道（cloudflared / ngrok / frp）时，连接是从本机发出的，
 * remoteAddress 就是 127.0.0.1 —— 单看地址会把设置页和配置接口一起暴露到公网。
 * 所以只要带了转发头（这些隧道都会加），一律不当本机。
 */
const isLocal = (req) => {
  const a = req.socket.remoteAddress || ''
  const loop = a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1'
  const proxied = Boolean(
    req.headers['cf-connecting-ip'] ||
      req.headers['cf-ray'] ||
      req.headers['x-forwarded-for'] ||
      req.headers['x-real-ip'] ||
      req.headers['forwarded']
  )
  return loop && !proxied
}

function serveFile(res, path, type) {
  try {
    const st = statSync(path)
    const buf = readFileSync(path)
    res.writeHead(200, { 'content-type': type, 'content-length': st.size })
    res.end(buf)
    return true
  } catch {
    return false
  }
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.json': 'application/json; charset=utf-8',
  '.md': 'text/plain; charset=utf-8',
}

/* ────────────────────────── 本地模型：自动拉起 Ollama ────────────────────────── */

import { spawn as spawnProc } from 'node:child_process'
import { createConnection } from 'node:net'

/** 端口有没人听 */
function portOpen(port, host = '127.0.0.1') {
  return new Promise((resolve) => {
    const sock = createConnection({ port, host })
    const done = (v) => {
      try {
        sock.destroy()
      } catch {}
      resolve(v)
    }
    sock.setTimeout(1200)
    sock.on('connect', () => done(true))
    sock.on('timeout', () => done(false))
    sock.on('error', () => done(false))
  })
}

/** provider=ollama 时，确保本机的 Ollama 服务在跑；不在就拉起来 */
async function ensureOllama(cfg) {
  if (cfg.provider !== 'ollama') return
  if (await portOpen(11434)) {
    log('· Ollama 已在运行')
    return
  }
  log('· Ollama 没在跑，尝试拉起…')
  const home = process.env.LOCALAPPDATA || ''
  const candidates = [
    home ? join(home, 'Programs', 'Ollama', 'ollama app.exe') : '',
    'C:\\Program Files\\Ollama\\ollama app.exe',
  ].filter(Boolean)
  for (const exe of candidates) {
    if (!existsSync(exe)) continue
    try {
      const env = { ...process.env }
      if (cfg.ollamaModels) env.OLLAMA_MODELS = cfg.ollamaModels
      spawnProc(exe, [], { detached: true, stdio: 'ignore', windowsHide: true, env }).unref()
    } catch (e) {
      log('! 拉起 Ollama 失败：' + e.message)
    }
    for (let i = 0; i < 20; i++) {
      await new Promise((r) => setTimeout(r, 1000))
      if (await portOpen(11434)) {
        log('· Ollama 起来了')
        return
      }
    }
  }
  log('! 没找到 Ollama，本地模式可能连不上（去 https://ollama.com 装一个）')
}
const server = createServer(async (req, res) => {
  if (req.method === 'OPTIONS') return json(res, 204, {})

  const cfg = loadConfig()
  const url = new URL(req.url, 'http://x')
  const path = decodeURIComponent(url.pathname)
  const authed = (req.headers.authorization || '') === 'Bearer ' + cfg.token
  const local = isLocal(req)

  /* ---- 设置页与静态资源（只给本机） ---- */
  if (path === '/' || path === '/index.html') {
    if (!local) return json(res, 403, { error: '设置页只允许本机打开' })
    if (serveFile(res, PATHS.settingsHtml, MIME['.html'])) return
    return json(res, 404, { error: '设置.html 不见了' })
  }

  if (path === '/file') {
    const abs = verifyFileSig(url.searchParams.get('p'), url.searchParams.get('e'), url.searchParams.get('s'), cfg)
    if (!abs) return json(res, 403, { error: '链接无效或已过期' })
    try {
      const st = statSync(abs)
      const name = basename(abs)
      res.writeHead(200, {
        'content-type': 'application/octet-stream',
        'content-length': st.size,
        // 文件名用 RFC5987 编码，中文名也不会乱码
        'content-disposition': "attachment; filename*=UTF-8''" + encodeURIComponent(name),
      })
      createReadStream(abs).pipe(res)
      log('↓ 手机在下载', name, (st.size / 1024).toFixed(0) + 'KB')
    } catch (e) {
      json(res, 500, { error: String(e.message) })
    }
    return
  }
  if (path === '/media') {
    const f = url.searchParams.get('f') || ''
    if (!f || f.includes('..') || f.includes('/') || f.includes('\\')) return json(res, 400, { error: 'bad f' })
    const p = join(PATHS.stickers, f)
    if (serveFile(res, p, MIME[extname(p).toLowerCase()] || 'application/octet-stream')) return
    return json(res, 404, { error: '没有这张图' })
  }

  /* ---- 本机配置接口 ---- */
  if (path.startsWith('/api/') && !local) {
    return json(res, 403, { error: '配置接口只允许本机访问' })
  }

  if (path === '/api/status') {
    return json(res, 200, {
      ok: true,
      port: cfg.port,
      profile: cfg.profileName,
      model: cfg.model,
      provider: cfg.provider,
      mode: cfg.mode || 'agent',
      dshExe: cfg.dshExe,
      dshOk: !!cfg.dshExe && existsSync(cfg.dshExe),
      keySet: !!cfg.apiKey,
      stickers: stickers().length,
      chatLogs: countChatLogs(),
      dialogDays: listDays().length,
      dialogTurns: listDays().reduce((n, d) => n + countTurns(d.path), 0),
      memory: PATHS.memory,
      busy: busy.size,
    })
  }

  if (path === '/api/config' && req.method === 'GET') {
    const masked = { ...cfg }
    if (masked.apiKey) masked.apiKey = masked.apiKey.slice(0, 6) + '…' + masked.apiKey.slice(-4)
    return json(res, 200, { config: masked, paths: PATHS })
  }

  if (path === '/api/config' && req.method === 'POST') {
    let body
    try {
      body = JSON.parse((await readBody(req)) || '{}')
    } catch {
      return json(res, 400, { error: 'body 不是合法 JSON' })
    }
    const allow = ['port', 'token', 'mode', 'provider', 'model', 'apiKey', 'apiBase', 'permissionMode', 'profileName', 'dshExe', 'dshEntry', 'localBaseUrl', 'localContext', 'ollamaModels']
    const patch = {}
    for (const k of allow) if (body[k] !== undefined) patch[k] = body[k]
    // 前端把打码后的 key 原样传回来时不要覆盖真 key
    if (patch.apiKey && patch.apiKey.includes('…')) delete patch.apiKey
    if (patch.port) patch.port = Number(patch.port)
    const next = saveConfig(patch)
    log('· 配置已更新（模型 ' + next.model + '）')
    return json(res, 200, { ok: true, note: '端口/口令改动需要重启服务生效' })
  }

  if (path === '/api/test' && req.method === 'POST') {
    try {
      const r = await runChat(cfg, '', '用一句话说：连接正常。')
      return json(res, 200, { ok: true, reply: r.reply.slice(0, 200), sessionId: r.sessionId })
    } catch (e) {
      return json(res, 200, { ok: false, error: String(e.message).slice(0, 500) })
    }
  }

  if (path === '/api/balance') {
    if (!cfg.apiKey) return json(res, 200, { ok: false, error: '没填 API Key，查不了余额' })
    try {
      const r = await fetch(cfg.apiBase.replace(/\/+$/, '') + '/user/balance', {
        headers: { authorization: 'Bearer ' + cfg.apiKey },
      })
      const text = await r.text()
      let data
      try {
        data = JSON.parse(text)
      } catch {
        data = { raw: text.slice(0, 300) }
      }
      return json(res, 200, { ok: r.ok, status: r.status, data })
    } catch (e) {
      return json(res, 200, { ok: false, error: String(e.message) })
    }
  }

  /* ---- App 接口 ---- */
  if (path === '/ping') {
    if (!authed) return json(res, 401, { error: '口令不对' })
    return json(res, 200, { ok: true, busy: busy.size, profile: cfg.profileName, model: cfg.model })
  }

  if (path === '/upload' && req.method === 'POST') {
    if (!authed) return json(res, 401, { error: '口令不对' })
    let body
    try {
      body = JSON.parse((await readBody(req)) || '{}')
    } catch {
      return json(res, 400, { error: 'body 不是合法 JSON' })
    }
    const name = String(body.name || 'file').replace(/[\\/:*?"<>|]/g, '_').slice(0, 120)
    const b64 = String(body.data || '')
    if (!b64) return json(res, 400, { error: 'data 是空的' })
    try {
      mkdirSync(PATHS.uploads, { recursive: true })
      const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
      const ext = extname(name)
      const target = join(PATHS.uploads, stamp + '-' + (name.slice(0, name.length - ext.length) || 'file') + ext)
      writeFileSync(target, Buffer.from(b64, 'base64'))
      log('↑ 收到', name)
      return json(res, 200, { path: target, name })
    } catch (e) {
      return json(res, 500, { error: String(e.message) })
    }
  }

  if (path === '/chat' && req.method === 'POST') {
    if (!authed) return json(res, 401, { error: '口令不对' })
    let body
    try {
      body = JSON.parse((await readBody(req)) || '{}')
    } catch {
      return json(res, 400, { error: 'body 不是合法 JSON' })
    }
    const text = String(body.text || '').trim()
    const asked = String(body.sessionId || '').trim()
    if (!text) return json(res, 400, { error: 'text 是空的' })
    if (text.length > MAX_TEXT) return json(res, 400, { error: '太长了' })
    if (!cfg.dshExe || !existsSync(cfg.dshExe)) {
      return json(res, 500, { error: '找不到 DSH，请在设置页里检查安装路径' })
    }

    const key = asked || 'new'
    if (busy.has(key)) return json(res, 409, { error: '上一句还在跑' })
    busy.add(key)
    const t0 = Date.now()
    appendSharedLog('ta', text)
    try {
      let out = await runChat(cfg, asked, text)
      let split = splitParts(out.reply, cfg)
      // 正文和附件都空 = 她只写了个解析不了的标记，重来一次（只重试一次）
      if (!split.text && split.parts.length === 0) {
        log('· 回复是空的（标记没解析出来），重试一次')
        out = await runChat(cfg, out.sessionId, text)
        split = splitParts(out.reply, cfg)
      }
      const finalText = split.text || (split.parts.length ? '' : '嗯……我在呢。')
      appendSharedLog('小鱼', finalText || '[表情]')
      log('←', out.sessionId, Math.round((Date.now() - t0) / 1000) + 's', (finalText || '[表情]').slice(0, 50))
      return json(res, 200, { reply: finalText, sessionId: out.sessionId, parts: split.parts })
    } catch (e) {
      log('!', String(e.message).slice(0, 150))
      return json(res, 502, { error: String(e.message).slice(0, 600) })
    } finally {
      busy.delete(key)
    }
  }

  json(res, 404, { error: '没有这个接口' })
})

function countChatLogs() {
  try {
    return readdirSync(PATHS.chatLog).length
  } catch {
    return 0
  }
}
const startCfg = loadConfig()
ensureOllama(startCfg).finally(() => {
server.listen(startCfg.port, '0.0.0.0', () => {
  log('小鱼手机服务已启动  http://0.0.0.0:' + startCfg.port)
  log('设置页（本机打开）：http://127.0.0.1:' + startCfg.port + '/')
  log('模型：' + startCfg.provider + ' / ' + startCfg.model)
  log('记忆：' + PATHS.chatLog)
  log('DSH：' + (startCfg.dshExe || '（未找到，去设置页里填）'))
})
})
