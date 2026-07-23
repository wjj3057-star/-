# Training minigpt-code further on your own GPU

The same code trains on a GPU with no changes to the model — you just install a
CUDA build of PyTorch and pass a few flags. A GPU is typically **20–100× faster**
than the 4-core CPU this was prototyped on, which lets you use a bigger model,
longer context, and far more data.

## 1. Install a CUDA PyTorch

Check your CUDA version with `nvidia-smi` (top-right), then install the matching
wheel. For CUDA 12.1:

```bash
pip install torch numpy --index-url https://download.pytorch.org/whl/cu121
python -c "import torch; print(torch.__version__, torch.cuda.is_available())"
# expect: 2.x.y+cu121 True
```

(Apple Silicon: plain `pip install torch` and use `--device mps`.)

## 2. Get the code and a checkpoint

```bash
git clone <this repo> && cd minilm
pip install -r requirements.txt
```

To **continue** the run trained here, keep `out/ckpt.pt` and `out/vocab.json`
and use `--resume`. To start fresh, just run `train.py`.

## 3. Train on the GPU

Resume the existing model on a GPU with mixed precision and compilation:

```bash
python -m minigpt.train --resume \
    --device cuda --amp --compile \
    --max-steps 40000 --eval-interval 1000
```

Or train a **bigger** model from scratch (needs the same corpus at
`data/python_corpus.txt`):

```bash
python -m minigpt.train \
    --device cuda --amp --compile \
    --n-layer 8 --n-head 8 --n-embd 512 --block-size 256 \
    --batch-size 64 --grad-accum 4 \
    --lr 3e-4 --max-steps 50000 --eval-interval 1000
```

### What each GPU flag does

| Flag | Effect |
|------|--------|
| `--device cuda` | Run on the GPU (`auto` picks cuda→mps→cpu automatically). |
| `--amp` | Automatic mixed precision — bf16 on Ampere+/`float16`+GradScaler elsewhere. ~2× faster, ~half the memory. |
| `--compile` | `torch.compile` fuses the graph — another large speedup on PyTorch 2.x (first step is slow while it compiles). |
| `--grad-accum N` | Accumulate N micro-batches per update, so effective batch = `batch_size × N` without more memory. |
| `--batch-size` | Raise until you nearly fill VRAM (watch `nvidia-smi`). |

TF32 matmuls are enabled automatically on CUDA.

## 4. Scaling recipe (what actually improves quality)

Roughly in order of impact:

1. **More and better data.** 7 MB of stdlib is tiny. Point the corpus builder at
   more code — your own repos, a language you care about, or a public code
   dataset — and rebuild `data/python_corpus.txt`. More data helps more than
   more parameters at this scale.
2. **A real subword tokenizer.** Char-level is simple but short-sighted. Swap in
   BPE (e.g. `tiktoken`'s `gpt2` encoding, ~50k vocab) so each step covers ~4×
   more text and the model learns tokens, not letters. You'd replace
   `CharTokenizer` with a thin BPE wrapper and set `vocab_size` accordingly —
   the model and training loop are unchanged.
3. **Longer context** (`--block-size 512`/`1024`) so it can see whole functions.
4. **More parameters** (`--n-layer`, `--n-embd`) once data and context are set.
5. **More steps** with the cosine schedule; watch val loss and stop when it
   flattens.

### Sizing guide (rough, for a single modern GPU)

| VRAM | Comfortable config | Notes |
|------|--------------------|-------|
| 8 GB | `n_layer 6 n_embd 384 block 256 batch 32` | add `--grad-accum` for bigger effective batch |
| 16 GB | `n_layer 8 n_embd 512 block 512 batch 48` | |
| 24 GB+ | `n_layer 12 n_embd 768 block 1024 batch 32 --grad-accum 8` | GPT-2 small-ish |

## 5. Checkpoints are portable

Checkpoints save the model weights, optimizer state, config, and step count.
Train on the GPU, copy `out/ckpt.pt` + `out/vocab.json` anywhere, and run
`minigpt.sample` / `minigpt.think` on CPU or GPU — device is auto-detected.

## 6. Multi-GPU (optional)

For several GPUs, wrap the training step in `torch.nn.parallel.DistributedDataParallel`
and launch with `torchrun --nproc_per_node=N`. The single-GPU loop here is the
right starting point; DDP is a mechanical addition (init process group, wrap the
model, shard the batch) once one GPU is saturated.

---

**Reality check.** This architecture is genuine GPT and scales, but matching a
frontier coding model needs orders of magnitude more compute, data, and
engineering (a large tokenizer, a huge multi-language corpus, weeks on many
GPUs, and instruction/RL post-training). What the GPU path realistically buys
you here is a much stronger *small* model — coherent, syntactically solid code
completion — not a Claude replacement.
