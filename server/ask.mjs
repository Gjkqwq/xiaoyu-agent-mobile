#!/usr/bin/env node
/**
 * 任务中途问 ta 一句话，然后等他回答。
 *
 *   node server/ask.mjs "登录哪个 Steam 账号？" "账号A|账号B|先不登"
 *   node server/ask.mjs "这份 PPT 要几页？" ""            # 不给选项 = 要他自由回答
 *
 * 行为：
 *   1. 把问题推到手机（App 里出现一张卡片）
 *   2. 这个进程会一直挂着等（最长 5 分钟）
 *   3. ta 一回答，答案直接打印出来 → 你看到之后继续干活
 *
 * 什么时候用：**需要 ta 做决定的时刻**，比如多个账号选哪个、要删的东西问一句、
 * 拿不准 ta 想要哪种风格。别自己猜着干完再问。
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..')

let cfg
try {
  cfg = JSON.parse(readFileSync(join(ROOT, '配置.json'), 'utf8'))
} catch (e) {
  console.error('读不到 配置.json：' + e.message)
  process.exit(1)
}

const base = 'http://127.0.0.1:' + (cfg.port || 19401)
const headers = {
  authorization: 'Bearer ' + cfg.token,
  'content-type': 'application/json',
}

const question = String(process.argv[2] || '').trim()
const rawOptions = String(process.argv[3] || '').trim()
if (!question) {
  console.log('用法: node server/ask.mjs "问题" "选项1|选项2|选项3"')
  console.log('      （第二个参数留空 = 让 ta 自由回答）')
  process.exit(1)
}
const options = rawOptions ? rawOptions.split('|').map((s) => s.trim()).filter(Boolean) : []

async function main() {
  let id
  try {
    const r = await fetch(base + '/ask', {
      method: 'POST',
      headers,
      body: JSON.stringify({ question, options }),
    })
    const j = await r.json()
    if (!j.ok) throw new Error(j.error || '投递失败')
    id = j.id
  } catch (e) {
    console.error('把问题发到手机失败：' + e.message)
    console.error('（可以自己先做个稳妥的选择，或者告诉 ta 你卡在哪儿）')
    process.exit(2)
  }

  console.log('已把问题推到手机：' + question)
  if (options.length) console.log('选项：' + options.join(' / '))
  console.log('等他回答…（最多 5 分钟）')

  try {
    const r = await fetch(base + '/ask/wait?id=' + encodeURIComponent(id), {
      headers,
      signal: AbortSignal.timeout(310_000),
    })
    const j = await r.json()
    if (j.timeout) {
      console.log('---')
      console.log('（ta 5 分钟没回。别一直等 —— 先做不影响的部分，或者直接说你在等他。）')
      process.exit(3)
    }
    console.log('---')
    console.log('ta 的回答：' + (j.answer || '（空）'))
  } catch (e) {
    console.log('---')
    console.log('（等回答的时候断线了：' + e.message + '）')
    process.exit(4)
  }
}

main()
