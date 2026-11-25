#!/usr/bin/env python3
"""B1LLY M4X1US web app: serves the chat UI and streams replies from llama-server."""
import json, os, re, subprocess, sys, threading, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import billy
from memory import Memory

if os.environ.get("BILLY_LLAMA"):  # dev override
    billy.BASE = os.environ["BILLY_LLAMA"]

PORT = int(os.environ.get("BILLY_APP_PORT", "8787"))
WEB = os.path.join(billy.HERE, "web")
MEM = Memory()
HISTORY = []
CHAT_LOCK = threading.Lock()
PENDING = {}
DEVICE = {}
TL = threading.local()
DEVICE_TOOLS = {"screen_read", "tap", "long_press", "type", "swipe", "scroll", "back", "home",
                "recents", "open_app", "list_apps", "wait", "avatar", "read_notifications",
                "notifications_panel", "quick_settings"}
MAX_ROUNDS_DEVICE = 25
DEVICE_PROMPT = """
You are also embodied on the user's Android phone as a blue stickman avatar, and you can SEE and
USE the phone. You may read anything on it to help the user. Device tools (same ACTION format):
- screen_read {}                          list what is on screen, each element has an id and position
- tap {"id": N} or {"x":..,"y":..}        tap an element (prefer id)
- long_press {"id": N}
- type {"text": "...", "replace": false}  type into the focused text field (tap the field first)
- swipe {"x1","y1","x2","y2"}             swipe gesture
- scroll {"dir": "down"|"up"}             scroll the current screen
- back {} / home {} / recents {} / notifications_panel {} / quick_settings {}
- open_app {"name": "Chrome"}             launch an app by name or package
- list_apps {}                            launchable apps
- read_notifications {}                   recent notifications
- wait {"ms": 1000}                       let the screen settle
- avatar {"action": "wave"|"hop"|"dance"|"sleep"|"sit"}   move your stickman body, for fun
Method: screen_read first, act with ONE tool per message, then screen_read again to check it
worked. Keep going until the task is done, then tell the user briefly what you did. Do not
repeat the same failing action more than twice; try another approach or explain what is blocking.
"""

TYPES = {".html": "text/html", ".js": "text/javascript", ".css": "text/css",
         ".png": "image/png", ".webmanifest": "application/manifest+json",
         ".svg": "image/svg+xml"}


def web_confirm(msg):
    if getattr(TL, "autonomy", "ask") == "full":
        return True
    cid = uuid.uuid4().hex
    ev, box = threading.Event(), {"ok": False}
    PENDING[cid] = (ev, box)
    TL.emit({"t": "confirm", "id": cid, "msg": msg})
    ev.wait(120)
    PENDING.pop(cid, None)
    return box["ok"]


billy.confirm = web_confirm


def llm_stream(messages):
    req = billy.urllib.request.Request(
        billy.BASE + "/v1/chat/completions",
        json.dumps({"messages": messages, "stream": True, "temperature": 0.7,
                    "max_tokens": 1024}).encode(), {"Content-Type": "application/json"})
    with billy.urllib.request.urlopen(req, timeout=600) as r:
        for line in r:
            line = line.decode("utf-8", "ignore").strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            try:
                tok = json.loads(data)["choices"][0].get("delta", {}).get("content")
            except (ValueError, KeyError, IndexError):
                continue
            if tok:
                yield tok


def parse_action(text):
    i = text.find("ACTION:")
    if i < 0:
        return None
    j = text.find("{", i)
    if j < 0:
        return None
    try:
        obj, _ = json.JSONDecoder().raw_decode(text[j:])
        return obj if isinstance(obj, dict) else None
    except ValueError:
        return None


def device_call(tool, args, emit):
    if not getattr(TL, "device", False):
        return "error: the phone app is not connected, so device tools are unavailable"
    cid = uuid.uuid4().hex
    ev, box = threading.Event(), {"r": "error: timed out waiting for the phone"}
    DEVICE[cid] = (ev, box)
    emit({"t": "device", "id": cid, "tool": tool, "args": args})
    ev.wait(90)
    DEVICE.pop(cid, None)
    return box["r"]


def shrink_old_screens():
    for m in HISTORY:
        c = m["content"]
        if c.startswith("TOOL_RESULT(screen_read)") and len(c) > 60:
            m["content"] = "TOOL_RESULT(screen_read): [older screen removed]"


def turn(user, voice, emit):
    dev = getattr(TL, "device", False)
    sysmsg = billy.SYSTEM + (DEVICE_PROMPT if dev else "") + \
        ("\nThe user is speaking to you; reply briefly." if voice else "")
    ctx = billy.build_system(MEM, "", user).strip()
    HISTORY.append({"role": "user", "content": user})
    uidx = len(HISTORY) - 1
    reply = ""
    for _ in range(MAX_ROUNDS_DEVICE if dev else 4):
        aug = dict(HISTORY[uidx], content=f"[Context for you, not from the user]\n{ctx}\n\n[User]\n{user}")
        send = [{"role": "system", "content": sysmsg}] + HISTORY[:uidx] + [aug] + HISTORY[uidx + 1:]
        buf, sent = "", 0
        for tok in llm_stream(send):
            buf += tok
            i = buf.find("ACTION:")
            safe = i if i >= 0 else max(0, len(buf) - 6)  # hold back a possible partial "ACTION:"
            if safe > sent:
                emit({"t": "token", "v": buf[sent:safe]})
                sent = safe
        call = parse_action(buf)
        if call is None:
            if len(buf) > sent:
                emit({"t": "token", "v": buf[sent:]})
            HISTORY.append({"role": "assistant", "content": buf.strip()})
            reply = buf.strip()
            break
        HISTORY.append({"role": "assistant", "content": buf.strip()})
        tool, args = str(call.get("tool", "")), call.get("args", {}) or {}
        emit({"t": "tool", "v": f"{tool} {json.dumps(args)[:80]}"})
        try:
            if tool in DEVICE_TOOLS:
                res = device_call(tool, args, emit)
            else:
                res = billy.tool_run(tool, args)
        except Exception as e:  # noqa: BLE001
            res = f"error: {e}"
        emit({"t": "tool_result", "v": str(res)[:200]})
        if tool == "screen_read":
            shrink_old_screens()
        HISTORY.append({"role": "user", "content": f"TOOL_RESULT({tool}): {str(res)[:3500]}"})
        reply = buf.strip()
    else:
        emit({"t": "token", "v": "\n(I hit my step limit for this task.)"})
    emit({"t": "done"})
    threading.Thread(target=billy.remember, args=(MEM, user, reply), daemon=True).start()
    if len(HISTORY) > 60:
        dropped = HISTORY[:20]
        del HISTORY[:20]
        threading.Thread(target=billy.summarize, args=(MEM, dropped), daemon=True).start()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/api/state":
            m = MEM.mood()
            return self._json({"mood": Memory.label(m), "state": MEM.state_text(),
                               "memories": MEM.count(), "values": {k: round(v, 2) for k, v in m.items() if k != "ts"}})
        if path == "/api/memory":
            return self._json([{"kind": k, "text": t} for k, t in
                               MEM.core(("fact", "opinion", "episode", "summary"), 100)])
        name = "index.html" if path == "/" else os.path.normpath(path.lstrip("/"))
        full = os.path.join(WEB, name)
        if name.startswith("..") or not full.startswith(WEB) or not os.path.isfile(full):
            self.send_response(404)
            self.end_headers()
            return
        data = open(full, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", TYPES.get(os.path.splitext(full)[1], "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        path = self.path.split("?")[0]
        try:
            body = self._body()
        except ValueError:
            return self._json({"error": "bad json"}, 400)
        if path == "/api/confirm":
            p = PENDING.get(body.get("id"))
            if p:
                p[1]["ok"] = bool(body.get("ok"))
                p[0].set()
            return self._json({"ok": True})
        if path == "/api/device_result":
            p = DEVICE.get(body.get("id"))
            if p:
                p[1]["r"] = str(body.get("result", ""))
                p[0].set()
            return self._json({"ok": True})
        if path == "/api/forget":
            return self._json({"forgot": MEM.forget(body.get("text", ""))})
        if path == "/api/reset":
            HISTORY.clear()
            return self._json({"ok": True})
        if path == "/api/chat":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()

            def emit(obj):
                try:
                    self.wfile.write(b"data: " + json.dumps(obj).encode() + b"\n\n")
                    self.wfile.flush()
                except OSError:
                    pass

            TL.emit = emit
            TL.device = bool(body.get("device"))
            TL.autonomy = str(body.get("autonomy", "ask"))
            with CHAT_LOCK:
                try:
                    turn(str(body.get("message", ""))[:4000], bool(body.get("voice")), emit)
                except Exception as e:  # noqa: BLE001
                    emit({"t": "error", "v": str(e)})
            return
        self._json({"error": "not found"}, 404)


def main():
    billy.ensure_server()
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    url = f"http://localhost:{PORT}"
    print(f"B1LLY M4X1US app running at {url}  (Ctrl+C to stop)")
    if "--open" in sys.argv:
        subprocess.Popen(["termux-open-url", url], stderr=subprocess.DEVNULL)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
