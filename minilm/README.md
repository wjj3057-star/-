# minigpt-code — a code LLM trained from scratch 🧠

A small GPT (decoder-only transformer) **trained from random initialization** on
Python source code. No pretrained weights, no API calls — the model here learns
the statistics of code purely from the training corpus, and then generates code
character by character.

## Honest expectations

This is **not** mid-tier by frontier standards, and it can't be: it's a ~3M
parameter char-level model trained for a short time on a single 4-core CPU, on
~7 MB of Python source. What it genuinely is:

- A **real, from-scratch language model** — the full GPT architecture
  (multi-head causal self-attention, pre-norm blocks, weight-tied LM head),
  implemented and trained here, not wrapped around someone else's model.
- **Actually learning**: validation loss falls from ~5.1 (random, = log₂(216)
  bits/char) toward ~1.x, and the samples visibly progress from noise → words →
  indented, keyword-rich, Python-shaped code.

Think of it as the "engine built from raw parts" companion to the `forge/`
agent (which wraps a frontier model). Scale it up — more params, more data, more
steps, a GPU — and the same code trains a much stronger model.

## What's here

```
minilm/
├── minigpt/
│   ├── model.py   # GPT: attention + transformer blocks + LM head (from scratch)
│   ├── data.py    # char tokenizer + batching
│   ├── train.py   # training loop: AdamW, cosine LR, eval, checkpointing, samples
│   └── sample.py  # generate code from a checkpoint
├── data/python_corpus.txt   # built from the local Python stdlib source
├── out/           # checkpoints (ckpt.pt), vocab.json, train.log
└── tests/         # architecture + "it actually learns" tests
```

## Setup

```bash
cd minilm
pip install -r requirements.txt        # torch (CPU is fine) + numpy
```

## Build the corpus (optional — one is committed)

```bash
python - <<'PY'
import pathlib
keep = []
for p in pathlib.Path("/usr/lib/python3.11").rglob("*.py"):
    if {"test","tests"} & set(p.parts): continue
    try: t = p.read_text(encoding="utf-8")
    except Exception: continue
    if 200 <= len(t) <= 60000: keep.append(t)
pathlib.Path("data/python_corpus.txt").write_text("\n\n".join(keep))
PY
```

## Train

```bash
python -m minigpt.train --max-steps 8000 --eval-interval 250
# smaller/faster:
python -m minigpt.train --max-steps 2000 --n-layer 4 --n-embd 256 --block-size 128
# resume from the last checkpoint:
python -m minigpt.train --resume
```

Training prints validation loss and a live code sample every `eval-interval`
steps, and saves the best checkpoint to `out/ckpt.pt`.

## Generate code

```bash
python -m minigpt.sample --prompt "def fibonacci(n):" --max-new 300
python -m minigpt.sample --prompt "class " --temperature 0.7 --top-k 40
```

## Model

| Knob | Default | Meaning |
|------|---------|---------|
| `--n-layer` | 4 | transformer blocks |
| `--n-head` | 4 | attention heads |
| `--n-embd` | 256 | embedding width |
| `--block-size` | 128 | context length (chars) |
| `--batch-size` | 32 | sequences per step |
| `--lr` | 3e-4 | peak learning rate (cosine schedule + warmup) |

Default config ≈ 3.25M parameters. Everything is standard, scalable GPT: raise
the dims/steps and point it at a larger corpus for a stronger model.

## Tests

```bash
pip install pytest && pytest -q
```

Covers the tokenizer, forward/loss shapes, generation length and context
cropping, and a **learning test** that asserts loss drops to <30% of its initial
value on a learnable stream — i.e. the training actually works.

## How training reads

The final loss is cross-entropy in nats/char; divide by ln(2) for bits/char.
Random init on a 216-symbol vocab starts near log₂(216) ≈ 7.75 bits/char
(≈ 5.4 nats). A well-trained char model on code reaches ~1–1.5 bits/char, at
which point samples look convincingly like Python even though they aren't
executable programs.
