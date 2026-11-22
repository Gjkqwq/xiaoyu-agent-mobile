#!/usr/bin/env node
/**
 * 记忆检索 —— 给她（agent）用的命令行工具。
 *
 *   node server/recall.mjs list [天数]              最近 N 天有哪些天、每天几条
 *   node server/recall.mjs recent [天数] [最多条数]  最近 N 天的对话正文
 *   node server/recall.mjs day 2026-11-22            某一天全文
 *   node server/recall.mjs search 关键词 [天数]      在最近 N 天里搜
 *   node server/recall.mjs range 10 20               10~20 天前那一段（再往前推一窗口）
 *
 * 设计意图（配合人格里的规矩）：
 *   默认只看最近 10 天；没找到就 range 10 20，再没找到 20 30 …… 一次推一窗口，
 *   直到找到或者翻到头。这样既不浪费 token，又能一直往上追。
 */
import { listDays, countTurns, readDay, daysAgo, dirOfDay } from './memory.mjs'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const [cmd, a1, a2] = process.argv.slice(2)

function die(msg) {
  console.error(msg)
  process.exit(1)
}

const all = listDays() // 新的在前
if (all.length === 0) {
  console.log('（记忆里还没有任何对话记录）')
  process.exit(0)
}

const total = all.length

switch (cmd) {
  case 'list': {
    const days = Number(a1) || 10
    const picked = all.filter((d) => daysAgo(d.day) < days)
    console.log(`共 ${total} 天有记录（最早 ${all[total - 1].day}，最近 ${all[0].day}）`)
    console.log(`----- 最近 ${days} 天 -----`)
    if (picked.length === 0) console.log('（这 ' + days + ' 天里没有记录）')
    for (const d of picked) {
      const n = countTurns(d.path)
      console.log(`${d.day}  周${'日一二三四五六'[new Date(d.day + 'T00:00:00').getDay()]}  ${n} 条  ${(d.size / 1024).toFixed(1)}KB`)
    }
    if (picked.length < total) {
      console.log(`（还有 ${total - picked.length} 天更早的记录，用 range ${days} ${days * 2} 继续往前看）`)
    }
    break
  }

  case 'recent': {
    const days = Number(a1) || 10
    const maxTurns = Number(a2) || 300
    const picked = all.filter((d) => daysAgo(d.day) < days)
    if (picked.length === 0) {
      console.log(`最近 ${days} 天没有记录。`)
      break
    }
    // 从旧到新输出，最后一句在最下面
    let budget = maxTurns
    const chunks = []
    for (const d of [...picked].reverse()) {
      const text = readDay(d.day) || ''
      const lines = text.split('\n').filter((l) => /^- \d{2}:\d{2} |^# /.test(l))
      if (lines.length > budget) {
        chunks.push(lines.slice(-budget).join('\n'))
        budget = 0
        break
      }
      chunks.push(lines.join('\n'))
      budget -= lines.length
      if (budget <= 0) break
    }
    console.log(`----- 最近 ${days} 天（最多 ${maxTurns} 条，旧的在上）-----`)
    console.log(chunks.join('\n\n'))
    break
  }

  case 'day': {
    if (!a1) die('用法：node server/recall.mjs day 2026-11-22')
    const text = readDay(a1)
    if (!text) die(`那天没有记录：${a1}`)
    console.log(text)
    break
  }

  case 'search': {
    if (!a1) die('用法：node server/recall.mjs search 关键词 [天数]')
    const days = Number(a2) || 10
    const kw = String(a1).toLowerCase()
    const picked = all.filter((d) => daysAgo(d.day) < days)
    let hits = 0
    console.log(`----- 最近 ${days} 天里包含「${a1}」的对话 -----`)
    for (const d of [...picked].reverse()) {
      const text = readDay(d.day) || ''
      const lines = text.split('\n').filter((l) => l.toLowerCase().includes(kw))
      if (lines.length) {
        hits += lines.length
        console.log(`\n[${d.day}]`)
        console.log(lines.slice(0, 40).join('\n'))
      }
    }
    if (hits === 0) {
      console.log('没找到。可以往前推：node server/recall.mjs range ' + days + ' ' + (days * 2))
    } else {
      console.log(`\n（共 ${hits} 条）`)
    }
    break
  }

  case 'range': {
    const from = Number(a1) || 10
    const to = Number(a2) || from + 10
    const picked = all.filter((d) => daysAgo(d.day) >= from && daysAgo(d.day) < to)
    console.log(`----- ${from}~${to} 天前（共 ${picked.length} 天有记录）-----`)
    if (picked.length === 0) {
      const oldest = daysAgo(all[total - 1].day)
      console.log(`这一段没有记录。最早的记录在 ${all[total - 1].day}（约 ${oldest} 天前）。`)
      break
    }
    for (const d of [...picked].reverse()) {
      console.log(`\n[${d.day}]`)
      const text = readDay(d.day) || ''
      console.log(text.split('\n').filter((l) => /^- \d{2}:\d{2} /.test(l)).join('\n'))
    }
    console.log(`\n（继续往前：node server/recall.mjs range ${to} ${to + 10}）`)
    break
  }

  default:
    console.log(`记忆检索工具

  node server/recall.mjs list [天数]              看最近 N 天有哪些天、各多少条
  node server/recall.mjs recent [天数] [条数]     读最近 N 天的对话
  node server/recall.mjs day 2026-11-22           读某一天
  node server/recall.mjs search 关键词 [天数]     在最近 N 天里搜关键词
  node server/recall.mjs range 10 20              再往前推一个窗口

当前共 ${total} 天记录，最早 ${all[total - 1].day}，最近 ${all[0].day}。
存储位置：${dirOfDay(all[0].day).replace(/[\\/]\d+$/, '')}`)
}
