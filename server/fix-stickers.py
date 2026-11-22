"""把表情包库统一成 png（手机认的格式），并修好 index.json。

用法：
    python server/fix-stickers.py           # 转换 + 改索引
"""
import json
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
LIB = os.path.join(HERE, "..", "表情包")
INDEX = os.path.join(LIB, "index.json")


def fmt_of(path):
    try:
        with open(path, "rb") as f:
            head = f.read(12)
        if head[:8] == b"\x89PNG\r\n\x1a\n":
            return "png"
        if head[:3] == b"\xff\xd8\xff":
            return "jpg"
        if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
            return "webp"
        if head[:6] in (b"GIF87a", b"GIF89a"):
            return "gif"
        if head[:2] == b"BM":
            return "bmp"
        return "unknown"
    except Exception:
        return "unknown"


def main():
    if not os.path.exists(INDEX):
        print("没有 index.json")
        return 1
    with open(INDEX, "r", encoding="utf-8") as f:
        entries = json.load(f)

    changed = 0
    for e in entries:
        name = e.get("file", "")
        path = os.path.join(LIB, name)
        if not os.path.exists(path):
            print("缺文件，跳过：", name)
            continue
        kind = fmt_of(path)
        if kind in ("png", "jpg"):
            # 只有没扩展名的补上扩展名
            if "." not in name:
                new_name = name + "." + kind
                os.rename(path, os.path.join(LIB, new_name))
                e["file"] = new_name
                changed += 1
                print("补扩展名：{} → {}".format(name[:16], new_name[:24]))
            continue
        # 其它格式 → 转 png
        try:
            im = Image.open(path)
            if getattr(im, "is_animated", False):
                im.seek(0)
            im = im.convert("RGBA")
            base = name.split(".")[0]
            new_name = base + ".png"
            im.save(os.path.join(LIB, new_name), "PNG")
            if new_name != name and os.path.exists(path):
                os.remove(path)
            e["file"] = new_name
            changed += 1
            print("转 png：{} ({} → png)".format(base[:16], kind))
        except Exception as ex:
            print("转换失败：{} {}".format(name[:20], ex))

    with open(INDEX, "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, indent=1)

    # 统计
    kinds = {}
    for e in entries:
        p = os.path.join(LIB, e["file"])
        kinds[fmt_of(p)] = kinds.get(fmt_of(p), 0) + 1
    print("")
    print("共 {} 条，改动 {} 条。格式分布：{}".format(len(entries), changed, kinds))
    return 0


if __name__ == "__main__":
    sys.exit(main())
