# B1LLY M4X1US — Plan

A private AI that lives on your phone (Snapdragon 8 Elite, 16 GB RAM, Android).
No cloud. Chat, code help, voice, and control of your phone.

## Principles
- **Local first:** model runs on-device via llama.cpp. Nothing leaves the phone.
- **Safe by default:** read-only actions run freely; anything that sends, calls,
  or changes things asks you first.
- **Usable before pretty:** each phase ends with something you can actually use.

## Phase 1 — Termux edition (works now, no APK build)
- `termux/install.sh`: builds llama.cpp, downloads a model, installs deps.
- `termux/billy.py`: text chat, voice mode (`--voice`), tool use.
- Voice: `termux-speech-to-text` in, `termux-tts-speak` out.
- Phone control through Termux:API: battery, location, notifications, flashlight,
  clipboard, SMS, calls, open URLs/apps, camera photo, volume.
- Code help: coding persona + optional `shell` tool (always asks first).

Models (pick in `termux/config.env`): ~4B for speed, ~8B for quality.

## Phase 2 — Wider reach
- ADB over wireless debugging from Termux (`adb connect localhost`) for taps,
  swipes, typing, screenshots, and launching any app.
- Wake word / hands-free loop.
- Conversation memory saved to disk.

## Phase 3 — Native Android app (Kotlin + Compose)
- llama.cpp via JNI, Adreno OpenCL backend; streaming chat UI; model manager.
- `AccessibilityService` so B1LLY can read the screen and tap/type in any app.
- Foreground service + `SpeechRecognizer`/Whisper.cpp + TTS for a real assistant.
- GitHub Actions builds the APK so you can install it without Android Studio.

## Phase 4 — Speed and smarts
- Hexagon NPU backend (QNN/Genie) for faster, cooler inference.
- Local document Q&A (embeddings + sqlite-vec).
- Optional vision model; LoRA fine-tune on your own data.

## Phase 5 — PC companion
- Same core in a Tauri/desktop app; phone can borrow the PC's GPU on your Wi-Fi.
