#!/usr/bin/env node
/**
 * Cloudflare 隧道封装：
 *   - 起 cloudflared quick tunnel
 *   - 从输出里抓 https://xxx.trycloudflare.com
 *   - 写进 穿透地址.txt 并在窗口里醒目打印
 *   - 断了自动重连（地址会变，所以每次重连都重新打印）
 */
import { spawn } from 'node:child_process'
import { writeFileSync, readFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..')
const EXE = join(HERE, 'bin', 'cloudflared.exe')
const PORT = (() => {
  try {
    return JSON.parse(readFileSync(join(ROOT, '配置.json'), 'utf8')).port || 19401
  } catch {
    return 19401
  }
})()

if (!existsSync(EXE)) {
  console.error('找不到 server/bin/cloudflared.exe，先下载它')
  process.exit(1)
}

const stamp = () => new Date().toTimeString().slice(0, 8)

/** 从公网验证这个地址真的能用（带口令打 /ping） */
async function verify(url) {
  try {
    const cfg = JSON.parse(readFileSync(join(ROOT, '配置.json'), 'utf8'))
    for (let i = 0; i < 6; i++) {
      await new Promise((r) => setTimeout(r, 2500))
      try {
        const res = await fetch(url + '/ping', {
          headers: { authorization: 'Bearer ' + cfg.token },
          signal: AbortSignal.timeout(15000),
        })
        if (res.ok) return true
      } catch {
        /* 再等一轮 */
      }
    }
  } catch {}
  return false
}
function run() {
  console.log('[' + stamp() + '] 启动隧道 → http://127.0.0.1:' + PORT)
  // 这台机器 IPv6 不通（edge 连 2606:... 会 control stream timeout），
  // 强制 IPv4 + HTTP2，绕开 UDP/QUIC 和 IPv6。
  const child = spawn(
    EXE,
    [
      'tunnel',
      '--url',
      'http://127.0.0.1:' + PORT,
      '--no-autoupdate',
      '--edge-ip-version',
      '4',
      '--protocol',
      'http2',
    ],
    {
      windowsHide: true,
    }
  )

  let buf = ''
  let announced = false

  const onData = (d) => {
    const text = d.toString('utf8')
    buf += text
    if (!announced) {
      const m = buf.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/i)
      if (m) {
        const url = m[0]
        // 先自检：真的能从公网访问到 /ping 才算数
        verify(url).then((ok) => {
          if (!ok) {
            console.log('[' + stamp() + '] 地址拿到了但连不通，重开隧道…')
            try { child.kill() } catch {}
            return
          }
          announced = true
          const info = [
          '公网地址：' + url,
          '手机 App 里把「服务地址」改成上面这个（口令不变）',
            '启动时间：' + new Date().toLocaleString(),
          ].join('\n')
          writeFileSync(join(ROOT, '穿透地址.txt'), info + '\n', 'utf8')
          console.log('')
          console.log('  ┌──────────────────────────────────────────────')
          console.log('  │  公网地址：' + url)
          console.log('  │  把这个填进手机 App 的「服务地址」')
          console.log('  │  口令不变；这个地址关掉窗口就失效')
          console.log('  └──────────────────────────────────────────────')
          console.log('')
        })
      }
    }
    // 只打印关键行，避免刷屏
    for (const line of text.split('\n')) {
      if (/ERR|failed|error/i.test(line)) console.log('[' + stamp() + '] ' + line.trim())
    }
  }

  child.stdout.on('data', onData)
  child.stderr.on('data', onData)

  child.on('exit', (code) => {
    console.log('[' + stamp() + '] 隧道断开（exit ' + code + '），5 秒后重连…')
    setTimeout(run, 5000)
  })
}

run()
process.stdin.resume()