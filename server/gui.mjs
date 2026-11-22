#!/usr/bin/env node
/**
 * 图形界面操作（批量版）
 *
 * 单步（跟以前一样）：
 *   node server/gui.mjs shot
 *   node server/gui.mjs ensure cloudmusic "C:\...\cloudmusic.exe"
 *
 * 批量（**推荐**，一次 PowerShell 跑完十几步，快得多）：
 *   node server/gui.mjs seq "ensure|cloudmusic|C:\Program Files\Netease\CloudMusic\cloudmusic.exe;;focus|cloudmusic;;shot;;clickat|800|600;;type|die for you|cloudmusic;;keys|{ENTER}|cloudmusic"
 *
 * 步骤之间用 ;; 分隔，参数之间用 | 分隔。
 */
import { spawnSync } from 'node:child_process'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const PS1 = join(HERE, 'gui.ps1')
const args = process.argv.slice(2)

function runPs(psArgs) {
  const res = spawnSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', PS1, ...psArgs], {
    encoding: 'utf8',
    windowsHide: true,
    timeout: 180_000,
  })
  const out = (res.stdout || '').trim()
  const err = (res.stderr || '').trim()
  if (out) console.log(out)
  if (err && !out) console.error(err)
  return res.status || 0
}

if (args.length === 0) {
  console.log('用法: node server/gui.mjs <命令> [参数…]   或   node server/gui.mjs seq "步骤1;;步骤2;;…"')
  process.exit(0)
}

if (args[0] === 'seq') {
  const spec = args[1] || ''
  const steps = spec
    .split(';;')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const p = s.split('|').map((x) => x.trim())
      return { cmd: p[0], a1: p[1] || '', a2: p[2] || '', a3: p[3] || '' }
    })
  if (!steps.length) {
    console.error('没有解析出任何步骤')
    process.exit(1)
  }
  console.log('批量执行 ' + steps.length + ' 步（一次 PowerShell）')
  const b64 = Buffer.from(JSON.stringify(steps), 'utf8').toString('base64')
  process.exit(runPs(['seq', b64]))
}

const cmd = args[0]
const rest = args.slice(1)
if (cmd === 'shot' && !rest[0]) rest[0] = join(HERE, '..', '记忆', '产出', 'screen-' + Date.now() + '.png')
if (cmd === 'shot' && rest[0]) rest[0] = resolve(rest[0])
process.exit(runPs([cmd, ...rest.map(String).slice(0, 3)]))