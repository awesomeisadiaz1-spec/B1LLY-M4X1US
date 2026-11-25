#!/data/data/com.termux/files/usr/bin/bash
# B1LLY M4X1US installer for Termux.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env

pkg update -y
pkg install -y git cmake clang make curl python termux-api

if [ ! -d llama.cpp ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp
fi
cmake -S llama.cpp -B llama.cpp/build -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF
cmake --build llama.cpp/build --config Release -j"$(nproc)" --target llama-server

mkdir -p models
if [ ! -f "models/$MODEL_FILE" ]; then
  curl -L --fail -C - -o "models/$MODEL_FILE" "$MODEL_URL"
fi
echo "Done. Run: python billy.py   (or python billy.py --voice)"
