"""Extended thinking for the mini code model — test-time compute scaling.

A small base language model can't reason by itself, but we can spend more
compute at inference to think longer and deeper before committing to an answer.
This module adds exactly that, with an `effort` knob analogous to the effort
levels in production reasoning systems:

1. **Thinking scratchpad.** Before writing the answer, the model drafts a short
   plan (as code comments). The answer is then generated conditioned on the
   prompt *and* that plan — more deliberation context per token.

2. **Best-of-N with self-verification.** We sample several candidates and score
   each by (a) how much valid Python it produces (`ast.parse`) and (b) the
   model's own confidence (mean token log-probability). The best candidate is
   returned. More effort = more samples + longer thinking = better output, at
   the cost of wall-clock time.

Nothing here is a wrapper around another model: the scorer *is* this model
evaluating its own drafts, plus a real Python syntax check.
"""

from __future__ import annotations

import ast
from dataclasses import dataclass, field

import torch

from .data import CharTokenizer
from .model import GPT

# effort -> (n_samples, think_tokens, answer_tokens, temperature, top_k)
EFFORT_LEVELS: dict[str, dict] = {
    "low":    dict(n_samples=1,  think_tokens=0,   answer_tokens=200, temperature=0.7, top_k=40),
    "medium": dict(n_samples=4,  think_tokens=48,  answer_tokens=220, temperature=0.8, top_k=40),
    "high":   dict(n_samples=8,  think_tokens=96,  answer_tokens=240, temperature=0.9, top_k=50),
    "max":    dict(n_samples=16, think_tokens=192, answer_tokens=280, temperature=1.0, top_k=60),
}


@dataclass
class Candidate:
    thinking: str
    answer: str
    validity: float          # 0..1, fraction of the answer that parses as Python
    confidence: float        # mean token log-prob under the model (higher = surer)
    score: float = 0.0
    text: str = ""


@dataclass
class ThinkResult:
    prompt: str
    effort: str
    best: Candidate
    candidates: list[Candidate] = field(default_factory=list)

    @property
    def answer(self) -> str:
        return self.best.answer


def python_validity_score(code: str) -> float:
    """Fraction of the code (by lines) that forms a parseable Python prefix.

    A fully valid snippet scores 1.0; garbage after a valid head is penalised
    proportionally. This is a real syntactic-correctness signal for code.
    """
    code = code.rstrip()
    if not code.strip():
        return 0.0
    if _parses(code):
        return 1.0
    lines = code.split("\n")
    good = 0
    for i in range(len(lines), 0, -1):
        if _parses("\n".join(lines[:i])):
            good = i
            break
    return good / len(lines)


def _parses(code: str) -> bool:
    try:
        ast.parse(code)
        return True
    except (SyntaxError, ValueError):
        return False


@torch.no_grad()
def sequence_logprob(model: GPT, tok: CharTokenizer, text: str, device) -> float:
    """Mean per-character log-probability the model assigns to `text`.

    This is the model grading its own draft — higher means the continuation is
    more natural to the model. Returned in nats/char.
    """
    ids = tok.encode(text)
    if len(ids) < 2:
        return -100.0
    ids = ids[-model.cfg.block_size:]
    x = torch.tensor([ids[:-1]], dtype=torch.long, device=device)
    y = torch.tensor([ids[1:]], dtype=torch.long, device=device)
    _, loss = model(x, y)
    return -loss.item()  # -CE == mean log-prob


@torch.no_grad()
def _generate(model: GPT, tok: CharTokenizer, prompt: str, n_tokens: int,
              temperature: float, top_k: int, device) -> str:
    """Generate a continuation and return only the newly produced text."""
    ids = tok.encode(prompt) or tok.encode("\n")
    ctx = torch.tensor([ids], dtype=torch.long, device=device)
    out = model.generate(ctx, n_tokens, temperature=temperature, top_k=top_k)
    return tok.decode(out[0, len(ids):].tolist())


def _stop_at_dedent(prompt: str, answer: str) -> str:
    """Trim the answer at the point it leaves the current code block.

    Keeps the generated body of the thing we were completing and drops the
    model wandering into an unrelated next definition.
    """
    lines = answer.split("\n")
    kept: list[str] = []
    started_body = False
    for ln in lines:
        if ln.strip() == "":
            kept.append(ln)
            continue
        indented = ln[0] in " \t"
        if indented:
            started_body = True
            kept.append(ln)
        elif started_body:
            # returned to column 0 after the body began -> block finished
            break
        else:
            kept.append(ln)
    return "\n".join(kept).rstrip()


@torch.no_grad()
def think(
    model: GPT,
    tok: CharTokenizer,
    prompt: str,
    effort: str = "medium",
    device: str | None = None,
) -> ThinkResult:
    """Deliberate on `prompt` and return the best answer plus the full trace.

    Higher `effort` spends more compute (more samples, longer thinking) and
    generally produces more valid, higher-confidence code.
    """
    if effort not in EFFORT_LEVELS:
        raise ValueError(f"effort must be one of {list(EFFORT_LEVELS)}")
    cfg = EFFORT_LEVELS[effort]
    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    model.eval()

    candidates: list[Candidate] = []
    for _ in range(cfg["n_samples"]):
        thinking = ""
        answer_ctx = prompt
        if cfg["think_tokens"] > 0:
            think_seed = prompt.rstrip("\n") + "\n    # plan:"
            thinking = _generate(
                model, tok, think_seed, cfg["think_tokens"],
                cfg["temperature"], cfg["top_k"], device,
            )
            # keep the plan as an in-body comment the answer can build on
            plan = thinking.split("\n")[0].strip()
            answer_ctx = think_seed + " " + plan + "\n"

        raw = _generate(
            model, tok, answer_ctx, cfg["answer_tokens"],
            cfg["temperature"], cfg["top_k"], device,
        )
        answer = _stop_at_dedent(prompt, (answer_ctx[len(prompt):] + raw))
        full = prompt + answer
        validity = python_validity_score(full)
        confidence = sequence_logprob(model, tok, full, device)
        candidates.append(
            Candidate(
                thinking=thinking.strip(),
                answer=answer,
                validity=validity,
                confidence=confidence,
                text=full,
            )
        )

    # score: validity dominates (correct code matters most), confidence breaks ties.
    for c in candidates:
        c.score = c.validity * 10.0 + c.confidence
    best = max(candidates, key=lambda c: c.score)
    candidates.sort(key=lambda c: c.score, reverse=True)
    return ThinkResult(prompt=prompt, effort=effort, best=best, candidates=candidates)
