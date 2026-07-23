"""Train the small GPT from scratch on the code corpus.

Everything is CPU-friendly and checkpointed: the run periodically evaluates
validation loss, prints a code sample, and writes out/ckpt.pt so training can be
resumed and sampling can happen at any time.
"""

from __future__ import annotations

import argparse
import math
import time

import torch

from .data import CharTokenizer, Dataset
from .model import GPT, GPTConfig


def get_lr(step, warmup, max_steps, lr, min_lr):
    if step < warmup:
        return lr * (step + 1) / warmup
    if step > max_steps:
        return min_lr
    ratio = (step - warmup) / max(1, (max_steps - warmup))
    coeff = 0.5 * (1.0 + math.cos(math.pi * ratio))
    return min_lr + coeff * (lr - min_lr)


@torch.no_grad()
def estimate_loss(model, data, batch_size, block_size, device, iters=50):
    model.eval()
    out = {}
    for split in ("train", "val"):
        losses = torch.zeros(iters)
        for k in range(iters):
            x, y = data.get_batch(split, batch_size, block_size, device)
            _, loss = model(x, y)
            losses[k] = loss.item()
        out[split] = losses.mean().item()
    model.train()
    return out


def main():
    p = argparse.ArgumentParser(description="Train mini code GPT from scratch.")
    p.add_argument("--corpus", default="data/python_corpus.txt")
    p.add_argument("--out", default="out/ckpt.pt")
    p.add_argument("--vocab-out", default="out/vocab.json")
    p.add_argument("--max-steps", type=int, default=3000)
    p.add_argument("--batch-size", type=int, default=32)
    p.add_argument("--block-size", type=int, default=128)
    p.add_argument("--n-layer", type=int, default=4)
    p.add_argument("--n-head", type=int, default=4)
    p.add_argument("--n-embd", type=int, default=256)
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--eval-interval", type=int, default=250)
    p.add_argument("--resume", action="store_true")
    p.add_argument("--seed", type=int, default=1337)
    args = p.parse_args()

    torch.manual_seed(args.seed)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"device={device} threads={torch.get_num_threads()}")

    # tokenizer from the corpus
    import pathlib
    text = pathlib.Path(args.corpus).read_text(encoding="utf-8")
    tok = CharTokenizer.from_text(text)
    tok.save(args.vocab_out)
    del text
    print(f"vocab_size={tok.vocab_size}")

    data = Dataset(args.corpus, tok)
    print(f"train tokens={len(data.train):,} val tokens={len(data.val):,}")

    cfg = GPTConfig(
        vocab_size=tok.vocab_size,
        block_size=args.block_size,
        n_layer=args.n_layer,
        n_head=args.n_head,
        n_embd=args.n_embd,
    )
    model = GPT(cfg).to(device)
    print(f"parameters={model.num_params()/1e6:.2f}M")

    optimizer = torch.optim.AdamW(
        model.parameters(), lr=args.lr, betas=(0.9, 0.95), weight_decay=0.1
    )

    start_step = 0
    best_val = float("inf")
    if args.resume:
        ckpt = torch.load(args.out, map_location=device)
        model.load_state_dict(ckpt["model"])
        optimizer.load_state_dict(ckpt["optimizer"])
        start_step = ckpt["step"]
        best_val = ckpt.get("best_val", best_val)
        print(f"resumed from step {start_step}")

    warmup = max(50, args.max_steps // 20)
    min_lr = args.lr / 10
    t0 = time.time()
    model.train()

    for step in range(start_step, args.max_steps + 1):
        lr = get_lr(step, warmup, args.max_steps, args.lr, min_lr)
        for g in optimizer.param_groups:
            g["lr"] = lr

        x, y = data.get_batch("train", args.batch_size, args.block_size, device)
        _, loss = model(x, y)
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()

        if step % args.eval_interval == 0:
            stats = estimate_loss(model, data, args.batch_size, args.block_size, device)
            dt = time.time() - t0
            print(
                f"step {step:5d} | train {stats['train']:.3f} | val {stats['val']:.3f} "
                f"| lr {lr:.1e} | {dt:.0f}s"
            )
            if stats["val"] < best_val:
                best_val = stats["val"]
                torch.save(
                    {
                        "model": model.state_dict(),
                        "optimizer": optimizer.state_dict(),
                        "config": vars(cfg),
                        "step": step,
                        "best_val": best_val,
                    },
                    args.out,
                )
            # quick qualitative sample
            ctx = torch.tensor([tok.encode("def ")], dtype=torch.long, device=device)
            sample = tok.decode(model.generate(ctx, 160, temperature=0.8)[0].tolist())
            print("  sample: " + repr(sample[:160]))

    print(f"done. best val loss={best_val:.3f}. checkpoint -> {args.out}")


if __name__ == "__main__":
    main()
