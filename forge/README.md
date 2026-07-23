# Forge 🔨 — a high-performance coding AI

Forge is a coding-specialized AI agent. It plans, writes code, **runs it**, reads
the real output, and self-corrects in a loop until the work is actually verified —
which is what separates a genuinely useful coding AI from one that only produces
plausible-looking code.

## Why this design (and an honest note)

You cannot train a frontier LLM from scratch in a sandbox — that takes tens of
thousands of GPUs, petabytes of data, and months. **The way you get the best
coding performance available today is to take the strongest base model and wrap
it in excellent agent scaffolding.** That is what Forge is:

- **Base model:** Claude **Opus 4.8** (`claude-opus-4-8`) — currently
  state-of-the-art for coding and long-horizon agentic work.
- **Adaptive thinking + `xhigh` effort** — the setting that maximizes coding
  quality on Opus 4.8.
- **A real agent loop:** plan → act with tools → **run tests / execute code** →
  read the failure → fix → repeat until green.
- **Empirical verification is enforced by the system prompt**, not hoped for.
  Forge is instructed never to claim success without running the code.

The intelligence comes from the model; the **coding reliability** comes from the
scaffolding — the tools, the test-in-the-loop verification, and the prompt.

## Install

```bash
cd forge
pip install -e .          # or: pip install -r requirements.txt
export ANTHROPIC_API_KEY=sk-ant-...
```

## Use it

```bash
# One-shot task in the current directory
forge "Add a --json output flag to cli.py and add a test that proves it works"

# Point at a project
forge -w ./myproject "The test_parser suite fails — find the bug and fix it"

# Interactive session
forge --repl
```

As a library:

```python
from forge_agent import ForgeAgent

agent = ForgeAgent(workspace="./scratch")
summary = agent.run(
    "Write primes.py with nth_prime(n), add pytest tests, run them, make them pass."
)
print(summary)
```

See [`examples/programmatic.py`](examples/programmatic.py).

## Tools the agent can use

| Tool | Purpose |
|------|---------|
| `read_file` | Read a file (line-numbered, range-selectable) |
| `write_file` | Create / overwrite a file |
| `edit_file` | Unique-match string replacement in a file |
| `list_dir` | List a directory |
| `bash` | Run a shell command in the workspace (timeout-guarded) |
| `run_tests` | Auto-detect and run the test suite (pytest / unittest) |

**Safety:** all file operations are confined to the workspace root — path
traversal (`..`, absolute paths outside the root) is rejected — and `bash` runs
inside the workspace with a timeout. Point Forge at a directory you're happy for
it to modify and run programs in.

## How the loop works

```
task ─▶ Claude (plan + tool calls) ─▶ Forge runs the tools ─▶ results fed back
          ▲                                                          │
          └──────────────────  repeat until no more tool calls  ◀────┘
                         (model has verified & summarized)
```

Each turn streams the model's reasoning and output; Forge executes any tool
calls, feeds every result back in one message, and continues until the model
stops calling tools — i.e. it has run the code, confirmed it works, and written
its summary.

## Configuration

| Flag | Default | Notes |
|------|---------|-------|
| `--model` | `claude-opus-4-8` | Any current Claude model id |
| `--effort` | `xhigh` | `low`/`medium`/`high`/`xhigh`/`max` |
| `--max-turns` | `60` | Agent-loop iteration cap |
| `--no-thinking` | off | Hide the model's reasoning stream |
| `-w/--workspace` | `.` | Directory the agent may read/write/run in |

## Tests

```bash
pip install pytest
pytest -q
```

The suite (11 tests) covers the tool primitives, the workspace security
boundary, and the **full agent loop with a stubbed model** — so the control flow
is verified without needing an API key. The live model loop naturally requires
`ANTHROPIC_API_KEY`.

## Project layout

```
forge/
├── forge_agent/
│   ├── agent.py     # the plan→act→verify loop over the Messages API
│   ├── tools.py     # file / bash / test tools + workspace sandbox
│   ├── prompts.py   # coding-tuned system prompt
│   └── cli.py       # `forge` command
├── examples/programmatic.py
└── tests/           # tool + loop tests (no API key required)
```
