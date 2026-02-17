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

    def __init__(self, rating_matrix, dropout_range=(0.3, 0.7)):
        self.matrix = rating_matrix
        self.n_users = rating_matrix.shape[0]
        self.n_anime = rating_matrix.shape[1]
        self.dropout_range = dropout_range

    def __len__(self) -> int:
        return self.n_users

    def __getitem__(self, idx: int):
        row = self.matrix.getrow(idx).toarray().flatten()  # (n_anime,)

        # Build target: watched flags + normalized ratings.
        watched = (row != 0).astype(np.float32)
        ratings = row.astype(np.float32)

        # Build input: same as target but with denoising dropout.
        dropout_rate = np.random.uniform(*self.dropout_range)
        mask = np.random.binomial(1, 1 - dropout_rate, size=self.n_anime).astype(np.float32)
        input_watched = watched * mask
        input_ratings = ratings * mask

        # Concatenate watched flags + ratings for input.
        input_vec = np.concatenate([input_watched, input_ratings])  # (n_anime * 2,)

        return (
            torch.FloatTensor(input_vec),
            torch.FloatTensor(watched),       # watch target
            torch.FloatTensor(ratings),       # rating target
            torch.BoolTensor(watched > 0),    # rating mask (loss only on watched)
        )
