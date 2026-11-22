/**
 * 配置与路径。
 *
 * 整个包要能「拷到另一台电脑直接用」，所以：
 *   - 包内路径一律用相对本文件的位置算，不写死 D:\chat\...
 *   - DSH 的安装位置允许自动探测：找不到就用配置里的，再找不到给出可读的提示
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const PKG_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export const CONFIG_PATH = join(PKG_ROOT, '配置.json')

export const PATHS = {
  root: PKG_ROOT,
  persona: join(PKG_ROOT, '人格', '人设.md'),
  memoryGuide: join(PKG_ROOT, '人格', '记忆指引.md'),
  tune: join(PKG_ROOT, '人格', '自我调校.md'),
  agents: join(PKG_ROOT, '人格', 'AGENTS.md'),
  chatLog: join(PKG_ROOT, '记忆', '聊天记录'),
  memory: join(PKG_ROOT, '记忆', '记忆区'),
  stickers: join(PKG_ROOT, '表情包'),
  stickerIndex: join(PKG_ROOT, '表情包', 'index.json'),
  uploads: join(PKG_ROOT, '记忆', '上传'),
  settingsHtml: join(PKG_ROOT, '设置.html'),
  android: join(PKG_ROOT, '安卓工程'),
}

const DEFAULTS = {
  port: 19401,
  token: '',
  provider: 'deepseek-account',
  model: 'deepseek-flash',
  apiKey: '',
  apiBase: 'https://api.deepseek.com',
  permissionMode: 'danger-full-access',
  timeoutMs: 300000,
  profileName: 'phone',
  dshExe: '',
  dshEntry: '',
  updatedAt: '',
}

export function loadConfig() {
  let raw = {}
  try {
    raw = JSON.parse(readFileSync(CONFIG_PATH, 'utf8'))
  } catch {
    raw = {}
  }
  const cfg = { ...DEFAULTS, ...raw }

  // DSH 位置：配置优先，其次自动探测
  if (!cfg.dshExe || !existsSync(cfg.dshExe)) {
    const found = detectDsh()
    if (found) {
      cfg.dshExe = found.exe
      cfg.dshEntry = found.entry
    }
  }
  return cfg
}

export function saveConfig(patch) {
  const cur = loadConfig()
  const next = { ...cur, ...patch, updatedAt: new Date().toISOString() }
  writeFileSync(CONFIG_PATH, JSON.stringify(next, null, 2) + '\n', 'utf8')
  return next
}

/** 常见安装位置探一遍；返回 { exe, entry }。 */
export function detectDsh() {
  const candidates = []
  if (process.env.DSH_EXE) candidates.push(process.env.DSH_EXE)
  const home = process.env.USERPROFILE || process.env.HOME || ''
  const drives = ['C:', 'D:', 'E:', 'F:']
  for (const d of drives) {
    candidates.push(`${d}\\DeepseekHarness\\DeepSeek Harness.exe`)
    candidates.push(`${d}\\DeepSeek Harness\\DeepSeek Harness.exe`)
    candidates.push(`${d}\\Program Files\\DeepSeek Harness\\DeepSeek Harness.exe`)
  }
  if (home) {
    candidates.push(join(home, 'AppData', 'Local', 'Programs', 'deepseek-harness', 'DeepSeek Harness.exe'))
  }
  for (const exe of candidates) {
    if (exe && existsSync(exe)) {
      const root = dirname(exe)
      const entry = join(
        root,
        'resources',
        'app.asar',
        'dsh',
        'node_modules',
        '@deepseek-ai',
        'dsh-desktop-host',
        'lib',
        'cli.js'
      )
      return { exe, entry }
    }
  }
  return null
}

/** DSH profile 目录（跨机器通用，放在用户目录下）。 */
export function profileDir(name) {
  const home = process.env.USERPROFILE || process.env.HOME || '.'
  return join(home, '.dsh', 'profiles', name)
}
