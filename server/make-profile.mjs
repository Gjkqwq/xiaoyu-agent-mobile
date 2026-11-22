/**
 * 生成 DSH 的 phone profile。
 *
 * 两种模式：
 *   mode: agent （默认）  人格 + 记忆 + 全部工具 + 完全权限，能操作电脑
 *   mode: chat            只聊天：关掉全部工具，system prompt 精简
 *                         （本地 chat 模型大多不支持 tools，开着会直接 400）
 *
 * 每次启动都按当前位置重新生成 —— profile 里是绝对路径，换电脑就变。
 */
import { spawnSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { PATHS, loadConfig, profileDir } from './config.mjs'

const cfg = loadConfig()
const name = cfg.profileName || 'phone'
const dir = profileDir(name)
const DIALOGS = join(PATHS.memory, '..', '对话')
const isChat = (cfg.mode || 'agent') === 'chat'

const TOOL_IDS = [
  'tool-plugin-manager', 'tool-bash', 'tool-pwsh', 'tool-jobs', 'tool-fs', 'tool-fs-search',
  'tool-skill', 'tool-subagent-control', 'tool-subagent-list-agents', 'tool-subagent',
  'tool-subagent-fork', 'tool-workflow', 'tool-result-pruner', 'tool-todo', 'tool-goal',
  'tool-ralph', 'tool-web',
]

function block(text, indent = 6) {
  const pad = ' '.repeat(indent)
  return String(text)
    .replace(/\r\n/g, '\n')
    .split('\n')
    .map((l) => (l.trim() === '' ? '' : pad + l))
    .join('\n')
}

function readOr(path, fallback) {
  try {
    return readFileSync(path, 'utf8').trim() || fallback
  } catch {
    return fallback
  }
}

function fillPaths(text) {
  return text
    .replaceAll('{对话}', DIALOGS)
    .replaceAll('{记忆区}', PATHS.memory)
    .replaceAll('{自我调校}', PATHS.tune)
    .replaceAll('{AGENTS}', join(PATHS.root, 'AGENTS.md'))
    .replaceAll('{表情包}', PATHS.stickerIndex)
}

/** 表情包名字清单（纯聊天模式没有工具，只能把名字直接喂给她） */
function stickerNames() {
  try {
    const list = JSON.parse(readFileSync(PATHS.stickerIndex, 'utf8'))
    return list.map((x) => x.summary).filter(Boolean)
  } catch {
    return []
  }
}
// 当前情绪值：会注入到 system prompt，影响她回不回、怎么回
function currentMood() {
  try {
    const j = JSON.parse(readFileSync(join(PATHS.memory, '情绪.json'), 'utf8'))
    return Number(j.mood) || 60
  } catch {
    return 60
  }
}
const mood = currentMood()

const persona = readOr(PATHS.persona, '你是 ta 的伙伴，不是助手。说话短、热、像在手机上打字。')

let memoryGuide = fillPaths(readOr(PATHS.memoryGuide, ''))
if (isChat) {
  const names = stickerNames()
  memoryGuide = [
    '【这次是纯聊天模式】',
    '',
    '你手上没有工具，跑不了命令、也读不了文件。不要假装查过东西，也别报路径和命令。',
    '你能依靠的就是：你自己的性格（上面那段人设）、ta 此刻说的话、你记得的事。',
    '记不清就直说记不清 —— ta 需要翻旧账时，可以让他切回「能操作电脑」的模式再问。',
    '',
    '【你可以发表情包】',
    '',
    '在回复里单独占一行写：[[表情:名字]]',
    '名字必须从下面这一串里原样挑（写错会发不出来）：',
    names.length ? names.join(' / ') : '（收藏夹是空的）',
    '',
    '习惯：两三句就配一张；有时干脆只发一张图不写字（回复里就那一行标记）。',
    '别把标记塞进句子中间，也别解释它是什么。',
  ].join('\n')
}

const moodBlock = [
  '',
  '【当前情绪值：' + mood + '/100】',
  mood < 30
    ? '你现在情绪很低/有点生气。可以已读不回：ta 发来的消息你会看到，但有较大概率先晾着不回；连着被戳 3~5 条之后才回一句冷淡的短句。别解释情绪，别演。'
    : mood < 55
      ? '你现在情绪一般，回得短一点、淡一点，不太主动。'
      : '你现在心情不错，话可以多一点、热一点。',
].join('\n')

const suffix = [
  memoryGuide + moodBlock,
  '',
  '【这台电脑上的真实路径（换机器由启动脚本自动改）】',
  '- 对话记录：' + DIALOGS,
  '- 记忆区：' + PATHS.memory,
  '- 自我调校：' + PATHS.tune,
  '- 表情包：' + PATHS.stickerIndex,
].join('\n')

const localBlock =
  cfg.provider === 'ollama'
    ? [
        '- id: llm-pi-ai',
        '  config:',
        '    providers:',
        '      ollama:',
        '        displayName: Ollama 本地',
        '        apiKeyEnv: OLLAMA_API_KEY',
        '        api: openai-completions',
        '        baseURL: ' + (cfg.localBaseUrl || 'http://127.0.0.1:11434/v1'),
        '        models:',
        '          - id: ' + cfg.model,
        '            name: ' + cfg.model + '（本地）',
        '            contextWindow: ' + (cfg.localContext || 8192),
        '            maxTokens: 2048',
        '            input: [text]',
        '            compat:',
        '              chatTemplateKwargs:',
        '                enable_thinking: false',
        '        defaultInput: [text]',
        '        compat:',
        '          chatTemplateKwargs:',
        '            enable_thinking: false',
        '',
      ].join('\n')
    : ''

const lines = []
lines.push('# 由 小鱼手机版/server/make-profile.mjs 自动生成，勿手改')
lines.push('# 想改人格 → 人格/人设.md；想改规矩 → 人格/AGENTS.md；想改记忆策略 → 人格/记忆指引.md')
lines.push('')
lines.push('- id: agent-default-model')
lines.push('  config:')
lines.push('    provider: ' + cfg.provider)
lines.push('    model: ' + cfg.model)
lines.push('')
lines.push('- id: approval')
lines.push('  config:')
lines.push('    policy: never')
lines.push('')
lines.push('- id: sandbox-policy')
lines.push('  config:')
lines.push('    mode: ' + cfg.permissionMode)
lines.push('')
for (const id of TOOL_IDS) {
  lines.push('- id: ' + id)
  lines.push('  disabled: ' + (isChat ? 'true' : 'false'))
}
lines.push('')
lines.push('- id: system-prompt')
lines.push('  config:')
lines.push('    personaPrefix: |-')
lines.push(block(persona))
lines.push('    personaSuffix: |-')
lines.push(block(suffix))
lines.push('')

const yaml = localBlock + lines.join('\n')

function runDsh(args) {
  const res = spawnSync(cfg.dshExe, ['--expose-internals', cfg.dshEntry, ...args], {
    env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' },
    windowsHide: true,
    encoding: 'utf8',
    timeout: 120_000,
  })
  return { code: res.status, out: res.stdout || '', err: res.stderr || '' }
}

if (!existsSync(dir)) {
  console.log('· 首次运行：创建 DSH profile「' + name + '」…')
  mkdirSync(dir, { recursive: true })
  if (existsSync(cfg.dshExe)) {
    const r = runDsh(['--profile', name, '--from-default-profile', 'headless', '--dump-config'])
    if (r.code !== 0 && !existsSync(join(dir, 'package.json'))) {
      console.error('  创建 profile 失败：' + (r.err || r.out).slice(0, 300))
    }
  }
}

mkdirSync(dir, { recursive: true })
writeFileSync(join(dir, 'cordis.patch.yml'), yaml, 'utf8')

try {
  const srcAgents = PATHS.agents
  const dstAgents = join(PATHS.root, 'AGENTS.md')
  if (existsSync(srcAgents) && srcAgents !== dstAgents) copyFileSync(srcAgents, dstAgents)
} catch (e) {
  console.error('  同步 AGENTS.md 失败：' + e.message)
}

for (const p of [DIALOGS, PATHS.memory, join(PATHS.memory, '日记'), PATHS.uploads]) {
  try {
    mkdirSync(p, { recursive: true })
  } catch {}
}

console.log('· profile 已更新：' + dir)
console.log('  模式：' + (isChat ? 'chat（纯聊天，关闭全部工具）' : 'agent（全工具）'))
console.log('  模型：' + cfg.provider + ' / ' + cfg.model)
console.log('  对话记录：' + DIALOGS)