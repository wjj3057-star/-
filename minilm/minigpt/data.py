"""Character-level tokenizer and batching for the corpus.

Char-level keeps the whole pipeline self-contained (no external tokenizer) and
the vocab tiny. The vocabulary is derived from the training text and saved with
the checkpoint so sampling uses the exact same mapping.
"""

from __future__ import annotations

import json
import pathlib

import numpy as np
import torch


class CharTokenizer:
    def __init__(self, chars: list[str]):
        self.chars = chars
        self.stoi = {c: i for i, c in enumerate(chars)}
        self.itos = {i: c for i, c in enumerate(chars)}

    @property
    def vocab_size(self) -> int:
        return len(self.chars)

    @classmethod
    def from_text(cls, text: str) -> "CharTokenizer":
        return cls(sorted(set(text)))

    def encode(self, text: str) -> list[int]:
        # unknown chars are skipped (rare; keeps sampling robust)
        return [self.stoi[c] for c in text if c in self.stoi]

    def decode(self, ids) -> str:
        return "".join(self.itos.get(int(i), "") for i in ids)

    def save(self, path: str) -> None:
        pathlib.Path(path).write_text(
            json.dumps({"chars": self.chars}, ensure_ascii=False)
        )

    @classmethod
    def load(cls, path: str) -> "CharTokenizer":
        data = json.loads(pathlib.Path(path).read_text())
        return cls(data["chars"])


class Dataset:
    """Holds encoded train/val token arrays and yields random batches."""

    def __init__(self, corpus_path: str, tokenizer: CharTokenizer, val_frac=0.05):
        text = pathlib.Path(corpus_path).read_text(encoding="utf-8")
        ids = np.array(tokenizer.encode(text), dtype=np.uint16)
        n_val = int(len(ids) * val_frac)
        self.train = ids[:-n_val]
        self.val = ids[-n_val:]

    def get_batch(self, split: str, batch_size: int, block_size: int, device):
        data = self.train if split == "train" else self.val
        ix = torch.randint(len(data) - block_size - 1, (batch_size,))
        x = torch.stack(
            [torch.from_numpy(data[i : i + block_size].astype(np.int64)) for i in ix]
        )
        y = torch.stack(
            [torch.from_numpy(data[i + 1 : i + 1 + block_size].astype(np.int64)) for i in ix]
        )
        return x.to(device), y.to(device)
