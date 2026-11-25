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
TL = threading.local()
TYPES = {".html": "text/html", ".js": "text/javascript", ".css": "text/css",
         ".png": "image/png", ".webmanifest": "application/manifest+json",
         ".svg": "image/svg+xml"}


def web_confirm(msg):
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


def turn(user, voice, emit):
    sysmsg = billy.SYSTEM + ("\nThe user is speaking to you; reply briefly." if voice else "")
    ctx = billy.build_system(MEM, "", user).strip()
    HISTORY.append({"role": "user", "content": user})
    uidx = len(HISTORY) - 1
    reply = ""
    for _ in range(4):
        aug = dict(HISTORY[uidx], content=f"[Context for you, not from the user]\n{ctx}\n\n[User]\n{user}")
        send = [{"role": "system", "content": sysmsg}] + HISTORY[:uidx] + [aug] + HISTORY[uidx + 1:]
        buf, streaming = "", None
        for tok in llm_stream(send):
            buf += tok
            if streaming is None and len(buf.lstrip()) >= 7:
                streaming = not buf.lstrip().startswith("ACTION")
                if streaming:
                    emit({"t": "token", "v": buf})
            elif streaming:
                emit({"t": "token", "v": tok})
        if streaming is None:
            streaming = True
            emit({"t": "token", "v": buf})
        HISTORY.append({"role": "assistant", "content": buf.strip()})
        m = billy.ACTION_RE.search(buf)
        if streaming or not m:
            reply = buf.strip()
            break
        try:
            call = json.loads(m.group(1))
            emit({"t": "tool", "v": f"{call.get('tool')} {json.dumps(call.get('args', {}))[:80]}"})
            res = billy.tool_run(call.get("tool", ""), call.get("args", {}) or {})
        except Exception as e:  # noqa: BLE001
            res = f"error: {e}"
        emit({"t": "tool_result", "v": str(res)[:200]})
        HISTORY.append({"role": "user", "content": f"TOOL_RESULT: {res}"})
    emit({"t": "done"})
    threading.Thread(target=billy.remember, args=(MEM, user, reply), daemon=True).start()
    if len(HISTORY) > 40:
        dropped = HISTORY[:10]
        del HISTORY[:10]
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
