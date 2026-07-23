"""Tests for the extended-thinking module.

The Python-validity scorer is deterministic and needs no model. The `think`
loop is exercised on a tiny random model to verify it returns a well-formed,
scored deliberation and that higher effort produces more candidates.
"""

import torch

from minigpt.data import CharTokenizer
from minigpt.model import GPT, GPTConfig
from minigpt.reasoning import (
    EFFORT_LEVELS,
    python_validity_score,
    sequence_logprob,
    think,
)


def test_validity_full_valid():
    assert python_validity_score("def f(x):\n    return x + 1\n") == 1.0


def test_validity_zero_on_garbage():
    assert python_validity_score("def @@@ ??? )(") == 0.0


def test_validity_partial_prefix():
    # first two lines parse; the third is broken -> partial credit in (0, 1)
    code = "def f():\n    return 1\n    @@@ )("
    s = python_validity_score(code)
    assert 0.0 < s < 1.0


def test_validity_empty():
    assert python_validity_score("   \n  ") == 0.0


def test_effort_levels_monotonic():
    n = [EFFORT_LEVELS[e]["n_samples"] for e in ("low", "medium", "high", "max")]
    assert n == sorted(n) and n[0] < n[-1]
    t = [EFFORT_LEVELS[e]["think_tokens"] for e in ("low", "medium", "high", "max")]
    assert t == sorted(t)


def _tiny():
    tok = CharTokenizer.from_text(
        "def f(x):\n    return x + 1\nclass A:\n    pass\n"
        "abcdefghijklmnopqrstuvwxyz0123456789()[]:_= \n#"
    )
    cfg = GPTConfig(vocab_size=tok.vocab_size, block_size=64,
                    n_layer=2, n_head=2, n_embd=64)
    return GPT(cfg), tok


def test_sequence_logprob_is_negative_and_finite():
    torch.manual_seed(0)
    model, tok = _tiny()
    lp = sequence_logprob(model, tok, "def f(x):\n    return x", device="cpu")
    assert lp < 0 and lp > -100


def test_think_returns_scored_candidates():
    torch.manual_seed(0)
    model, tok = _tiny()
    result = think(model, tok, "def f(x):", effort="medium", device="cpu")
    exp = EFFORT_LEVELS["medium"]["n_samples"]
    assert len(result.candidates) == exp
    # best is the max-scoring candidate and score ties to validity+confidence
    assert result.best is result.candidates[0]
    assert all(0.0 <= c.validity <= 1.0 for c in result.candidates)
    for c in result.candidates:
        assert abs(c.score - (c.validity * 10.0 + c.confidence)) < 1e-6
    assert result.answer == result.best.answer


def test_higher_effort_more_samples():
    torch.manual_seed(0)
    model, tok = _tiny()
    low = think(model, tok, "def f(x):", effort="low", device="cpu")
    high = think(model, tok, "def f(x):", effort="high", device="cpu")
    assert len(high.candidates) > len(low.candidates)
