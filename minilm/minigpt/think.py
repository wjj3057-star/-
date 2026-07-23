"""Generate code *with extended thinking* — spend more compute for better output.

    python -m minigpt.think --prompt "def fibonacci(n):" --effort high
    python -m minigpt.think --prompt "def is_prime(n):" --effort max --show-thinking

Higher --effort samples more candidates and thinks longer before answering,
then returns the most valid, highest-confidence one.
"""

from __future__ import annotations

import argparse
import time

import torch

from .data import CharTokenizer
from .model import GPT, GPTConfig
from .reasoning import EFFORT_LEVELS, think


def main():
    p = argparse.ArgumentParser(description="Extended-thinking code generation.")
    p.add_argument("--ckpt", default="out/ckpt.pt")
    p.add_argument("--vocab", default="out/vocab.json")
    p.add_argument("--prompt", default="def ")
    p.add_argument("--effort", default="high", choices=list(EFFORT_LEVELS))
    p.add_argument("--show-thinking", action="store_true",
                   help="Print the deliberation (all candidates + scores).")
    p.add_argument("--seed", type=int, default=None)
    args = p.parse_args()

    if args.seed is not None:
        torch.manual_seed(args.seed)
    device = "cuda" if torch.cuda.is_available() else "cpu"

    tok = CharTokenizer.load(args.vocab)
    ckpt = torch.load(args.ckpt, map_location=device)
    model = GPT(GPTConfig(**ckpt["config"])).to(device)
    model.load_state_dict(ckpt["model"])
    model.eval()

    cfg = EFFORT_LEVELS[args.effort]
    print(f"[thinking] effort={args.effort} "
          f"samples={cfg['n_samples']} think_tokens={cfg['think_tokens']}")
    t0 = time.time()
    result = think(model, tok, args.prompt, effort=args.effort, device=device)
    dt = time.time() - t0

    if args.show_thinking:
        print("\n=== deliberation (candidates, best first) ===")
        for i, c in enumerate(result.candidates):
            print(f"\n--- candidate {i} | validity={c.validity:.2f} "
                  f"confidence={c.confidence:.3f} score={c.score:.3f} ---")
            if c.thinking:
                print("  plan:", c.thinking.split(chr(10))[0][:80])
            print("  " + c.answer.replace("\n", "\n  "))

    print(f"\n=== chosen answer  (thought for {dt:.1f}s, "
          f"validity={result.best.validity:.2f}, "
          f"confidence={result.best.confidence:.3f}) ===\n")
    print(result.prompt + result.best.answer)


if __name__ == "__main__":
    main()
