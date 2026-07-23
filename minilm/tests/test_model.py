"""Tests for the from-scratch GPT: tokenizer, shapes, generation, and that a
few optimizer steps actually reduce the loss (i.e. the model learns)."""

import torch

from minigpt.data import CharTokenizer
from minigpt.model import GPT, GPTConfig


def test_tokenizer_roundtrip():
    tok = CharTokenizer.from_text("def foo(): return 42\n")
    text = "def foo"
    assert tok.decode(tok.encode(text)) == text
    assert tok.vocab_size == len(set("def foo(): return 42\n"))


def test_tokenizer_skips_unknown():
    tok = CharTokenizer.from_text("abc")
    # 'z' is not in vocab -> skipped, not crashed
    assert tok.decode(tok.encode("abzc")) == "abc"


def _tiny_model(vocab_size=16):
    cfg = GPTConfig(vocab_size=vocab_size, block_size=32, n_layer=2, n_head=2, n_embd=32)
    return GPT(cfg), cfg


def test_forward_shapes_and_loss():
    model, cfg = _tiny_model()
    x = torch.randint(0, cfg.vocab_size, (4, 16))
    y = torch.randint(0, cfg.vocab_size, (4, 16))
    logits, loss = model(x, y)
    assert logits.shape == (4, 16, cfg.vocab_size)
    assert loss.ndim == 0 and loss.item() > 0


def test_generate_extends_sequence():
    model, cfg = _tiny_model()
    ctx = torch.zeros((1, 3), dtype=torch.long)
    out = model.generate(ctx, max_new_tokens=10, temperature=1.0, top_k=5)
    assert out.shape == (1, 13)  # 3 prompt + 10 generated
    assert out.max().item() < cfg.vocab_size


def test_respects_block_size():
    model, cfg = _tiny_model()
    # generate() must crop context to block_size internally without error
    ctx = torch.zeros((1, 5), dtype=torch.long)
    out = model.generate(ctx, max_new_tokens=cfg.block_size + 20, top_k=3)
    assert out.shape[1] == 5 + cfg.block_size + 20


def test_learns_on_repeating_pattern():
    """On a trivially learnable stream, loss should drop substantially."""
    torch.manual_seed(0)
    model, cfg = _tiny_model(vocab_size=4)
    # repeating 0,1,2,3,0,1,2,3,... -> next token is fully determined
    seq = torch.arange(4).repeat(200)
    opt = torch.optim.AdamW(model.parameters(), lr=1e-3)

    def batch():
        i = torch.randint(0, len(seq) - cfg.block_size - 1, (16,))
        x = torch.stack([seq[j : j + cfg.block_size] for j in i])
        y = torch.stack([seq[j + 1 : j + 1 + cfg.block_size] for j in i])
        return x, y

    x, y = batch()
    first = model(x, y)[1].item()
    for _ in range(150):
        x, y = batch()
        loss = model(x, y)[1]
        opt.zero_grad()
        loss.backward()
        opt.step()
    last = model(x, y)[1].item()
    assert last < first * 0.3, f"loss did not drop enough: {first:.3f} -> {last:.3f}"
