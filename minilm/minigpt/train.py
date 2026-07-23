"""Train the small GPT from scratch on the code corpus.

CPU-friendly by default, and GPU-ready: pass --device cuda to train on a GPU
with automatic mixed precision (bf16 where supported, else fp16 + GradScaler),
optional torch.compile, and gradient accumulation for large effective batches.
The run periodically evaluates validation loss, prints a code sample, and
writes a checkpoint so training can be resumed and sampling can happen anytime.
"""

from __future__ import annotations

import argparse
import contextlib
import math
import pathlib
import time

import torch

from .data import CharTokenizer, Dataset
from .model import GPT, GPTConfig


def pick_device(arg: str) -> str:
    if arg != "auto":
        return arg
    if torch.cuda.is_available():
        return "cuda"
    if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def amp_context(device: str, enabled: bool):
    """Return (autocast_ctx, dtype, use_grad_scaler)."""
    if not enabled or device == "cpu":
        return contextlib.nullcontext(), torch.float32, False
    if device == "cuda":
        bf16 = torch.cuda.is_bf16_supported()
        dtype = torch.bfloat16 if bf16 else torch.float16
        return torch.autocast("cuda", dtype=dtype), dtype, (dtype == torch.float16)
    if device == "mps":
        return torch.autocast("cpu", dtype=torch.bfloat16), torch.bfloat16, False
    return contextlib.nullcontext(), torch.float32, False


def get_lr(step, warmup, max_steps, lr, min_lr):
    if step < warmup:
        return lr * (step + 1) / warmup
    if step > max_steps:
        return min_lr
    ratio = (step - warmup) / max(1, (max_steps - warmup))
    coeff = 0.5 * (1.0 + math.cos(math.pi * ratio))
    return min_lr + coeff * (lr - min_lr)


@torch.no_grad()
def estimate_loss(model, data, batch_size, block_size, device, autocast, iters=50):
    model.eval()
    out = {}
    for split in ("train", "val"):
        losses = torch.zeros(iters)
        for k in range(iters):
            x, y = data.get_batch(split, batch_size, block_size, device)
            with autocast:
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
    p.add_argument("--grad-accum", type=int, default=1,
                   help="Micro-batches accumulated per optimizer step.")
    p.add_argument("--block-size", type=int, default=128)
    p.add_argument("--n-layer", type=int, default=4)
    p.add_argument("--n-head", type=int, default=4)
    p.add_argument("--n-embd", type=int, default=256)
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--eval-interval", type=int, default=250)
    p.add_argument("--device", default="auto",
                   help="auto | cuda | mps | cpu")
    p.add_argument("--amp", action="store_true",
                   help="Enable automatic mixed precision (recommended on GPU).")
    p.add_argument("--compile", action="store_true",
                   help="torch.compile the model (PyTorch 2.x, big GPU speedup).")
    p.add_argument("--resume", action="store_true")
    p.add_argument("--seed", type=int, default=1337)
    args = p.parse_args()

    torch.manual_seed(args.seed)
    device = pick_device(args.device)
    if device == "cuda":
        torch.backends.cuda.matmul.allow_tf32 = True
        torch.backends.cudnn.allow_tf32 = True
    autocast, amp_dtype, use_scaler = amp_context(device, args.amp)
    scaler = torch.cuda.amp.GradScaler(enabled=use_scaler)
    print(f"device={device} threads={torch.get_num_threads()} "
          f"amp={args.amp} dtype={amp_dtype} compile={args.compile} "
          f"grad_accum={args.grad_accum}")

    text = pathlib.Path(args.corpus).read_text(encoding="utf-8")
    tok = CharTokenizer.from_text(text)
    tok.save(args.vocab_out)
    del text
    print(f"vocab_size={tok.vocab_size}")

    data = Dataset(args.corpus, tok)
    print(f"train tokens={len(data.train):,} val tokens={len(data.val):,}")

    cfg = GPTConfig(
        vocab_size=tok.vocab_size, block_size=args.block_size,
        n_layer=args.n_layer, n_head=args.n_head, n_embd=args.n_embd,
    )
    model = GPT(cfg).to(device)
    print(f"parameters={model.num_params()/1e6:.2f}M "
          f"effective_batch={args.batch_size * args.grad_accum}")

    optimizer = torch.optim.AdamW(
        model.parameters(), lr=args.lr, betas=(0.9, 0.95), weight_decay=0.1
    )

    start_step, best_val = 0, float("inf")
    if args.resume:
        ckpt = torch.load(args.out, map_location=device)
        model.load_state_dict(ckpt["model"])
        if ckpt.get("optimizer") is not None:
            optimizer.load_state_dict(ckpt["optimizer"])
        else:
            print("note: checkpoint has no optimizer state (slim/inference "
                  "checkpoint) — starting the optimizer fresh.")
        start_step = ckpt.get("step", 0)
        best_val = ckpt.get("best_val", best_val)
        print(f"resumed from step {start_step}")

    raw_model = model  # keep an un-compiled handle for saving
    if args.compile:
        model = torch.compile(model)

    warmup = max(50, args.max_steps // 20)
    min_lr = args.lr / 10
    t0 = time.time()
    model.train()

    for step in range(start_step, args.max_steps + 1):
        lr = get_lr(step, warmup, args.max_steps, args.lr, min_lr)
        for g in optimizer.param_groups:
            g["lr"] = lr

        if step % args.eval_interval == 0:
            stats = estimate_loss(
                model, data, args.batch_size, args.block_size, device, autocast
            )
            dt = time.time() - t0
            print(f"step {step:5d} | train {stats['train']:.3f} | "
                  f"val {stats['val']:.3f} | lr {lr:.1e} | {dt:.0f}s")
            if stats["val"] < best_val:
                best_val = stats["val"]
                torch.save(
                    {
                        "model": raw_model.state_dict(),
                        "optimizer": optimizer.state_dict(),
                        "config": vars(cfg),
                        "step": step,
                        "best_val": best_val,
                    },
                    args.out,
                )
            ctx = torch.tensor([tok.encode("def ")], dtype=torch.long, device=device)
            sample = tok.decode(raw_model.generate(ctx, 160, temperature=0.8)[0].tolist())
            print("  sample: " + repr(sample[:160]))

        # gradient accumulation over micro-batches
        optimizer.zero_grad(set_to_none=True)
        for micro in range(args.grad_accum):
            x, y = data.get_batch("train", args.batch_size, args.block_size, device)
            with autocast:
                _, loss = model(x, y)
                loss = loss / args.grad_accum
            scaler.scale(loss).backward()
        scaler.unscale_(optimizer)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        scaler.step(optimizer)
        scaler.update()

    print(f"done. best val loss={best_val:.3f}. checkpoint -> {args.out}")


if __name__ == "__main__":
    main()
