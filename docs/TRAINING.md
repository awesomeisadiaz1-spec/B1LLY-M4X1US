# Making B1LLY's own model

Training from scratch needs thousands of GPUs and trillions of tokens. We
**fine-tune** an open base model instead: it keeps the base model's knowledge and
takes on B1LLY's voice, opinions, and character.

## Pipeline
1. **Base model:** Qwen3 4B or 8B (or Gemma 3 / Llama 3.x).
2. **Dataset (1k-5k chat examples, JSONL):**
   - Persona/voice: how B1LLY talks, jokes, disagrees, and shows feelings.
   - Memory use: examples where relevant memories are in the prompt and used naturally.
   - Tool use: the `ACTION: {...}` format from `billy.py`.
   - Code help: mix in an open code-instruction dataset so skills do not regress.
   - **Your own real chats** with B1LLY (exported from `~/.billy`) - best data there is.
3. **Train with LoRA/QLoRA** using Unsloth on a free Colab T4 or a rented GPU (~$1-3).
4. **Merge and quantize:** merge the LoRA, convert to GGUF with llama.cpp,
   quantize to Q4_K_M.
5. **Ship:** put the `.gguf` in `termux/models/` and point `config.env` at it.

## Why memory and mood live outside the weights
Weights are frozen and slow to change. The SQLite memory (`memory.py`) lets B1LLY
learn something today and use it tomorrow, with no limit on size, and lets you
inspect and delete anything (`/memory`, `/forget`). Fine-tuning gives the
*personality*; the database gives the *life history*.
