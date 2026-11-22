#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
小鱼 · 软件适配层（app.py）

设计原则（这是重点）：
  1. **只提供「打开 / 聚焦 / 在内部做一件事」，绝不提供「关闭」**
     —— 她永远没有权限关窗口，反复开关的病根就此消失。
  2. **能用协议/命令行的，绝不点鼠标**
     Edge: msedge --new-window <url>    （复用已开实例，不新开进程）
     Steam: steam://open/main           （拉起已在跑的 Steam）
  3. 需要点内部控件的，优先 UIA（pywinauto），找不到才交给视觉兜底。

命令（由 app.mjs 转发，也可直接跑）：
  python app.py list
  python app.py edge open <url>
  python app.py edge search <关键词>
  python app.py steam open
  python app.py steam play <appid>
  python app.py state <名字>          # 只查状态，不做任何操作
"""
import json
import os
import subprocess
import sys
import time

# 输出固定 UTF-8，避免控制台编码打架
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

# 常见软件的进程名与可执行文件候选（顺带修掉「edge 其实是 msedge」这类坑）
APPS = {
    "edge": {
        "proc": ["msedge"],
        "exe": [
            r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
            r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        ],
    },
    "chrome": {
        "proc": ["chrome"],
        "exe": [
            r"C:\Program Files\Google\Chrome\Application\chrome.exe",
            r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        ],
    },
    "steam": {
        "proc": ["steam"],
        "exe": [r"C:\Program Files (x86)\Steam\steam.exe", r"D:\Steam\steam.exe", r"E:\Steam\steam.exe"],
    },
    "qq": {"proc": ["QQ"], "exe": [r"C:\Program Files\Tencent\QQNT\QQ.exe"]},
    "wechat": {
        "proc": ["WeChat", "Weixin"],
        "exe": [
            r"C:\Program Files\Tencent\Weixin\Weixin.exe",
            r"C:\Program Files (x86)\Tencent\WeChat\WeChat.exe",
        ],
    },
    "netease": {
        "proc": ["cloudmusic"],
        "exe": [r"C:\Program Files\Netease\CloudMusic\cloudmusic.exe"],
    },
}


def running(proc_names):
    """返回正在跑的 pid 列表（不依赖 wmic/tasklist，纯 tasklist 输出解析）"""
    try:
        out = subprocess.run(
            ["tasklist", "/FO", "CSV", "/NH"], capture_output=True, text=True, timeout=10
        ).stdout
    except Exception:
        return []
    pids = []
    for line in out.splitlines():
        parts = [p.strip('"') for p in line.split('","')]
        if len(parts) < 2:
            continue
        name = parts[0].strip('"').lower()
        for want in proc_names:
            if name == (want.lower() + ".exe") or name == want.lower():
                try:
                    pids.append(int(parts[1]))
                except ValueError:
                    pass
    return pids


def find_exe(key):
    for exe in APPS.get(key, {}).get("exe", []):
        if os.path.exists(exe):
            return exe
    # Steam 装在别的盘：问注册表
    if key == "steam":
        try:
            import winreg
            k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam")
            path = winreg.QueryValueEx(k, "SteamPath")[0]
            cand = os.path.join(path, "steam.exe")
            if os.path.exists(cand):
                return cand
        except Exception:
            pass
    return None


STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".app-state.json")


def recent(url):
    """60 秒内开过同一个地址就不要再开（防止她连调两次多开窗口）"""
    try:
        with open(STATE, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        data = {}
    last = data.get(url, 0)
    if time.time() - last < 60:
        return True
    data[url] = time.time()
    # 只留最近 30 条
    items = sorted(data.items(), key=lambda kv: kv[1])[-30:]
    try:
        with open(STATE, "w", encoding="utf-8") as f:
            json.dump(dict(items), f)
    except Exception:
        pass
    return False


def open_uri(uri):
    """用系统关联打开协议（steam:// tencent:// 等），比 shell 稳"""
    try:
        os.startfile(uri)
        return True
    except Exception as e:
        print("协议打不开：" + str(e))
        return False


def launch(args, key):
    """启动但不重复启动：进程在跑就只发参数（浏览器会复用实例）"""
    exe = find_exe(key)
    if not exe:
        return open_uri(" ".join(args))
    subprocess.Popen([exe] + args[1:])
    return True


def cmd_list():
    print("支持的软件：")
    for key in APPS:
        pids = running(APPS[key]["proc"])
        state = "运行中 pid=" + ",".join(map(str, pids)) if pids else "未运行"
        print("  {:<8} {:<22} {}".format(key, state, "exe 已找到" if find_exe(key) else "exe 路径待确认"))
    print("")
    print("用法: app.mjs <软件> <动作> [参数]")
    print("  edge open <url>      在 Edge 里打开网址（复用已有窗口）")
    print("  edge search <关键词>  用 Edge 搜一下")
    print("  steam open           拉起 Steam")
    print("  steam play <appid>   启动游戏")
    print("  state <软件>          只看它在不在跑")


def cmd_state(key):
    if key not in APPS:
        print("不认识这个软件：", key)
        return 2
    pids = running(APPS[key]["proc"])
    if pids:
        print("{} 正在运行 pid={}".format(key, ",".join(map(str, pids))))
        print("ALREADY_RUNNING")
    else:
        print("{} 没在运行".format(key))
    return 0


def cmd_edge(action, arg):
    if action == "open":
        url = arg or "about:blank"
        already = bool(running(APPS["edge"]["proc"]))
        flag = "--new-tab" if already else "--new-window"
        if recent(url):
            print("这个页面刚开过，不重复开了：" + url)
            print("SKIPPED_DUPLICATE")
            return 0
        launch(["msedge", flag, url], "edge")
        print("已让 Edge 打开：" + url + "（" + ("加在现有窗口的标签里" if already else "新开窗口") + "）")
        print("（复用已有 Edge 实例，不会另开进程）")
        return 0
    if action == "search":
        q = arg.replace(" ", "+")
        url = "https://www.bing.com/search?q=" + q
        if recent(url):
            print("刚搜过这个了，不重复开：" + arg)
            print("SKIPPED_DUPLICATE")
            return 0
        launch(["msedge", "--new-window", url], "edge")
        print("已让 Edge 搜索：" + arg)
        return 0
    if action == "focus":
        print("Edge 不需要 focus：开新窗口就会到前台")
        return 0
    print("edge 支持: open <url> | search <关键词> | state")
    return 2


def cmd_steam(action, arg):
    if action == "open":
        open_uri("steam://open/main")
        print("已拉起 Steam")
        return 0
    if action == "play":
        if not arg:
            print("要给 appid，例如 steam play 730")
            return 2
        open_uri("steam://rungameid/" + str(arg))
        print("已请求启动游戏 appid=" + str(arg))
        return 0
    print("steam 支持: open | play <appid> | state")
    return 2


def main(argv):
    if len(argv) < 2:
        cmd_list()
        return 0
    cmd = argv[1].lower()
    if cmd == "list":
        cmd_list()
        return 0
    if cmd == "state":
        return cmd_state(argv[2].lower() if len(argv) > 2 else "")
    if cmd == "edge":
        return cmd_edge(argv[2].lower() if len(argv) > 2 else "", argv[3] if len(argv) > 3 else "")
    if cmd == "steam":
        return cmd_steam(argv[2].lower() if len(argv) > 2 else "", argv[3] if len(argv) > 3 else "")
    if cmd in APPS:
        print("{} 的适配还没做（P2 阶段）。现在可以先看 state，或者用 gui.mjs 操作。".format(cmd))
        return cmd_state(cmd)
    print("不认识的命令：", cmd)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
