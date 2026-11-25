#!/data/data/com.termux/files/usr/bin/bash
# Start B1LLY M4X1US and open the app.
cd "$(dirname "$0")"
termux-wake-lock 2>/dev/null
exec python app.py --open
