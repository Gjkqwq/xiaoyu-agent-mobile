#!/usr/bin/env node
/**
 * 软件适配层（她只调这个，不碰窗口）
 *
 *   node server/app.mjs list
 *   node server/app.mjs state edge            只看 Edge 在不在跑
 *   node server/app.mjs edge open https://bilibili.com
 *   node server/app.mjs edge search "the weeknd die for you"
 *   node server/app.mjs steam open
 *   node server/app.mjs steam play 730
 *
 * 为什么要有它：
 *   以前她直接 gui.mjs run/ensure/keys，会反复开窗口、还会按 ESC/Alt+F4 关掉。
 *   这里把「打开/聚焦」封死，**没有关闭命令**，她想关也关不了。
 */
import { spawnSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const PY = process.env.XIAOYU_PYTHON || 'C:\\Users\\YOGA\\.dsh\\dsh-runtimes\\dsh-primary-runtime\\dependencies\\python\\python.exe'
const SCRIPT = join(HERE, 'app.py')

const args = process.argv.slice(2)
if (args.length === 0) {
  console.log('用法: node server/app.mjs <软件> <动作> [参数]    例: edge search "周杰伦"')
  console.log('      node server/app.mjs list')
  process.exit(0)
}

const res = spawnSync(PY, [SCRIPT, ...args], { encoding: 'utf8', windowsHide: true, timeout: 60_000 })
const out = (res.stdout || '').trim()
const err = (res.stderr || '').trim()
if (out) console.log(out)
if (err) console.error(err.slice(0, 600))
process.exit(res.status === null ? 1 : res.status)