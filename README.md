# 小鱼 · 手机版（Xiaoyu Phone）

把你电脑上的 AI agent 接进手机：**发消息、发表情包、收图片和文件、还能让它干活**。
一个文件夹装下人格、记忆、表情包、服务端和安卓 App —— **换电脑整个拷走就能继续用**。

> 不是套壳聊天机器人：手机上的每一句话都进本机的 agent，能读写文件、跑命令、翻你自己的记忆。

---

## 它能做什么

| 能力 | 说明 |
|---|---|
| 💬 聊天 | 她的回复按行拆成多条气泡逐条蹦出来，不是一大段 |
| 😀 表情包 | 你发给她、她**也能发给你**（在回复里写 `[[表情:名字]]`，服务翻译成真图） |
| 🖼 图片 | 双向：你发的她能看到（走 agent 的读图能力），她也能发给你 |
| 📎 文件 | **她做出来的 PPT / C 代码 / PDF 都能直接发到手机**，点一下下载 |
| 🧠 记忆 | 一天一个文件夹存档对话；默认只看最近 10 天，不够就自动往前推 10 天 |
| 🎨 界面 | Discord 风格深色 + 自定义壁纸（图片/视频，可铺满、可拖动定位） |
| 🌍 内网穿透 | 一条命令拿到公网 https 地址，任何网络都能连 |

## 架构

```
手机 App (Kotlin + Compose)
        │  HTTP  + Bearer token
        ▼
桥接服务 server/server.mjs          ← 这个仓库
        │  spawn
        ▼
DeepSeek Harness（headless / agent 模式）
        │
        ├── 人格：人格/*.md
        ├── 记忆：记忆/对话/年/月/日/对话.md
        ├── 表情包：表情包/
        └── 工具：读写文件、跑 PowerShell、联网
```

桥接服务只做三件事，所以很好审：

1. 收一句话 → 跑一次 `dsh --profile phone` → 把回答按标记拆成正文 + 附件
2. 收文件 → 存到 `记忆/上传/`
3. 发文件 → 给一个**带签名和时效**的下载链接（不能随便读你硬盘）

## 快速开始

### 0. 前置

- **Node.js 20+**
- **DeepSeek Harness**（本项目的 agent 运行时）
- Windows 10/11（服务端脚本用了 `.cmd`；核心逻辑是纯 Node，其他系统也能跑）

### 1. 准备配置

```bash
cp 配置.example.json 配置.json
```

编辑 `配置.json`：

| 字段 | 说明 |
|---|---|
| `token` | **必改**。手机 App 用它认证，随便一串随机字符 |
| `mode` | `agent`（能操作电脑）或 `chat`（纯聊天，本地小模型用） |
| `provider` / `model` | 云端 `deepseek-account` / `deepseek-flash`；本地 `ollama` / `qwen2.5:7b` |
| `apiKey` | 可留空（用 DSH 里登录的账号）；填了就能查余额 |
| `dshExe` | DSH 可执行文件路径，留空会自动探测 |

### 2. 启动

```bash
双击 启动.cmd
```

它会：生成 agent 配置（人格 + 记忆路径 + 工具权限）→ 起服务 → 打开设置页 `http://127.0.0.1:19401/`。

### 3. 装 App

`release/xiaoyu-v2.7.apk`（或自己用 Android Studio 编译 `安卓工程/`）。
App 设置里填：

- **服务地址**：`http://<电脑局域网IP>:19401`
- **口令**：`配置.json` 里的 `token`

### 4. 不在同一个网络？

```bash
双击 一键启动.cmd
```

它会同时起服务和 Cloudflare 隧道，打印一个 `https://xxx.trycloudflare.com`，填进 App 即可。
（首次需要把 `cloudflared.exe` 放进 `server/bin/`，见 `server/cf-tunnel.mjs` 顶部注释。）

## 设置页

浏览器打开 `http://127.0.0.1:19401/`（**只允许本机**，走隧道会被 403）：

- 看当前真实在跑的模式/模型/记忆条数
- 改模型、填 API Key、查余额、跑一句话自检
- 改端口、口令、权限模式、DSH 路径
- 预览所有表情包

## 记忆机制

```
记忆/对话/2026/11/22/对话.md
记忆/对话/2026/11/23/对话.md
```

- 每轮对话由服务**自动追加**，只写不重写，硬盘多大存多少
- 她默认读最近 **10 天**；搜不到就 `range 10 20`，再不到 `20 30`…… 一次推一个窗口
- 检索工具：`node server/recall.mjs list|recent|search|day|range`
- 另外她还会自己写日记：`记忆/记忆区/日记/`

## 给她的标记语法

她在回复里写这些，服务会翻译成真东西：

```
[[表情:斜眼鄙视的棕白小狗]]
[[图片:D:\path\to\image.jpg]]
[[文件:D:\path\to\report.pptx]]
```

文件不是直接塞 base64，而是返回一个 **HMAC 签名 + 30 分钟过期** 的下载链接。

## 目录

```
server/          桥接服务
  server.mjs        HTTP + 会话 + 附件 + 设置页托管
  memory.mjs        记忆读写（年/月/日 分目录）
  recall.mjs        记忆检索 CLI
  make-profile.mjs  生成 DSH 的 agent 配置
  config.mjs        配置 + DSH 自动探测
  cf-tunnel.mjs     Cloudflare 隧道封装（IPv4/HTTP2 + 自检）
  tunnel.mjs        SSH 隧道（零下载备用方案）
人格/            人设.md、AGENTS.md、记忆指引.md、自我调校.md
记忆/            对话、日记、上传、产出
表情包/          index.json + 图片
安卓工程/        Kotlin + Compose 源码
```

## 安全说明

- **口令是唯一钥匙**，别提交 `配置.json`（`.gitignore` 已排除）
- 设置页与 `/api/*` 只在**本机直连**时可用；带任何转发头（`cf-*`、`x-forwarded-for`）一律 403
- 隧道只暴露 `/chat`、`/upload`、`/ping`，都要口令
- 默认权限模式是 `danger-full-access`（能操作电脑）。要收紧就改 `workspace-write`
- 文件下载链接签名绑定路径 + 过期时间，篡改即 403

## 已知限制

- 本地小模型（如 `qwen2.5:7b`）跑 `agent` 模式会被工具定义带偏，必须用 `chat` 模式
- 部分 DeepSeek 开源模型（如 `deepseek-v2:16b`）**不支持 tools**，在 `agent` 模式下无法使用
- 免费隧道地址每次启动都会变；要固定地址得用 Tailscale 或命名隧道

## License

MIT