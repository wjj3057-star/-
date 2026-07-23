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
│   ├── model.py      # GPT: attention + transformer blocks + LM head (from scratch)
│   ├── data.py       # char tokenizer + batching
│   ├── train.py      # training loop: AdamW, cosine LR, AMP/compile/grad-accum, GPU-ready
│   ├── sample.py     # plain generation from a checkpoint
│   ├── reasoning.py  # extended thinking: scratchpad + best-of-N + self-verification
│   ├── think.py      # `minigpt.think` — generate with an effort budget
│   ├── mathsolve.py  # symbolic math engine (SymPy) — exact high-school+ math
│   ├── converse.py   # dialogue engine: TF-IDF retrieval + reflection + memory
│   └── chat.py       # `minigpt.chat` — router (conversation + math + code)
├── data/python_corpus.txt   # built from the local Python stdlib source
├── out/              # checkpoints (ckpt.pt), vocab.json, train.log
├── GPU.md            # how to train further on your own GPU
└── tests/            # architecture, learning, and reasoning tests
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

## Extended thinking 🧠 (test-time compute)

A tiny base model can't reason on its own, but it *can* spend more compute at
inference to think longer and pick better output. `minigpt.think` adds this with
an `effort` knob, exactly like production reasoning systems:

```bash
python -m minigpt.think --prompt "def is_prime(n):" --effort high --show-thinking
```

Two real mechanisms (see `minigpt/reasoning.py`):

1. **Thinking scratchpad** — before answering, the model drafts a short plan (as
   code comments); the answer is generated conditioned on the prompt *and* that
   plan, i.e. more deliberation context.
2. **Best-of-N + self-verification** — several candidates are sampled and scored
   by (a) how much valid Python they produce (`ast.parse`) and (b) the model's
   own confidence (mean token log-prob). The best is returned.

| effort | samples | thinking tokens | ← more effort = longer, deeper |
|--------|---------|-----------------|-------------------------------|
| low    | 1  | 0   | fast, one shot |
| medium | 4  | 48  | |
| high   | 8  | 96  | |
| max    | 16 | 192 | slowest, best pick |

The scorer *is* this model grading its own drafts (plus a genuine Python syntax
check) — nothing is delegated to another model. As the base model trains longer,
the same machinery yields better selections.

```python
from minigpt import think, CharTokenizer, GPT, GPTConfig
# result = think(model, tok, "def quicksort(a):", effort="max")
# result.answer / result.best.validity / result.candidates
```

## Talk to it: conversation + exact math 💬➗

`minigpt.chat` routes each message and answers in Korean when you write Korean:

- **Conversation** → a real dialogue engine (`minigpt/converse.py`): **TF-IDF
  retrieval** over a bilingual small-talk knowledge base, **ELIZA-style
  reflection** for open-ended statements, and **memory** (it remembers your
  name and follows up). This is the most human-like chat achievable without a
  large pretrained model — see the honest note below.
- **Math** → a real symbolic engine (**SymPy**, `minigpt/mathsolve.py`) — exact
  answers to high-school-and-up math.
- **Code** → the trained neural model's extended-thinking generator.

```bash
python -m minigpt.chat                              # interactive
python -m minigpt.chat --once "integrate x^2 from 0 to 1"
```

```
you> hey there
bot> Hey! Good to see you. How's it going?
you> my name is Alex
bot> Nice to meet you, Alex! How can I help?
you> i think programming is hard
bot> What makes you think programming is hard?
you> x^2를 0부터 1까지 적분해줘
bot> ∫[0..1] x**2 dx = 1/3
you> 오늘 좀 우울해
bot> 이해해요. 가끔은 말로 꺼내는 게 도움이 돼요. 무슨 일이에요?
you> what is my name?
bot> You told me your name is Alex.
```

### How human-like is the conversation? (honest)

The dialogue engine — retrieval + reflection + memory — makes small talk,
follows your feelings, and remembers you, so it feels far more like talking to
someone than a fixed menu of replies. But it is **not** open-domain human-level
conversation: that requires a large pretrained language model (billions of
parameters, vast data), which can't be trained from scratch on a CPU. Within a
home-built project this is the honest ceiling. For genuinely human-like chat,
plug a large model in behind the same `Assistant.respond()` interface — the
router, math, and code parts stay exactly as they are. (The sibling `forge/`
project is the wrap-a-frontier-model path.)

### Math engine on its own

`minigpt.mathsolve.solve_math()` (also usable directly) covers:

| Operation | Example | Answer |
|-----------|---------|--------|
| solve / systems | `solve x^2 - 5x + 6 = 0` | `x = 2, 3` |
| complex roots | `solve x^2 + 1 = 0` | `x = -I, I` |
| derivative | `d/dx sin(x)*x^2` | `x*(x*cos(x) + 2*sin(x))` |
| definite integral | `integral of x^2 from 0 to 1` | `1/3` |
| Gaussian integral | `integrate exp(-x^2) from -oo to oo` | `sqrt(pi)` |
| limit | `limit sin(x)/x as x -> 0` | `1` |
| factor / expand | `factor x^2 - 5x + 6` | `(x - 3)*(x - 2)` |
| Taylor series | `series exp(x) to order 5` | `1 + x + x**2/2 + ...` |
| symbolic sum | `sum k^2 for k = 1 to n` | `n**3/3 + n**2/2 + n/6` |

The scorer and solver are genuine symbolic math — the answers are exact, not
model guesses. This is the honest way to give a small system real math ability:
give it a tool, the same pattern frontier agents use.

## Train further on your own GPU

The same code trains on a GPU with mixed precision, `torch.compile`, and
gradient accumulation — typically 20–100× faster than CPU. Full guide:
**[GPU.md](GPU.md)**. Quick version:

```bash
pip install torch --index-url https://download.pytorch.org/whl/cu121
python -m minigpt.train --resume --device cuda --amp --compile \
    --max-steps 40000 --eval-interval 1000
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
