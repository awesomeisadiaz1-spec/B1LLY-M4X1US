#!/usr/bin/env python3
"""B1LLY M4X1US - local AI assistant for Termux (llama.cpp server + Termux:API)."""
import argparse, json, os, re, shlex, subprocess, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
NAME = "B1LLY M4X1US"


def load_config():
    cfg = {"MODEL_FILE": "model.gguf", "CTX": "8192", "THREADS": "6", "PORT": "8080"}
    path = os.path.join(HERE, "config.env")
    if os.path.exists(path):
        for line in open(path):
            m = re.match(r'\s*([A-Z_]+)="?([^"#\n]*)"?', line)
            if m:
                cfg[m.group(1)] = m.group(2).strip()
    return cfg


CFG = load_config()
BASE = f"http://127.0.0.1:{CFG['PORT']}"

SYSTEM = f"""You are {NAME}, a private AI assistant running locally on the user's Android phone.
Be direct, helpful, and concise. You are good at general questions and writing/debugging code.
When speaking aloud (voice mode) keep answers short and avoid markdown.

You can control the phone with tools. To use one, reply with exactly one line:
ACTION: {{"tool": "<name>", "args": {{...}}}}
and nothing else. You will receive the result and can then answer. Tools:
- battery {{}}                       battery status
- location {{}}                      current GPS location
- notify {{"title","text"}}          show a notification
- flashlight {{"on": true|false}}    torch
- clipboard_get {{}} / clipboard_set {{"text"}}
- open_url {{"url"}}                 open a link or web search in the browser
- open_app {{"package"}}             launch an app by package name (e.g. com.android.chrome)
- volume {{"stream":"music","level":0-15}}
- photo {{"path"}}                   take a photo with the back camera
- sms_send {{"number","text"}}       (user confirms first)
- call {{"number"}}                  (user confirms first)
- shell {{"command"}}                run a shell command in Termux (user confirms first)
Only use a tool when the user's request needs it. Never invent tool results."""


def sh(cmd, timeout=30):
    try:
        r = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True,
                           text=True, timeout=timeout)
        return ((r.stdout or "") + (r.stderr or "")).strip() or "(ok)"
    except Exception as e:  # noqa: BLE001
        return f"error: {e}"


def confirm(msg):
    return input(f"\n[!] {msg}  Allow? [y/N] ").strip().lower() == "y"


def tool_run(name, a):
    q = lambda s: shlex.quote(str(s))
    if name == "battery":
        return sh("termux-battery-status")
    if name == "location":
        return sh("termux-location -p network", 40)
    if name == "notify":
        return sh(f"termux-notification -t {q(a.get('title', NAME))} -c {q(a.get('text', ''))}")
    if name == "flashlight":
        return sh(f"termux-torch {'on' if a.get('on', True) else 'off'}")
    if name == "clipboard_get":
        return sh("termux-clipboard-get")
    if name == "clipboard_set":
        return sh(f"termux-clipboard-set {q(a.get('text', ''))}")
    if name == "open_url":
        return sh(f"termux-open-url {q(a.get('url', ''))}")
    if name == "open_app":
        return sh(f"monkey -p {q(a.get('package', ''))} -c android.intent.category.LAUNCHER 1")
    if name == "volume":
        return sh(f"termux-volume {q(a.get('stream', 'music'))} {int(a.get('level', 5))}")
    if name == "photo":
        return sh(f"termux-camera-photo -c 0 {q(a.get('path', os.path.expanduser('~/billy.jpg')))}")
    if name == "sms_send":
        if confirm(f"Send SMS to {a.get('number')}: {a.get('text')!r}"):
            return sh(f"termux-sms-send -n {q(a.get('number'))} {q(a.get('text', ''))}")
        return "user denied"
    if name == "call":
        if confirm(f"Call {a.get('number')}"):
            return sh(f"termux-telephony-call {q(a.get('number'))}")
        return "user denied"
    if name == "shell":
        if confirm(f"Run shell command: {a.get('command')}"):
            return sh(a.get("command", ""), 120)[:4000]
        return "user denied"
    return f"unknown tool: {name}"


ACTION_RE = re.compile(r"ACTION:\s*(\{.*\})", re.S)


def post(path, body):
    req = urllib.request.Request(BASE + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)


def server_up():
    try:
        urllib.request.urlopen(BASE + "/health", timeout=2)
        return True
    except Exception:  # noqa: BLE001
        return False


def ensure_server():
    if server_up():
        return
    binary = os.path.join(HERE, "llama.cpp/build/bin/llama-server")
    model = os.path.join(HERE, "models", CFG["MODEL_FILE"])
    if not (os.path.exists(binary) and os.path.exists(model)):
        sys.exit("Model or llama-server missing. Run: bash install.sh")
    print(f"Starting {NAME} brain (first load takes a bit)...")
    subprocess.Popen([binary, "-m", model, "-c", CFG["CTX"], "-t", CFG["THREADS"],
                      "--port", CFG["PORT"], "--host", "127.0.0.1"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(180):
        if server_up():
            return
        time.sleep(1)
    sys.exit("Server did not start.")


def chat(history, voice):
    for _ in range(4):  # allow a few tool round-trips
        out = post("/v1/chat/completions", {"messages": history, "temperature": 0.7,
                                            "max_tokens": 1024})
        text = out["choices"][0]["message"]["content"].strip()
        m = ACTION_RE.search(text)
        history.append({"role": "assistant", "content": text})
        if not m:
            return text
        try:
            call = json.loads(m.group(1))
            res = tool_run(call.get("tool", ""), call.get("args", {}) or {})
        except Exception as e:  # noqa: BLE001
            res = f"error: {e}"
        print(f"  [tool] {m.group(1)[:80]} -> {res[:120]}")
        history.append({"role": "user", "content": f"TOOL_RESULT: {res}"})
    return text


def listen():
    out = sh("termux-speech-to-text", 60)
    return "" if out in ("(ok)", "") or out.startswith("error") else out


def speak(text):
    clean = re.sub(r"[*_`#>]", "", text)
    subprocess.run(["termux-tts-speak", clean[:1500]])


def main():
    ap = argparse.ArgumentParser(description=NAME)
    ap.add_argument("--voice", action="store_true", help="talk instead of type")
    args = ap.parse_args()
    ensure_server()
    sysmsg = SYSTEM + ("\nThe user is speaking to you; reply briefly." if args.voice else "")
    history = [{"role": "system", "content": sysmsg}]
    print(f"{NAME} online. Type 'exit' to quit.\n")
    while True:
        try:
            user = listen() if args.voice else input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if args.voice and user:
            print(f"you> {user}")
        if not user:
            continue
        if user.lower() in ("exit", "quit", "bye"):
            break
        history.append({"role": "user", "content": user})
        reply = chat(history, args.voice)
        print(f"\n{NAME}> {reply}\n")
        if args.voice:
            speak(reply)
        if len(history) > 40:  # keep context bounded
            history = history[:1] + history[-30:]


if __name__ == "__main__":
    main()
