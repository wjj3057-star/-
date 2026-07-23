"""Generate code from a trained checkpoint.

    python -m minigpt.sample --prompt "def fibonacci(n):" --max-new 300
"""

from __future__ import annotations

import argparse

import torch

from .data import CharTokenizer
from .model import GPT, GPTConfig


def main():
    p = argparse.ArgumentParser(description="Sample from the mini code GPT.")
    p.add_argument("--ckpt", default="out/ckpt.pt")
    p.add_argument("--vocab", default="out/vocab.json")
    p.add_argument("--prompt", default="def ")
    p.add_argument("--max-new", type=int, default=300)
    p.add_argument("--temperature", type=float, default=0.8)
    p.add_argument("--top-k", type=int, default=40)
    p.add_argument("--seed", type=int, default=None)
    args = p.parse_args()

    if args.seed is not None:
        torch.manual_seed(args.seed)

    device = "cuda" if torch.cuda.is_available() else "cpu"
    tok = CharTokenizer.load(args.vocab)
    ckpt = torch.load(args.ckpt, map_location=device)
    cfg = GPTConfig(**ckpt["config"])
    model = GPT(cfg).to(device)
    model.load_state_dict(ckpt["model"])
    model.eval()

    ids = tok.encode(args.prompt) or tok.encode("\n")
    ctx = torch.tensor([ids], dtype=torch.long, device=device)
    out = model.generate(
        ctx, args.max_new, temperature=args.temperature, top_k=args.top_k
    )
    print(tok.decode(out[0].tolist()))


if __name__ == "__main__":
    main()
