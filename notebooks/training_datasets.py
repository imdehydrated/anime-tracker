"""Shared dataset classes for notebook training.

Keeping these classes in a real module (instead of notebook cells) allows
Windows multiprocessing workers to import them when DataLoader/Trainer uses
`num_workers > 0`.
"""

from __future__ import annotations

import numpy as np
import torch
from torch.utils.data import Dataset


class AnimeCorpusDataset(Dataset):
    """Tokenized corpus dataset used for MLM pretraining."""

    def __init__(self, texts, tokenizer, max_length: int):
        self.encodings = tokenizer(
            texts,
            truncation=True,
            max_length=max_length,
            padding="max_length",
            return_tensors="pt",
        )

    def __len__(self) -> int:
        return self.encodings["input_ids"].shape[0]

    def __getitem__(self, idx: int):
        return {k: v[idx] for k, v in self.encodings.items()}


class CFDataset(Dataset):
    """Dataset that converts sparse CF rows to dense denoising autoencoder inputs."""

    def __init__(
        self,
        rating_matrix,
        dropout_range=(0.45, 0.85),
        min_kept_items: int = 2,
        long_tail_alpha: float = 0.35,
        max_pos_weight: float = 4.0,
    ):
        self.matrix = rating_matrix
        self.n_users = rating_matrix.shape[0]
        self.n_anime = rating_matrix.shape[1]
        self.dropout_range = dropout_range
        self.min_kept_items = max(1, int(min_kept_items))

        # Long-tail item weights used by training loss in notebook 04.
        item_counts = np.asarray(rating_matrix.getnnz(axis=0)).astype(np.float32)
        nonzero_counts = item_counts[item_counts > 0]
        median_count = float(np.median(nonzero_counts)) if nonzero_counts.size > 0 else 1.0
        raw_weights = ((median_count + 1.0) / (item_counts + 1.0)) ** float(long_tail_alpha)
        self.item_positive_weights = np.clip(raw_weights, 1.0, float(max_pos_weight)).astype(np.float32)

    def __len__(self) -> int:
        return self.n_users

    def __getitem__(self, idx: int):
        row = self.matrix.getrow(idx).toarray().flatten()  # (n_anime,)

        # Build target: watched flags + normalized ratings.
        watched = (row != 0).astype(np.float32)
        ratings = row.astype(np.float32)

        # Build input with denoising dropout over observed interactions only.
        input_watched = np.zeros_like(watched, dtype=np.float32)
        input_ratings = np.zeros_like(ratings, dtype=np.float32)

        watched_idx = np.flatnonzero(watched > 0)
        if watched_idx.size > 0:
            dropout_rate = float(np.random.uniform(*self.dropout_range))
            keep_prob = 1.0 - dropout_rate
            keep_mask = np.random.binomial(1, keep_prob, size=watched_idx.size).astype(np.int8)

            # Ensure some signal remains so sparse users do not become all-zero inputs.
            min_keep = min(self.min_kept_items, watched_idx.size)
            if int(keep_mask.sum()) < min_keep:
                forced = np.random.choice(watched_idx.size, size=min_keep, replace=False)
                keep_mask[forced] = 1

            kept_idx = watched_idx[keep_mask > 0]
            input_watched[kept_idx] = 1.0
            input_ratings[kept_idx] = ratings[kept_idx]

        # Concatenate watched flags + ratings for input.
        input_vec = np.concatenate([input_watched, input_ratings])  # (n_anime * 2,)

        return (
            torch.FloatTensor(input_vec),
            torch.FloatTensor(watched),       # watch target
            torch.FloatTensor(ratings),       # rating target
            torch.BoolTensor(watched > 0),    # rating mask (loss only on watched)
        )
