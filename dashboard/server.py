# -*- coding: utf-8 -*-
"""
修仙脚本工具 —— 本地应用后端（零依赖，仅标准库）

职责：
  - 托管静态前端（static/）
  - 列出修仙日常分步配置、模板缺口
  - 拉起 run.py 跑指定配置并回显日志
  - 读取 Tabbit 浏览器同步数据里的任务标题
  - 提供已接通的 GitHub 仓库信息

启动：python server.py
前端：浏览器打开 http://127.0.0.1:8765
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
STATIC_DIR = os.path.join(BASE_DIR, "static")

with open(CONFIG_PATH, "r", encoding="utf-8") as f:
    CONFIG = json.load(f)

XIUXIAN_ROOT = os.path.normpath(os.path.join(
    CONFIG["xiuxian"]["root"]))
CONFIG_DIR = os.path.join(XIUXIAN_ROOT, CONFIG["xiuxian"]["config_dir"])
TPL_DIR = os.path.join(XIUXIAN_ROOT, CONFIG["xiuxian"]["tpl_dir"])
RUNNER = os.path.join(XIUXIAN_ROOT, CONFIG["xiuxian"]["runner"])

# 运行中的任务：job_id -> 状态
JOBS = {}
JOB_LOCK = threading.Lock()
JOB_SEQ = 0


# ---------------------------------------------------------------- 配置扫描
def _available_templates():
    """返回 (stems, bases, filenames)，与 runner 的三级匹配对齐。"""
    stems, bases, files = set(), set(), []
    if os.path.isdir(TPL_DIR):
        for name in os.listdir(TPL_DIR):
            if name.lower().endswith(".png"):
                stem = os.path.splitext(name)[0]
                stems.add(stem)
                bases.add(stem.rsplit("_", 1)[0])
                files.append(name)
    return stems, bases, files


def _tpl_available(tpl, stems, bases, files):
    if tpl in stems or tpl in bases:
        return True
    return any(tpl in fn for fn in files)


def _scan_scripts():
    stems, bases, files = _available_templates()
    scripts = []
    if not os.path.isdir(CONFIG_DIR):
        return scripts
    for name in sorted(os.listdir(CONFIG_DIR)):
        if not name.lower().endswith(".config.json"):
            continue
        path = os.path.join(CONFIG_DIR, name)
        try:
            with open(path, "r", encoding="utf-8") as f:
                flow = json.load(f)
        except Exception as e:
            scripts.append({"file": name, "error": "配置解析失败: %s" % e})
            continue
        steps = flow.get("steps", [])
        pending = []
        used = set()
        for st in steps:
            tpl = st.get("tpl")
            if tpl:
                used.add(tpl)
                if not _tpl_available(tpl, stems, bases, files):
                    pending.append(tpl)
        scripts.append({
            "file": name,
            "name": flow.get("name", name),
            "steps": len(steps),
            "used_templates": sorted(used),
            "pending_templates": sorted(set(pending)),
            "note": (flow.get("_note") or "")[:400],
            "order_slot": flow.get("_order_slot", ""),
        })
    return scripts


# ---------------------------------------------------------------- Tabbit 任务解析
# 纯界面/菜单/营销噪声词，不作为任务项展示
NOISE = {
    "新标签页", "新对话", "入门指南", "邀请计划", "用量详情", "浏览器",
    "电影网站", "免费在线播放", "高清全集", "高清完整版", "金牌影院",
    "学生专区", "升级套餐", "级套餐", "动旗下", "分钟上手",
}
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
# 中文之后粘连的长字母数字串（base64 / 设备码）
TRAIL_TOKEN_RE = re.compile(r"[A-Za-z0-9]{8,}$")
# 乱码碎片：数字与生僻字混排
GARBAGE_RE = re.compile(r"[0-9][一-鿿]|[一-鿿][0-9]")


def _clean_title(raw):
    t = UUID_RE.sub("", raw)
    # 中文之后粘连的截断 hex / 设备码片段（含连字符）
    t = re.sub(r"[一-鿿]\s*[0-9a-f][0-9a-f\-]{5,}$", lambda m: m.group(0)[0], t)
    t = TRAIL_TOKEN_RE.sub("", t)
    # 反复剥离前导分隔符、数字、小写字母；单独大写字母后紧跟中文也去掉
    for _ in range(4):
        t = t.strip("-—·、 ")
        t = re.sub(r"^[0-9]+", "", t)
        t = re.sub(r"^[a-z]+", "", t)
        t = re.sub(r"^[A-Z](?=[一-鿿])", "", t)
    t = t.strip("-—·、 ")
    # 去掉末尾计数数字
    t = re.sub(r"[0-9]+$", "", t).strip()
    return t


def _read_leveldb_titles():
    sync_dir = CONFIG["tabbit"]["sync_leveldb"]
    titles = {}
    if not os.path.isdir(sync_dir):
        return []
    for fn in sorted(os.listdir(sync_dir)):
        p = os.path.join(sync_dir, fn)
        if not os.path.isfile(p):
            continue
        try:
            raw = open(p, "rb").read()
        except (PermissionError, OSError):
            continue
        # 实测可读中文标题只在 UTF-8 段，UTF-16 解码会产生乱码，故只解 UTF-8
        text = raw.decode("utf-8", errors="ignore")
        for m in re.findall(r"[一-鿿A-Za-z0-9（）()·、\-:：]{4,}", text):
            if not re.search(r"[一-鿿]", m):
                continue
            t = _clean_title(m)
            if len(re.findall(r"[一-鿿]", t)) < 2:
                continue
            if t in NOISE:
                continue
            if GARBAGE_RE.search(t) and len(t) <= 6:
                continue
            titles[t] = titles.get(t, 0) + 1
    return [{"title": t, "hits": c} for t, c in sorted(titles.items(), key=lambda x: (-x[1], x[0]))]


# ---------------------------------------------------------------- 设备状态
ADB = r"D:\A-软件\雷电模拟器\安卓9安装包\leidian\LDPlayer9\adb.exe"
DEVICE = "emulator-5554"


def _device_status():
    status = {
        "accessibility": "已连接",
        "script": "待机",
        "screen": "540 × 960",
        "account": "已勾选 1 / 共 1 个（只会跑勾选的）",
        "overlay": "已授予",
        "android": "9",
        "log_path": "/storage/emulated/0/Android/data/com.xiuxian.script/files/logs",
        "game": "天途 (com.tt.spgw.qg)",
        "game_installed": True,
        "game_version": "天途 · 1.0.4",
        "game_package": "com.tt.spgw.qg",
        "adb_ok": os.path.isfile(ADB),
    }
    sel = _load_selection()
    if sel.get("package"):
        status["game"] = "%s (%s)" % (sel.get("name") or sel["package"], sel["package"])
        status["game_package"] = sel["package"]
        if sel.get("version"):
            status["game_version"] = "%s · %s" % (
                sel.get("name") or sel["package"], sel["version"])
    # 账号计数：取当前选中游戏名下已保存的账号
    acc = _load_accounts().get("games", {})
    game_key = sel.get("package") or "com.tt.spgw.qg"
    rows = acc.get(game_key) or []
    if rows:
        checked = len([a for a in rows if a.get("checked", True)])
        status["account"] = "已勾选 %d / 共 %d 个（只会跑勾选的）" % (checked, len(rows))
    elif acc:
        status["account"] = "当前游戏未配置账号（共 %d 个游戏有记录）" % len(acc)
    try:
        out = subprocess.run([ADB, "-s", DEVICE, "shell", "wm", "size"],
                              capture_output=True, timeout=8, stdin=subprocess.DEVNULL)
        m = re.search(r"(\d+)x(\d+)", out.stdout.decode("utf-8", "ignore"))
        if m:
            status["screen"] = "%s × %s" % (m.group(1), m.group(2))
    except Exception:
        pass
    return status


# ---------------------------------------------------------------- 数据文件
DATA_DIR = os.path.join(BASE_DIR, "data")


def _read_data(name, default):
    """读取 data/ 下的 JSON。缺失或损坏时返回 default，并带上状态标记。"""
    path = os.path.join(DATA_DIR, name)
    info = {"_missing": False, "_file": name, "data": default, "_error": ""}
    if not os.path.isfile(path):
        info["_missing"] = True
        return info
    try:
        with open(path, "r", encoding="utf-8") as f:
            info["data"] = json.load(f)
    except Exception as e:
        info["_error"] = str(e)
    return info


def _write_data(name, obj):
    """原子写入 data/ 下的 JSON（先写临时文件再替换）。"""
    os.makedirs(DATA_DIR, exist_ok=True)
    path = os.path.join(DATA_DIR, name)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def _size_text(n):
    try:
        n = float(n)
    except (TypeError, ValueError):
        return "-"
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return ("%d %s" % (n, unit)) if unit == "B" else ("%.1f %s" % (n, unit))
        n /= 1024.0
    return "-"


# ---------------------------------------------------------------- ADB 辅助
def _adb(*args, **kw):
    """执行 adb 命令；ADB 或设备缺失时返回 (False, 错误说明)，不抛异常。"""
    timeout = kw.get("timeout", 10)
    if not os.path.isfile(ADB):
        return False, "未找到 ADB：%s" % ADB
    try:
        out = subprocess.run(
            [ADB, "-s", DEVICE] + list(args),
            capture_output=True, timeout=timeout, stdin=subprocess.DEVNULL)
    except Exception as e:
        return False, "ADB 执行失败：%s" % e
    text = out.stdout.decode("utf-8", "ignore")
    if out.returncode != 0 and not text.strip():
        err = out.stderr.decode("utf-8", "ignore").strip()
        return False, "ADB 返回错误：%s" % (err or "returncode=%s" % out.returncode)
    return True, text


# 已知游戏包名 -> 展示名（与安卓端安装包库同一批游戏）
GAME_LABELS = {
    "com.tt.spgw.qg": "天途",
}
SCRIPT_PKG = "com.xiuxian.script"


def _scan_games():
    """扫描模拟器已安装的三方应用，标出已知游戏。"""
    ok, text = _adb("shell", "pm", "list", "packages", "-3")
    if not ok:
        return {"ok": False, "error": text, "games": [],
                "hint": "需要雷电模拟器在线（adb devices 可见 emulator-5554）；本地 APK 可直接看「安装包库」"}
    pkgs = sorted({ln.replace("package:", "").strip()
                   for ln in text.splitlines() if ln.startswith("package:")})
    games = []
    for p in pkgs:
        if p == SCRIPT_PKG:
            continue
        label = GAME_LABELS.get(p)
        games.append({"package": p, "name": label or p, "known": bool(label)})
    sel = _load_selection()
    return {"ok": True, "error": "", "games": games, "hint": "",
            "selected": sel.get("package", "")}


def _load_selection():
    info = _read_data("selection.json", {})
    data = info["data"] if isinstance(info["data"], dict) else {}
    return data


def _load_accounts():
    info = _read_data("accounts.json", {"games": {}})
    data = info["data"] if isinstance(info["data"], dict) else {"games": {}}
    if not isinstance(data.get("games"), dict):
        data["games"] = {}
    return data


# ---------------------------------------------------------------- 安装包库
APK_RE = re.compile(r"^(?P<game>[^\\-]+)-(?P<channel>[^\\-]+)-(?P<ver>[0-9][0-9A-Za-z._]*)$")


def _apk_dir():
    rel = (CONFIG.get("xiuxian", {}) or {}).get("apk_dir") or "安装包库"
    path = rel if os.path.isabs(rel) else os.path.join(XIUXIAN_ROOT, rel)
    return os.path.normpath(path)


def _scan_apk_library():
    """扫描本地安装包目录，按 游戏-渠道-版本.apk 解析文件名。"""
    d = _apk_dir()
    if not os.path.isdir(d):
        return {"ok": False, "dir": d, "items": [], "games": [],
                "error": "安装包目录不存在：%s" % d}
    items = []
    for name in sorted(os.listdir(d)):
        if not name.lower().endswith(".apk"):
            continue
        path = os.path.join(d, name)
        if not os.path.isfile(path):
            continue
        stem = os.path.splitext(name)[0]
        m = APK_RE.match(stem)
        if m:
            game, channel, ver = m.group("game"), m.group("channel"), m.group("ver")
        else:
            game, channel, ver = stem, "", ""
        try:
            st = os.stat(path)
            size = st.st_size
            mtime = time.strftime("%Y-%m-%d %H:%M", time.localtime(st.st_mtime))
        except OSError:
            size, mtime = 0, ""
        items.append({
            "file": name, "game": game, "channel": channel or "默认",
            "version": ver or "-", "size": size,
            "size_text": _size_text(size), "mtime": mtime,
        })
    items.sort(key=lambda x: x["mtime"], reverse=True)
    items.sort(key=lambda x: (x["game"], x["channel"]))
    games = sorted({i["game"] for i in items})
    return {"ok": True, "dir": d, "items": items, "games": games, "error": ""}


# ---------------------------------------------------------------- 仙榜 / 活动
def _rank_view():
    """仙榜：按榜单汇总，entries 为空表示尚未采集到游戏内数据。"""
    info = _read_data("rank.json", {})
    data = info["data"] if isinstance(info["data"], dict) else {}
    boards = []
    for b in (data.get("boards") or []):
        if not isinstance(b, dict):
            continue
        bb = dict(b)
        bb["entries"] = bb.get("entries") or []
        bb["ready"] = bool(bb["entries"])
        boards.append(bb)
    return {
        "boards": boards,
        "source": data.get("source", ""),
        "note": data.get("note", ""),
        "updated": data.get("updated", ""),
        "missing": info["_missing"],
        "error": info["_error"],
    }


def _activity_view():
    """活动：进行中/未开始/已结束分组由 start/end 日期推导。"""
    info = _read_data("activity.json", {})
    data = info["data"] if isinstance(info["data"], dict) else {}
    today = time.strftime("%Y-%m-%d")
    acts = []
    for a in (data.get("activities") or []):
        if not isinstance(a, dict):
            continue
        aa = dict(a)
        start, end = aa.get("start") or "", aa.get("end") or ""
        if end and end < today:
            state = "ended"
        elif start and start > today:
            state = "upcoming"
        else:
            state = "running"
        aa["state"] = state
        aa["tasks"] = aa.get("tasks") or []
        acts.append(aa)
    order = {"running": 0, "upcoming": 1, "ended": 2}
    acts.sort(key=lambda x: (order.get(x["state"], 3), x.get("end") or ""))
    return {
        "activities": acts,
        "updated": data.get("updated", ""),
        "note": data.get("note", ""),
        "today": today,
        "missing": info["_missing"],
        "error": info["_error"],
    }


# ---------------------------------------------------------------- 任务拉起
def _run_job(job_id, rel_config, loops, speed, probe, shot):
    job = JOBS[job_id]
    cmd = [sys.executable, RUNNER, rel_config]
    if loops and loops > 1:
        cmd += ["--loops", str(loops)]
    if speed and abs(speed - 1.0) > 1e-6:
        cmd += ["--speed", str(speed)]
    if probe:
        cmd += ["--probe", probe]
    if shot:
        cmd += ["--shot"]
    job["cmd"] = " ".join(cmd)
    try:
        proc = subprocess.Popen(
            cmd, cwd=XIUXIAN_ROOT,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL, text=True, encoding="utf-8", errors="ignore",
            bufsize=1,
        )
        job["pid"] = proc.pid
        for line in proc.stdout:
            line = line.rstrip("\n")
            job["log"].append(line)
            if len(job["log"]) > 3000:
                job["log"].pop(0)
        rc = proc.wait()
        job["returncode"] = rc
        job["status"] = "finished" if rc == 0 else "error"
    except Exception as e:
        job["log"].append("启动失败: %s" % e)
        job["status"] = "error"
    finally:
        job["finished_at"] = time.strftime("%H:%M:%S")


def start_job(rel_config, loops, speed, probe, shot):
    global JOB_SEQ
    with JOB_LOCK:
        JOB_SEQ += 1
        job_id = "job-%d" % JOB_SEQ
        JOBS[job_id] = {
            "id": job_id, "status": "running", "config": rel_config,
            "log": [], "started_at": time.strftime("%H:%M:%S"),
            "finished_at": None, "returncode": None, "pid": None, "cmd": "",
        }
    t = threading.Thread(target=_run_job, args=(job_id, rel_config, loops, speed, probe, shot), daemon=True)
    t.start()
    return job_id


# ---------------------------------------------------------------- HTTP 处理
class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("[%s] %s\n" % (self.address_string(), fmt % args))

    def _send_json(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_static(self, rel):
        rel = rel.lstrip("/")
        if rel == "":
            rel = "index.html"
        path = os.path.normpath(os.path.join(STATIC_DIR, rel))
        if not path.startswith(STATIC_DIR):
            self.send_error(403)
            return
        if not os.path.isfile(path):
            self.send_error(404)
            return
        ext = os.path.splitext(path)[1].lower()
        ctype = {
            ".html": "text/html; charset=utf-8",
            ".css": "text/css; charset=utf-8",
            ".js": "application/javascript; charset=utf-8",
            ".png": "image/png",
            ".svg": "image/svg+xml",
        }.get(ext, "application/octet-stream")
        with open(path, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/status":
            self._send_json(_device_status())
            return
        if path == "/api/scripts":
            self._send_json({"scripts": _scan_scripts()})
            return
        if path == "/api/tasks":
            self._send_json({"tasks": _read_leveldb_titles()})
            return
        if path == "/api/project-tasks":
            p = os.path.join(BASE_DIR, "data", "project_tasks.json")
            try:
                with open(p, "r", encoding="utf-8") as f:
                    self._send_json(json.load(f))
            except Exception as e:
                self._send_json({"tasks": [], "error": str(e)})
            return
        if path == "/api/rank":
            self._send_json(_rank_view())
            return
        if path == "/api/activity":
            self._send_json(_activity_view())
            return
        if path == "/api/packages":
            self._send_json(_scan_apk_library())
            return
        if path == "/api/games":
            self._send_json(_scan_games())
            return
        if path == "/api/accounts":
            acc = _load_accounts()
            sel = _load_selection()
            self._send_json({
                "games": acc.get("games", {}),
                "updated": acc.get("updated", ""),
                "selected": sel.get("package", ""),
            })
            return
        if path == "/api/github":
            self._send_json({"github": CONFIG["github"]})
            return
        if path.startswith("/api/run/"):
            job_id = path.rsplit("/", 1)[-1]
            job = JOBS.get(job_id)
            if not job:
                self._send_json({"error": "任务不存在"}, 404)
                return
            self._send_json({
                "id": job["id"], "status": job["status"], "config": job["config"],
                "cmd": job["cmd"], "pid": job["pid"],
                "started_at": job["started_at"], "finished_at": job["finished_at"],
                "returncode": job["returncode"], "log": job["log"],
            })
            return
        self._send_static(path)

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/api/run":
            try:
                length = int(self.headers.get("Content-Length", 0))
                payload = json.loads(self.rfile.read(length) or b"{}")
            except Exception as e:
                self._send_json({"error": "请求体解析失败: %s" % e}, 400)
                return
            rel_config = payload.get("config", "")
            if not rel_config or not rel_config.endswith(".config.json"):
                self._send_json({"error": "缺少配置文件名"}, 400)
                return
            abs_cfg = os.path.normpath(os.path.join(CONFIG_DIR, rel_config))
            if not abs_cfg.startswith(CONFIG_DIR) or not os.path.isfile(abs_cfg):
                self._send_json({"error": "配置文件不存在"}, 400)
                return
            loops = int(payload.get("loops", 1) or 1)
            speed = float(payload.get("speed", 1.0) or 1.0)
            probe = payload.get("probe") or None
            shot = bool(payload.get("shot", False))
            job_id = start_job(abs_cfg, loops, speed, probe, shot)
            self._send_json({"job_id": job_id})
            return
        if path == "/api/game/select":
            try:
                length = int(self.headers.get("Content-Length", 0))
                payload = json.loads(self.rfile.read(length) or b"{}")
            except Exception as e:
                self._send_json({"error": "请求体解析失败: %s" % e}, 400)
                return
            pkg = (payload.get("package") or "").strip()
            if not pkg:
                self._send_json({"error": "缺少游戏包名"}, 400)
                return
            name = (payload.get("name") or GAME_LABELS.get(pkg) or pkg).strip()
            version = (payload.get("version") or "").strip()
            _write_data("selection.json", {
                "package": pkg, "name": name, "version": version,
                "updated": time.strftime("%Y-%m-%d %H:%M:%S"),
            })
            self._send_json({"ok": True, "package": pkg, "name": name, "version": version})
            return
        if path == "/api/accounts":
            try:
                length = int(self.headers.get("Content-Length", 0))
                payload = json.loads(self.rfile.read(length) or b"{}")
            except Exception as e:
                self._send_json({"error": "请求体解析失败: %s" % e}, 400)
                return
            game = (payload.get("game") or "").strip()
            accounts = payload.get("accounts")
            if not game or not isinstance(accounts, list):
                self._send_json({"error": "缺少游戏或账号列表"}, 400)
                return
            clean = []
            for a in accounts:
                if not isinstance(a, dict):
                    continue
                user = str(a.get("user", "")).strip()
                if not user:
                    continue
                clean.append({
                    "user": user,
                    "pass": str(a.get("pass", "")),
                    "server": str(a.get("server", "")).strip(),
                    "checked": bool(a.get("checked", True)),
                })
            acc = _load_accounts()
            acc.setdefault("games", {})[game] = clean
            acc["updated"] = time.strftime("%Y-%m-%d %H:%M:%S")
            _write_data("accounts.json", acc)
            checked = len([a for a in clean if a["checked"]])
            self._send_json({"ok": True, "game": game,
                             "count": len(clean), "checked": checked})
            return
        self.send_error(404)


def main():
    host = CONFIG["server"]["host"]
    port = CONFIG["server"]["port"]
    server = ThreadingHTTPServer((host, port), Handler)
    print("修仙脚本工具本地后端已启动: http://%s:%d" % (host, port))
    print("修仙根目录: %s" % XIUXIAN_ROOT)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n退出")


if __name__ == "__main__":
    main()
