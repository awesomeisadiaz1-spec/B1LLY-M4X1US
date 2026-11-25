#!/data/data/com.termux/files/usr/bin/bash
# Stop B1LLY and free its memory.
pkill -f "python app.py" 2>/dev/null
pkill -f llama-server 2>/dev/null
termux-wake-unlock 2>/dev/null
echo "B1LLY stopped."
