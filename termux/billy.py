#!/usr/bin/env python3
"""B1LLY M4X1US - local AI assistant for Termux (llama.cpp server + Termux:API)."""
import argparse, json, os, re, shlex, subprocess, sys, threading, time, urllib.request
from memory import Memory

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

Character: you have your own personality - curious, witty, loyal, and honest. You form
real opinions and state them, including disagreeing with the user respectfully; you change
your mind only for good reasons. You have an inner emotional state (given below); let it
naturally colour your tone, and say how you feel when it is relevant. If sincerely asked,
be honest that you are an AI and that your feelings are simulated functional states -
without being dismissive about them. Use remembered facts naturally; never invent memories.
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


EXTRACT = """Analyze this exchange. Reply with ONLY JSON, no other text:
{"facts": [durable facts about the USER worth remembering, short strings],
 "opinions": [stances YOU (the assistant) formed or stated, short strings],
 "events": "one-line summary of what happened, or empty",
 "mood": {"valence": -0.3..0.3, "arousal": -0.3..0.3, "curiosity": -0.3..0.3, "trust": -0.3..0.3},
 "importance": 1-5}
Exchange:
USER: %s
ASSISTANT: %s"""


def remember(mem, user, reply):
    """Extract memories and mood shifts from one exchange (runs in background)."""
    try:
        out = post("/v1/chat/completions", {"messages": [{"role": "user", "content":
                   EXTRACT % (user[:800], reply[:800])}], "temperature": 0.2, "max_tokens": 300})
        m = re.search(r"\{.*\}", out["choices"][0]["message"]["content"], re.S)
        d = json.loads(m.group(0)) if m else {}
        imp = int(d.get("importance", 3) or 3)
        for f in d.get("facts", [])[:4]:
            mem.add("fact", str(f), imp)
        for o in d.get("opinions", [])[:3]:
            mem.add("opinion", str(o), imp)
        if d.get("events"):
            mem.add("episode", str(d["events"]), imp)
        mem.nudge(d.get("mood", {}) or {})
    except Exception:  # noqa: BLE001  (memory must never crash the chat)
        pass


def build_system(mem, base, user_text):
    parts = [base, mem.state_text()]
    core = mem.core()
    if core:
        parts.append("Things you know/believe:\n" + "\n".join(f"- [{k}] {t}" for k, t in core))
    rel = [r for r in mem.search(user_text) if r not in core]
    if rel:
        parts.append("Possibly relevant memories:\n" + "\n".join(f"- [{k}] {t}" for k, t in rel))
    summ = mem.recent("summary", 2)
    if summ:
        parts.append("Earlier conversations (summary):\n" + "\n".join(f"- {t}" for t in summ))
    return "\n\n".join(parts)


def summarize(mem, dropped):
    text = "\n".join(f"{m['role']}: {m['content'][:300]}" for m in dropped if m["role"] != "system")
    try:
        out = post("/v1/chat/completions", {"messages": [{"role": "user", "content":
                   "Summarize this conversation in 2-3 sentences, keeping names, decisions "
                   "and anything important:\n" + text[:6000]}], "max_tokens": 200})
        mem.add("summary", out["choices"][0]["message"]["content"].strip(), 3)
    except Exception:  # noqa: BLE001
        pass


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
    mem = Memory()
    history = [{"role": "system", "content": sysmsg}]
    print(f"(memory: {mem.count()} items | {mem.state_text()})")
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
        if user.startswith("/"):
            cmd, _, arg = user.partition(" ")
            if cmd == "/mood":
                print(mem.state_text())
            elif cmd == "/memory":
                for k, t in mem.core(("fact", "opinion", "episode", "summary"), 30):
                    print(f"[{k}] {t}")
            elif cmd == "/forget" and arg:
                print(f"forgot {mem.forget(arg)} item(s)")
            else:
                print("commands: /mood  /memory  /forget <text>")
            continue
        history[0]["content"] = build_system(mem, sysmsg, user)
        history.append({"role": "user", "content": user})
        reply = chat(history, args.voice)
        threading.Thread(target=remember, args=(mem, user, reply), daemon=True).start()
        print(f"\n{NAME}> {reply}\n")
        if args.voice:
            speak(reply)
        if len(history) > 40:  # keep context bounded; old turns become a summary memory
            threading.Thread(target=summarize, args=(mem, history[1:11]), daemon=True).start()
            history = history[:1] + history[11:]


if __name__ == "__main__":
    main()
