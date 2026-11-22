"""把表情包里所有非 PNG/JPG 的图真正转成 PNG（不是改扩展名）。"""
import json
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
LIB = os.path.join(HERE, "..", "表情包")
INDEX = os.path.join(LIB, "index.json")


def kind(path):
    with open(path, "rb") as f:
        h = f.read(12)
    if h[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if h[:3] == b"\xff\xd8\xff":
        return "jpg"
    if h[:4] == b"RIFF" and h[8:12] == b"WEBP":
        return "webp"
    if h[:6] in (b"GIF87a", b"GIF89a"):
        return "gif"
    if h[:2] == b"BM":
        return "bmp"
    return "unknown"


with open(INDEX, "r", encoding="utf-8") as f:
    entries = json.load(f)

converted = 0
for e in entries:
    name = e["file"]
    path = os.path.join(LIB, name)
    if not os.path.exists(path):
        print("缺文件:", name[:20])
        continue
    k = kind(path)
    if k in ("png", "jpg"):
        continue
    base = name.rsplit(".", 1)[0]
    out = os.path.join(LIB, base + ".png")
    try:
        # 先把像素读进内存并关掉文件句柄，否则 Windows 上删不掉原文件（WinError 5）
        src = Image.open(path)
        if getattr(src, "is_animated", False):
            src.seek(0)
        frame = src.convert("RGBA").copy()
        src.close()
        del src
        frame.save(out, "PNG")
        frame.close()
        if os.path.abspath(out) != os.path.abspath(path):
            for _ in range(3):
                try:
                    os.remove(path)
                    break
                except PermissionError:
                    import time
                    time.sleep(0.3)
        e["file"] = base + ".png"
        converted += 1
        print("转码:", name[:14], k, "->", base[:14] + ".png")
    except Exception as ex:
        print("失败:", name[:20], ex)

with open(INDEX, "w", encoding="utf-8") as f:
    json.dump(entries, f, ensure_ascii=False, indent=1)

stat = {}
for e in entries:
    p = os.path.join(LIB, e["file"])
    k = kind(p) if os.path.exists(p) else "missing"
    stat[k] = stat.get(k, 0) + 1
print("")
print("转码 {} 条；现在格式分布：{}".format(converted, stat))
