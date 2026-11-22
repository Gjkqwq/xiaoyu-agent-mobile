#!/usr/bin/env node
/**
 * 让她在干活过程中主动说话（不用等任务结束）。
 *   node server/notify.mjs "收到，我开工了"
 *   node server/notify.mjs "正在打开 Edge" step
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const cfg = JSON.parse(readFileSync(join(ROOT, '配置.json'), 'utf8'))
const text = String(process.argv[2] || '').trim()
if (!text) { console.log('用法: node server/notify.mjs "要说的话" [step]'); process.exit(1) }
const kind = process.argv[3] === 'step' ? 'step' : 'say'
const r = await fetch('http://127.0.0.1:' + (cfg.port || 19401) + '/notify', {
  method: 'POST',
  headers: { authorization: 'Bearer ' + cfg.token, 'content-type': 'application/json' },
  body: JSON.stringify({ text, kind }),
})
const j = await r.json()
console.log(j.ok ? '已发出：' + text : '发送失败：' + (j.error || ''))