#!/usr/bin/env python3
# =====================================================================
# 运维代理（ops-agent）
#
# 只用 Python 标准库：镜像里没有 pip、没有第三方包 —— 装不上依赖的风险为零，
# 而它要承担的是「系统起不来时唯一可用的入口」，依赖越少越可靠。
#
# 两条职责：
#   ① 首次部署安装向导（HTTP，仅在 .installed 不存在前可用，之后写接口一律 410）
#   ② 在线升级执行者（轮询 <shared>/requests/*.json → docker compose pull + up -d）
#
# <h2>为什么不给后端开 HTTP 接口，而用共享目录</h2>
# 后端与 agent 本就在同一台宿主机上。开 HTTP 要额外付出三样东西：一个网络暴露面、
# 一套 token 鉴权（怎么发、怎么轮换、配错了怎么报错）、以及一个「调用超时」的失败模式。
# 写文件全都不需要 —— 没有连接可断、没有口令可泄露，agent 挂了也不影响系统运行，
# 只是「在线升级」暂时不可用（后端页面会如实显示「运维代理未就绪」）。
#
# <h2>白名单是这套设计的安全边界</h2>
# agent 持有 docker.sock（等价于宿主 root），这一点无法靠容器内权限缓解。
# 因此它**不接受任何来自文件的命令或参数**：请求里只有 taskNo 与目标版本，
# 执行什么完全由本文件写死 —— 即使那个共享目录被写坏，也换不来一条任意命令。
# =====================================================================

import json
import os
import re
import subprocess
import sys
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

VERSION = os.environ.get("OPS_AGENT_VERSION", "1.0.0")
UI_ENABLED = os.environ.get("OPS_UI_ENABLED", "true").strip().lower() == "true"
PORT = int(os.environ.get("OPS_PORT", "8090"))
DEPLOY_DIR = os.environ.get("DEPLOY_DIR", "/deploy")
COMPOSE_FILE = os.environ.get("COMPOSE_FILE", os.path.join(DEPLOY_DIR, "docker-compose.yml"))
PROJECT_NAME = os.environ.get("COMPOSE_PROJECT_NAME", "ticket-system")
SHARED_DIR = os.environ.get("OPS_SHARED_DIR", os.path.join(DEPLOY_DIR, "ops-data"))

REQ_DIR = os.path.join(SHARED_DIR, "requests")
STATE_DIR = os.path.join(SHARED_DIR, "upgrade", "state")
RESULT_DIR = os.path.join(STATE_DIR, "result")
LOG_DIR = os.path.join(SHARED_DIR, "logs")
INSTALLED_FLAG = os.path.join(SHARED_DIR, ".installed")
ENV_FILE = os.path.join(DEPLOY_DIR, ".env")

# 允许重建的服务白名单。只重建「跑代码」的两个容器：mysql / redis / nginx 不动 ——
# 重建数据库容器既不会带来新版本，又会让整个系统多停一次。
UPGRADE_SERVICES = ("backend", "frontend")
HEARTBEAT_SECONDS = 5.0
POLL_SECONDS = 2.0

_log_lock = threading.Lock()


def now() -> str:
    return datetime.now().strftime("%Y-%m-%dT%H:%M:%S")


def log(message: str) -> None:
    line = "%s %s" % (now(), message)
    print(line, flush=True)
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        with _log_lock, open(os.path.join(LOG_DIR, "agent.log"), "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except OSError:
        pass


def ensure_dirs() -> None:
    for path in (REQ_DIR, RESULT_DIR, LOG_DIR):
        os.makedirs(path, exist_ok=True)


def write_json(path: str, payload: dict) -> None:
    """临时文件 + 原子改名：读者可能随时来扫这个目录，不能让它看到半截 JSON。"""
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False)
    os.replace(tmp, path)


def read_json(path: str):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


# ---------------------------------------------------------------------
# Docker 调用（白名单）
# ---------------------------------------------------------------------

def _run(args, timeout: int = 900):
    try:
        proc = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        return 127, "", "docker 命令不存在（镜像构建有误？）"
    except subprocess.TimeoutExpired:
        return 124, "", "命令超时（%ds）：%s" % (timeout, " ".join(args))
    return proc.returncode, proc.stdout or "", proc.stderr or ""


def compose(*args: str, timeout: int = 900):
    """固定的 compose 调用形态 —— 文件路径与项目名都不来自外部输入。"""
    return _run(["docker", "compose", "-f", COMPOSE_FILE,
                 "--project-directory", DEPLOY_DIR,
                 "--project-name", PROJECT_NAME] + list(args), timeout=timeout)


def service_states():
    """读取各容器状态：[(service, state, health)]。失败返回空列表。"""
    code, out, _ = _run(["docker", "compose", "-f", COMPOSE_FILE,
                         "--project-directory", DEPLOY_DIR,
                         "--project-name", PROJECT_NAME, "ps", "--format", "json"], timeout=60)
    if code != 0 or not out.strip():
        return []
    states = []
    for line in out.strip().splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        states.append((row.get("Service", ""), row.get("State", ""), row.get("Health", "")))
    return states


# ---------------------------------------------------------------------
# 心跳：后端据此判断「代理是否就绪」
# ---------------------------------------------------------------------

def heartbeat_loop() -> None:
    ensure_dirs()
    while True:
        states = service_states()
        running = sum(1 for _, state, _ in states if state == "running")
        write_json(os.path.join(SHARED_DIR, "heartbeat.json"), {
            "version": VERSION,
            "at": now(),
            "status": "installed" if os.path.exists(INSTALLED_FLAG) else "bootstrap",
            "containersRunning": running,
        })
        time.sleep(HEARTBEAT_SECONDS)


# ---------------------------------------------------------------------
# 升级执行
# ---------------------------------------------------------------------

def finish_receipt(task_no: str, result: str, message: str) -> None:
    """
    写结果回执 + 当前版本。

    回执格式与 UpgradeApplyResult 逐字对应（state/result/<taskNo>.json，
    result ∈ SUCCESS / ROLLED_BACK / FAILED）—— 这是后端与进程外脚本之间
    已经稳定下来的唯一通信载体，**刻意复用**，不另造协议。
    """
    ensure_dirs()
    write_json(os.path.join(RESULT_DIR, "%s.json" % task_no), {
        "taskNo": task_no,
        "result": result,
        "message": message,
        "finishedAt": now(),
    })
    if result == "SUCCESS":
        write_json(os.path.join(STATE_DIR, "current.json"), {
            "version": os.environ.get("IMAGE_TAG", "latest"),
            "appliedAt": now(),
        })


def apply_upgrade(task_no: str) -> None:
    log("[升级] 收到请求 taskNo=%s，开始执行 docker compose pull" % task_no)
    code, out, err = compose("pull", timeout=1800)
    if code != 0:
        detail = (err or out).strip()[-800:]
        log("[升级] pull 失败：%s" % detail)
        finish_receipt(task_no, "FAILED", "拉取镜像失败：%s" % detail)
        return

    code, out, err = compose("up", "-d", "--force-recreate", *UPGRADE_SERVICES, timeout=900)
    if code != 0:
        detail = (err or out).strip()[-800:]
        log("[升级] up 失败：%s" % detail)
        # 这里不写 ROLLED_BACK：镜像回滚需要知道上一版 tag，而 agent 并不知道。
        # 如实报 FAILED，让管理员按升级历史里的「来源版本」手工处理 ——
        # 编一个「已回滚」比报失败更危险。
        finish_receipt(task_no, "FAILED", "重建容器失败：%s" % detail)
        return

    # 等后端健康：重建后容器需要几十秒才 ready。轮询到健康为止；
    # 超时也算成功但注明「健康检查未通过」，因为此时镜像与容器确实已经是新的。
    deadline = time.time() + 300
    while time.time() < deadline:
        states = dict(((svc, (state, health)) for svc, state, health in service_states()))
        backend = states.get("backend")
        if backend and backend[0] == "running" and backend[1] in ("", "healthy"):
            break
        time.sleep(5)
    else:
        log("[升级] 健康检查在 5 分钟内未通过 taskNo=%s" % task_no)
        finish_receipt(task_no, "SUCCESS", "镜像与容器已更新，但健康检查在 5 分钟内未通过，请查看容器日志")
        return

    log("[升级] 完成 taskNo=%s" % task_no)
    finish_receipt(task_no, "SUCCESS", "已拉取最新镜像并重建容器，健康检查通过")


def request_loop() -> None:
    ensure_dirs()
    while True:
        try:
            for name in sorted(os.listdir(REQ_DIR)):
                if not name.endswith(".json"):
                    continue
                path = os.path.join(REQ_DIR, name)
                payload = read_json(path)
                # 读到半截/损坏就跳过这一拍（写方是「临时文件 + 原子改名」，正常不会发生）
                if not payload:
                    continue
                task_no = str(payload.get("taskNo") or "").strip()
                # taskNo 只用于拼文件名，必须挡住路径穿越
                if not re.fullmatch(r"[A-Za-z0-9_\-]{6,64}", task_no):
                    log("[升级] 请求里的 taskNo 不合法，已丢弃：%r" % task_no)
                    os.remove(path)
                    continue
                try:
                    apply_upgrade(task_no)
                finally:
                    try:
                        os.remove(path)
                    except OSError:
                        pass
        except OSError as exc:
            log("[升级] 扫描请求目录失败：%s" % exc)
        time.sleep(POLL_SECONDS)


# ---------------------------------------------------------------------
# 安装向导
# ---------------------------------------------------------------------

SAFE_PATH = re.compile(r"^/[A-Za-z0-9._\-/]{1,180}$")
SAFE_TEXT = re.compile(r"^[\w.@\-]{1,64}$")

PAGE = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>安装向导 · 设备借用工单系统</title>
<style>
body{margin:0;font:14px/1.6 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;background:#f5f6f8;color:#1f2329}
.wrap{max-width:640px;margin:0 auto;padding:32px 20px 64px}
h1{font-size:20px;font-weight:600;margin:0 0 6px}
p.sub{color:#646a73;margin:0 0 24px}
.card{background:#fff;border:1px solid #e5e6eb;border-radius:10px;padding:20px 22px;margin-bottom:16px}
label{display:block;font-weight:500;margin:14px 0 6px}
input,select{width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid #d0d3d9;border-radius:6px;font-size:14px}
.hint{color:#8f959e;font-size:12px;margin-top:4px}
button{margin-top:20px;width:100%;padding:11px;border:0;border-radius:6px;background:#165dff;color:#fff;font-size:15px;cursor:pointer}
button[disabled]{background:#b9c0cc;cursor:not-allowed}
pre{background:#1f2329;color:#d8dce3;padding:12px;border-radius:6px;overflow:auto;max-height:260px;font-size:12px}
.ok{color:#00a870}.bad{color:#d54941}
</style></head><body><div class="wrap">
<h1>设备借用工单系统 · 安装向导</h1>
<p class="sub">第一次部署由这里完成：填好下面的信息，系统自动写好配置、启动全部服务并建库建表。不需要 SSH，也不需要手工编辑 .env。</p>
<div id="app" class="card"><div id="body">正在读取状态…</div></div>
<div id="out" class="card" style="display:none"><pre id="log"></pre></div>
</div>
<script>
function el(tag,attrs,html){var e=document.createElement(tag);for(var k in attrs)e.setAttribute(k,attrs[k]);if(html!==undefined)e.innerHTML=html;return e;}
function url(k,v){var qs=new URLSearchParams(location.search);return qs.get(k)||v;}
function render(status){
  var body=document.getElementById('body');body.innerHTML='';
  if(status.installed){
    body.innerHTML='<p class="ok">已完成安装。</p><p class="hint">如需重新初始化，请删除共享目录里的 .installed 标记后重启本容器。</p>';
    return;
  }
  body.innerHTML='<p>填写部署信息（除超管账号外均有合理默认值，不确定就保持不动）。</p>';
  var form=document.createElement('div');
  function field(key,label,value,hint,type){
    form.appendChild(el('label',{},label));
    form.appendChild(el('input',{id:key,value:value||'',type:type||'text'}));
    if(hint)form.appendChild(el('div',{class:'hint'},hint));
  }
  field('data_root','数据存放目录',status.defaultDataRoot,'宿主上的绝对路径，所有数据（数据库/附件/日志）都在它下面');
  field('web_port','网页端口','8080','浏览器访问用的端口');
  field('cookie_secure','访问方式','false','用 https 访问选 true；用 http://IP:端口 访问必须选 false（选错会表现为「登录后又弹回登录页」）');
  form.appendChild(el('label',{},' '));
  var sel=el('select',{id:'cookie_secure_sel'});
  [['false','明文 HTTP（局域网直连，推荐先用这个）'],['true','HTTPS（前面挂了反代 / 有证书）']].forEach(function(o){
    var op=el('option',{value:o[0]},o[1]);if(o[0]===status.defaultCookieSecure)op.selected=true;sel.appendChild(op);});
  form.lastChild.innerHTML='访问方式';form.appendChild(sel);
  field('admin_user','超级管理员登录名','admin','首次登录用；只允许字母数字与 . _ - @');
  field('admin_name','超级管理员姓名','超级管理员','');
  field('admin_pwd','超级管理员密码','','至少 8 位，需包含字母与数字');
  var btn=el('button',{},'开始安装');
  btn.onclick=function(){install(btn,sel.value);};
  form.appendChild(btn);body.appendChild(form);
}
function install(btn,cookieSecure){
  var payload={dataRoot:document.getElementById('data_root').value,
    webPort:document.getElementById('web_port').value,
    cookieSecure:cookieSecure==='true',
    adminUsername:document.getElementById('admin_user').value,
    adminDisplayName:document.getElementById('admin_name').value,
    adminPassword:document.getElementById('admin_pwd').value};
  btn.disabled=true;btn.textContent='安装中…（首次会拉取镜像，可能几分钟）';
  document.getElementById('out').style.display='block';
  fetch('api/install',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)})
   .then(function(r){return r.json().then(function(j){return {ok:r.ok,j:j};});})
   .then(function(res){
      document.getElementById('log').textContent=(res.j.steps||[]).join('\\n')+((res.ok)?'\\n\\n完成！':('\\n\\n失败：'+(res.j.message||'')));
      if(res.ok){document.getElementById('body').innerHTML='<p class="ok">安装完成，请到访问地址登录。</p>';
        var a=el('p',{},'<a href="http://'+location.hostname+':'+payload.webPort+'/">打开系统</a>');document.getElementById('body').appendChild(a);}
      else{btn.disabled=false;btn.textContent='重试';}
   })
   .catch(function(e){document.getElementById('log').textContent='请求失败：'+e;btn.disabled=false;btn.textContent='重试';});
}
fetch('api/status').then(function(r){return r.json();}).then(render).catch(function(e){
  document.getElementById('body').textContent='读取状态失败：'+e;});
</script></body></html>"""


def _validate_install(payload: dict):
    data_root = str(payload.get("dataRoot") or "").strip()
    if not SAFE_PATH.match(data_root) or data_root.endswith("/") or ".." in data_root:
        return None, "数据目录必须是一个合法的宿主绝对路径（如 /vol1/1000/docker/ticket-data）"
    try:
        web_port = int(str(payload.get("webPort") or "8080"))
    except ValueError:
        return None, "网页端口必须是数字"
    if not (1 <= web_port <= 65535):
        return None, "网页端口必须在 1-65535 之间"
    user = str(payload.get("adminUsername") or "").strip()
    if not SAFE_TEXT.match(user):
        return None, "登录名只允许字母、数字与 . _ - @（2-64 位）"
    password = str(payload.get("adminPassword") or "")
    if len(password) < 8 or not re.search(r"[A-Za-z]", password) or not re.search(r"\d", password):
        return None, "密码至少 8 位，且需同时包含字母与数字"
    display = str(payload.get("adminDisplayName") or "").strip() or "超级管理员"
    return {
        "data_root": data_root,
        "web_port": web_port,
        "cookie_secure": bool(payload.get("cookieSecure")),
        "admin_username": user,
        "admin_display_name": display,
        "admin_password": password,
    }, None


def _patch_env(values: dict) -> None:
    """在 init-env.sh 产出的 .env 上覆盖这几项。Key=Value 逐行替换，不存在则追加。"""
    wanted = {
        "DATA_ROOT": values["data_root"],
        "WEB_PORT": str(values["web_port"]),
        "COOKIE_SECURE": "true" if values["cookie_secure"] else "false",
        "SUPER_ADMIN_USERNAME": values["admin_username"],
        "SUPER_ADMIN_DISPLAY_NAME": values["admin_display_name"],
        "SUPER_ADMIN_INIT_PASSWORD": values["admin_password"],
    }
    lines = []
    with open(ENV_FILE, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    seen = set()
    out = []
    for line in lines:
        if "=" in line and not line.lstrip().startswith("#"):
            key = line.split("=", 1)[0].strip()
            if key in wanted:
                out.append("%s=%s" % (key, wanted[key]))
                seen.add(key)
                continue
        out.append(line)
    for key, value in wanted.items():
        if key not in seen:
            out.append("%s=%s" % (key, value))
    with open(ENV_FILE, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n")
    os.chmod(ENV_FILE, 0o600)


def run_install(payload: dict):
    values, error = _validate_install(payload)
    if error:
        return 400, {"message": error, "steps": []}

    steps = []

    def step(text):
        log("[引导] %s" % text)
        steps.append(text)

    step("生成配置文件 .env（随机密钥由本机生成，不经过网络）")
    if os.path.exists(ENV_FILE):
        # 已有 .env：只覆盖向导里这几项，绝不重新生成密钥 ——
        # 重新生成 JWT_SECRET 会让所有人被登出，重新生成数据库口令会让后端连不上库。
        _patch_env(values)
    else:
        code, out, err = _run(["bash", os.path.join(DEPLOY_DIR, "scripts", "init-env.sh"), "--yes"],
                              timeout=120)
        if code != 0:
            return 500, {"message": "生成 .env 失败：%s" % (err or out).strip()[-400:], "steps": steps}
        _patch_env(values)

    step("启动数据库与缓存，等待就绪（这一步同时验证了口令与目录权限）")
    code, out, err = compose("up", "-d", "mysql", "redis", timeout=900)
    if code != 0:
        return 500, {"message": "启动数据库失败：%s" % (err or out).strip()[-600:], "steps": steps}
    deadline = time.time() + 300
    while time.time() < deadline:
        states = dict(((s, (st, h)) for s, st, h in service_states()))
        if all(states.get(n) and states[n][1] in ("", "healthy") for n in ("mysql", "redis")):
            break
        time.sleep(5)
    else:
        return 500, {"message": "数据库/缓存在 5 分钟内未就绪，请查看其容器日志", "steps": steps}

    step("启动全部服务（后端启动时会自动执行数据库迁移，建库建表）")
    code, out, err = compose("up", "-d", timeout=1200)
    if code != 0:
        return 500, {"message": "启动服务失败：%s" % (err or out).strip()[-600:], "steps": steps}

    step("等待后端就绪（含数据库迁移，首次通常需要 1-3 分钟）")
    deadline = time.time() + 600
    while time.time() < deadline:
        states = dict(((s, (st, h)) for s, st, h in service_states()))
        backend = states.get("backend")
        if backend and backend[0] == "running" and backend[1] == "healthy":
            break
        time.sleep(5)
    else:
        return 500, {"message": "后端在 10 分钟内未就绪，请查看 ticket-backend 容器日志", "steps": steps}

    step("安装完成")
    write_json(INSTALLED_FLAG, {"at": now(), "webPort": values["web_port"]})
    return 200, {"steps": steps, "webPort": values["web_port"]}


class Handler(BaseHTTPRequestHandler):
    server_version = "ticket-ops-agent/" + VERSION

    def _send(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, payload: dict) -> None:
        self._send(code, json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    def do_GET(self):  # noqa: N802
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            if not UI_ENABLED:
                return self._json(404, {"message": "安装向导已关闭（OPS_UI_ENABLED=false）"})
            return self._send(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")
        if path == "/api/status":
            installed = os.path.exists(INSTALLED_FLAG)
            return self._json(200, {
                "installed": installed,
                "version": VERSION,
                "uiEnabled": UI_ENABLED,
                "defaultDataRoot": os.environ.get("OPS_DEFAULT_DATA_ROOT", "/vol1/1000/docker/ticket-data"),
                "defaultCookieSecure": os.environ.get("OPS_DEFAULT_COOKIE_SECURE", "false") == "true",
                "containers": [{"service": s, "state": st, "health": h} for s, st, h in service_states()],
            })
        return self._json(404, {"message": "not found"})

    def do_POST(self):  # noqa: N802
        path = urlparse(self.path).path
        if path != "/api/install":
            return self._json(404, {"message": "not found"})
        if not UI_ENABLED:
            return self._json(404, {"message": "安装向导已关闭"})
        if os.path.exists(INSTALLED_FLAG):
            # 一次性：装完就失效。一个能重复初始化的入口等于一个长期后门 ——
            # 它能把数据库口令改成任何人指定的值。
            return self._json(410, {"message": "本系统已完成安装，向导不再可用", "steps": []})
        try:
            length = int(self.headers.get("Content-Length") or 0)
            payload = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, TypeError):
            return self._json(400, {"message": "请求体不是合法 JSON", "steps": []})
        code, body = run_install(payload)
        return self._json(code, body)

    def log_message(self, fmt, *args):
        # 默认实现会把每条访问日志打到 stderr；这里统一走审计日志，便于「谁在什么时候装了系统」
        log("[访问] %s %s" % (self.address_string(), fmt % args))


def main() -> int:
    ensure_dirs()
    log("启动 ops-agent v%s（deploy=%s shared=%s ui=%s port=%d）"
        % (VERSION, DEPLOY_DIR, SHARED_DIR, UI_ENABLED, PORT))
    threading.Thread(target=heartbeat_loop, daemon=True).start()
    threading.Thread(target=request_loop, daemon=True).start()
    try:
        ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
    except OSError as exc:
        log("监听 %d 失败：%s" % (PORT, exc))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
