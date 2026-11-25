# B1LLY M4X1US

Your own local AI for Android (and later PC). Runs fully on-device.

See [PLAN.md](PLAN.md) for the roadmap.

## Quick start (Termux)
1. Install **Termux** and **Termux:API** from F-Droid (not the Play Store build),
   then grant Termux the microphone, location, SMS, phone, and contacts permissions
   you want B1LLY to use.
2. In Termux:
   ```sh
   pkg install -y git
   git clone https://github.com/awesomeisadiaz1-spec/B1LLY-M4X1US
   cd B1LLY-M4X1US/termux
   bash install.sh
   ```
   If `git clone` asks for a password, the repo is private: use a GitHub personal
   access token (fine-tuned to this repo, read-only) as the password, not your account password.
3. Chat: `python billy.py`   Voice: `python billy.py --voice`

## App mode (recommended)
`bash start.sh` starts B1LLY and opens the chat app in Chrome. In Chrome tap the menu
(⋮) → **Add to Home screen** / **Install app** to get a B1LLY icon. For one-tap launch from
the home screen, install **Termux:Widget** (F-Droid) and run `bash make-shortcut.sh`.
`bash stop.sh` shuts everything down and frees RAM.

Edit `termux/config.env` to change the model or context size.

## Safety
B1LLY runs read-only actions (battery, location, ...) on its own. Sending SMS,
placing calls, and running shell commands always show a prompt and need a `y`.

## Memory and mood
Chat commands: `/mood` (inner state), `/memory` (what B1LLY remembers), `/forget <text>`.
Everything is stored locally in `~/.billy/billy.db`. See `docs/TRAINING.md` for making a custom model.

## The stickman app (Android)
1. Download `B1LLY-M4X1US.apk` from this repo's **Releases** page (`billy-latest`) and install it.
2. Open it and work down the checklist: draw over apps, Accessibility, notification access,
   microphone. (If Accessibility is greyed out: App info → ⋮ → *Allow restricted settings*.)
3. In Termux run `bash start.sh` and leave it running - that is B1LLY's brain.
4. Tap **Start B1LLY**. A blue stickman appears on top of your apps. Tap him to talk.
Build it yourself: `cd android && ./gradlew assembleDebug`.
