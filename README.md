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
3. Chat: `python billy.py`   Voice: `python billy.py --voice`

Edit `termux/config.env` to change the model or context size.

## Safety
B1LLY runs read-only actions (battery, location, ...) on its own. Sending SMS,
placing calls, and running shell commands always show a prompt and need a `y`.
