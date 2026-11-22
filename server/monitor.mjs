#!/usr/bin/env node
/**
 * 终端里的「运行状态」窗口。
 *
 * 干嘛的：
 *   - 一直显示她在干什么（当前任务 / 第几步 / 正在跑什么命令 / 跑了多久）
 *   - **关掉这个窗口 = 立刻停掉正在跑的任务，并退出服务**
 *
 * 用法：双击 监控.cmd（它会先确保服务在跑，再挂上这个窗口）
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const cfg = JSON.parse(readFileSync(join(ROOT, '配置.json'), 'utf8'))
const BASE = 'http://127.0.0.1:' + (cfg.port || 19401)
const H = { authorization: 'Bearer ' + cfg.token }

const C = {
  reset: '\u001b[0m', dim: '\u001b[2m', bold: '\u001b[1m',
  blue: '\u001b[38;5;75m', green: '\u001b[38;5;114m',
  yellow: '\u001b[38;5;222m', red: '\u001b[38;5;203m', gray: '\u001b[38;5;245m',
}
const clear = () => process.stdout.write('\u001b[2J\u001b[H')
const t = () => new Date().toTimeString().slice(0, 8)

function bar(ms) {
  const s = Math.floor(ms / 1000)
  return s < 60 ? s + 's' : Math.floor(s / 60) + 'm' + (s % 60) + 's'
}

let lastSeq = 0
const log = []

async function shutdown(reason) {
  process.stdout.write('\n' + C.yellow + '正在停止…（杀掉正在跑的任务）' + C.reset + '\n')
  try {
    await fetch(BASE + '/stop', { method: 'POST', headers: H })
    await fetch(BASE + '/shutdown', { method: 'POST', headers: H })
  } catch {}
  process.stdout.write(C.green + '已停止。' + C.reset + '\n')
  process.exit(0)
}

// 关窗口 / Ctrl+C / 点 X —— 都要真的停掉
process.on('SIGINT', () => shutdown('SIGINT'))
process.on('SIGBREAK', () => shutdown('SIGBREAK'))
process.on('SIGHUP', () => shutdown('SIGHUP'))
process.on('exit', () => {})

async function tick() {
  let state = null
  let items = []
  try {
    state = await (await fetch(BASE + '/state', { headers: H })).json()
    const box = await (await fetch(BASE + '/inbox?since=' + lastSeq, { headers: H })).json()
    if (box.seq > lastSeq) lastSeq = box.seq
    items = box.items || []
  } catch (e) {
    clear()
    console.log(C.red + '连不上服务' + C.reset + C.gray + '（' + BASE + '）' + C.reset)
    console.log(C.gray + '服务可能已经关了；按 Ctrl+C 退出。' + C.reset)
    return
  }

  for (const it of items) {
    const label = it.type === 'question' ? '问' : it.type === 'step' ? '步' : '说'
    log.push(C.blue + t() + ' ' + label + C.reset + ' ' + String(it.reply || it.question || '').slice(0, 80))
  }
  if (log.length > 8) log.splice(0, log.length - 8)

  clear()
  const r = state.running
  console.log(C.bold + C.blue + '  小鱼 · 运行状态' + C.reset + C.gray + '       关掉这个窗口 = 停止一切' + C.reset)
  console.log(C.gray + '  ' + BASE + C.reset)
  console.log('')

  if (r) {
    console.log(C.yellow + '  ▶ 正在干活' + C.reset + C.gray + '  ' + bar(r.ms) + C.reset)
    console.log('    任务：' + C.bold + String(r.text || '').slice(0, 60) + C.reset)
    console.log('    当前：' + C.green + (r.step || '正在想…') + C.reset + (r.detail ? C.gray + '  ' + r.detail + C.reset : ''))
  } else {
    console.log(C.green + '  ● 空闲中' + C.reset + C.gray + '   等着你说话' + C.reset)
  }
  console.log(C.gray + '    队列：' + (state.queued || 0) + ' 条待跑' + C.reset)
  console.log('')
  console.log(C.gray + '  ─── 最近发生 ───' + C.reset)
  if (log.length === 0) console.log(C.gray + '    （还没有动静）' + C.reset)
  else console.log(log.slice(-6).map((l) => '    ' + l).join('\n'))
  console.log('')
  console.log(C.gray + '  Ctrl+C 或关窗口都能让它停下来' + C.reset)
}

;(async () => {
  clear()
  console.log(C.gray + '  正在连接服务…' + C.reset)
  for (;;) {
    await tick()
    await new Promise((r) => setTimeout(r, 1000))
  }
})()