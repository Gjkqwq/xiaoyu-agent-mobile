import { existsSync, readdirSync, readFileSync } from "node:fs"
import { join } from "node:path"
import { appendTurn, listDays } from "./memory.mjs"
import { PATHS } from "./config.mjs"

const src = PATHS.chatLog
if (!existsSync(src)) { console.log("没有旧聊天记录"); process.exit(0) }
let moved = 0, skipped = 0
for (const name of readdirSync(src)) {
  if (!name.endsWith(".md")) continue
  const m = name.match(/(\d{4}-\d{2}-\d{2})\.md$/)
  if (!m) { skipped++; continue }
  const day = m[1]
  const text = readFileSync(join(src, name), "utf8")
  for (const line of text.split("\n")) {
    const t = line.match(/^- (\d{2}:\d{2})\s+(.+?)：(.*)$/)
    if (!t) continue
    const [, hhmm, whoRaw, bodyRaw] = t
    const who = /ta|手机|姐姐|用户/i.test(whoRaw) ? "ta" : "小鱼"
    const body = bodyRaw.replace(/ ⏎ /g, "\n")
    const [h, mi] = hhmm.split(":").map(Number)
    const [y, mo, d] = day.split("-").map(Number)
    appendTurn(who, body, new Date(y, mo - 1, d, h, mi))
    moved++
  }
}
console.log(`迁移完成：${moved} 条，跳过 ${skipped} 个文件`)
console.log(`现在共有 ${listDays().length} 天记录`)