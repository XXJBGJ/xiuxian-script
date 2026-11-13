#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建时从游戏 APK 里取真实启动图标，生成 GenIcons.java（由 GameIcons 优先读取）。

为什么要放进构建里：
  安装包库里的图标必须和真正装出来的 APP 图标一致。图标可能是自适应图标(anydpi xml)、
  也可能只放在某个密度目录里，靠猜文件名一定会取错。这里用 Android 官方 aapt 读
  AndroidManifest 里登记的 application-icon，取最高密度那张，就是真实图标。
"""
import base64, io, json, os, re, subprocess, sys, urllib.request, zipfile

REPO = "XXJBGJ/xiuxian-script"
OUT = "app/src/main/java/com/xiuxian/script/GenIcons.java"
TMP = "/tmp/gameapk"
ICON_PX = 96

# 文件名 -> 图标 key（与 BundledGameIcons.keyFor 保持一致）
RULES = [
    ("4399", "game_4399"),
    ("baidu", "game_baidu"),
    ("百度", "game_baidu"),
    ("guaimao", "game_guaimao"),
    ("怪猫", "game_guaimao"),
    ("jiuyou", "game_jiuyou"),
    ("九游", "game_jiuyou"),
    ("jule", "game_jule"),
    ("聚乐", "game_jule"),
    ("moniqi", "game_moniqi"),
    ("模拟器", "game_moniqi"),
    ("xiaomi", "game_xiaomi"),
    ("小米", "game_xiaomi"),
    ("tiantu", "game_tiantu"),
    ("pupu", "game_tiantu"),
    ("天途", "game_tiantu"),
    ("噗噗", "game_tiantu"),
]


def key_of(name):
    low = name.lower()
    for kw, key in RULES:
        if kw in low:
            return key
    return None


def http_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "icon-gen"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def download(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 100000:
        return path
    req = urllib.request.Request(url, headers={"User-Agent": "icon-gen"})
    with urllib.request.urlopen(req, timeout=300) as r, open(path, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
    return path


# APK 里的资源目录用密度等级名标注（mipmap-xxhdpi-v4），不是数字，这里换算成可比较的数值
DENS = [("xxxhdpi", 640), ("xxhdpi", 480), ("xhdpi", 320), ("hdpi", 240),
        ("mdpi", 160), ("ldpi", 120), ("nodpi", 0), ("anydpi", 0)]


def dens_of(path):
    low = path.lower()
    for name, val in DENS:
        if name in low:
            return val
    m = re.search(r"-(\d+)dpi", low)
    return int(m.group(1)) if m else 0


def find_aapt():
    for root in filter(None, [os.environ.get("ANDROID_SDK_ROOT"),
                              os.environ.get("ANDROID_HOME"),
                              "/usr/local/lib/android/sdk"]):
        bt = os.path.join(root, "build-tools")
        if not os.path.isdir(bt):
            continue
        for ver in sorted(os.listdir(bt), reverse=True):
            for exe in ("aapt", "aapt2"):
                p = os.path.join(bt, ver, exe)
                if os.path.isfile(p):
                    return p
    out = subprocess.run(["bash", "-lc", "command -v aapt || command -v aapt2"],
                         capture_output=True, text=True).stdout.strip()
    return out or None


def badging_icons(aapt, apk):
    """返回 [(density, path)]，按密度从高到低"""
    try:
        out = subprocess.run([aapt, "dump", "badging", apk],
                             capture_output=True, text=True, timeout=180).stdout
    except Exception as e:
        print("  aapt 失败:", e)
        return []
    pairs = []
    for m in re.finditer(r"application-icon-(\d+):'([^']+)'", out):
        dens, p = int(m.group(1)), m.group(2)
        if dens == 65534:          # anydpi 自适应图标 xml，不是图片
            continue
        if p.lower().endswith((".png", ".webp", ".jpg", ".jpeg")):
            pairs.append((dens, p))
    pairs.sort(reverse=True)
    if not pairs:
        m = re.search(r"application:.*?\bicon='([^']+)'", out)
        if m:
            icon = m.group(1)
            if icon.lower().endswith((".png", ".webp", ".jpg", ".jpeg")):
                pairs.append((0, icon))
            else:
                pairs.append((65533, icon))   # 自适应图标 xml，下面再往里挖
    return pairs


def adaptive_foreground(apk, xml_path):
    """自适应图标 xml 里真正的图形在 foreground 指向的图，把它找出来"""
    with zipfile.ZipFile(apk) as z:
        try:
            xml = z.read(xml_path).decode("utf-8", "ignore")
        except KeyError:
            return []
    names = re.findall(r"@(?:drawable|mipmap)/([A-Za-z0-9_]+)", xml)
    hits = []
    with zipfile.ZipFile(apk) as z:
        for nm in z.namelist():
            if not nm.lower().endswith((".png", ".webp", ".jpg", ".jpeg")):
                continue
            base = os.path.basename(nm).split(".")[0]
            if base in names:
                hits.append((dens_of(nm), nm))
    hits.sort(reverse=True)
    return hits


def scan_by_name(apk):
    """最后兜底：直接找图标类文件，按密度排序，排除自适应 xml"""
    hits = []
    with zipfile.ZipFile(apk) as z:
        for nm in z.namelist():
            low = nm.lower()
            if not low.endswith((".png", ".webp", ".jpg", ".jpeg")):
                continue
            base = os.path.basename(low)
            if not any(k in base for k in ("ic_launcher", "app_icon", "icon", "logo")):
                continue
            if "/res/" not in low and not low.startswith("res/"):
                continue
            hits.append((dens_of(nm), nm))
    hits.sort(reverse=True)
    return hits


def read_icon(apk, inner):
    with zipfile.ZipFile(apk) as z:
        for nm in (inner, inner.lstrip("/")):
            try:
                return z.read(nm)
            except KeyError:
                continue
    return None


def shrink(raw):
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(raw)).convert("RGBA")
        im = im.resize((ICON_PX, ICON_PX), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "PNG", optimize=True)
        return buf.getvalue()
    except Exception as e:
        print("  缩图跳过(%s)，用原图" % e)
        return raw


def java_literal(b64, chunk=7000):
    parts = [b64[i:i + chunk] for i in range(0, len(b64), chunk)]
    return " +\n            ".join('"%s"' % p for p in parts) if len(parts) > 1 else '"%s"' % parts[0]


def main():
    os.makedirs(TMP, exist_ok=True)
    aapt = find_aapt()
    print("aapt =", aapt)
    if not aapt:
        print("找不到 aapt，退出")
        return 1

    rel = []
    for page in (1, 2, 3):
        rel += http_json("https://api.github.com/repos/%s/releases?per_page=100&page=%d" % (REPO, page))

    assets = []
    for r in rel:
        for a in r.get("assets", []):
            n = a["name"]
            if n.lower().endswith(".apk") and "xiuxian" not in n.lower():
                assets.append(a)
    print("游戏包数量:", len(assets))

    result = {}
    for a in assets:
        key = key_of(a["name"])
        if not key:
            print("跳过(无对应 key):", a["name"])
            continue
        apk = os.path.join(TMP, a["name"])
        try:
            download(a["browser_download_url"], apk)
        except Exception as e:
            print("下载失败", a["name"], e)
            continue
        pairs = badging_icons(aapt, apk)
        got = None
        for dens, inner in pairs:
            if dens == 65533:                       # 自适应图标：挖 foreground
                cands = adaptive_foreground(apk, inner)
                for d2, nm in cands:
                    raw = read_icon(apk, nm)
                    if raw and len(raw) > 500:
                        print("  %-34s -> %-14s %s (自适应前景, %d bytes)"
                              % (a["name"], key, nm, len(raw)))
                        got = shrink(raw)
                        break
                if got:
                    break
                continue
            raw = read_icon(apk, inner)
            if raw and len(raw) > 500:
                print("  %-34s -> %-14s %s (density=%d, %d bytes)"
                      % (a["name"], key, inner, dens, len(raw)))
                got = shrink(raw)
                break
        if not got:
            for dens, nm in scan_by_name(apk):
                raw = read_icon(apk, nm)
                if raw and len(raw) > 500:
                    print("  %-34s -> %-14s %s (兜底扫描, %d bytes)"
                          % (a["name"], key, nm, len(raw)))
                    got = shrink(raw)
                    break
        if got:
            result[key] = base64.b64encode(got).decode("ascii")
        else:
            print("  %-34s 没取到图标" % a["name"])

    if not result:
        print("一个图标都没取到，保留原有图标")
        return 0

    lines = [
        "package com.xiuxian.script;",
        "",
        "import java.util.HashMap;",
        "",
        "/**",
        " * 构建时自动生成：图标直接用游戏 APK 里登记的真实启动图标（aapt dump badging）。",
        " * 这样安装包库显示的图标，和真正装上之后桌面上的图标完全一致。",
        " * 请勿手工编辑，改 tools/gen_icons.py。",
        " */",
        "public final class GenIcons {",
        "",
        "    private static final HashMap<String, String> M = new HashMap<String, String>();",
        "",
        "    static {",
    ]
    for k, v in sorted(result.items()):
        lines.append("        M.put(\"%s\"," % k)
        lines.append("            %s);" % java_literal(v))
    lines += [
        "    }",
        "",
        "    private GenIcons() {",
        "    }",
        "",
        "    public static String get(String key) {",
        "        return key == null ? null : M.get(key);",
        "    }",
        "}",
        "",
    ]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("已生成", OUT, "图标数:", len(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
