#!/usr/bin/env node
/**
 * 内网穿透：把本机的 19401 端口暴露成一个公网 https 地址。
 *
 * 为什么用 SSH：Windows 10+ 自带 ssh.exe，不用下载任何东西。
 * 之前试过 cloudflared（60MB 二进制），GitHub 在国内下不动。
 *
 * 用法：node server/tunnel.mjs
 * 它会：
 *   1. 起一条 SSH 反向隧道（localhost.run，免注册）
 *   2. 把拿到的 https 地址打印出来，并写进 穿透地址.txt
 *   3. 一直挂着（Ctrl+C 结束）
 *
 * 注意：免费隧道每次启动地址都会变，手机 App 里要跟着改。
 */
import { spawn } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..')
const cfg = JSON.parse(readFileSync(join(ROOT, '配置.json'), 'utf8'))
const PORT = cfg.port || 19401
const OUT = join(ROOT, '穿透地址.txt')

const PROVIDERS = [
  {
    name: 'localhost.run',
    args: ['-o', 'StrictHostKeyChecking=no', '-o', 'ServerAliveInterval=20', '-R', `80:localhost:${PORT}`, 'nokey@localhost.run'],
    // 真正的隧道地址是随机子域 + lhr.life / localhost.run；要排掉欢迎语里的 admin.localhost.run
    re: /https:\/\/([a-z0-9][a-z0-9-]{3,})\.(?:lhr\.life|localhost\.run)(?![\w.-])/i,
  },
  {
    name: 'pinggy.io',
    args: ['-p', '443', '-o', 'StrictHostKeyChecking=no', '-o', 'ServerAliveInterval=20', '-R', `0:localhost:${PORT}`, 'a.pinggy.io'],
    re: /https:\/\/[a-z0-9-]+\.(?:pinggy\.link|pinggy\.io|a\.free\.pinggy\.link)/i,
  },
]

function log(...a) {
  console.log('[' + new Date().toTimeString().slice(0, 8) + ']', ...a)
}

function tryProvider(p) {
  return new Promise((resolve) => {
    log('尝试 ' + p.name + ' …')
    const child = spawn('ssh', p.args, { windowsHide: true })
    let found = null
    let buf = ''

    const onData = (d) => {
      buf += d.toString('utf8')
      // 去掉 ANSI 颜色/二维码转义，避免正则被干扰
      const clean = buf.replace(/\u001b\[[0-9;]*m/g, '').replace(/[\u2580-\u259f]/g, ' ')
      const m = clean.match(p.re)
      const sub = m ? m[1].toLowerCase() : ''
      const bad = !sub || sub === 'admin' || sub === 'www' || sub === 'localhost'
      if (m && !bad && !found) {
        found = m[0]
        resolve({ url: found, child, provider: p.name })
      }
    }
    child.stdout.on('data', onData)
    child.stderr.on('data', onData)
    child.on('exit', () => {
      if (!found) resolve(null)
    })
    setTimeout(() => {
      if (!found) {
        try {
          child.kill()
        } catch {}
        resolve(null)
      }
    }, 30_000)
  })
}

const banner = () => {
  console.log('')
  console.log('  ============================================')
  console.log('     小鱼 · 内网穿透')
  console.log('  ============================================')
  console.log('')
}

async function main() {
  banner()
  log('本机服务端口：' + PORT)
  if (existsSync(OUT)) {
    try {
      const old = readFileSync(OUT, 'utf8').trim()
      if (old) log('上次的地址（多半已失效）：' + old)
    } catch {}
  }

  for (const p of PROVIDERS) {
    const r = await tryProvider(p)
    if (!r) {
      log(p.name + ' 没成功，换下一个')
      continue
    }
    const line = [
      '公网地址：' + r.url,
      '手机 App 里把「服务地址」改成：' + r.url,
      '口令不用变（还是 配置.json 里的 token）',
      '这个地址只在本次运行有效，关掉窗口就失效。',
      '启动时间：' + new Date().toLocaleString(),
    ].join('\n')
    writeFileSync(OUT, r.url + '\n' + line + '\n', 'utf8')

    console.log('')
    console.log('  ┌─────────────────────────────────────────────')
    console.log('  │  公网地址：' + r.url)
    console.log('  │  手机 App 里把「服务地址」改成上面这个')
    console.log('  │  口令不变（配置.json 里的 token）')
    console.log('  │  关掉这个窗口 = 隧道断开')
    console.log('  └─────────────────────────────────────────────')
    console.log('')

    r.child.on('exit', (code) => {
      log('隧道断开了（exit ' + code + '），按回车退出或重新运行本脚本')
    })
    // 保持前台运行
    process.stdin.resume()
    return
  }

  log('所有隧道都失败了。可能原因：')
  log('  · 网络连不上 localhost.run / pinggy.io（需要能访问境外）')
  log('  · 或者 ssh 被防火墙拦了')
  process.exit(1)
}

main()