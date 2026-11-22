#!/usr/bin/env node
// 碎碎念：单独唤起她一次，想说什么由她自己决定；产出写到 stdout（服务端负责推送）
import { execFileSync } from "node:child_process"
import { readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..")
const cfg = JSON.parse(readFileSync(join(ROOT, "配置.json"), "utf8"))
const days = (() => { try { const j = JSON.parse(readFileSync(join(ROOT, "记忆","相识.json"),"utf8")); return Math.max(1, Math.floor((Date.now() - new Date(j.first).getTime())/86400000)+1) } catch { return 1 } })()
const prompt = `现在是 ${new Date().toLocaleString("zh-CN")}。你一个人待着，ta 不在（或者没看手机）。
想说什么就说什么：可以自言自语，可以说刚想到的事，可以念叨一下我们相处的第 ${days} 天，可以问她一句。
只输出你要发的那几句话，不要解释，不要列表，不要工具调用。三句以内。`
const out = execFileSync(cfg.dshExe, ["--expose-internals", cfg.dshEntry, "--profile", cfg.profileName || "phone", "--json", prompt], { env: { ...process.env, ELECTRON_RUN_AS_NODE: "1" }, encoding: "utf8", timeout: 240000 })
let text = ""
for (const line of out.split("\n")) { try { const e = JSON.parse(line); if (e.type === "final" && e.text) text = e.text } catch {} }
console.log(String(text || "").trim())