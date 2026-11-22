/**
 * 记忆存储：一天一个文件夹，形如 记忆/对话/2026/11/22/对话.md
 *
 * 为什么这么存：
 *   - 一天一个文件，找「某天聊了什么」是 O(1)，不用扫一个巨大的日志
 *   - 年份/月份做目录，翻很老的记录时也只列一层，几千天也不慢
 *   - 只追加，不重写，硬盘有多大就能存多少
 */
import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { PATHS } from './config.mjs'

export const DIALOG_ROOT = join(PATHS.memory, '..', '对话')

const pad2 = (n) => String(n).padStart(2, '0')

/** 某一天对应的目录：记忆/对话/2026/11/22 */
export function dayDir(date = new Date()) {
  return join(DIALOG_ROOT, String(date.getFullYear()), pad2(date.getMonth() + 1), pad2(date.getDate()))
}

export function dayFile(date = new Date()) {
  return join(dayDir(date), '对话.md')
}

/** 把 yyyy-mm-dd 转成目录路径 */
export function dirOfDay(day) {
  const [y, m, d] = String(day).split('-')
  return join(DIALOG_ROOT, y, m, d)
}

/** 追加一条对话。who = 'ta' 或 '小鱼'。 */
export function appendTurn(who, text, at = new Date()) {
  try {
    const dir = dayDir(at)
    mkdirSync(dir, { recursive: true })
    const file = join(dir, '对话.md')
    const stamp = `${pad2(at.getHours())}:${pad2(at.getMinutes())}`
    const head = existsSync(file) ? '' : `# ${at.getFullYear()}-${pad2(at.getMonth() + 1)}-${pad2(at.getDate())} 对话\n\n`
    const body = String(text).replace(/\r\n/g, '\n').trim()
    // 多行内容缩进两格，保持「一条一行」好扫
    const lines = body
      .split('\n')
      .map((l, i) => (i === 0 ? l : '  ' + l))
      .join('\n')
    appendFileSync(file, `${head}- ${stamp} ${who}：${lines}\n`, 'utf8')
    return true
  } catch (e) {
    return false
  }
}

/** 列出所有有记录的日期（新的在前）。 */
export function listDays() {
  const out = []
  if (!existsSync(DIALOG_ROOT)) return out
  for (const y of readdirSync(DIALOG_ROOT)) {
    const yp = join(DIALOG_ROOT, y)
    if (!safeIsDir(yp)) continue
    for (const m of readdirSync(yp)) {
      const mp = join(yp, m)
      if (!safeIsDir(mp)) continue
      for (const d of readdirSync(mp)) {
        const dp = join(mp, d)
        if (!safeIsDir(dp)) continue
        const f = join(dp, '对话.md')
        if (!existsSync(f)) continue
        out.push({ day: `${y}-${m}-${d}`, path: f, size: statSync(f).size })
      }
    }
  }
  return out.sort((a, b) => (a.day < b.day ? 1 : -1))
}

/** 距离今天多少天（今天=0） */
export function daysAgo(day) {
  const [y, m, d] = day.split('-').map(Number)
  const then = new Date(y, m - 1, d)
  const now = new Date()
  const t0 = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.round((t0 - then) / 86_400_000)
}

/** 某天文件里的「正文条目」数（以 - HH:MM 开头的行） */
export function countTurns(path) {
  try {
    return readFileSync(path, 'utf8').split('\n').filter((l) => /^- \d{2}:\d{2} /.test(l)).length
  } catch {
    return 0
  }
}

/** 读某天全文 */
export function readDay(day) {
  const f = join(dirOfDay(day), '对话.md')
  if (!existsSync(f)) return null
  return readFileSync(f, 'utf8')
}

function safeIsDir(p) {
  try {
    return statSync(p).isDirectory()
  } catch {
    return false
  }
}
