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
    }
    try:
        out = subprocess.run([ADB, "-s", DEVICE, "shell", "wm", "size"],
                              capture_output=True, timeout=8, stdin=subprocess.DEVNULL)
        m = re.search(r"(\d+)x(\d+)", out.stdout.decode("utf-8", "ignore"))
        if m:
            status["screen"] = "%s × %s" % (m.group(1), m.group(2))
    except Exception:
        pass
    return status


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
